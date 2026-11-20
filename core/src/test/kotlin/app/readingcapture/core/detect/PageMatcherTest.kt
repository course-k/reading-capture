package app.readingcapture.core.detect

import app.readingcapture.core.frame.LumaImage
import app.readingcapture.core.synthetic.Hand
import app.readingcapture.core.synthetic.PageOptions
import app.readingcapture.core.synthetic.SceneRenderer
import app.readingcapture.core.synthetic.SceneState
import app.readingcapture.core.synthetic.Shadow
import app.readingcapture.core.synthetic.SyntheticPage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageMatcherTest {

    private val renderer = SceneRenderer()
    private val matcher = PageMatcher()

    private fun work(state: SceneState, frame: Int = 0): LumaImage = renderer.render(state, frame).downsampleToLongSide(320)

    private fun compare(a: SceneState, b: SceneState): MatchResult =
        matcher.compare(matcher.prepare(work(a, 1)), matcher.prepare(work(b, 2)))

    private val page = SyntheticPage(1)

    @Test
    fun `動いていなければ同じページ`() {
        val r = compare(SceneState(page), SceneState(page))
        assertTrue(r.toString(), r.same)
        assertTrue(r.identical)
    }

    @Test
    fun `少しずれても同じページ`() {
        for ((dx, dy) in listOf(4f to -3f, 12f to 8f, -30f to 20f, 60f to -40f)) {
            val r = compare(SceneState(page), SceneState(page, dx = dx, dy = dy))
            assertTrue("ずれ ($dx,$dy): $r", r.same)
        }
    }

    @Test
    fun `少し回っても、反りが変わっても同じページ`() {
        assertTrue(compare(SceneState(page), SceneState(page, rotationDeg = 1.2f)).same)
        assertTrue(compare(SceneState(page), SceneState(page, curl = 6f)).same)
        assertTrue(compare(SceneState(page, curl = 5f), SceneState(page, curl = -3f, dx = 5f)).same)
    }

    @Test
    fun `明るさや影が変わっても同じページ`() {
        assertTrue(compare(SceneState(page), SceneState(page, gain = 0.65f)).same)
        assertTrue(compare(SceneState(page), SceneState(page, gain = 1.08f)).same)
        val shadow = Shadow(cx = 150f, cy = 250f, radius = 300f, strength = 0.45f)
        assertTrue(compare(SceneState(page), SceneState(page, shadow = shadow)).same)
        assertTrue(compare(SceneState(page, shadow = shadow), SceneState(page, shadow = shadow.copy(cx = 380f, cy = 500f))).same)
    }

    @Test
    fun `手が一部を隠しても同じページ`() {
        val r = compare(SceneState(page), SceneState(page, hand = Hand(tipX = 260f, tipY = 400f)))
        assertTrue(r.toString(), r.same)
        val r2 = compare(SceneState(page, hand = Hand(tipX = 300f, tipY = 200f)), SceneState(page, dx = 6f))
        assertTrue(r2.toString(), r2.same)
    }

    @Test
    fun `少しぼけても同じページ`() {
        val r = compare(SceneState(page), SceneState(page, blurRadius = 1, dx = 3f))
        assertTrue(r.toString(), r.same)
    }

    @Test
    fun `同じ組み方の別のページは違うページ`() {
        // 同じ本のページは行の位置がそろっている。それでも違うと分かること
        for (i in 2..31) {
            val other = SyntheticPage(i)
            val r = compare(SceneState(page), SceneState(other))
            assertFalse("ページ $i: $r", r.same)
        }
    }

    @Test
    fun `図も見出しも無い文字だけのページどうしでも違うページ`() {
        val plain = PageOptions(figure = false, heading = false)
        for (i in 0 until 20) {
            val a = SyntheticPage(100 + i, options = plain)
            val b = SyntheticPage(200 + i, options = plain)
            val r = compare(SceneState(a), SceneState(b, dx = (i % 5) * 3f, dy = (i % 3) * 4f))
            assertFalse("組 $i: $r", r.same)
        }
    }

    @Test
    fun `欄外に注のあるページどうしでも違うページ`() {
        val notes = PageOptions(marginNotes = true)
        for (i in 0 until 10) {
            val r = compare(SceneState(SyntheticPage(300 + i, options = notes)), SceneState(SyntheticPage(400 + i, options = notes)))
            assertFalse("組 $i: $r", r.same)
        }
    }

    @Test
    fun `ページと机だけでは違う`() {
        assertFalse(compare(SceneState(page), SceneState(null)).same)
    }

    @Test
    fun `模様のある区画の割合はページで高く机で低い`() {
        val pageTex = matcher.texturedFraction(matcher.prepare(work(SceneState(page))))
        val deskTex = matcher.texturedFraction(matcher.prepare(work(SceneState(null))))
        assertTrue("ページ $pageTex", pageTex > 0.4f)
        assertTrue("机 $deskTex", deskTex < 0.08f)
    }
}
