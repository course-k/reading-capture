package app.readingcapture.capture

import android.graphics.Bitmap
import app.readingcapture.core.detect.PageFingerprint
import app.readingcapture.core.quality.TextBox
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import java.util.concurrent.TimeUnit

/**
 * 端末の中の文字認識（ML Kit）を、2 つのことにだけ使う。
 *
 * 1. ページを見分けるための指紋を作る。読めた文字を、続く 3 文字ずつの組の数値（ハッシュ）に変える
 * 2. 文字の行がどこにあるか（枠）を取り出す。撮った写真で、文字が端にかかっていないかを見るため
 *
 * どちらも、読めた文字は、この中で数値や枠に変えたらすぐ捨てる。文字のままでは、覚えも、保存も、送りもしない。
 * 認識の部品はアプリに同梱してあるので、通信はしない。
 * https://developers.google.com/ml-kit/vision/text-recognition/v2/android （2026-11-20 に確認）
 *
 * どの関数も、結果を待つので、画面のスレッドからは呼ばないこと。
 */
class TextLineDetector {

    /** 写真 1 枚を読んだ結果。 */
    class Reading(val boxes: List<TextBox>, val fingerprint: PageFingerprint)

    private val recognizer by lazy { TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()) }

    private var cropBitmap: Bitmap? = null
    private var cropPixels = IntArray(0)

    /** 画像から、行の枠と、ページの指紋を取り出す。 */
    fun read(bitmap: Bitmap): Reading {
        val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 10, TimeUnit.SECONDS)
        val boxes = ArrayList<TextBox>()
        for (block in text.textBlocks) for (line in block.lines) {
            val r = line.boundingBox ?: continue
            boxes += TextBox(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat())
        }
        return Reading(boxes, fingerprintOf(text))
    }

    fun detect(bitmap: Bitmap): List<TextBox> = read(bitmap).boxes

    fun fingerprint(bitmap: Bitmap): PageFingerprint = read(bitmap).fingerprint

    /**
     * カメラの映像のコマ（明るさの面）の、まん中の部分だけを読んで、指紋を作る。
     *
     * ページ全体を読むと、実機（Pixel 6a）で 1 回 0.6〜1.5 秒かかり、撮るまでが遅くなる。
     * 見分けるだけなら一部で足りるので、まん中（縦横それぞれ [CENTER_FRACTION]）だけを読む。
     * ページ全体の指紋は、撮ったあとの写真から作って補う。
     *
     * @param rotationDegrees コマを正しい向きにするのに要る回転
     */
    fun fingerprintOfCenter(y: ByteArray, width: Int, height: Int, rowStride: Int, rotationDegrees: Int): PageFingerprint {
        val cw = (width * CENTER_FRACTION).toInt() and 1.inv()
        val ch = (height * CENTER_FRACTION).toInt() and 1.inv()
        val x0 = (width - cw) / 2
        val y0 = (height - ch) / 2
        if (cropPixels.size != cw * ch) cropPixels = IntArray(cw * ch)
        val pixels = cropPixels
        var i = 0
        for (row in 0 until ch) {
            var p = (y0 + row) * rowStride + x0
            for (col in 0 until cw) {
                val v = y[p++].toInt() and 0xFF
                pixels[i++] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        val bitmap = cropBitmap?.takeIf { it.width == cw && it.height == ch }
            ?: Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888).also { cropBitmap = it }
        bitmap.setPixels(pixels, 0, cw, 0, 0, cw, ch)
        val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, rotationDegrees)), 5, TimeUnit.SECONDS)
        return fingerprintOf(text)
    }

    private fun fingerprintOf(text: Text): PageFingerprint {
        val lines = ArrayList<String>()
        for (block in text.textBlocks) for (line in block.lines) lines += line.text
        return PageFingerprint.of(lines)
    }

    companion object {
        const val CENTER_FRACTION = 0.62f
    }
}
