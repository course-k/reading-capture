package app.readingcapture.core.inbox

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * 取り込み箱（reading-inbox）の中の並び。アプリと、読書の相手役（Claude）とのあいだの約束。
 * 同じ内容を取り込み箱の README に書いてある。変えるときは両方を直す。
 *
 * ```
 * books.json                                         本の一覧（Claude が書く。アプリは読むだけ）
 * books/<本の id>/toc.json                           目次（Claude が書く。アプリは読むだけ）
 * books/<本の id>/photos/<年-月-日>/<時分秒-ミリ秒>-<種類>.<拡張子>   写真（アプリが置く）
 * books/<本の id>/notes/<年-月-日>/<時分秒-ミリ秒>.json            話した言葉（アプリが置く）
 * ```
 */
object InboxLayout {

    const val BOOKS = "books.json"

    private val DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val TIME = DateTimeFormatter.ofPattern("HHmmss-SSS")
    private val BOOK_ID = Regex("[a-z0-9][a-z0-9-]{0,63}")

    /** 本の id として使える形か。フォルダ名になるので、半角の英小文字・数字・ハイフンだけにする。 */
    fun isValidBookId(id: String): Boolean = BOOK_ID.matches(id)

    fun toc(bookId: String): String = "books/${checked(bookId)}/toc.json"

    fun photo(bookId: String, capturedAt: OffsetDateTime, kind: PhotoKind, extension: String = "jpg"): String {
        require(extension.matches(Regex("[a-z0-9]{1,5}"))) { "拡張子が不正: $extension" }
        return "books/${checked(bookId)}/photos/${DAY.format(capturedAt)}/${TIME.format(capturedAt)}-${kind.tag}.$extension"
    }

    fun note(bookId: String, spokenAt: OffsetDateTime): String =
        "books/${checked(bookId)}/notes/${DAY.format(spokenAt)}/${TIME.format(spokenAt)}.json"

    private fun checked(bookId: String): String {
        require(isValidBookId(bookId)) { "本の id が不正: $bookId" }
        return bookId
    }
}

enum class PhotoKind(val tag: String) {
    /** アプリのカメラで撮った紙のページ */
    CAMERA("cam"),

    /** ほかのアプリの「共有」から受け取った画像（Kindle のスクリーンショットなど） */
    SHARED("share"),
}

/** 話した言葉 1 回分。取り込み箱の `notes/` に、このままの形で置く。 */
@Serializable
data class NoteFile(
    val schema: Int = 1,
    @SerialName("book_id") val bookId: String,
    @SerialName("book_title") val bookTitle: String,
    val chapter: String? = null,
    val section: String? = null,
    @SerialName("chapter_id") val chapterId: String? = null,
    @SerialName("section_id") val sectionId: String? = null,
    /** 「残す」を押した日時（端末の時刻、時差つき） */
    @SerialName("spoken_at") val spokenAt: String,
    /** 入力欄に入っていた文字そのまま */
    val text: String,
) {
    fun encode(): ByteArray = (JSON.encodeToString(serializer(), this) + "\n").toByteArray(Charsets.UTF_8)

    companion object {
        private val JSON = Json {
            prettyPrint = true
            encodeDefaults = true
            explicitNulls = false
            ignoreUnknownKeys = true
        }

        fun decode(bytes: ByteArray): NoteFile = JSON.decodeFromString(serializer(), bytes.toString(Charsets.UTF_8))
    }
}
