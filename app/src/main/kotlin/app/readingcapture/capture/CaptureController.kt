package app.readingcapture.capture

import android.content.Context
import android.os.SystemClock
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import app.readingcapture.core.detect.CaptureEngine
import app.readingcapture.core.detect.CaptureReason
import app.readingcapture.core.detect.CaptureRequest
import app.readingcapture.core.detect.EngineStatus
import app.readingcapture.core.detect.FrameResult
import app.readingcapture.core.frame.LumaImage
import app.readingcapture.core.inbox.InboxLayout
import app.readingcapture.core.inbox.PhotoKind
import app.readingcapture.core.quality.PhotoWarnings
import app.readingcapture.data.BookRepository
import app.readingcapture.data.Db
import app.readingcapture.data.Photo
import app.readingcapture.data.PhotoState
import app.readingcapture.data.PhotoStore
import app.readingcapture.data.Settings
import app.readingcapture.diag.Diagnostics
import app.readingcapture.sync.SyncEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.concurrent.Executors
import kotlin.math.abs

/** 「撮る」の画面に出すもの。 */
data class CaptureUi(
    val cameraReady: Boolean = false,
    val cameraError: String? = null,
    val status: EngineStatus = EngineStatus.STARTING,
    /** 止まってから撮るまでの進み具合（0..1） */
    val settleProgress: Float = 0f,
    /** 最後に撮れた時刻（画面を一瞬光らせる合図） */
    val capturedAtMs: Long = 0,
    val hasTorch: Boolean = false,
    val torchOn: Boolean = false,
    /** 合わせ込み用の数値（設定で出すことにしたときだけ） */
    val numbers: String? = null,
)

/**
 * カメラと判定をつなぐ。
 *
 * 映像のコマは判定用のスレッド 1 本で受け、[CaptureEngine] に渡す。エンジンが「撮って」と言ったら撮り、
 * 保存できたら台帳に載せて振動する。エンジンに触るのは判定用のスレッドだけ。
 */
