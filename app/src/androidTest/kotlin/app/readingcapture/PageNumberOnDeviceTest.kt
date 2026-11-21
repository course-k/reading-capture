package app.readingcapture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.readingcapture.capture.TextLineDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 端末の中の文字認識を実際に動かして、ページ番号が読めるかを確かめる。
 * 合成したページは、下のまん中に「ページの通し番号 + 30」を刷ってある（page_ok は 31、page_notes_ok は 32）。
 */
@RunWith(AndroidJUnit4::class)
class PageNumberOnDeviceTest {

    private val detector = TextLineDetector()

    private fun view(asset: String, scale: Float = 1f, rotate: Float = 0f, dx: Float = 0f, dy: Float = 0f): Bitmap {
        val context = InstrumentationRegistry.getInstrumentation().context
        val source = context.assets.open(asset).use { BitmapFactory.decodeStream(it) }
        val out = Bitmap.createBitmap(960, 1280, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.rgb(96, 96, 96))
        val m = Matrix()
        m.postScale(960f / source.width, 1280f / source.height)
        m.postScale(scale, scale, 480f, 640f)
        m.postRotate(rotate, 480f, 640f)
        m.postTranslate(dx, dy)
        canvas.drawBitmap(source, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /**
     * 縦に持ったスマホのカメラが返す形（横長 1280×960 の明るさの面。90 度回すと正しい向きになる）に直す。
     * 面の (x, y) は、正しい向きの画像の (960 - 1 - y, x) に当たる。
     */
    private fun sensorPlane(upright: Bitmap): ByteArray {
        val pixels = IntArray(960 * 1280)
        upright.getPixels(pixels, 0, 960, 0, 0, 960, 1280)
        val plane = ByteArray(1280 * 960)
        for (y in 0 until 960) for (x in 0 until 1280) {
            val p = pixels[x * 960 + (959 - y)]
            plane[y * 1280 + x] = ((((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8).toByte()
        }
        return plane
    }

    @Test
    fun 写真から_ページ番号が読める() {
        assertEquals(31, detector.read(view("page_ok.png")).pageNumber)
        assertEquals(32, detector.read(view("page_notes_ok.png")).pageNumber)
        assertEquals(31, detector.read(view("page_ok.png", scale = 0.88f, rotate = 4f, dx = 20f)).pageNumber)
    }

    @Test
    fun 映像のコマの_上下の帯だけから_ページ番号が読める() {
        val result = detector.glimpse(sensorPlane(view("page_ok.png")), 1280, 960, 1280, 90)
        assertTrue("帯の中の文字 ${result.chars}", result.glimpse.hasText)
        assertEquals(31, result.glimpse.pageNumber)
        // 少しずれたり傾いたりしても
        val moved = detector.glimpse(sensorPlane(view("page_notes_ok.png", scale = 0.93f, rotate = -3f, dy = -10f)), 1280, 960, 1280, 90)
        // このページは下が余白で、下の帯に本文の行が入らない。番号だけでも読めること
        assertEquals(32, moved.glimpse.pageNumber)
    }

    @Test
    fun 帯は_ページ全体より読む文字が少ない() {
        val upright = view("page_ok.png")
        val whole = detector.read(upright).chars
        val band = detector.glimpse(sensorPlane(upright), 1280, 960, 1280, 90).chars
        assertTrue("帯 $band 文字、全体 $whole 文字", band < whole / 2)
    }

    @Test
    fun ページ番号が画面から切れていれば_番号なしと答える() {
        // 下が切れて、番号が写っていない
        val result = detector.glimpse(sensorPlane(view("page_ok.png", dy = 130f)), 1280, 960, 1280, 90)
        assertNull(result.glimpse.pageNumber)
        assertTrue(result.glimpse.hasText)
    }

    @Test
    fun 机だけなら_文字なしと答える() {
        val blank = Bitmap.createBitmap(960, 1280, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(96, 96, 96)) }
        val result = detector.glimpse(sensorPlane(blank), 1280, 960, 1280, 90)
        assertNull(result.glimpse.pageNumber)
        assertFalse(result.glimpse.hasText)
    }
}
