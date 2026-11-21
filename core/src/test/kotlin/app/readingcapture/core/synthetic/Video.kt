package app.readingcapture.core.synthetic

import app.readingcapture.core.detect.CaptureEngine
import app.readingcapture.core.detect.CaptureReason
import app.readingcapture.core.detect.CaptureRequest
import app.readingcapture.core.detect.EngineStatus
import app.readingcapture.core.detect.PageGlimpse
import app.readingcapture.core.detect.PageNeed
import app.readingcapture.core.frame.LumaImage
import java.util.Random
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** 合成した映像の台本。コマごとの [SceneState] を順に積む。 */
class VideoScript(val fps: Int = 30) {

    val frames = mutableListOf<SceneState>()
    val actions = mutableListOf<Pair<Int, (Simulation) -> Unit>>()

    val durationMs: Int get() = frames.size * 1000 / fps

    private fun frameCount(ms: Int) = maxOf(1, (ms * fps / 1000f).roundToInt())

    fun hold(state: SceneState, ms: Int) = apply { repeat(frameCount(ms)) { frames.add(state) } }

    /** t を 0→1 に動かしながら描く。 */
    fun animate(ms: Int, f: (Float) -> SceneState) = apply {
        val n = frameCount(ms)
        for (i in 0 until n) frames.add(f((i + 1f) / n))
    }

    /** いまの位置で、エンジンに対する操作（シャッター、消す、など）を差し込む。 */
    fun act(action: (Simulation) -> Unit) = apply { actions.add(frames.size to action) }

    /**
     * ページをめくる。右から手が入り、境目が右から左へ動き、手が抜け、反りが戻る。
     * 片手でスマホを持ち、もう片方の手でめくるので、めくっているあいだはスマホも少し動く。
     * @param base 位置・明るさなどの土台
     */
    fun turn(
        from: SyntheticPage,
        to: SyntheticPage,
        base: SceneState = SceneState(page = null),
        turnMs: Int = 900,
        handInMs: Int = 300,
        handOutMs: Int = 350,
        relaxMs: Int = 250,
        gyro: Float = 0.4f,
    ) = apply {
        val w = 480f
        // 手が入る
        animate(handInMs) { t ->
            base.copy(page = from, hand = Hand(tipX = w + 40 - t * 140, tipY = 330f), gyro = gyro)
        }
        // めくる
        animate(turnMs) { t ->
            val boundaryX = 30 + 420 * (1 - t)
            base.copy(page = from, nextPage = to, turnProgress = t, hand = Hand(tipX = boundaryX - 20, tipY = 330f), gyro = gyro)
        }
        // 手が抜ける（新しいページは少し反っている）
        animate(handOutMs) { t ->
            base.copy(page = to, curl = 5f, hand = Hand(tipX = 10 + t * (w + 60), tipY = 330f + t * 80), gyro = gyro)
        }
        // 反りが戻る
        animate(relaxMs) { t -> base.copy(page = to, curl = 5f * (1 - t)) }
    }
}

/** 撮った 1 回分の記録。 */
data class SimCapture(
    val timeMs: Long,
    val reason: CaptureReason,
    /** 撮る合図が出た瞬間に映っていたページ（めくりの途中なら null） */
    val pageId: Int?,
    val handPresent: Boolean,
    val blurRadius: Int,
    val curl: Float,
    /** 撮っているあいだに動いた、とエンジンが言ったか */
    val moved: Boolean,
    val requestId: Long = 0,
    /** 撮っているあいだにスマホが動いていて、写真がぶれた */
    val blurred: Boolean = false,
)

/**
 * 台本の映像をエンジンに流し、いつ・どのページで撮ったかを記録する。実物に近づけるため、次のものを足す。
 *
 * - 手に持っているときの、ゆっくりした位置・傾き・大きさのふらつきと、細かな揺れ、明るさのちらつき
 *   （実機で、これらの無い合成の映像では通るのに自動で撮れない、という不具合が出た）
 * - スマホ自体の動き（回る速さ）
 * - ページ番号の読み落としと読み違い、読むのにかかる時間（そのあいだコマが届かない）、読んでいるあいだの動き
 * - 写真を撮るときの、判定用の映像の乱れ（実機で、これを「動いた」と数えて同じ場所でもう一度撮っていた）
 * - 撮ったあとの写真の点検（番号が読めるまでの時間、ぶれていて読めない写真）
 * - 撮る合図から撮り終わりまでの時間と、撮れたときの振動による揺れ
 */
