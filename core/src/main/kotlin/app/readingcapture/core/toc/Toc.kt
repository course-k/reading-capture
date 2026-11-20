package app.readingcapture.core.toc

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 取り込み箱の `books.json`。本の一覧。 */
@Serializable
data class BookList(
    val schema: Int = 1,
    val books: List<BookEntry> = emptyList(),
)

@Serializable
data class BookEntry(
    /** 取り込み箱の中のフォルダ名になる。半角の英小文字・数字・ハイフンだけ */
    val id: String,
    /** 本の名前。記録のリポジトリの `読書メモ/<本の名前>.md` と同じ */
    val title: String,
)

/** 取り込み箱の `books/<id>/toc.json`。1 冊の目次。 */
@Serializable
data class Toc(
    val schema: Int = 1,
    @SerialName("book_id") val bookId: String,
    val title: String,
    val chapters: List<Chapter> = emptyList(),
)

@Serializable
data class Chapter(
    val id: String,
    val title: String,
    /** 始まりのページ（分かるときだけ） */
    val page: Int? = null,
    val sections: List<Section> = emptyList(),
)

@Serializable
data class Section(
    val id: String,
    val title: String,
    val page: Int? = null,
)

/** 目次の中の 1 か所。節の無い章は [sectionIndex] が -1。 */
data class TocPosition(val chapterIndex: Int, val sectionIndex: Int)

object TocJson {
    val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun parseBooks(text: String): BookList = json.decodeFromString(BookList.serializer(), text)
    fun parseToc(text: String): Toc = json.decodeFromString(Toc.serializer(), text)
    fun encode(books: BookList): String = json.encodeToString(BookList.serializer(), books)
    fun encode(toc: Toc): String = json.encodeToString(Toc.serializer(), toc)
}

/** 目次を、前後に 1 つずつ動ける並びとして扱う。 */
class TocNavigator(val toc: Toc) {

    /** 選べる場所を、本の順に並べたもの。節のある章は節だけ、節の無い章は章そのもの。 */
    val positions: List<TocPosition> = buildList {
        toc.chapters.forEachIndexed { ci, chapter ->
            if (chapter.sections.isEmpty()) add(TocPosition(ci, -1))
            else chapter.sections.indices.forEach { add(TocPosition(ci, it)) }
        }
    }

    val isEmpty: Boolean get() = positions.isEmpty()

    fun chapter(position: TocPosition): Chapter = toc.chapters[position.chapterIndex]

    fun section(position: TocPosition): Section? =
        if (position.sectionIndex < 0) null else toc.chapters[position.chapterIndex].sections[position.sectionIndex]

    fun first(): TocPosition? = positions.firstOrNull()

    fun next(position: TocPosition): TocPosition? {
        val i = positions.indexOf(position)
        return if (i < 0 || i + 1 >= positions.size) null else positions[i + 1]
    }

    fun previous(position: TocPosition): TocPosition? {
        val i = positions.indexOf(position)
        return if (i <= 0) null else positions[i - 1]
    }

    /** 覚えておいた章・節の id から場所を引く。目次が直されて見つからなければ null。 */
    fun find(chapterId: String?, sectionId: String?): TocPosition? {
        if (chapterId == null) return null
        val ci = toc.chapters.indexOfFirst { it.id == chapterId }
        if (ci < 0) return null
        val chapter = toc.chapters[ci]
        if (chapter.sections.isEmpty()) return TocPosition(ci, -1)
        val si = chapter.sections.indexOfFirst { it.id == sectionId }
        return TocPosition(ci, if (si < 0) 0 else si)
    }

    /** 画面に出す短い名前。「第2章 ／ 1 病院とは」の形。章の名前は最初の区切りまでに縮める。 */
    fun label(position: TocPosition): String {
        val chapter = chapter(position)
        val section = section(position) ?: return chapter.title
        return "${shortChapter(chapter.title)} ／ ${section.title}"
    }

    companion object {
        /** 「第2章 医療機関における医療情報システム」→「第2章」。区切りが無ければそのまま。 */
        fun shortChapter(title: String): String {
            val cut = title.indexOfFirst { it == ' ' || it == '　' }
            return if (cut in 1..8) title.substring(0, cut) else title
        }
    }
}
