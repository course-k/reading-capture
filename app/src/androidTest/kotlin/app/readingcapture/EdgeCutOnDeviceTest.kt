package app.readingcapture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.readingcapture.capture.TextLineDetector
import app.readingcapture.core.quality.EdgeCutCheck
import app.readingcapture.core.quality.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 端末の中の文字認識を実際に動かして、「文字が端にかかっているか」を確かめる。
 * 画像は合成したページ（乱数で選んだ文字）で、本物の本は使わない。
 * 作り方: `./gradlew :core:test --tests '*SampleGenerator*' -Psamples=1` で core/build/samples/assets に出る。
 */
@RunWith(AndroidJUnit4::class)
class EdgeCutOnDeviceTest {

    private val detector = TextLineDetector()

    private fun sides(asset: String): Pair<Set<Side>, Int> {
        val context = InstrumentationRegistry.getInstrumentation().context
        val source = context.assets.open(asset).use { BitmapFactory.decodeStream(it) }
        // アプリが点検に使う大きさ（長い辺 1536）に合わせる
        val bitmap = Bitmap.createScaledBitmap(source, 1152, 1536, true)
        // アプリと同じ道筋: 写真を読んで、ページ番号の位置から外側を決め、切れを見る
        val reading = detector.read(bitmap)
        return EdgeCutCheck.evaluate(reading.boxes, bitmap.width, bitmap.height, outerSide = reading.outerSide).sides to reading.boxes.size
    }

    @Test
    fun 余白ごと入っているページは知らせない() {
        val (s, lines) = sides("page_ok.png")
        assertTrue("行が見つかっていない: $lines", lines >= 15)
        assertEquals(emptySet<Side>(), s)
    }

    @Test
    fun 欄外の注まで入っているページは知らせない() {
        val (s, lines) = sides("page_notes_ok.png")
        assertTrue("行が見つかっていない: $lines", lines >= 15)
        assertEquals(emptySet<Side>(), s)
    }

    @Test
    fun 欄外の注が右に切れていれば右を知らせる() {
        assertEquals(setOf(Side.RIGHT), sides("page_notes_cut_right.png").first)
    }

    @Test
    fun 上が切れていれば上を知らせる() {
        assertEquals(setOf(Side.TOP), sides("page_cut_top.png").first)
    }

    @Test
    fun 左が切れていれば左を知らせる() {
        assertEquals(setOf(Side.LEFT), sides("page_cut_left.png").first)
    }
}
