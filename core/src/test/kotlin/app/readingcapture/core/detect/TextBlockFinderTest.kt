package app.readingcapture.core.detect

import app.readingcapture.core.synthetic.SceneRenderer
import app.readingcapture.core.synthetic.SceneState
import app.readingcapture.core.synthetic.SyntheticPage
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 合成したページで、文字のかたまりの範囲が取れることを確かめる。 */
class TextBlockFinderTest {

    private val renderer = SceneRenderer()
    private val page = SyntheticPage(31)

    private fun find(state: SceneState) = TextBlockFinder.find(renderer.render(state).downsampleToLongSide(320))

    @Test
    fun `ページが収まっていれば、本文の範囲が端から離れて取れる`() {
        val block = find(SceneState(page))
        assertNotNull(block)
        block!!
        assertTrue("$block", block.left > 0.03f && block.right < 0.97f && block.top > 0.02f && block.bottom < 0.98f)
        assertTrue("本文は映像の半分より広い: $block", block.right - block.left > 0.5f && block.bottom - block.top > 0.5f)
    }

    @Test
    fun `ページが左へはみ出していれば、範囲が左の端に付く`() {
        val block = find(SceneState(page, dx = -110f))
        assertNotNull(block)
        assertTrue("$block", block!!.left < 0.02f)
    }

    @Test
    fun `机だけなら、本文は無い、または狭い`() {
        val block = find(SceneState(null))
        assertTrue("$block", block == null || (block.right - block.left) * (block.bottom - block.top) < 0.2f)
    }

    @Test
    fun `少し動いただけなら重なりは大きく、大きく動けば小さい`() {
        val a = find(SceneState(page))!!
        val near = find(SceneState(page, dx = 4f, dy = -3f))!!
        val far = find(SceneState(page, dx = 120f))!!
        assertTrue("near ${TextBlockFinder.overlap(a, near)}", TextBlockFinder.overlap(a, near) > 0.85f)
        assertTrue("far ${TextBlockFinder.overlap(a, far)}", TextBlockFinder.overlap(a, far) < 0.8f)
    }

    @Test
    fun `暗くて何も写っていなければ null`() {
        assertNull(find(SceneState(page, gain = 0.02f)))
    }
}
