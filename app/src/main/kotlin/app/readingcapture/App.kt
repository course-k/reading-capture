package app.readingcapture

import android.app.Application
import app.readingcapture.capture.CaptureController
import app.readingcapture.capture.Haptics
import app.readingcapture.capture.PhotoProcessor
import app.readingcapture.data.BookRepository
import app.readingcapture.data.Db
import app.readingcapture.data.PhotoStore
import app.readingcapture.data.Settings
import app.readingcapture.data.TokenVault
import app.readingcapture.diag.Diagnostics
import app.readingcapture.sync.SyncEngine
import app.readingcapture.sync.SyncRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class App : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.start()
    }
}

/** アプリ全体で 1 つずつ持つもの。画面が作り直されても、撮る・上げるは途切れない。 */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val diag = Diagnostics(app)
    val db = Db(app)
    val settings = Settings(app)
    val vault = TokenVault(app)
    val store = PhotoStore(app)
    val books = BookRepository(app, settings)
    val haptics = Haptics(app, settings)

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()

    val syncRunner = SyncRunner(db, store, settings, vault, books, http, diag)
    val sync = SyncEngine(app, syncRunner, db, scope)
    val processor = PhotoProcessor(app, db, store, diag)
    val capture = CaptureController(app, db, store, settings, processor, haptics, sync, diag)

    fun start() {
        processor.onProcessed = { sync.kick() }
        // 前回、仕上げの途中で終わっていた写真を拾う
        processor.recover()
        sync.start()
        refreshBooks(force = true)
    }

    /** 本の一覧と目次を、取り込み箱から読み直す。 */
    fun refreshBooks(force: Boolean, done: (Result<Boolean>) -> Unit = {}) {
        scope.launch(Dispatchers.IO) {
            done(runCatching { syncRunner.refreshBooks(force) })
        }
    }

    /** いま選んでいる本の id。まだ本が無いあいだは、仮の入れ先。 */
    val currentBookId: String get() = settings.currentBookId.value ?: BookRepository.UNSORTED_ID
}
