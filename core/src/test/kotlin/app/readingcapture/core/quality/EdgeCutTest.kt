package app.readingcapture.core.quality

import app.readingcapture.core.synthetic.PageOptions
import app.readingcapture.core.synthetic.SyntheticPage
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「文字が画像の端にかかっているか」のテスト。
 * 合成したページの行の枠を、カメラの枠で切り取って、文字認識が返すはずの枠を作る。
 */
class EdgeCutTest {

    private val w = SyntheticPage.PAGE_W.toFloat()
    private val h = SyntheticPage.PAGE_H.toFloat()

    /**
     * ページ座標の枠 [x0,y0,x1,y1] をカメラの画像として切り取ったときの、行の枠。
     * @param pages ページと、その左上の位置（ページ座標）
     */
    private fun frame(x0: Float, y0: Float, x1: Float, y1: Float, vararg pages: Pair<SyntheticPage, Float>): Triple<List<TextBox>, Int, Int> {
        val out = mutableListOf<TextBox>()
        for ((page, offsetX) in pages) {
            page.full // 行の枠を作らせる
            for (b in page.lineBoxes) {
                val l = b[0] + offsetX
                val r = b[2] + offsetX
                val cl = maxOf(l, x0)
                val cr = minOf(r, x1)
                val ct = maxOf(b[1], y0)
                val cb = minOf(b[3], y1)
                // 半分も見えない文字は、認識されない
                if (cr - cl < 8f || cb - ct < (b[3] - b[1]) * 0.5f) continue
                out += TextBox(cl - x0, ct - y0, cr - x0, cb - y0)
            }
        }
        return Triple(out, (x1 - x0).toInt(), (y1 - y0).toInt())
    }

    private fun sides(f: Triple<List<TextBox>, Int, Int>) = EdgeCutCheck.evaluate(f.first, f.second, f.third).sides

    private val plain = SyntheticPage(1)
    private val notes = SyntheticPage(2, options = PageOptions(marginNotes = true))

    @Test
    fun `余白ごと全部入っていれば、切れていない`() {
        assertEquals(emptySet<Side>(), sides(frame(-30f, -30f, w + 30f, h + 30f, plain to 0f)))
        assertEquals(emptySet<Side>(), sides(frame(0f, 0f, w, h, notes to 0f)))
    }

    @Test
    fun `欄外の注の右端が切れていれば、右が切れている`() {
        // 前回の手作業で起きた切れ方。注の最後の 1〜2 文字が画像の外に出る
        assertEquals(setOf(Side.RIGHT), sides(frame(0f, 0f, w - 60f, h, notes to 0f)))
        assertEquals(setOf(Side.RIGHT), sides(frame(-40f, -20f, w - 90f, h + 20f, notes to 0f)))
    }

    @Test
    fun `本文の左が切れていれば、左が切れている`() {
        assertEquals(setOf(Side.LEFT), sides(frame(120f, 0f, w, h, plain to 0f)))
    }

    @Test
    fun `上や下が切れていれば、そう知らせる`() {
        assertEquals(setOf(Side.TOP), sides(frame(0f, 150f, w, h, plain to 0f)))
        assertEquals(setOf(Side.BOTTOM), sides(frame(0f, 0f, w, h - 250f, plain to 0f)))
    }

    @Test
    fun `となりのページの文字が端に写り込んでいても、切れているとは言わない`() {
        // 右どなりのページ（のどをはさんで 190 画素先から本文が始まる）が、右の端に少し写る
        val facing = SyntheticPage(5)
        assertEquals(emptySet<Side>(), sides(frame(-20f, 0f, w + 180f, h, plain to 0f, facing to w)))
        // 左どなりのページが左の端に写る
        assertEquals(emptySet<Side>(), sides(frame(-200f, 0f, w + 20f, h, plain to 0f, facing to -w)))
    }

    @Test
    fun `となりのページが写り込み、しかも欄外の注が切れていれば、切れていると言う`() {
        val facing = SyntheticPage(5)
        // 左にとなりのページ、右は注が切れている
        assertEquals(setOf(Side.RIGHT), sides(frame(-200f, 0f, w - 70f, h, notes to 0f, facing to -w)))
    }

    @Test
    fun `文字が無ければ、切れていない`() {
        assertEquals(emptySet<Side>(), EdgeCutCheck.evaluate(emptyList(), 1000, 1300).sides)
    }
}