class Simulation(
    val engine: CaptureEngine,
    private val renderer: SceneRenderer = SceneRenderer(),
    private val workLongSide: Int = 320,
    private val captureLatencyMs: Long = 250,
    private val vibrationMs: Long = 120,
    private val vibrationShakePx: Float = 2f,
    /** 撮るのに失敗させる回数（最初の n 回） */
    var failFirstCaptures: Int = 0,
    /** 撮り終わりの知らせを返さない回数（最初の n 回） */
    var dropFirstFinishes: Int = 0,
    /** 手に持っているときのふらつきの大きさ（1 = ふつう、0 = 台に固定） */
    private val handheld: Float = 1f,
    /** ページ番号のあたりだけを読むのにかかる時間。実機では 0.15〜0.86 秒 */
    private val glimpseLatencyMs: Long = 200,
    /** ページ番号を読み落とす割合。実機の写真 8 枚では 1 枚で読めなかった */
    private val numberMiss: Double = 0.12,
    /** ページ番号を読み違えたことにする（本当の番号 → 読めた番号）。null なら読み違えない */
    var misreadNumber: ((Int) -> Int)? = null,
    /** 撮った写真の点検にかかる時間 */
    private val processLatencyMs: Long = 1400,
    /** 写真を撮っている最中と、そのあとこの時間、判定用の映像が乱れる。0 なら乱れない */
    private val captureDisturbanceMs: Long = 350,
) {
    private class Inspection(val dueMs: Long, val requestId: Long, val page: SyntheticPage?, val usable: Boolean)

    private var readingUntil = -1L
    private val inspections = ArrayList<Inspection>()
    val captures = mutableListOf<SimCapture>()
    val requests = mutableListOf<Pair<Long, Int?>>()
    val statuses = mutableListOf<EngineStatus>()

    /** ページ番号のあたりだけを読んだ回数 */
    var glimpses = 0
        private set

    /** 読んでいるあいだに動いていたので、結果を使わなかった回数 */
    var rejectedGlimpses = 0
        private set
    var nowMs = 0L
        private set

    private val random = Random(20261121)
    private var pending: CaptureRequest? = null
    private var pendingState: SceneState? = null
    private var pendingFinishAt = 0L
    private var shakeUntil = -1L
    private var disturbedUntil = -1L
    private var current: SceneState = SceneState(null)

    fun run(script: VideoScript): List<SimCapture> {
        val actionsByFrame = script.actions.groupBy({ it.first }, { it.second })
        for ((i, raw) in script.frames.withIndex()) {
            nowMs = i * 1000L / script.fps
            current = raw
            actionsByFrame[i]?.forEach { it(this) }
            finishIfDue(raw)

            val t = nowMs / 1000.0
            // 手に持っているときの、ゆっくりしたふらつき。大きさは、実機（Pixel 6a）を手に持って止めたときの
            // 映像の変化（直前のコマから変わった区画の割合が 0.002〜0.03）に合わせてある
            val amp = handheld * 0.35f
            val driftX = amp * (7 * sin(2 * PI * t / 3.1 + 0.4) + 4 * sin(2 * PI * t / 1.3 + 1.9)).toFloat()
            val driftY = amp * (6 * sin(2 * PI * t / 2.7 + 2.2) + 3 * sin(2 * PI * t / 1.1 + 0.3)).toFloat()
            val driftRot = amp * (1.2 * sin(2 * PI * t / 4.3 + 1.0)).toFloat()
            val driftScale = 1f + amp * (0.025 * sin(2 * PI * t / 5.1 + 0.7)).toFloat()
            var state = raw.copy(
                dx = raw.dx + driftX,
                dy = raw.dy + driftY,
                rotationDeg = raw.rotationDeg + driftRot,
                scale = raw.scale * driftScale,
                // 細かな揺れと、明るさのちらつき
                shakeX = raw.shakeX + (random.nextFloat() * 2 - 1) * 0.5f,
                shakeY = raw.shakeY + (random.nextFloat() * 2 - 1) * 0.5f,
                gain = raw.gain * (1f + (random.nextFloat() * 2 - 1) * 0.012f),
            )
            var gyro = gyroOf(raw)
            if (nowMs < shakeUntil) {
                val sign = if (i % 2 == 0) 1 else -1
                state = state.copy(shakeX = state.shakeX + sign * vibrationShakePx, shakeY = state.shakeY - sign * vibrationShakePx * 0.5f)
                gyro = maxOf(gyro, 0.8f)
            }
            // 写真を撮っている最中と直後は、判定用の映像が大きく乱れる（スマホは動いていない）
            if (captureDisturbanceMs > 0 && (pending != null || nowMs < disturbedUntil)) {
                val sign = if (i % 2 == 0) 1 else -1
                state = state.copy(shakeX = state.shakeX + sign * 14f, shakeY = state.shakeY - sign * 9f, gain = state.gain * (1f + sign * 0.2f))
            }
            // 写真の点検が済んだら、結果を渡す
            val due = inspections.filter { it.dueMs <= nowMs }
            for (e in due) {
                inspections.remove(e)
                val number = e.page?.takeIf { it.options.pageNumber && random.nextDouble() >= numberMiss * 0.5 }?.id
                engine.onPhotoInspected(e.requestId, number, e.usable)
            }
            // 文字を読んでいるあいだは、コマが届かない
            if (nowMs < readingUntil) continue
            val frame = renderer.render(state, i).toWork()
            var result = engine.onFrame(frame, nowMs, gyro)
            var rounds = 0
            while (result.need != PageNeed.NONE && rounds++ < 3) {
                val from = maxOf(nowMs, readingUntil)
                glimpses++
                readingUntil = from + glimpseLatencyMs
                // 読んでいるあいだの動き（台本の、この先のコマから）
                val lastFrame = minOf(script.frames.size - 1, ((readingUntil * script.fps) / 1000).toInt())
                var motion = 0f
                for (k in i..lastFrame) motion = maxOf(motion, gyroOf(script.frames[k]))
                val before = engine.status
                result = engine.onPageGlimpsed(glimpse(raw), readingUntil, motion)
                if (result.request == null && motion > 0.2f && before != EngineStatus.NOT_A_PAGE) rejectedGlimpses++
            }
            statuses.add(result.status)
            result.request?.let { begin(it, script, i) }
        }
        return captures
    }

    private fun gyroOf(state: SceneState): Float = state.gyro ?: (0.03f + 0.04f * random.nextFloat()) * maxOf(handheld, 0.05f)

    /** シャッター・音量ボタン。 */
    fun manual() {
        engine.requestManual(nowMs)?.let { req ->
            requests += nowMs to current.truthPageId
            pending = req
            pendingState = current
            pendingMotion = current.gyro ?: 0f
            pendingFinishAt = nowMs + captureLatencyMs
        }
    }

    /** ページ番号のあたりだけを読むまね。 */
    private fun glimpse(state: SceneState): PageGlimpse {
        val page = visiblePage(state) ?: return PageGlimpse(null, hasText = false)
        if (!page.options.pageNumber || random.nextDouble() < numberMiss) return PageGlimpse(null, hasText = true)
        return PageGlimpse(misreadNumber?.invoke(page.id) ?: page.id, hasText = true)
    }

    /** 暗すぎる、ぼけている、めくっている途中は、ほとんど読めない。 */
    private fun visiblePage(state: SceneState): SyntheticPage? = when {
        state.gain < 0.2f || state.blurRadius >= 4 -> null
        state.nextPage != null && state.turnProgress >= 0.98f -> state.nextPage
        state.nextPage != null && state.turnProgress > 0.02f -> null
        else -> state.page
    }

    // 撮っているあいだ（撮る合図から撮り終わりまで）の、スマホの動きの最大
    private var pendingMotion = 0f

    private fun begin(request: CaptureRequest, script: VideoScript, frameIndex: Int) {
        // 撮り始めるのは、文字を読み終えてから。写るのは、そのときに映っているもの
        val startMs = maxOf(nowMs, readingUntil)
        val startFrame = minOf(script.frames.size - 1, ((startMs * script.fps) / 1000).toInt())
        val endFrame = minOf(script.frames.size - 1, (((startMs + captureLatencyMs) * script.fps) / 1000).toInt())
        val state = script.frames[startFrame]
        requests += nowMs to state.truthPageId
        pending = request
        pendingState = state
        var motion = 0f
        for (k in startFrame..endFrame) motion = maxOf(motion, script.frames[k].gyro ?: 0f)
        pendingMotion = motion
        pendingFinishAt = startMs + captureLatencyMs
    }

    private fun finishIfDue(current: SceneState) {
        val req = pending ?: return
        if (nowMs < pendingFinishAt) return
        val state = pendingState ?: current
        pending = null
        disturbedUntil = nowMs + captureDisturbanceMs
        if (dropFirstFinishes > 0) {
            dropFirstFinishes--
            return
        }
        if (failFirstCaptures > 0) {
            failFirstCaptures--
            engine.onCaptureFinished(req.id, nowMs, saved = false)
            return
        }
        val outcome = engine.onCaptureFinished(req.id, nowMs, saved = true)
        // 撮っているあいだにスマホが動いていたら、写真はぶれて読めない
        val blurred = pendingMotion > 0.25f
        val page = visiblePage(state)
        captures.add(
            SimCapture(req.timeMs, req.reason, state.truthPageId, state.hand != null, state.blurRadius, state.curl, outcome.movedDuringCapture, req.id, blurred),
        )
        engine.notifyVibration(nowMs, vibrationMs + 150)
        shakeUntil = nowMs + vibrationMs
        inspections += Inspection(nowMs + processLatencyMs, req.id, page.takeUnless { blurred }, usable = !blurred && page != null)
    }

    private fun LumaImage.toWork(): LumaImage = downsampleToLongSide(workLongSide)
}
