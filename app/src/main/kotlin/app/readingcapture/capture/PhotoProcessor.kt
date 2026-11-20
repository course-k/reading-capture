package app.readingcapture.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import app.readingcapture.core.frame.LumaImage
import app.readingcapture.core.quality.EdgeCutCheck
import app.readingcapture.core.quality.EdgeCutResult
import app.readingcapture.core.quality.PhotoMeasure
import app.readingcapture.core.quality.PhotoMeasurer
import app.readingcapture.core.quality.PhotoWarnings
import app.readingcapture.core.quality.QualityJudge
import app.readingcapture.data.Db
import app.readingcapture.data.PhotoState
import app.readingcapture.data.PhotoStore
import app.readingcapture.diag.Diagnostics
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 撮った 1 枚の仕上げと点検。カメラとは別のスレッドで、1 枚ずつ順に行う。
 *
 * 1. 向きを直す（上が上になるように画素を回す。向きの注記に頼らないので、どこで開いても正しい向きになる）
 * 2. 大きさをそろえる（長い辺を [TARGET_LONG_SIDE] に。切り抜きはしない）
 * 3. 点検する（文字が端にかかっていないか、ぶれていないか、暗すぎないか）
 * 4. 取り込み箱に上げる形（JPEG）で保存する
 *
 * 文字の認識は「文字の行がどこにあるか」を知るためだけに使う。読み取った文字は受け取らず、どこにも残さない。
 */
