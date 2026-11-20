package app.readingcapture.core.synthetic

import app.readingcapture.core.detect.CaptureEngine
import app.readingcapture.core.detect.CaptureReason
import app.readingcapture.core.detect.CaptureRequest
import app.readingcapture.core.detect.EngineStatus
import app.readingcapture.core.frame.LumaImage
import kotlin.math.roundToInt

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
    ) = apply {
        val w = 480f
        // 手が入る
        animate(handInMs) { t ->
            base.copy(page = from, hand = Hand(tipX = w + 40 - t * 140, tipY = 330f))
        }
        // めくる
        animate(turnMs) { t ->
            val boundaryX = 30 + 420 * (1 - t)
            base.copy(page = from, nextPage = to, turnProgress = t, hand = Hand(tipX = boundaryX - 20, tipY = 330f))
        }
        // 手が抜ける（新しいページは少し反っている）
        animate(handOutMs) { t ->
            base.copy(page = to, curl = 5f, hand = Hand(tipX = 10 + t * (w + 60), tipY = 330f + t * 80))
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
)

/**
 * 台本の映像をエンジンに流し、いつ・どのページで撮ったかを記録する。
 * 実物と同じように、撮る合図から撮り終わりまでに時間がかかり、撮れたら振動してカメラが少し揺れる。
 */
class Simulation(
    val engine: CaptureEngine,
    private val renderer: SceneRenderer = SceneRenderer(),
    private val workLongSide: Int = 320,
    private val captureLatencyMs: Long = 350,
    private val vibrationMs: Long = 60,
    private val vibrationShakePx: Float = 2f,
    /** 撮るのに失敗させる回数（最初の n 回） */
    var failFirstCaptures: Int = 0,
    /**
     * 止まっていても続く、映像の細かな揺れ（画素、コマごとに乱数）。
     * 本物のカメラは、台に固定していても、ピントの微調整・手ぶれ補正・ノイズで 1 画素に満たない幅で揺れ続ける。
     * 実機（Pixel 6a）で、これを入れていない合成の映像では通るのに自動で撮れない、という不具合が出たので、既定で入れる。
     */
    private val jitterPx: Float = 0.5f,
    /** コマごとの明るさのちらつき（割合） */
    private val flicker: Float = 0.012f,
) {
    private val jitterRandom = java.util.Random(20261121)
    val captures = mutableListOf<SimCapture>()
    val statuses = mutableListOf<EngineStatus>()
    var nowMs = 0L
        private set

    private var pending: CaptureRequest? = null
    private var pendingState: SceneState? = null
    private var pendingFinishAt = 0L
    private var shakeUntil = -1L

    fun run(script: VideoScript): List<SimCapture> {
        val actionsByFrame = script.actions.groupBy({ it.first }, { it.second })
        for ((i, raw) in script.frames.withIndex()) {
            nowMs = i * 1000L / script.fps
            actionsByFrame[i]?.forEach { it(this) }
            finishIfDue(raw)
            var state = raw.copy(
                shakeX = raw.shakeX + (jitterRandom.nextFloat() * 2 - 1) * jitterPx,
                shakeY = raw.shakeY + (jitterRandom.nextFloat() * 2 - 1) * jitterPx,
                gain = raw.gain * (1f + (jitterRandom.nextFloat() * 2 - 1) * flicker),
            )
            if (nowMs < shakeUntil) {
                val sign = if (i % 2 == 0) 1 else -1
                state = state.copy(shakeX = state.shakeX + sign * vibrationShakePx, shakeY = state.shakeY - sign * vibrationShakePx * 0.5f)
            }
            val frame = renderer.render(state, i).toWork()
            val result = engine.onFrame(frame, nowMs)
            statuses.add(result.status)
            result.request?.let { begin(it, raw) }
        }
        return captures
    }

    /** シャッター・音量ボタン。 */
    fun manual(state: SceneState) {
        engine.requestManual(nowMs)?.let { begin(it, state) }
    }

    private fun begin(request: CaptureRequest, state: SceneState) {
        pending = request
        pendingState = state
        pendingFinishAt = nowMs + captureLatencyMs
    }

    private fun finishIfDue(current: SceneState) {
        val req = pending ?: return
        if (nowMs < pendingFinishAt) return
        val state = pendingState ?: current
        pending = null
        if (failFirstCaptures > 0) {
            failFirstCaptures--
            engine.onCaptureFinished(req.id, nowMs, saved = false)
            return
        }
        val outcome = engine.onCaptureFinished(req.id, nowMs, saved = true)
        captures.add(
            SimCapture(req.timeMs, req.reason, state.truthPageId, state.hand != null, state.blurRadius, state.curl, outcome.movedDuringCapture),
        )
        engine.notifyVibration(nowMs, vibrationMs)
        shakeUntil = nowMs + vibrationMs
    }

    private fun LumaImage.toWork(): LumaImage = downsampleToLongSide(workLongSide)
}
