package app.readingcapture.capture

import android.graphics.Bitmap
import app.readingcapture.core.detect.NumberCandidate
import app.readingcapture.core.detect.PageFingerprint
import app.readingcapture.core.detect.PageGlimpse
import app.readingcapture.core.detect.PageNumberPicker
import app.readingcapture.core.detect.ReadLine
import app.readingcapture.core.quality.Side
import app.readingcapture.core.quality.TextBox
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import java.util.concurrent.TimeUnit

/**
 * 端末の中の文字認識（ML Kit）を、3 つのことにだけ使う。
 *
 * 1. ページ番号を読む（数字だけ）
 * 2. ページを見分けるための指紋を作る。読めた文字を、続く 3 文字ずつの組の数値（ハッシュ）に変える
 * 3. 文字の行がどこにあるか（枠）を取り出す。撮った写真で、文字が端にかかっていないかを見るため
 *
 * どれも、読めた文字は、この中で数字・数値・枠に変えたらすぐ捨てる。文字のままでは、覚えも、保存も、送りもしない。
 * 認識の部品はアプリに同梱してあるので、通信はしない。
 * https://developers.google.com/ml-kit/vision/text-recognition/v2/android （2026-11-20 に確認）
 *
 * どの関数も、結果を待つので、画面のスレッドからは呼ばないこと。
 */
class TextLineDetector {

    /** 写真 1 枚を読んだ結果。 */
    class Reading(
        val boxes: List<TextBox>,
        val fingerprint: PageFingerprint,
        val pageNumber: Int?,
        /** ページの外側（綴じ目の反対側）。ページ番号が左右どちらかの隅にあれば、そちら。分からなければ null */
        val outerSide: Side?,
        val numbers: List<NumberCandidate>,
    )

    /** ページ番号のあたりを読んだ結果と、読めた文字の数（記録用）。 */
    class GlimpseResult(val glimpse: PageGlimpse, val chars: Int, val numbers: List<NumberCandidate> = emptyList(), val lines: List<ReadLine> = emptyList())

