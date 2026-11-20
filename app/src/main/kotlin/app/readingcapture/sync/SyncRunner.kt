package app.readingcapture.sync

import app.readingcapture.core.inbox.DeleteItem
import app.readingcapture.core.inbox.GitHubClient
import app.readingcapture.core.inbox.InboxConfig
import app.readingcapture.core.inbox.InboxException
import app.readingcapture.core.inbox.InboxUploader
import app.readingcapture.core.inbox.NoteFile
import app.readingcapture.core.inbox.UploadItem
import app.readingcapture.data.BookRepository
import app.readingcapture.data.Db
import app.readingcapture.data.Note
import app.readingcapture.data.NoteState
import app.readingcapture.data.PhotoState
import app.readingcapture.data.PhotoStore
import app.readingcapture.data.Settings
import app.readingcapture.data.TokenVault
import app.readingcapture.diag.Diagnostics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

/** 上げられない理由。画面の言葉に直す。 */
enum class SyncProblem {
    /** 鍵がまだ入っていない */
    NO_KEY,

    /** 鍵が無効（期限切れ・取り消し・打ち間違い） */
    BAD_KEY,

    /** 鍵は通るが、この取り込み箱に書き込めない */
    NO_PERMISSION,

    /** 取り込み箱が見つからない */
    NOT_FOUND,

    /** つながらない（電波が無い）。つながったら自動で続ける */
    OFFLINE,

    /** GitHub 側の一時的な不調・混雑。少し待って続ける */
    BUSY,

    /** そのほか */
    OTHER,
}

data class SyncStatus(val running: Boolean = false, val problem: SyncProblem? = null, val detail: String? = null)

sealed interface SyncOutcome {
    /** 上げるものはもう無い */
    data object Done : SyncOutcome

    /** まだある。[afterMs] ミリ秒後にもう一度 */
    data class More(val afterMs: Long) : SyncOutcome

    /** 一時的に失敗。あとでやり直す */
    data class Retry(val afterMs: Long) : SyncOutcome

    /** 持ち主が設定を直すまで進めない */
    data object Blocked : SyncOutcome
}

/**
 * 端末にたまっている写真と言葉を、取り込み箱に上げる 1 回分。
 *
 * 台帳の「上がった」は、取り込み箱に入ったと確かめられてから付ける。
 * 途中で切れても台帳は「まだ」のままなので、次の回が続きからやり直す。
 */