class PhotoProcessor(
    context: Context,
    private val db: Db,
    private val store: PhotoStore,
    private val diag: Diagnostics,
) {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "photo-processor") }
    private val lines = TextLineDetector()
    private val judge = QualityJudge()
    private var lastBoxes: Triple<List<app.readingcapture.core.quality.TextBox>, Int, Int>? = null

    /** 点検で知らせることが見つかったときに呼ぶ（仕上げのスレッドから）。 */
    var onWarning: ((photoId: String, warnings: PhotoWarnings) -> Unit)? = null

    /** 台帳に載っていない写真を台帳に載せる。載せられたら true を返す。 */
    var adoptOrphan: ((photoId: String) -> Boolean?)? = null

    /** 1 枚の仕上げが済むたびに呼ぶ。 */
    var onProcessed: (() -> Unit)? = null

    fun enqueue(photoId: String, movedDuringCapture: Boolean) {
        executor.execute {
            try {
                process(photoId, movedDuringCapture)
            } catch (t: Throwable) {
                // 仕上げに失敗しても、撮ったものは失わない。カメラが保存したままの形で上げる
                diag.log("process_error", "id" to photoId, "error" to t.toString())
                runCatching { fallbackToRaw(photoId) }
            }
            onProcessed?.invoke()
        }
    }

    /** 前回、仕上げの途中で終わっていた分を拾う。 */
    fun recover() {
        executor.execute {
            val known = db.photosInState(PhotoState.RAW).map { it.id }
            // カメラが保存したのに台帳に載る前に落ちた写真も、捨てずに拾う（いま選んでいる本の写真として）
            val adopted = store.rawIds().filter { it !in known && db.photo(it) == null }.mapNotNull { id -> adoptOrphan?.invoke(id)?.let { id } }
            for (id in known + adopted) enqueue(id, movedDuringCapture = false)
        }
    }

    /** 本を切り替えた、撮り始めた。ぶれの比べ先を忘れる。 */
    fun resetSession() {
        executor.execute { judge.reset() }
    }

    private fun process(id: String, moved: Boolean) {
        val started = System.currentTimeMillis()
        val row = db.photo(id) ?: run {
            store.deleteAll(id, null)
            return
        }
        if (row.state != PhotoState.RAW) return
        val raw = store.rawFile(id)
        if (!raw.exists() || raw.length() == 0L) {
            diag.log("raw_missing", "id" to id)
            db.removePhotoRow(id)
            return
        }

        val degrees = when (ExifInterface(raw.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
        val source = BitmapFactory.decodeFile(raw.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
            ?: throw IllegalStateException("写真を開けない")
        val long = max(source.width, source.height)
        val scale = if (long > TARGET_LONG_SIDE) TARGET_LONG_SIDE.toFloat() / long else 1f
        val matrix = Matrix().apply {
            postRotate(degrees.toFloat())
            postScale(scale, scale)
        }
        val upright = if (degrees == 0 && scale == 1f) source else
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true).also { if (it !== source) source.recycle() }

        // 点検用に縮めたもの
        val inspectScale = INSPECT_LONG_SIDE.toFloat() / max(upright.width, upright.height)
        val inspect = if (inspectScale < 1f)
            Bitmap.createScaledBitmap(upright, (upright.width * inspectScale).roundToInt(), (upright.height * inspectScale).roundToInt(), true)
        else upright

        val measure = measure(inspect)
        lastBoxes = null
        val (cut, lineCount) = detectCut(inspect)
        lastBoxes?.let { (b, w, h) -> logBoxes(id, b, w, h, cut) }
        val warnings = judge.judge(measure, cut, moved)

        // 仕上げたものを保存する。書き終えてから名前を付け替えるので、途中で落ちても半端なファイルは残らない
        val fileName = "$id.jpg"
        val tmp = store.tempFile(id)
        FileOutputStream(tmp).use { out ->
            upright.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.flush()
            out.fd.sync()
        }
        val target = store.photoFile(fileName)
        if (!tmp.renameTo(target)) throw IllegalStateException("保存できない")
        writeThumb(inspect, store.thumbFile(id))

        val width = upright.width
        val height = upright.height
        if (inspect !== upright) inspect.recycle()
        upright.recycle()

        db.markPhotoReady(id, fileName, width, height, target.length(), warnings)
        raw.delete()
        // 仕上げのあいだに持ち主が消していたら、いま作ったファイルも片付ける
        if (db.photo(id)?.fileName != fileName) store.deleteAll(id, fileName)

        diag.log(
            "photo", "id" to id, "w" to width, "h" to height, "bytes" to target.length(), "rawBytes" to row.bytes,
            "sharp" to measure.sharpness, "sharpX" to measure.sharpnessX, "sharpY" to measure.sharpnessY, "paper" to measure.paperLuma,
            "lines" to lineCount, "cut" to cut.sides.joinToString(",") { it.name }, "moved" to moved,
            "blur" to warnings.blurred, "dark" to warnings.dark, "ms" to System.currentTimeMillis() - started,
        )
        // 持ち主がそのあいだに消していなければ知らせる
        if (warnings.any && db.photo(id)?.state == PhotoState.READY) onWarning?.invoke(id, warnings)
    }

    private fun measure(bitmap: Bitmap): PhotoMeasure {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val luma = ByteArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            luma[i] = ((((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8).toByte()
        }
        return PhotoMeasurer.measure(LumaImage(w, h, luma).downsampleToLongSide(PhotoMeasurer.MEASURE_LONG_SIDE))
    }

    /**
     * 合わせ込み用: 端末に残っている仕上げ済みの写真について、文字の行の枠の並び（位置と大きさの数値だけ）を記録する。
     * 切れの判定が外れたときに、どういう並びで外れたかを調べるのに使う。文字そのものは記録しない。
     */
    fun reinspect(limit: Int = 6) {
        executor.execute {
            for (p in db.photosInState(PhotoState.READY).takeLast(limit)) {
                val file = p.fileName?.let { store.photoFile(it) } ?: continue
                if (!file.exists()) continue
                runCatching {
                    val source = BitmapFactory.decodeFile(file.path) ?: return@runCatching
                    val scale = INSPECT_LONG_SIDE.toFloat() / max(source.width, source.height)
                    val bitmap = if (scale < 1f) Bitmap.createScaledBitmap(source, (source.width * scale).roundToInt(), (source.height * scale).roundToInt(), true) else source
                    val boxes = lines.detect(bitmap)
                    logBoxes(p.id, boxes, bitmap.width, bitmap.height, EdgeCutCheck.evaluate(boxes, bitmap.width, bitmap.height))
                    if (bitmap !== source) bitmap.recycle()
                    source.recycle()
                }.onFailure { diag.log("reinspect_error", "id" to p.id, "error" to it.toString()) }
            }
            diag.log("reinspect_done")
        }
    }

    private fun logBoxes(id: String, boxes: List<app.readingcapture.core.quality.TextBox>, w: Int, h: Int, cut: EdgeCutResult) {
        val packed = boxes.joinToString(";") { "${it.left.roundToInt()},${it.top.roundToInt()},${it.right.roundToInt()},${it.bottom.roundToInt()}" }
        diag.log("boxes", "id" to id, "w" to w, "h" to h, "n" to boxes.size, "cut" to cut.sides.joinToString(",") { it.name }, "b" to packed)
    }

    /** 文字の行の枠だけを取り出して、端にかかっているかを見る。 */
    private fun detectCut(bitmap: Bitmap): Pair<EdgeCutResult, Int> = try {
        val boxes = lines.detect(bitmap)
        val result = EdgeCutCheck.evaluate(boxes, bitmap.width, bitmap.height)
        // 知らせを出すときは、行の枠の並び（数値だけ）を残しておく。外れていたときに調べられるように
        if (result.isCut) lastBoxes = Triple(boxes, bitmap.width, bitmap.height)
        result to boxes.size
    } catch (e: Exception) {
        diag.log("ocr_error", "error" to e.toString())
        EdgeCutResult(emptySet()) to -1
    }

    private fun writeThumb(source: Bitmap, file: File) {
        val scale = THUMB_WIDTH.toFloat() / source.width
        val thumb = Bitmap.createScaledBitmap(source, THUMB_WIDTH, (source.height * scale).roundToInt().coerceAtLeast(1), true)
        FileOutputStream(file).use { thumb.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        if (thumb !== source) thumb.recycle()
    }

    /** 仕上げられなかった写真を、カメラが保存したままの形で上げる対象にする。 */
    private fun fallbackToRaw(id: String) {
        val row = db.photo(id) ?: return
        if (row.state != PhotoState.RAW) return
        val raw = store.rawFile(id)
        if (!raw.exists() || raw.length() == 0L) {
            db.removePhotoRow(id)
            return
        }
        val fileName = "$id.jpg"
        val target = store.photoFile(fileName)
        if (!raw.renameTo(target)) raw.copyTo(target, overwrite = true)
        db.markPhotoReady(id, fileName, 0, 0, target.length(), PhotoWarnings())
        raw.delete()
    }

    companion object {
        /** 仕上げた写真の長い辺。前回の手作業（1920×2560）より大きく、欄外の小さな字に余裕を持たせる */
        const val TARGET_LONG_SIDE = 3072
        const val JPEG_QUALITY = 88

        /** 点検に使う大きさ。文字の認識の目安（1 文字 16 画素以上）を、欄外の小さな字でも満たす大きさ */
        const val INSPECT_LONG_SIDE = 1536
        const val THUMB_WIDTH = 360
    }
}