    private val recognizer by lazy { TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()) }

    private var cropPixels = IntArray(0)

    /** 画像から、行の枠・ページの指紋・ページ番号を取り出す。 */
    fun read(bitmap: Bitmap): Reading {
        val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 10, TimeUnit.SECONDS)
        val (lines, numbers) = layout(text)
        val number = PageNumberPicker.pickCandidate(numbers, lines)
        val outer = number?.let {
            val center = (it.box.left + it.box.right) / 2 / bitmap.width
            when {
                center < 0.4f -> Side.LEFT
                center > 0.6f -> Side.RIGHT
                else -> null
            }
        }
        return Reading(lines.map { it.box }, fingerprintOf(text), number?.value, outer, numbers)
    }

    fun detect(bitmap: Bitmap): List<TextBox> = read(bitmap).boxes

    fun fingerprint(bitmap: Bitmap): PageFingerprint = read(bitmap).fingerprint

    /**
     * カメラの映像のコマ（明るさの面）の、上の帯と下の帯だけを読んで、ページ番号を探す。
     *
     * 読む時間は文字の量にほぼ比例する（実機で、ページ全体は 0.6〜1.5 秒、98 文字なら 0.14 秒）。
     * ページ番号は上か下の端にあるので、そこだけ読めば速い。
     *
     * @param rotationDegrees コマを正しい向きにするのに要る回転。スマホを縦に持つと 90
     */
    fun glimpse(
        y: ByteArray, width: Int, height: Int, rowStride: Int, rotationDegrees: Int,
        topBand: Float = TOP_BAND, bottomBand: Float = BOTTOM_BAND, upscale: Float = UPSCALE,
    ): GlimpseResult {
        if (rotationDegrees != 90) {
            // 縦に持つ前提の切り出しが使えない向き。全体を読む（遅いが、正しく動く）
            val text = process(fill(y, rowStride, intArrayOf(0, width), height), rotationDegrees)
            return toGlimpse(text)
        }
        // 縦に持ったとき、映像の上は面の左、下は面の右に当たる。左右の帯を横に並べると、起こしたときに上下に並ぶ
        val top = (width * topBand).toInt()
        val bottom = (width * bottomBand).toInt()
        val columns = if (top > 0) intArrayOf(0, top, width - bottom, width) else intArrayOf(width - bottom, width)
        var bitmap = fill(y, rowStride, columns, height)
        if (upscale != 1f) bitmap = Bitmap.createScaledBitmap(bitmap, (bitmap.width * upscale).toInt(), (bitmap.height * upscale).toInt(), true)
        return toGlimpse(process(bitmap, rotationDegrees))
    }

    /**
     * カメラの映像のコマ（明るさの面）の、まん中の部分だけを読んで、指紋を作る。
     * ページ番号が「もう撮った番号」だったときに、番号の読み違いでないかを確かめるのに使う。
     */
    fun fingerprintOfCenter(y: ByteArray, width: Int, height: Int, rowStride: Int, rotationDegrees: Int): PageFingerprint {
        val cw = (width * CENTER_FRACTION).toInt()
        val ch = (height * CENTER_FRACTION).toInt()
        val x0 = (width - cw) / 2
        val y0 = (height - ch) / 2
        return fingerprintOf(process(fill(y, rowStride, intArrayOf(x0, x0 + cw), ch, y0), rotationDegrees))
    }

    // ---- 内部 ----

    /** 面の中の、横の範囲（始まりと終わりの組を並べたもの）を、灰色の画像に写す。 */
    private fun fill(y: ByteArray, rowStride: Int, columns: IntArray, rows: Int, rowOffset: Int = 0): Bitmap {
        var w = 0
        var k = 0
        while (k < columns.size) {
            w += columns[k + 1] - columns[k]
            k += 2
        }
        if (cropPixels.size != w * rows) cropPixels = IntArray(w * rows)
        val pixels = cropPixels
        var i = 0
        for (row in 0 until rows) {
            val base = (rowOffset + row) * rowStride
            k = 0
            while (k < columns.size) {
                for (x in columns[k] until columns[k + 1]) {
                    val v = y[base + x].toInt() and 0xFF
                    pixels[i++] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                }
                k += 2
            }
        }
        return Bitmap.createBitmap(pixels, w, rows, Bitmap.Config.ARGB_8888)
    }

    private fun process(bitmap: Bitmap, rotationDegrees: Int): Text =
        Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, rotationDegrees)), 5, TimeUnit.SECONDS)

    private fun toGlimpse(text: Text): GlimpseResult {
        val (lines, numbers) = layout(text)
        val chars = lines.sumOf { it.chars }
        val number = PageNumberPicker.pick(numbers, lines, edgeBandOnly = true)
        return GlimpseResult(PageGlimpse(number, hasText = number != null || chars >= MIN_TEXT_CHARS), chars, numbers, lines)
    }

    /** 行の枠と文字数、数字だけの短い語を取り出す。文字は持ち出さない。 */
    private fun layout(text: Text): Pair<List<ReadLine>, List<NumberCandidate>> {
        val lines = ArrayList<ReadLine>()
        val numbers = ArrayList<NumberCandidate>()
        for (block in text.textBlocks) for (line in block.lines) {
            val lr = line.boundingBox ?: continue
            val chars = line.text.count { !it.isWhitespace() }
            lines += ReadLine(TextBox(lr.left.toFloat(), lr.top.toFloat(), lr.right.toFloat(), lr.bottom.toFloat()), chars)
            for (element in line.elements) {
                val t = element.text.trim()
                if (t.length !in 1..3 || !t.all { it in '0'..'9' }) continue
                val r = element.boundingBox ?: continue
                numbers += NumberCandidate(t.toInt(), TextBox(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat()), chars)
            }
        }
        return lines to numbers
    }

    private fun fingerprintOf(text: Text): PageFingerprint {
        val lines = ArrayList<String>()
        for (block in text.textBlocks) for (line in block.lines) lines += line.text
        return PageFingerprint.of(lines)
    }

    companion object {
        const val CENTER_FRACTION = 0.62f

        /**
         * ページ番号を探す帯の高さ（映像の高さに対する割合）。
         * 狭くすると速いが、帯に番号しか入らないとき（ページの下が余白や図）に、番号を見つけられなくなる
         * （エミュレーターで、上 14%・下 24% では読めず、上 20%・下 30% で読めた）。
         */
        const val TOP_BAND = 0.20f
        const val BOTTOM_BAND = 0.30f
        const val UPSCALE = 1f

        /**
         * 帯の中にこれだけ文字があれば、ページが写っているとみなす（番号が読めなかったとき、先に撮るかどうかの境目）。
         * 実機で、ページをきちんと映したときの帯の文字は 93〜666。めくっている途中に撮ってしまったときは 9 だった。
         */
        const val MIN_TEXT_CHARS = 40
    }
}
