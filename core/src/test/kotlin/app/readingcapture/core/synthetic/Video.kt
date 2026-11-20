package app.readingcapture.core.synthetic

import app.readingcapture.core.detect.CaptureEngine
import app.readingcapture.core.detect.CaptureReason
import app.readingcapture.core.detect.CaptureRequest
import app.readingcapture.core.detect.EngineStatus
import app.readingcapture.core.detect.PageFingerprint
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
        gyro: Float = 0.3f,
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
)

/**
 * 台本の映像をエンジンに流し、いつ・どのページで撮ったかを記録する。実物に近づけるため、次のものを足す。
 *
 * - 手に持っているときの、ゆっくりした位置・傾き・大きさのふらつきと、細かな揺れ、明るさのちらつき
 *   （実機で、これらの無い合成の映像では通るのに自動で撮れない、という不具合が出た）
 * - スマホ自体の動き（回る速さ）
 * - 文字認識の読み違いと読み落とし
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
    /** 文字認識が 1 文字を読み違える割合 */
    private val charError: Double = 0.10,
    /** 文字認識が 1 行を読み落とす割合 */
    private val lineDrop: Double = 0.08,
) {
    val captures = mutableListOf<SimCapture>()
    val requests = mutableListOf<Pair<Long, Int?>>()
    val statuses = mutableListOf<EngineStatus>()

    /** 文字認識を回した回数 */
    var pageChecks = 0
        private set
    var nowMs = 0L
        private set

    private val random = Random(20261121)
    private var pending: CaptureRequest? = null
    private var pendingState: SceneState? = null
    private var pendingFinishAt = 0L
    private var shakeUntil = -1L
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
            var gyro = raw.gyro ?: (0.03f + 0.04f * random.nextFloat()) * maxOf(handheld, 0.05f)
            if (nowMs < shakeUntil) {
                val sign = if (i % 2 == 0) 1 else -1
                state = state.copy(shakeX = state.shakeX + sign * vibrationShakePx, shakeY = state.shakeY - sign * vibrationShakePx * 0.5f)
                gyro = maxOf(gyro, 0.8f)
            }
            val frame = renderer.render(state, i).toWork()
            var result = engine.onFrame(frame, nowMs, gyro)
            if (result.needsPageCheck) {
                pageChecks++
                result = engine.onPageObserved(observe(raw), nowMs)
            }
            statuses.add(result.status)
            result.request?.let { begin(it, raw) }
        }
        return captures
    }

    /** シャッター・音量ボタン。 */
    fun manual() {
        engine.requestManual(nowMs)?.let { begin(it, current) }
    }

    /** 文字認識のまね。写っているページの文字を、読み違い・読み落としを混ぜて指紋にする。 */
    private fun observe(state: SceneState): PageFingerprint {
        // 暗すぎる、ぼけている、めくっている途中は、ほとんど読めない
        val page = when {
            state.gain < 0.2f || state.blurRadius >= 4 -> null
            state.nextPage != null && state.turnProgress >= 0.98f -> state.nextPage
            state.nextPage != null && state.turnProgress > 0.02f -> null
            else -> state.page
        } ?: return PageFingerprint.of(emptyList())
        page.full
        val drop = lineDrop + (if (state.hand != null) 0.2 else 0.0) + state.blurRadius * 0.08
        val error = charError + state.blurRadius * 0.05
        val lines = page.textLines.filter { random.nextDouble() >= drop }.map { line ->
            buildString { for (c in line) append(if (random.nextDouble() < error) (0x4E00 + random.nextInt(3000)).toChar() else c) }
        }
        return PageFingerprint.of(lines)
    }

    private fun begin(request: CaptureRequest, state: SceneState) {
        requests += nowMs to state.truthPageId
        pending = request
        pendingState = state
        pendingFinishAt = nowMs + captureLatencyMs
    }

    private fun finishIfDue(current: SceneState) {
        val req = pending ?: return
        if (nowMs < pendingFinishAt) return
        val state = pendingState ?: current
        pending = null
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
        captures.add(
            SimCapture(req.timeMs, req.reason, state.truthPageId, state.hand != null, state.blurRadius, state.curl, outcome.movedDuringCapture),
        )
        engine.notifyVibration(nowMs, vibrationMs + 150)
        shakeUntil = nowMs + vibrationMs
    }

    private fun LumaImage.toWork(): LumaImage = downsampleToLongSide(workLongSide)
}
