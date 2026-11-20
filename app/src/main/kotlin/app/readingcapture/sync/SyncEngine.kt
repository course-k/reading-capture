package app.readingcapture.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.readingcapture.App
import app.readingcapture.data.Db
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * 「上げる」を動かし続ける役。
 *
 * アプリが開いているあいだは、ここの見張りがすぐに上げる。
 * アプリが閉じられた・落とされたあとは、端末の仕組み（WorkManager）が、つながったときに続きを上げる。
 * どちらも同じ [SyncRunner.runOnce] を呼び、同時には 1 つしか動かない。
 */
class SyncEngine(
    private val context: Context,
    private val runner: SyncRunner,
    private val db: Db,
    private val scope: CoroutineScope,
) {
    private val kicks = Channel<Unit>(Channel.CONFLATED)

    fun start() {
        scope.launch(Dispatchers.IO) {
            var waitMs = 0L
            while (true) {
                // 合図か、決めた時間が来るまで待つ
                if (waitMs > 0) withTimeoutOrNull(waitMs) { kicks.receive() } else kicks.receive()
                waitMs = when (val outcome = runner.runOnce()) {
                    SyncOutcome.Done, SyncOutcome.Blocked -> 0
                    is SyncOutcome.More -> outcome.afterMs.coerceAtLeast(200)
                    is SyncOutcome.Retry -> outcome.afterMs
                }
            }
        }
        // つながったら、すぐ続きを上げる
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                kick()
            }
        })
        kick()
    }

    /** 上げるものができた・設定が変わった、など。 */
    fun kick() {
        kicks.trySend(Unit)
        if (db.hasWorkToSync()) scheduleBackground()
    }

    private fun scheduleBackground() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(45, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // すでに予約があれば、そのままにする
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    companion object {
        const val WORK_NAME = "inbox-sync"
    }
}

/** アプリが閉じられているあいだの「上げる」。 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val container = (applicationContext as App).container
        // 撮った直後にアプリが落とされた場合、仕上げ（起動時に拾われる）が済むのを待ってから上げる
        var waited = 0
        while (waited < 30 && container.db.photosInState(app.readingcapture.data.PhotoState.RAW).isNotEmpty()) {
            kotlinx.coroutines.delay(1000)
            waited++
        }
        when (container.syncRunner.runOnce()) {
            SyncOutcome.Done, SyncOutcome.Blocked -> Result.success()
            is SyncOutcome.More, is SyncOutcome.Retry -> Result.retry()
        }
    }
}
