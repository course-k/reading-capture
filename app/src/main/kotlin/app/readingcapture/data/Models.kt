package app.readingcapture.data

import app.readingcapture.core.inbox.PhotoKind
import app.readingcapture.core.quality.PhotoWarnings

enum class PhotoState {
    /** カメラが保存した直後。仕上げ（向きを直す・点検）がまだ */
    RAW,

    /** 仕上げ済み。これから上げる */
    READY,

    /** 取り込み箱に上がった */
    UPLOADED,

    /** 持ち主が消した。取り込み箱からも消す */
    DELETE_PENDING,
}

data class Photo(
    val id: String,
    val bookId: String,
    val capturedAt: Long,
    val kind: PhotoKind,
    val state: PhotoState,
    /** 取り込み箱の中のパス */
    val remotePath: String,
    /** 端末の中のファイル名（photos/ の下）。片付け済みなら null */
    val fileName: String?,
    val width: Int,
    val height: Int,
    val bytes: Long,
    val warnings: PhotoWarnings,
    val blobSha: String?,
    val uploadedAt: Long?,
)

enum class NoteState {
    /** これから上げる（新しく残した、または節を変えた） */
    PENDING,
    UPLOADED,
    DELETE_PENDING,
}

data class Note(
    val id: String,
    val bookId: String,
    val bookTitle: String,
    val chapterId: String?,
    val chapterTitle: String?,
    val sectionId: String?,
    val sectionTitle: String?,
    val text: String,
    val spokenAt: Long,
    val state: NoteState,
    val remotePath: String,
    /** 一度でも取り込み箱に上がったか（消すときに、取り込み箱からも消す必要があるか） */
    val everUploaded: Boolean,
)

/** 上げる状況。画面の右上と「撮る」の下に出す。 */
data class SyncCounts(val pending: Int, val uploadedToday: Int)
