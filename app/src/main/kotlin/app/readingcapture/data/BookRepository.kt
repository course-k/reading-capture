package app.readingcapture.data

import android.content.Context
import app.readingcapture.core.inbox.GitHubClient
import app.readingcapture.core.inbox.InboxLayout
import app.readingcapture.core.inbox.RawFile
import app.readingcapture.core.toc.BookEntry
import app.readingcapture.core.toc.Toc
import app.readingcapture.core.toc.TocJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * 本の一覧と目次。出どころは取り込み箱（Claude が置く）。アプリは読むだけで、自分では探さない。
 * 読んだものは端末に写しておくので、電波が無くても本と章・節を選べる。
 */
class BookRepository(context: Context, private val settings: Settings) {

    private val dir = File(context.filesDir, "inbox").apply { mkdirs() }
    private val booksFile = File(dir, "books.json")

    private val _books = MutableStateFlow(loadBooks())
    val books: StateFlow<List<BookEntry>> = _books

    private val tocs = HashMap<String, Toc?>()
    private val _tocVersion = MutableStateFlow(0)

    /** 目次を読み直すたびに増える。 */
    val tocVersion: StateFlow<Int> = _tocVersion

    fun book(id: String?): BookEntry? = _books.value.firstOrNull { it.id == id }

    /** 端末に写してある目次。まだ無ければ null。 */
    @Synchronized
    fun toc(bookId: String): Toc? = tocs.getOrPut(bookId) {
        val f = tocFile(bookId)
        if (!f.exists()) null else runCatching { TocJson.parseToc(f.readText()) }.getOrNull()
    }

    /**
     * 取り込み箱から読み直す。変わっていなければ何もしない。
     * @return 本の一覧か、いずれかの目次が新しくなったら true
     */
    fun refresh(client: GitHubClient): Boolean {
        var changed = false
        when (val r = client.getRaw(InboxLayout.BOOKS, settings.etag(InboxLayout.BOOKS).takeIf { booksFile.exists() })) {
            is RawFile.Found -> {
                val parsed = TocJson.parseBooks(r.bytes.toString(Charsets.UTF_8))
                writeAtomically(booksFile, r.bytes)
                settings.setEtag(InboxLayout.BOOKS, r.etag)
                _books.value = parsed.books.filter { InboxLayout.isValidBookId(it.id) }
                changed = true
            }
            RawFile.Missing, RawFile.NotModified -> Unit
        }
        for (book in _books.value) {
            val path = InboxLayout.toc(book.id)
            val f = tocFile(book.id)
            when (val r = client.getRaw(path, settings.etag(path).takeIf { f.exists() })) {
                is RawFile.Found -> {
                    val parsed = TocJson.parseToc(r.bytes.toString(Charsets.UTF_8))
                    writeAtomically(f, r.bytes)
                    settings.setEtag(path, r.etag)
                    synchronized(this) { tocs[book.id] = parsed }
                    changed = true
                }
                RawFile.Missing, RawFile.NotModified -> Unit
            }
        }
        // 選んでいる本が無い、または一覧から消えたら、最初の本を選ぶ
        val current = settings.currentBookId.value
        if (_books.value.isNotEmpty() && _books.value.none { it.id == current }) settings.setCurrentBook(_books.value.first().id)
        if (changed) _tocVersion.value = _tocVersion.value + 1
        return changed
    }

    private fun loadBooks(): List<BookEntry> {
        if (!booksFile.exists()) return emptyList()
        return runCatching { TocJson.parseBooks(booksFile.readText()).books }.getOrDefault(emptyList())
            .filter { InboxLayout.isValidBookId(it.id) }
    }

    private fun tocFile(bookId: String) = File(dir, "toc-$bookId.json")

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(target)
    }

    companion object {
        /** 本を選ぶ前に撮った写真の入れ先。取り込み箱の `books/unsorted/` に入る。 */
        const val UNSORTED_ID = "unsorted"
        val UNSORTED = BookEntry(UNSORTED_ID, "本を選んでいません")
    }
}
