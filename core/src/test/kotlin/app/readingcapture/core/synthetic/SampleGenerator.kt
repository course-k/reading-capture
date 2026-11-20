package app.readingcapture.core.synthetic

import app.readingcapture.core.frame.LumaImage
import org.junit.Assume
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * エミュレーターのカメラに見せるための、合成した画像と映像のコマを書き出す。
 * ふだんのテストでは動かない。`./gradlew :core:test --tests '*SampleGenerator*' -Psamples=1` で動かす。
 * 出力は core/build/samples/。本物の本のページは使わない。
 */
class SampleGenerator {

    private fun write(image: LumaImage, file: File) {
        val out = BufferedImage(image.width, image.height, BufferedImage.TYPE_BYTE_GRAY)
        out.raster.setDataElements(0, 0, image.width, image.height, image.data.copyOf(image.width * image.height))
        file.parentFile.mkdirs()
        ImageIO.write(out, "png", file)
    }

    @Test
    fun generate() {
        Assume.assumeTrue(System.getProperty("samples") != null)
        val dir = File("build/samples")
        dir.deleteRecursively()
        val renderer = SceneRenderer(960, 1280)
        val scale = 2f // 台本の座標は 480×640 で書いてあるので、2 倍にする
        fun SceneState.scaled() = copy(
            dx = dx * scale, dy = dy * scale, curl = curl * scale,
            hand = hand?.let { it.copy(tipX = it.tipX * scale, tipY = it.tipY * scale, size = it.size * scale) },
            noiseSigma = 1.2f,
        )
        val pages = listOf(
            SyntheticPage(1),
            SyntheticPage(2, options = PageOptions(marginNotes = true)),
            SyntheticPage(3, options = PageOptions(marginNotes = true)),
            SyntheticPage(4, options = PageOptions(figure = false)),
        )
        // 静止画
        write(renderer.render(SceneState(pages[0]).scaled()), File(dir, "still-page1.png"))
        write(renderer.render(SceneState(pages[1], dx = 40f).scaled()), File(dir, "still-cut-right.png"))
        // 文字認識の確かめ用（アプリの androidTest の assets に写す）
        val assets = File(dir, "assets")
        write(renderer.render(SceneState(pages[0]).scaled()), File(assets, "page_ok.png"))
        write(renderer.render(SceneState(pages[1]).scaled()), File(assets, "page_notes_ok.png"))
        write(renderer.render(SceneState(pages[1], dx = 60f).scaled()), File(assets, "page_notes_cut_right.png"))
        write(renderer.render(SceneState(pages[0], dy = -95f).scaled()), File(assets, "page_cut_top.png"))
        write(renderer.render(SceneState(pages[0], dx = -70f).scaled()), File(assets, "page_cut_left.png"))

        // 映像: 4 ページを順にめくる。3 ページ目は、欄外の注が右に切れる位置
        val script = VideoScript(fps = 15)
        script.hold(SceneState(pages[0]), 6000)
        script.turn(pages[0], pages[1])
        script.hold(SceneState(pages[1]), 5000)
        script.turn(pages[1], pages[2], base = SceneState(null, dx = 40f))
        script.hold(SceneState(pages[2], dx = 40f), 5000)
        script.turn(pages[2], pages[3])
        script.hold(SceneState(pages[3]), 5000)
        // 手が入って出る（撮り直さないはず）
        script.animate(500) { t -> SceneState(pages[3], hand = Hand(tipX = 520f - 260f * t, tipY = 380f)) }
        script.hold(SceneState(pages[3], hand = Hand(260f, 380f)), 1500)
        script.animate(500) { t -> SceneState(pages[3], hand = Hand(tipX = 260f + 300f * t, tipY = 380f)) }
        script.hold(SceneState(pages[3]), 4000)
        for ((i, state) in script.frames.withIndex()) {
            write(renderer.render(state.scaled(), i), File(dir, "video/f%05d.png".format(i)))
        }
        File(dir, "video-info.txt").writeText("fps=15 frames=${script.frames.size} seconds=${script.durationMs / 1000}\n")
    }
}
