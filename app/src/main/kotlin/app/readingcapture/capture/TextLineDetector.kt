package app.readingcapture.capture

import android.graphics.Bitmap
import app.readingcapture.core.quality.TextBox
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import java.util.concurrent.TimeUnit

/**
 * 画像の中の「文字の行がどこにあるか」だけを取り出す。
 *
 * 端末の中の文字認識（ML Kit）を使うが、受け取るのは行の枠だけ。読み取った文字は見ないし、残さない。
 * 認識の部品はアプリに同梱してあるので、通信はしない。
 * https://developers.google.com/ml-kit/vision/text-recognition/v2/android （2026-11-20 に確認）
 */
class TextLineDetector {

    private val recognizer by lazy { TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()) }

    /** 画面のスレッドからは呼ばないこと（結果を待つため）。 */
    fun detect(bitmap: Bitmap): List<TextBox> {
        val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 10, TimeUnit.SECONDS)
        val boxes = ArrayList<TextBox>()
        for (block in text.textBlocks) for (line in block.lines) {
            val r = line.boundingBox ?: continue
            boxes += TextBox(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat())
        }
        return boxes
    }
}
