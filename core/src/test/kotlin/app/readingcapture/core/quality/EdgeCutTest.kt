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

    @Test
    fun `となりのページの行の端が、本文のすぐそばに写り込んでいても、切れているとは言わない`() {
        // 実機（Pixel 6a、手持ち）で「左の端に文字がかかっています」と誤って知らせたときの、行の枠の並び。
        // 左端の 3 つ（幅 17〜21）が、となりのページの行の端。綴じ目の近くは紙が曲がって、本文（左端 59）との間が詰まって見える
        assertEquals(emptySet<Side>(), EdgeCutCheck.evaluate(realFacingPageIntrusion, 1152, 1536).sides)
    }

    @Test
    fun `縦書きの本でも、切れている側を知らせる`() {
        // 横書きの合成ページを 90 度回して、縦書きの並びにする
        val (boxes, w, h) = frame(120f, 0f, this.w, this.h, plain to 0f)
        val rotated = boxes.map { TextBox(it.top, it.left, it.bottom, it.right) }
        assertEquals(setOf(Side.TOP), EdgeCutCheck.evaluate(rotated, h, w).sides)
    }

    private val realFacingPageIntrusion = listOf(
        TextBox(5f, 518f, 26f, 531f), TextBox(5f, 555f, 22f, 568f), TextBox(7f, 592f, 28f, 606f),
        TextBox(59f, 82f, 337f, 116f), TextBox(78f, 115f, 735f, 151f), TextBox(61f, 152f, 737f, 184f),
        TextBox(63f, 186f, 736f, 216f), TextBox(66f, 222f, 613f, 247f), TextBox(84f, 252f, 736f, 283f),
        TextBox(59f, 286f, 742f, 317f), TextBox(69f, 323f, 739f, 347f), TextBox(68f, 351f, 360f, 378f),
        TextBox(80f, 387f, 744f, 413f), TextBox(69f, 420f, 737f, 443f), TextBox(76f, 455f, 733f, 476f),
        TextBox(68f, 485f, 741f, 510f), TextBox(76f, 520f, 734f, 540f), TextBox(78f, 552f, 736f, 571f),
        TextBox(80f, 586f, 137f, 603f), TextBox(90f, 613f, 742f, 637f), TextBox(74f, 645f, 659f, 668f),
        TextBox(84f, 709f, 354f, 731f), TextBox(93f, 736f, 740f, 762f), TextBox(88f, 766f, 724f, 792f),
        TextBox(89f, 798f, 572f, 823f), TextBox(106f, 824f, 729f, 855f), TextBox(90f, 854f, 731f, 886f),
        TextBox(88f, 884f, 738f, 917f), TextBox(94f, 921f, 481f, 946f), TextBox(112f, 948f, 555f, 979f),
        TextBox(94f, 974f, 664f, 1011f), TextBox(725f, 70f, 905f, 85f), TextBox(89f, 1002f, 732f, 1039f),
        TextBox(97f, 1042f, 292f, 1069f), TextBox(91f, 1069f, 557f, 1098f), TextBox(100f, 1100f, 515f, 1126f),
        TextBox(111f, 1120f, 721f, 1159f), TextBox(101f, 1162f, 349f, 1186f), TextBox(103f, 1187f, 550f, 1215f),
        TextBox(104f, 1213f, 623f, 1244f), TextBox(127f, 1235f, 735f, 1277f), TextBox(102f, 1275f, 546f, 1303f),
        TextBox(858f, 1329f, 882f, 1342f),
    )
}