class SyncRunner(
    private val db: Db,
    private val store: PhotoStore,
    private val settings: Settings,
    private val vault: TokenVault,
    private val books: BookRepository,
    private val http: OkHttpClient,
    private val diag: Diagnostics,
) {
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status

    private var lastBookRefreshMs = 0L
    private val bookLock = Any()

    // 上げる役は、鍵と取り込み箱が同じあいだ使い回す（自分が進めた先頭を覚えていて、古い読み取りを見分けるため）
    private var uploaderKey: String? = null
    private var uploader: InboxUploader? = null

    /** 取り込み箱につなぐ準備ができているか。 */
    fun clientOrNull(): GitHubClient? {
        val token = vault.load() ?: return null
        val (owner, repo) = InboxConfig.parseRepo(settings.inboxRepo) ?: return null
        return GitHubClient(http, InboxConfig(owner, repo, token))
    }

    /** 本の一覧と目次を読み直す。上げる処理が長引いていても待たされないよう、別の錠にしてある。 */
    fun refreshBooks(force: Boolean): Boolean = synchronized(bookLock) {
        val client = clientOrNull() ?: return false
        val now = System.currentTimeMillis()
        if (!force && now - lastBookRefreshMs < BOOK_REFRESH_INTERVAL_MS) return false
        val changed = books.refresh(client)
        lastBookRefreshMs = now
        changed
    }

    @Synchronized
    fun runOnce(): SyncOutcome {
        val client = clientOrNull()
        if (client == null) {
            // 取り込み箱に一度も上げていないものの「消す」は、端末の中だけで済ませる
            for (n in db.notesInState(NoteState.DELETE_PENDING)) if (!n.everUploaded) db.removeNoteRow(n.id)
            for (p in db.photosInState(PhotoState.DELETE_PENDING)) if (p.uploadedAt == null && p.blobSha == null) db.removePhotoRow(p.id)
            _status.value = SyncStatus(problem = SyncProblem.NO_KEY)
            return SyncOutcome.Blocked
        }
        _status.value = _status.value.copy(running = true)
        try {
            runCatching { refreshBooks(force = false) }
            val key = settings.inboxRepo + "|" + (vault.load()?.hashCode() ?: 0)
            val uploader = uploader?.takeIf { uploaderKey == key } ?: InboxUploader(client).also {
                uploader = it
                uploaderKey = key
            }
            val deadline = System.currentTimeMillis() + TIME_BUDGET_MS
            while (System.currentTimeMillis() < deadline) {
                val now = System.currentTimeMillis()
                val ready = db.photosInState(PhotoState.READY)
                // いちばん新しい 1 枚は、少しのあいだ待つ（すぐ「消す」「撮り直す」ことがあるため）
                val newest = ready.maxByOrNull { it.capturedAt }
                val held = newest?.takeIf { now - it.capturedAt < HOLD_NEWEST_MS }
                val photos = ready.filter { it !== held }.take(MAX_PHOTOS_PER_COMMIT)
                val notes = db.notesInState(NoteState.PENDING)
                val delPhotos = db.photosInState(PhotoState.DELETE_PENDING)
                val delNotes = db.notesInState(NoteState.DELETE_PENDING)

                if (photos.isEmpty() && notes.isEmpty() && delPhotos.isEmpty() && delNotes.isEmpty()) {
                    cleanUp()
                    _status.value = SyncStatus()
                    return if (held != null) SyncOutcome.More(HOLD_NEWEST_MS - (now - held.capturedAt) + 500) else SyncOutcome.Done
                }

                val puts = ArrayList<UploadItem>()
                val missingFiles = ArrayList<String>()
                for (p in photos) {
                    val file = p.fileName?.let { store.photoFile(it) }
                    if (file == null || !file.exists()) {
                        missingFiles += p.id
                        continue
                    }
                    puts += UploadItem("p:${p.id}", p.remotePath, { file.readBytes() }, p.blobSha)
                }
                for (n in notes) puts += UploadItem("n:${n.id}", n.remotePath, { n.toFile().encode() })
                val deletes = delPhotos.map { DeleteItem("dp:${it.id}", it.remotePath) } + delNotes.map { DeleteItem("dn:${it.id}", it.remotePath) }

                val started = System.currentTimeMillis()
                val result = uploader.upload(puts, deletes, message(photos.size - missingFiles.size, notes.size, deletes.size)) { key, sha ->
                    if (key.startsWith("p:")) db.setPhotoBlobSha(key.substring(2), sha)
                }
                val at = System.currentTimeMillis()
                for (p in photos) if ("p:${p.id}" in result.doneKeys) db.markPhotoUploaded(p.id, at)
                for (n in notes) if ("n:${n.id}" in result.doneKeys) db.markNoteUploaded(n.id, n)
                for (p in delPhotos) if ("dp:${p.id}" in result.doneKeys) db.removePhotoRow(p.id)
                for (n in delNotes) if ("dn:${n.id}" in result.doneKeys) db.removeNoteRow(n.id)
                // ファイルが見当たらない写真は、上げようが無い。台帳から外して記録に残す
                for (id in missingFiles) {
                    diag.log("photo_file_missing", "id" to id)
                    db.removePhotoRow(id)
                }
                diag.log(
                    "sync", "photos" to photos.size, "notes" to notes.size, "deletes" to deletes.size,
                    "ms" to at - started, "bytes" to photos.sumOf { it.bytes }, "commit" to (result.commitSha != null),
                )
            }
            _status.value = SyncStatus()
            return SyncOutcome.More(0)
        } catch (e: InboxException) {
            val (problem, outcome) = when (e) {
                is InboxException.Unauthorized -> SyncProblem.BAD_KEY to SyncOutcome.Blocked
                is InboxException.NoPermission -> SyncProblem.NO_PERMISSION to SyncOutcome.Blocked
                is InboxException.NotFound -> SyncProblem.NOT_FOUND to SyncOutcome.Blocked
                is InboxException.Network -> SyncProblem.OFFLINE to SyncOutcome.Retry(15_000)
                is InboxException.RateLimited -> SyncProblem.BUSY to SyncOutcome.Retry(e.retryAfterSeconds * 1000)
                is InboxException.Server -> SyncProblem.BUSY to SyncOutcome.Retry(30_000)
                is InboxException.Unexpected -> SyncProblem.OTHER to SyncOutcome.Retry(60_000)
            }
            diag.log("sync_error", "problem" to problem.name, "detail" to e.message)
            _status.value = SyncStatus(problem = problem, detail = e.message)
            return outcome
        } catch (e: Exception) {
            diag.log("sync_error", "problem" to "EXCEPTION", "detail" to e.toString())
            _status.value = SyncStatus(problem = SyncProblem.OTHER, detail = e.message)
            return SyncOutcome.Retry(60_000)
        } finally {
            _status.value = _status.value.copy(running = false)
        }
    }

    /** 持ち主が鍵や取り込み箱の名前を直した。止まっている理由を消す。 */
    fun clearProblem() {
        _status.value = SyncStatus()
    }

    private fun message(photos: Int, notes: Int, deletes: Int): String {
        val parts = ArrayList<String>()
        if (photos > 0) parts += "写真 $photos 枚"
        if (notes > 0) parts += "言葉 $notes 件"
        if (deletes > 0) parts += "取り消し $deletes 件"
        return "取り込み: " + parts.joinToString("・")
    }

    private fun Note.toFile() = NoteFile(
        bookId = bookId,
        bookTitle = bookTitle,
        chapter = chapterTitle,
        section = sectionTitle,
        chapterId = chapterId,
        sectionId = sectionId,
        spokenAt = OffsetDateTime.ofInstant(Instant.ofEpochMilli(spokenAt), ZoneId.systemDefault()).withNano(0).toString(),
        text = text,
    )

    /** 上がってから日がたった写真を、端末から片付ける。取り込み箱に上がったものだけが対象。 */
    private fun cleanUp() {
        val now = System.currentTimeMillis()
        for (p in db.uploadedPhotosBefore(now - KEEP_LOCAL_FILE_MS)) {
            p.fileName?.let { store.photoFile(it).delete() }
            store.thumbFile(p.id).delete()
            db.clearPhotoFile(p.id)
        }
        db.removeUploadedPhotoRowsBefore(now - KEEP_ROW_MS)
    }

    companion object {
        const val HOLD_NEWEST_MS = 15_000L
        const val MAX_PHOTOS_PER_COMMIT = 6
        const val TIME_BUDGET_MS = 7 * 60_000L
        const val KEEP_LOCAL_FILE_MS = 3 * 24 * 3600_000L
        const val KEEP_ROW_MS = 30 * 24 * 3600_000L
        const val BOOK_REFRESH_INTERVAL_MS = 5 * 60_000L
    }
}
