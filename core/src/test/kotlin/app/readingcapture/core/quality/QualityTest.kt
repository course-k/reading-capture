package app.readingcapture.core.quality

import app.readingcapture.core.synthetic.PageOptions
import app.readingcapture.core.synthetic.SyntheticPage
import app.readingcapture.core.synthetic.photo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityTest {

    private val noCut = EdgeCutResult(emptySet())

    @Test
    fun `鮮明さは、ぼけるほど下がる`() {
        for (id in 1..4) {
            val page = SyntheticPage(id, options = PageOptions(marginNotes = id % 2 == 0))
            val scores = (0..4).map { PhotoMeasurer.measure(page.photo(blurRadius = it)).sharpness!! }
            // ひどくぼけると 0 に張り付くので、そこから先は「上がらない」ことだけを見る
            for (i in 1 until scores.size) assertTrue("ページ $id: $scores", scores[i] <= scores[i - 1])
            assertTrue("ページ $id: $scores", scores[1] < scores[0] * 0.8f && scores[2] < scores[1] * 0.6f)
        }
    }

    @Test
    fun `横に流れたぶれは、横方向の鮮明さだけを下げる`() {
        val page = SyntheticPage(3)
        val still = PhotoMeasurer.measure(page.photo())
        val moved = PhotoMeasurer.measure(page.photo(motionX = 7))
        assertTrue(moved.sharpnessX!! < still.sharpnessX!! * 0.75f)
        assertTrue(moved.sharpnessY!! > still.sharpnessY!! * 0.9f)
        assertEquals(moved.sharpnessX, moved.sharpness)
        val movedY = PhotoMeasurer.measure(page.photo(motionY = 7))
        assertTrue(movedY.sharpnessY!! < still.sharpnessY!! * 0.75f)
    }

    @Test
    fun `真っ白な紙では鮮明さを測らない`() {
        val blank = app.readingcapture.core.frame.LumaImage(640, 800, ByteArray(640 * 800) { 230.toByte() })
        val m = PhotoMeasurer.measure(blank)
        assertNull(m.sharpness)
        assertEquals(230, m.paperLuma)
    }

    @Test
    fun `続けて撮った中で、ぼけた 1 枚だけを知らせる`() {
        val judge = QualityJudge()
        for (id in 1..6) {
            val w = judge.judge(PhotoMeasurer.measure(SyntheticPage(id).photo(blurRadius = 1)), noCut, false)
            assertFalse("ページ $id", w.blurred)
        }
        val scores = (1..6).map { PhotoMeasurer.measure(SyntheticPage(it).photo(blurRadius = 1)).sharpness }
        val blur3 = PhotoMeasurer.measure(SyntheticPage(7).photo(blurRadius = 3))
        val motion = PhotoMeasurer.measure(SyntheticPage(8).photo(blurRadius = 1, motionX = 9))
        assertTrue("ふだん $scores、ぼけ $blur3", judge.judge(blur3, noCut, false).blurred)
        assertTrue("ふだん $scores、ぶれ $motion", judge.judge(motion, noCut, false).blurred)
        // ぼけた 1 枚のあとも、ふつうの 1 枚は知らせない
        assertFalse(judge.judge(PhotoMeasurer.measure(SyntheticPage(9).photo(blurRadius = 1)), noCut, false).blurred)
    }

    @Test
    fun `文字の少ないページを、ぼけと間違えない`() {
        val judge = QualityJudge()
        for (id in 1..5) judge.judge(PhotoMeasurer.measure(SyntheticPage(id).photo()), noCut, false)
        val title = SyntheticPage(20, options = PageOptions(blankish = true))
        assertFalse(judge.judge(PhotoMeasurer.measure(title.photo()), noCut, false).blurred)
    }

    @Test
    fun `最初の 1 枚でも、ひどくぼけていれば知らせる`() {
        assertTrue(QualityJudge().judge(PhotoMeasurer.measure(SyntheticPage(1).photo(blurRadius = 4)), noCut, false).blurred)
        assertFalse(QualityJudge().judge(PhotoMeasurer.measure(SyntheticPage(1).photo()), noCut, false).blurred)
    }

    @Test
    fun `撮っているあいだに動いた写真は、少しのぼけでも知らせる`() {
        fun judgeAfterHistory(moved: Boolean): Boolean {
            val judge = QualityJudge()
            for (id in 1..5) judge.judge(PhotoMeasurer.measure(SyntheticPage(id).photo()), noCut, false)
            return judge.judge(PhotoMeasurer.measure(SyntheticPage(9).photo(blurRadius = 1)), noCut, moved).blurred
        }
        assertFalse(judgeAfterHistory(moved = false))
        assertTrue(judgeAfterHistory(moved = true))
    }

    @Test
    fun `暗すぎる写真を知らせる`() {
        val judge = QualityJudge()
        assertFalse(judge.judge(PhotoMeasurer.measure(SyntheticPage(1).photo(gain = 0.7f)), noCut, false).dark)
        assertTrue(judge.judge(PhotoMeasurer.measure(SyntheticPage(1).photo(gain = 0.3f, noiseSigma = 4f)), noCut, false).dark)
    }

    @Test
    fun `知らせる内容は数値にまとめて戻せる`() {
        val w = PhotoWarnings(cut = setOf(Side.RIGHT, Side.BOTTOM), blurred = true, dark = false)
        assertEquals(w, PhotoWarnings.fromBits(w.toBits()))
        assertFalse(PhotoWarnings.fromBits(0).any)
    }
}
