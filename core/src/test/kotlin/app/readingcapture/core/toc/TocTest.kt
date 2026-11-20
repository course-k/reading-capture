package app.readingcapture.core.toc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TocTest {

    private val toc = TocJson.parseToc(
        """
        {"schema":1,"book_id":"b","title":"本","unknown":true,"chapters":[
          {"id":"c01","title":"第1章 はじめに","sections":[{"id":"c01-s01","title":"1 背景","page":10},{"id":"c01-s02","title":"2 目的","page":13}]},
          {"id":"c02","title":"第2章 医療機関における医療情報システム","sections":[{"id":"c02-s01","title":"1 病院とは","page":32}]},
          {"id":"app","title":"付録","page":296}
        ]}
        """.trimIndent(),
    )
    private val nav = TocNavigator(toc)

    @Test
    fun `前後の節に 1 つずつ動ける。章をまたいでも続く`() {
        val first = nav.first()!!
        assertEquals("第1章 ／ 1 背景", nav.label(first))
        val second = nav.next(first)!!
        val third = nav.next(second)!!
        assertEquals("第2章 ／ 1 病院とは", nav.label(third))
        val fourth = nav.next(third)!!
        assertEquals("付録", nav.label(fourth))
        assertNull(nav.next(fourth))
        assertEquals(third, nav.previous(fourth))
        assertNull(nav.previous(first))
    }

    @Test
    fun `覚えておいた章・節から場所を引ける`() {
        assertEquals(TocPosition(1, 0), nav.find("c02", "c02-s01"))
        assertEquals(TocPosition(2, -1), nav.find("app", null))
        // 節が見つからなければ、その章の最初の節
        assertEquals(TocPosition(0, 0), nav.find("c01", "gone"))
        assertNull(nav.find("gone", null))
        assertNull(nav.find(null, null))
    }

    @Test
    fun `書き出して読み直しても同じ`() {
        assertEquals(toc, TocJson.parseToc(TocJson.encode(toc)))
        val books = BookList(books = listOf(BookEntry("b", "本")))
        assertEquals(books, TocJson.parseBooks(TocJson.encode(books)))
    }

    @Test
    fun `章の名前を短くする`() {
        assertEquals("第2章", TocNavigator.shortChapter("第2章 医療機関における医療情報システム"))
        assertEquals("医療情報システムの標準化について", TocNavigator.shortChapter("医療情報システムの標準化について"))
    }
}
