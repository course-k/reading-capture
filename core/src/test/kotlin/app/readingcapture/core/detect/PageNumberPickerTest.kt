package app.readingcapture.core.detect

import app.readingcapture.core.quality.TextBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageNumberPickerTest {

    /** 本文: 左端 130、右端 830、上 100 から 36 ずつ 36 行（実機の写真に近い並び、幅 1152×高さ 1536）。 */
    private val body = (0 until 36).map { ReadLine(TextBox(130f, 100f + it * 36f, 830f, 128f + it * 36f), 34) }

    private fun number(value: Int, x: Float, y: Float, lineChars: Int = 2) =
        NumberCandidate(value, TextBox(x, y, x + 30f, y + 17f), lineChars)

    @Test
    fun `本文の下の外にある、数字だけの短い行を選ぶ`() {
        // 実機で読めた位置（右下、高さ 17、行の文字数 2）
        assertEquals(13, PageNumberPicker.pick(listOf(number(13, 1030f, 1450f)), body))
    }

    @Test
    fun `本文の中の数字は選ばない`() {
        val inSentence = NumberCandidate(20, TextBox(300f, 500f, 330f, 528f), 34)
        val figureLabel = number(3, 400f, 700f, lineChars = 1) // 上下に本文がある短い数字（図の番号など）
        assertNull(PageNumberPicker.pick(listOf(inSentence, figureLabel), body))
        assertEquals(47, PageNumberPicker.pick(listOf(inSentence, figureLabel, number(47, 900f, 1440f)), body))
    }

    @Test
    fun `上にあるページ番号も選べる`() {
        assertEquals(128, PageNumberPicker.pick(listOf(number(128, 140f, 50f, lineChars = 3)), body))
    }

    @Test
    fun `上に柱の数字、下にページ番号があれば、下を選ぶ`() {
        assertEquals(33, PageNumberPicker.pick(listOf(number(2, 500f, 50f, lineChars = 1), number(33, 800f, 1450f)), body))
    }

    @Test
    fun `となりのページの番号が写り込んでいたら、本文に近いほうを選ぶ`() {
        // 綴じ目の向こう（左端）に、となりのページの番号 12。このページの番号 13 は本文の下
        val neighbor = number(12, 20f, 1450f)
        val own = number(13, 760f, 1452f)
        assertEquals(13, PageNumberPicker.pick(listOf(neighbor, own), body))
        assertEquals(13, PageNumberPicker.pick(listOf(own, neighbor), body))
    }

    @Test
    fun `本文が無ければ選ばない`() {
        assertNull(PageNumberPicker.pick(listOf(number(13, 500f, 500f)), emptyList()))
        assertNull(PageNumberPicker.pick(emptyList(), body))
    }

    @Test
    fun `4 桁以上や 0 は選ばない`() {
        assertNull(PageNumberPicker.pick(listOf(number(2026, 800f, 1450f, lineChars = 4), number(0, 700f, 1450f, lineChars = 1)), body))
    }

    @Test
    fun `帯に番号しか写っていなくても、帯だけを読んだときは番号とみなす`() {
        // ページの下が図や余白で、下の帯に本文の行が入らなかった（エミュレーターで、合成ページの 1 つがこうなった）
        val only = listOf(number(32, 460f, 250f))
        assertEquals(32, PageNumberPicker.pick(only, emptyList(), edgeBandOnly = true))
        // 写真全体を読んだときは、本文が無いのに番号だけあるのはおかしいので、選ばない
        assertNull(PageNumberPicker.pick(only, emptyList()))
    }
}
