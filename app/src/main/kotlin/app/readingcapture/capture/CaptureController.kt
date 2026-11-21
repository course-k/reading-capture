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
import app.readingcapture.core.detect.PageGlimpse
import app.readingcapture.core.detect.PageNeed
import app.readingcapture.core.detect.TextBlockFinder
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
    /** いまの状態に関わるページ番号（撮ったページ、もう撮ってあったページ）。読めていなければ null */
    val pageNumber: Int? = null,
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
    // 写真の id と、撮る合図の番号の対応（写真の点検の結果を、どの撮影のものか結び付ける）
    private val requestIds = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val ioExecutor = Executors.newSingleThreadExecutor { Thread(it, "capture-io") }
    private val mainExecutor = ContextCompat.getMainExecutor(context)

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var scratch = ByteArray(0)
    // スマホ自体が動いているか（回る速さ・揺れの大きさ）。映像の変化が、スマホの揺れか、写っているものの動きかを見分ける
    @Volatile private var gyroMax = 0f
    @Volatile private var accelMax = 0f
    // 前のコマからいままでの、回る速さの最大（判定に渡す）。センサーが無ければ -1 のまま
    @Volatile private var gyroSinceFrame = -1f
    private val pageReader = TextLineDetector()
    private val motionListener = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(event: android.hardware.SensorEvent) {
            val v = event.values
            val m = kotlin.math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            if (event.sensor.type == android.hardware.Sensor.TYPE_GYROSCOPE) {
                if (m > gyroMax) gyroMax = m
                if (m > gyroSinceFrame) gyroSinceFrame = m
            } else {
                if (m > accelMax) accelMax = m
            }
        }

        override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) = Unit
    }

    // 合わせ込み用: 映像の揺れの大きさを記録する
    private var probePrev: LumaImage? = null
    private var probeAnchor: LumaImage? = null
    private var probeAnchorAt = 0L
    private var probeLoggedAt = 0L
    private var lastPublishedProgress = -1f
    private var lastNumbersAt = 0L
    private var bound = false
    // 撮る合図を出したときに、映像から読めていたページ番号（判定用のスレッドだけが触る）
    private var pendingHintNumber: Int? = null
    private var lastCaptureFinishedAt = 0L

    private val referenceFile = java.io.File(context.filesDir, "capture/pages-v3.bin")

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
        // 写真の点検が済んだら、読めたページ番号と、読める写真だったかをエンジンに伝える
        processor.onInspected = { photoId, pageNumber, usable ->
            analysisExecutor.execute {
                val requestId = requestIds.remove(photoId)
                if (requestId != null) {
                    engine.onPhotoInspected(requestId, pageNumber, usable)
                    diag.log("photo_read", "id" to photoId, "page" to pageNumber, "usable" to usable)
                    saveReference()
                }
            }
        }
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
        val sensors = context.getSystemService(android.hardware.SensorManager::class.java)
        sensors.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE)?.let { sensors.registerListener(motionListener, it, android.hardware.SensorManager.SENSOR_DELAY_GAME) }
        sensors.getDefaultSensor(android.hardware.Sensor.TYPE_LINEAR_ACCELERATION)?.let { sensors.registerListener(motionListener, it, android.hardware.SensorManager.SENSOR_DELAY_GAME) }
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
                // 判定用の映像は 1280×960。止まったときに、このコマの文字を読んでページを見分けるので、
                // 文字が読める大きさが要る。これ以上大きくすると、写真のほうの大きさが制限される
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                            .setResolutionStrategy(ResolutionStrategy(android.util.Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                            .build(),
                    )
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
                _ui.update { it.copy(cameraReady = false, cameraError = "カメラを起動できません") }
            }
        }, mainExecutor)
    }

    /** 「撮る」の画面を離れた。カメラを離す（電池と熱のため）。 */
    fun unbind() {
        bound = false
        context.getSystemService(android.hardware.SensorManager::class.java).unregisterListener(motionListener)
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
    }

    /** 撮り直す。直前の 1 枚を消し、次に止まったら同じページでも撮る。 */
    fun retake(photo: Photo) {
        delete(photo)
        analysisExecutor.execute { engine.armRetake(SystemClock.elapsedRealtime()) }
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
            val motion = gyroSinceFrame.takeIf { it >= 0f }
            if (motion != null) gyroSinceFrame = 0f
            probe(frame, now)
            var result = engine.onFrame(frame, now, motion)
            logAroundCapture(result, now)
            var rounds = 0
            while (result.need != PageNeed.NONE && rounds++ < 3) {
                val rotation = proxy.imageInfo.rotationDegrees
                val started = SystemClock.elapsedRealtime()
                // 止まった。ページ番号のあたりだけを読む。読んでいるあいだのスマホの動きも見ておく
                if (gyroSinceFrame >= 0f) gyroSinceFrame = 0f
                val read = runCatching { pageReader.glimpse(scratch, w, h, rowStride, rotation) }
                    .onFailure { diag.log("read_error", "error" to it.toString()) }
                    .getOrDefault(TextLineDetector.GlimpseResult(PageGlimpse(null, hasText = false), 0))
                val after = SystemClock.elapsedRealtime()
                val readMotion = gyroSinceFrame.takeIf { it >= 0f }
                result = engine.onPageGlimpsed(read.glimpse, after, readMotion)
                val m = result.metrics
                // 文字のかたまりの範囲（記録するだけ。向きは映像の面のまま）
                val block = TextBlockFinder.find(frame)
                diag.log(
                    "glimpse", "ms" to after - started, "page" to read.glimpse.pageNumber, "text" to read.glimpse.hasText, "chars" to read.chars,
                    "status" to result.status.name, "request" to result.request?.reason?.name, "next" to result.need.name,
                    "cell" to m.cellFraction, "cmax" to m.cellMax, "gyro" to m.deviceMotion, "readGyro" to readMotion, "sharp" to m.sharpness,
                    "rot" to rotation, "block" to block?.let { "%.3f,%.3f,%.3f,%.3f".format(it.left, it.top, it.right, it.bottom) },
                    "blockText" to block?.textFraction, "e50" to block?.energy50, "e90" to block?.energy90,
                )
                // 読んでいるあいだの動きは、次のコマとの比較に持ち込まない（読み終えてからの動きだけを見る）
                if (gyroSinceFrame >= 0f) gyroSinceFrame = 0f
            }
            result.request?.let { pendingHintNumber = result.pageNumber }
            result.request?.let { takePicture(it) }
            publish(result, now)
        } catch (e: Exception) {
            diag.log("analyze_error", "error" to e.toString())
        } finally {
            proxy.close()
        }
    }

    /**
     * 撮っている最中と、撮り終えてから 1.2 秒のあいだは、コマごとの数値を記録する。
     * 写真を撮る処理で判定用の映像がどのくらい乱れるか、撮った直後に何が「動いた」に見えるかを確かめるため。
     */
    private fun logAroundCapture(result: FrameResult, now: Long) {
        if (!engine.isCapturing && now - lastCaptureFinishedAt > 1200) return
        val m = result.metrics
        diag.log(
            "post", "dt" to now - lastCaptureFinishedAt, "capturing" to engine.isCapturing, "st" to result.status.name,
            "c4" to m.cellFraction, "cmax" to m.cellMax, "gyro" to m.deviceMotion,
        )
    }

    /**
     * 映像がどのくらい揺れているかを、1 秒に 4 回記録する（数値だけ）。
     * 直前のコマとの差と、1 秒前のコマとの差を、細かい比べ方（画素ごと）と粗い比べ方（区画の平均）の両方で出す。
     */
    private fun probe(frame: LumaImage, now: Long) {
        val prev = probePrev
        val anchor = probeAnchor
        if (prev != null && anchor != null && now - probeLoggedAt >= 500) {
            probeLoggedAt = now
            val fine = app.readingcapture.core.detect.FrameDiff.compare(prev, frame)
            val fineA = app.readingcapture.core.detect.FrameDiff.compare(anchor, frame)
            val coarse = app.readingcapture.core.detect.CoarseDiff.compare(prev, frame)
            val coarseA = app.readingcapture.core.detect.CoarseDiff.compare(anchor, frame)
            diag.log(
                "frame", "st" to engine.status.name, "mean" to frame.mean().toFloat(),
                "fine" to fine.strongFraction, "fineA" to fineA.strongFraction, "abs" to fine.meanAbs, "absA" to fineA.meanAbs, "gain" to fineA.gain,
                "c2" to coarse.over[0], "c4" to coarse.over[1], "c6" to coarse.over[2], "c10" to coarse.over[3], "cmax" to coarse.max,
                "a2" to coarseA.over[0], "a4" to coarseA.over[1], "a6" to coarseA.over[2], "a10" to coarseA.over[3], "a16" to coarseA.over[4], "amax" to coarseA.max,
                "sharp" to app.readingcapture.core.detect.Sharpness.gradientEnergy(frame),
                "gyro" to gyroMax, "accel" to accelMax,
                "block" to TextBlockFinder.find(frame)?.let { "%.3f,%.3f,%.3f,%.3f".format(it.left, it.top, it.right, it.bottom) },
            )
            gyroMax = 0f
            accelMax = 0f
        }
        probePrev = frame
        if (anchor == null || now - probeAnchorAt >= 1000) {
            probeAnchor = frame
            probeAnchorAt = now
        }
    }

    private fun publish(result: FrameResult, now: Long) {
        val m = result.metrics
        val current = _ui.value
        val showNumbers = settings.showNumbers
        val numbers = if (showNumbers && now - lastNumbersAt > 200) {
            lastNumbersAt = now
            "フレーム差分 %.3f / %.0f  ジャイロ %.2f  シャープネス %.0f".format(m.cellFraction, m.cellMax, m.deviceMotion ?: -1f, m.sharpness)
        } else if (!showNumbers) null else current.numbers
        if (result.status != current.status || result.pageNumber != current.pageNumber || numbers != current.numbers) {
            _ui.update { it.copy(status = result.status, settleProgress = result.settleProgress, pageNumber = result.pageNumber, numbers = numbers) }
        }
    }

    /**
     * 最近撮ったページ番号を書いておく。アプリを開き直しても、同じページを二度撮りしないため。
     */
    private fun saveReference() {
        val pages = engine.rememberedPages()
        runCatching {
            referenceFile.parentFile?.mkdirs()
            java.io.DataOutputStream(referenceFile.outputStream().buffered()).use { out ->
                out.writeLong(System.currentTimeMillis())
                out.writeInt(pages.size)
                for (number in pages) out.writeInt(number)
            }
        }
    }

    private fun restoreReference() {
        if (!referenceFile.exists()) return
        runCatching {
            java.io.DataInputStream(referenceFile.inputStream().buffered()).use { input ->
                val savedAt = input.readLong()
                if (System.currentTimeMillis() - savedAt > REFERENCE_MAX_AGE_MS) return
                val count = input.readInt()
                if (count !in 0..64) return
                engine.restorePages(List(count) { input.readInt() })
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
        requestIds[id] = request.id
        val hintNumber = pendingHintNumber.takeIf { request.reason != CaptureReason.MANUAL }
        pendingHintNumber = null
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
                        lastCaptureFinishedAt = now
                        saveReference()
                        // 振動は写真を撮り終えてから（撮っている最中に揺らさない）
                        val ms = haptics.captured()
                        engine.notifyVibration(now, ms + 150)
                        if (warningDeferred) {
                            warningDeferred = false
                            analysisExecutor.schedule({ if (engine.isCapturing) warningDeferred = true else vibrateWarning() }, ms + 200, java.util.concurrent.TimeUnit.MILLISECONDS)
                        }
                        diag.log("captured", "id" to id, "reason" to request.reason.name, "latencyMs" to latency, "moved" to outcome.movedDuringCapture, "rawBytes" to raw.length())
                        processor.enqueue(id, outcome.movedDuringCapture, hintNumber)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    raw.delete()
                    requestIds.remove(id)
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