class CaptureController(
    private val context: Context,
    private val db: Db,
    private val store: PhotoStore,
    private val settings: Settings,
    private val processor: PhotoProcessor,
    private val haptics: Haptics,
    private val sync: SyncEngine,
    private val diag: Diagnostics,
) {
    private val _ui = MutableStateFlow(CaptureUi(torchOn = settings.torchOn))
    val ui: StateFlow<CaptureUi> = _ui

    private val engine = CaptureEngine()
    private val analysisExecutor = Executors.newSingleThreadScheduledExecutor { Thread(it, "capture-analysis") }
    private var warningDeferred = false
    private val ioExecutor = Executors.newSingleThreadExecutor { Thread(it, "capture-io") }
    private val mainExecutor = ContextCompat.getMainExecutor(context)

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var scratch = ByteArray(0)
    private var lastPublishedProgress = -1f
    private var lastNumbersAt = 0L
    private var bound = false

    private val referenceFile = java.io.File(context.filesDir, "capture/last-page.bin")

    init {
        // カメラが保存したのに台帳に載る前に落ちた写真を拾う
        processor.adoptOrphan = { id ->
            val capturedAt = id.substringBefore('-').toLongOrNull()
            if (capturedAt == null) null else runCatching {
                val bookId = settings.currentBookId.value ?: BookRepository.UNSORTED_ID
                val at = OffsetDateTime.ofInstant(Instant.ofEpochMilli(capturedAt), ZoneId.systemDefault())
                db.insertPhoto(
                    Photo(
                        id = id, bookId = bookId, capturedAt = capturedAt, kind = PhotoKind.CAMERA, state = PhotoState.RAW,
                        remotePath = InboxLayout.photo(bookId, at, PhotoKind.CAMERA), fileName = null,
                        width = 0, height = 0, bytes = store.rawFile(id).length(), warnings = PhotoWarnings(), blobSha = null, uploadedAt = null,
                    ),
                )
                diag.log("adopted", "id" to id)
                true
            }.getOrNull()
        }
        // アプリを開き直しても同じページを二度撮りしないよう、最後に撮ったページを思い出す（30 分以内のものだけ）
        analysisExecutor.execute { restoreReference() }
        processor.onWarning = { _, _ ->
            analysisExecutor.execute {
                // 次の 1 枚を撮っている最中に揺らすと、その 1 枚がぶれる。撮り終わるまで待つ
                if (engine.isCapturing) warningDeferred = true else vibrateWarning()
            }
        }
    }

    /** 「撮る」の画面を開いた。カメラをつなぐ。 */
    fun bind(owner: LifecycleOwner, previewView: PreviewView) {
        bound = true
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!bound) return@addListener
            try {
                val p = future.get()
                provider = p
                val fourThree = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build()
                val preview = Preview.Builder().setResolutionSelector(fourThree).build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                // 写真は 4:3 のいちばん大きい大きさで撮る。画面の向きは縦に固定してある
                val capture = ImageCapture.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                            .build(),
                    )
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetRotation(Surface.ROTATION_0)
                    .build()
                // 判定用の映像は既定の 640×480。大きくすると、写真のほうの大きさが制限される
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor, ::analyze)

                p.unbindAll()
                analysisExecutor.execute { engine.onCameraRestarted() }
                val cam = p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis)
                camera = cam
                imageCapture = capture
                val hasTorch = cam.cameraInfo.hasFlashUnit()
                if (hasTorch && settings.torchOn) cam.cameraControl.enableTorch(true)
                _ui.update { it.copy(cameraReady = true, cameraError = null, hasTorch = hasTorch, torchOn = hasTorch && settings.torchOn) }
                val res = capture.resolutionInfo?.resolution
                diag.log("camera_bound", "photoW" to res?.width, "photoH" to res?.height, "analysisW" to analysis.resolutionInfo?.resolution?.width, "analysisH" to analysis.resolutionInfo?.resolution?.height)
            } catch (e: Exception) {
                diag.log("camera_error", "error" to e.toString())
                _ui.update { it.copy(cameraReady = false, cameraError = "カメラを使えません") }
            }
        }, mainExecutor)
    }

    /** 「撮る」の画面を離れた。カメラを離す（電池と熱のため）。 */
    fun unbind() {
        bound = false
        provider?.unbindAll()
        camera = null
        imageCapture = null
        _ui.update { it.copy(cameraReady = false, status = EngineStatus.STARTING, settleProgress = 0f) }
    }

    /** シャッター、音量ボタン。 */
    fun shutter() {
        analysisExecutor.execute {
            engine.requestManual(SystemClock.elapsedRealtime())?.let { takePicture(it) }
        }
    }

    /** 直前の 1 枚を消す。 */
    fun deleteLast(photo: Photo) {
        delete(photo)
        analysisExecutor.execute {
            engine.onLastDeleted()
            saveReference()
        }
        haptics.tick()
    }

    /** 撮り直す。直前の 1 枚を消し、次に止まったら同じページでも撮る。 */
    fun retake(photo: Photo) {
        delete(photo)
        analysisExecutor.execute { engine.armRetake(SystemClock.elapsedRealtime()) }
        haptics.tick()
        diag.log("retake", "id" to photo.id)
    }

    fun delete(photo: Photo) {
        val fileName = db.deletePhoto(photo.id)
        store.deleteAll(photo.id, fileName ?: photo.fileName)
        diag.log("delete", "id" to photo.id, "kind" to photo.kind.name)
        sync.kick()
    }

    fun toggleTorch() {
        val cam = camera ?: return
        val on = !_ui.value.torchOn
        cam.cameraControl.enableTorch(on)
        settings.torchOn = on
        _ui.update { it.copy(torchOn = on) }
    }

    /** 本を切り替えた。前の本の最後のページと比べないよう、覚えているページを忘れる。 */
    fun onBookChanged() {
        analysisExecutor.execute {
            engine.reset()
            referenceFile.delete()
        }
        processor.resetSession()
    }

    // ---- 内部（判定用のスレッド） ----

    private fun analyze(proxy: ImageProxy) {
        try {
            val plane = proxy.planes[0]
            val buffer = plane.buffer
            val w = proxy.width
            val h = proxy.height
            val rowStride = plane.rowStride
            val needed = rowStride * h
            if (scratch.size < needed) scratch = ByteArray(needed)
            buffer.rewind()
            buffer.get(scratch, 0, minOf(buffer.remaining(), needed))
            val (dw, dh) = LumaImage.scaledSize(w, h, WORK_LONG_SIDE)
            val frame = LumaImage.fromPlane(scratch, w, h, rowStride, plane.pixelStride, dw, dh)
            val now = SystemClock.elapsedRealtime()
            val result = engine.onFrame(frame, now)
            result.request?.let { takePicture(it) }
            publish(result, now)
        } catch (e: Exception) {
            diag.log("analyze_error", "error" to e.toString())
        } finally {
            proxy.close()
        }
    }

    private fun publish(result: FrameResult, now: Long) {
        val m = result.metrics
        // 判定したコマ（同じページか・ページらしいかを見たコマ）は記録に残す
        if (m.match != null || m.texturedFraction != null) {
            diag.log(
                "decide", "status" to result.status.name, "request" to result.request?.reason?.name,
                "strong" to m.strongFraction, "sharp" to m.sharpness, "tex" to m.texturedFraction,
                "same" to m.match?.same, "identical" to m.match?.identical, "matched" to m.match?.matchedBlocks,
                "eligible" to m.match?.eligibleBlocks, "sx" to m.match?.shiftX, "sy" to m.match?.shiftY,
            )
        }
        val current = _ui.value
        val showNumbers = settings.showNumbers
        val numbers = if (showNumbers && now - lastNumbersAt > 200) {
            lastNumbersAt = now
            "動き %.3f  差 %.1f  明るさ比 %.2f  鮮明さ %.0f".format(m.strongFraction, m.meanAbs, m.gain, m.sharpness)
        } else if (!showNumbers) null else current.numbers
        if (result.status != current.status || abs(result.settleProgress - lastPublishedProgress) > 0.04f || numbers != current.numbers) {
            lastPublishedProgress = result.settleProgress
            _ui.update { it.copy(status = result.status, settleProgress = result.settleProgress, numbers = numbers) }
        }
    }

    /** 最後に撮ったページの、判定用の小さな画像（明るさだけ、320 画素）を書いておく。 */
    private fun saveReference() {
        val image = engine.lastReferenceImage() ?: return
        runCatching {
            referenceFile.parentFile?.mkdirs()
            java.io.DataOutputStream(referenceFile.outputStream().buffered()).use { out ->
                out.writeLong(System.currentTimeMillis())
                out.writeInt(image.width)
                out.writeInt(image.height)
                out.write(image.data, 0, image.width * image.height)
            }
        }
    }

    private fun restoreReference() {
        if (!referenceFile.exists()) return
        runCatching {
            java.io.DataInputStream(referenceFile.inputStream().buffered()).use { input ->
                val savedAt = input.readLong()
                val w = input.readInt()
                val h = input.readInt()
                if (System.currentTimeMillis() - savedAt > REFERENCE_MAX_AGE_MS || w !in 1..4096 || h !in 1..4096) return
                val data = ByteArray(w * h)
                input.readFully(data)
                engine.restoreReference(LumaImage(w, h, data))
            }
        }
    }

    /** 判定用のスレッドから呼ぶ。 */
    private fun vibrateWarning() {
        val ms = haptics.warning()
        engine.notifyVibration(SystemClock.elapsedRealtime(), ms + 100)
    }

    private fun takePicture(request: CaptureRequest) {
        val capture = imageCapture
        if (capture == null) {
            engine.onCaptureFinished(request.id, SystemClock.elapsedRealtime(), saved = false)
            return
        }
        val capturedAt = System.currentTimeMillis()
        val id = newPhotoId(capturedAt)
        val bookId = settings.currentBookId.value ?: BookRepository.UNSORTED_ID
        val raw = store.rawFile(id)
        val started = SystemClock.elapsedRealtime()
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(raw).build(),
            ioExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val at = OffsetDateTime.ofInstant(Instant.ofEpochMilli(capturedAt), ZoneId.systemDefault())
                    // 先に台帳に載せる。ここから先でアプリが落ちても、次に開いたときに仕上げから続ける
                    db.insertPhoto(
                        Photo(
                            id = id, bookId = bookId, capturedAt = capturedAt, kind = PhotoKind.CAMERA, state = PhotoState.RAW,
                            remotePath = InboxLayout.photo(bookId, at, PhotoKind.CAMERA), fileName = null,
                            width = 0, height = 0, bytes = raw.length(), warnings = PhotoWarnings(), blobSha = null, uploadedAt = null,
                        ),
                    )
                    val latency = SystemClock.elapsedRealtime() - started
                    _ui.update { it.copy(capturedAtMs = SystemClock.elapsedRealtime()) }
                    analysisExecutor.execute {
                        val now = SystemClock.elapsedRealtime()
                        val outcome = engine.onCaptureFinished(request.id, now, saved = true)
                        saveReference()
                        // 振動は写真を撮り終えてから（撮っている最中に揺らさない）
                        val ms = haptics.captured()
                        engine.notifyVibration(now, ms + 150)
                        if (warningDeferred) {
                            warningDeferred = false
                            analysisExecutor.schedule({ if (engine.isCapturing) warningDeferred = true else vibrateWarning() }, ms + 200, java.util.concurrent.TimeUnit.MILLISECONDS)
                        }
                        diag.log("captured", "id" to id, "reason" to request.reason.name, "latencyMs" to latency, "moved" to outcome.movedDuringCapture, "rawBytes" to raw.length())
                        processor.enqueue(id, outcome.movedDuringCapture)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    raw.delete()
                    diag.log("capture_error", "reason" to request.reason.name, "code" to exception.imageCaptureError, "error" to exception.message)
                    analysisExecutor.execute {
                        engine.onCaptureFinished(request.id, SystemClock.elapsedRealtime(), saved = false)
                        if (warningDeferred) {
                            warningDeferred = false
                            vibrateWarning()
                        }
                    }
                }
            },
        )
        if (request.reason != CaptureReason.AUTO) diag.log("manual_request", "reason" to request.reason.name)
    }

    companion object {
        const val WORK_LONG_SIDE = 320
        const val REFERENCE_MAX_AGE_MS = 30 * 60_000L

        /** 写真の id。時刻順に並ぶ。 */
        fun newPhotoId(atMs: Long): String = "%013d-%04x".format(atMs, (Math.random() * 0xFFFF).toInt())
    }
}
