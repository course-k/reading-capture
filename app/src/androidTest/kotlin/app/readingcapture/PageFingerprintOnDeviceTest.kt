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
import app.readingcapture.core.detect.PageFingerprint
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 端末の中の文字認識を実際に動かして、「同じページかどうか」を文字の指紋で見分けられるかを確かめる。
 * スマホを手に持つと、同じページでも位置・大きさ・傾きが毎回変わる。それでも同じと分かり、違うページは違うと分かること。
 * 画像は合成したページ（乱数で選んだ文字）で、本物の本は使わない。
 */
@RunWith(AndroidJUnit4::class)
class PageFingerprintOnDeviceTest {

    private val detector = TextLineDetector()

    /** 判定用の映像（1280×960 を縦にしたもの）と同じ大きさにして、位置・大きさ・傾きを変える。 */
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

    private fun fp(bitmap: Bitmap): PageFingerprint = detector.fingerprint(bitmap)

    @Test
    fun 同じページは_位置や大きさや傾きが変わっても_同じと分かる() {
        val base = fp(view("page_ok.png"))
        assertTrue("読めた文字が少ない: ${base.charCount}", base.charCount > 300)
        val views = mapOf(
            "ずれ" to view("page_ok.png", dx = 60f, dy = -40f),
            "小さく" to view("page_ok.png", scale = 0.8f),
            "大きく" to view("page_ok.png", scale = 1.15f),
            "傾き +7 度" to view("page_ok.png", rotate = 7f),
            "傾き -10 度と小さく" to view("page_ok.png", scale = 0.85f, rotate = -10f, dx = 30f),
            "左が切れた" to view("page_cut_left.png"),
            "上が切れた" to view("page_cut_top.png"),
        )
        val report = StringBuilder()
        var worst = 1f
        for ((name, v) in views) {
            val s = PageFingerprint.similarity(base, fp(v))
            report.append("$name=%.2f ".format(s))
            if (s < worst) worst = s
        }
        // 乱数の文字は読み違いが多い（実測で 0.31〜0.43）。見分けのしきい値は 0.2
        assertTrue("同じページの重なり: $report", worst >= 0.28f)
    }

    @Test
    fun 違うページは_違うと分かる() {
        val a = fp(view("page_ok.png"))
        val b = fp(view("page_notes_ok.png"))
        val s = PageFingerprint.similarity(a, b)
        assertTrue("違うページの重なり $s", s < 0.08f)
        val s2 = PageFingerprint.similarity(fp(view("page_ok.png", scale = 0.85f, rotate = 5f)), fp(view("page_notes_cut_right.png", rotate = -4f)))
        assertTrue("違うページの重なり $s2", s2 < 0.08f)
    }

    @Test
    fun 机だけなら_ページとは見なさない() {
        val blank = Bitmap.createBitmap(960, 1280, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(96, 96, 96)) }
        assertTrue(fp(blank).charCount < 10)
    }
}
