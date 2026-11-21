package app.readingcapture.core.quality

import app.readingcapture.core.frame.LumaImage
import app.readingcapture.core.synthetic.PageOptions
import app.readingcapture.core.synthetic.SceneRenderer
import app.readingcapture.core.synthetic.SceneState
import app.readingcapture.core.synthetic.Shadow
import app.readingcapture.core.synthetic.SyntheticPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/** 合成したページで、文字が端に届いているかの判定を確かめる。 */
class MarginCheckTest {

    private val renderer = SceneRenderer(width = 960, height = 1280)
    private val page = SyntheticPage(41)

    // 描く大きさを 2 倍にしたので、ずらす量も 2 倍の画素で指定する
    private fun cut(state: SceneState, outer: Side? = null) = MarginCheck.evaluate(renderer.render(state), outer).sides

    @Test
    fun `ページが収まっていれば、どの端も知らせない`() {
        assertEquals(emptySet<Side>(), cut(SceneState(page)))
    }

    @Test
    fun `傾いて写っていても、収まっていれば知らせない`() {
        // 実機で、行が傾いて写った写真（行の枠が縦に 135〜192 画素まで伸びた）に、上下の誤った知らせが出ていた
        for (deg in listOf(-12f, -6f, 6f, 12f)) {
            assertEquals("傾き $deg 度", emptySet<Side>(), cut(SceneState(page, rotationDeg = deg, scale = 0.86f)))
        }
    }

    @Test
    fun `下半分が影でも、収まっていれば知らせない`() {
        // 実機の写真は、スマホと手の影で、下のほうの紙の明るさが半分ほどになっていた
        assertEquals(emptySet<Side>(), cut(SceneState(page, shadow = Shadow(cx = 900f, cy = 1250f, radius = 1100f, strength = 0.55f))))
    }

    @Test
    fun `上がはみ出していれば、上を知らせる`() {
        assertTrue(Side.TOP in cut(SceneState(page, dy = -200f)))
    }

    @Test
    fun `下がはみ出していれば、下を知らせる`() {
        assertTrue(Side.BOTTOM in cut(SceneState(page, dy = 200f)))
    }

    @Test
    fun `本文が左右にはみ出していれば、外側が分からなくても知らせる`() {
        assertTrue(Side.LEFT in cut(SceneState(page, dx = -220f)))
        assertTrue(Side.RIGHT in cut(SceneState(page, dx = 220f)))
    }

    @Test
    fun `欄外の注が外側の端で切れていれば知らせ、同じ並びが綴じ目側なら知らせない`() {
        val notes = SyntheticPage(42, options = PageOptions(marginNotes = true))
        val state = SceneState(notes, dx = 110f)
        assertTrue("外側が右: ${cut(state, Side.RIGHT)}", Side.RIGHT in cut(state, Side.RIGHT))
        // 綴じ目側に同じ幅の文字が端まで届いているのは、隣のページの写り込み
        assertTrue("外側が左: ${cut(state, Side.LEFT)}", Side.RIGHT !in cut(state, Side.LEFT))
        assertTrue("外側が分からない: ${cut(state, null)}", Side.RIGHT !in cut(state, null))
    }

    /**
     * 手元の写真で数値を見るためのもの（ふだんは動かない）。環境変数 MARGIN_PHOTOS_DIR に、写真のあるフォルダを渡す。
     * 写真は読むだけで、どこにも書き出さない。出すのは、端ごとの数だけ。
     */
    @Test
    fun `手元の写真の数値を出す`() {
        val dir = System.getenv("MARGIN_PHOTOS_DIR")
        assumeTrue("MARGIN_PHOTOS_DIR が無いので飛ばす", !dir.isNullOrBlank())
        for (file in File(dir!!).listFiles { f -> f.extension.lowercase() == "jpg" }!!.sortedBy { it.name }) {
            val img = ImageIO.read(file)
            val w = img.width
            val h = img.height
            val row = IntArray(w)
            val luma = ByteArray(w * h)
            for (y in 0 until h) {
                img.getRGB(0, y, w, 1, row, 0, w)
                for (x in 0 until w) {
                    val p = row[x]
                    luma[y * w + x] = ((((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8).toByte()
                }
            }
            val image = LumaImage(w, h, luma).downsampleToLongSide(1536)
            val t = MarginCheck.touches(MarginCheck.textCells(image))
            val text = t.entries.joinToString(" ") { "${it.key.name[0]}=${it.value.count}/${it.value.bodyCount}" }
            println("MARGIN ${file.name.padEnd(26)} $text  外側なし=${MarginCheck.evaluate(image).sides} 左=${MarginCheck.evaluate(image, Side.LEFT).sides} 右=${MarginCheck.evaluate(image, Side.RIGHT).sides}")
        }
    }
}
