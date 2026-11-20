package app.readingcapture.core.detect

import app.readingcapture.core.frame.LumaImage

/** 画面に出す、いまの様子。 */
enum class EngineStatus {
    /** 始めたばかりで、露出やピントが落ち着くのを待っている */
    STARTING,

    /** 動いている（めくっている、手が入っている） */
    MOVING,

    /** 止まった。撮る前に少しだけ待っている */
    SETTLING,

    /** 撮っている最中 */
    CAPTURING,

    /** このページはもう撮った。めくるのを待っている */
    WAITING_NEXT_PAGE,

    /** ページらしいものが映っていない */
    NOT_A_PAGE,
}

enum class CaptureReason { AUTO, MANUAL, RETAKE }

/** エンジンが「いま撮って」と頼むときに返す。撮り終えたら同じ [id] で [CaptureEngine.onCaptureFinished] を呼ぶ。 */
data class CaptureRequest(val id: Long, val reason: CaptureReason, val timeMs: Long)

/** 1 コマを見た結果。 */
data class FrameResult(
    val status: EngineStatus,
    val request: CaptureRequest?,
    /** 止まってから撮るまでの進み具合（0..1）。画面の輪に使う */
    val settleProgress: Float,
    val metrics: FrameMetrics,
)

/** 合わせ込み用の数値。画像の中身は含まない。 */
data class FrameMetrics(
    val timeMs: Long,
    /** 基準のコマからの、はっきり変わった画素の割合 */
    val strongFraction: Float,
    val meanAbs: Float,
    val gain: Float,
    val sharpness: Float,
    /** 直近の同じページ判定（判定したコマだけ） */
    val match: MatchResult? = null,
    val texturedFraction: Float? = null,
)

/** 撮り終えたときにエンジンが返す。 */
data class CaptureOutcome(
    /** 撮っているあいだに映像が動いた（ぶれているかもしれない） */
    val movedDuringCapture: Boolean,
)

data class EngineConfig(
    /** 判定に使う画像の長いほうの辺（画素） */
    val workLongSide: Int = 320,
    /**
     * 「止まっている」とみなす、直前のコマからの変化の上限。区画（8×8 画素）の平均が 4 段階を超えて変わった区画の割合。
     * 本物のカメラの映像は、止まっていても 1 画素に満たない幅で揺れ続ける（ピントの微調整、手ぶれ補正、ノイズ）。
     * 画素ごとに比べるとその揺れを「動いた」と数えてしまうので、区画の平均で比べる。
     */
    val stillCellFraction: Float = 0.035f,
    /** 「止まっている」とみなす、区画の平均の変化の最大（1 か所でも大きく変われば、手や指が動いている） */
    val stillCellMax: Float = 14f,
    /** 「ほぼ止まっている」（手でページを押さえている、など）とみなす、変わった区画の割合の上限 */
    val looseCellFraction: Float = 0.10f,
    /**
     * 止まり始めたコマから、これ以上の割合の区画が（4 段階を超えて）変わったら、まだ止まっていない。
     * コマごとの変化は小さくても、ゆっくり動き続けている（ページの反りが戻っている途中、など）のを見分ける。
     */
    val driftCellFraction: Float = 0.04f,
    /** 判定を済ませたコマから、これ以上の割合の区画が（6 段階を超えて）変わったら、判定し直す */
    val changedCellFraction: Float = 0.05f,
    /** 止まってから撮るまでの時間 */
    val strictSettleMs: Long = 500,
    /** ほぼ止まっている状態が続いたら撮る、までの時間 */
    val looseSettleMs: Long = 1500,
    /** 撮り終えてから次を撮るまでに最低あける時間 */
    val refractoryMs: Long = 500,
    /** 始めてから判定を始めるまでの時間 */
    val startupGraceMs: Long = 800,
    /**
     * ピントが合ったとみなす条件 1: いまの鮮明さが、止まってからの最大のこの割合以上。
     * 本物の映像の鮮明さは、止まっていても 1〜2 割は揺れるので、揺れの幅では見ない。
     */
    val focusNearMax: Float = 0.8f,
    /** ピントが合ったとみなす条件 2: 鮮明さが、直前（0.1 秒ほど前）からこの割合を超えて上がり続けていない（合わせている最中でない） */
    val focusRising: Float = 1.12f,
    /** 鮮明さを見る時間 */
    val focusWindowMs: Long = 1000,
    /** ピントが落ち着かなくても、ここまで待ったら撮る */
    val focusTimeoutMs: Long = 1500,
    /** ページらしいとみなす、模様のある区画の割合の下限 */
    val minTexturedFraction: Float = 0.08f,
    /** 暗すぎて判定しない明るさ */
    val minMeanLuma: Float = 24f,
    /** 「撮り直す」のあと、動きが無くてもここまで待ったら撮る */
    val retakeTimeoutMs: Long = 2500,
    /** 「ページらしくない」と判断したあと、動きが無くてもこの間隔で見直す（露出がゆっくり明るくなった、など） */
    val notAPageRecheckMs: Long = 1500,
    /** 撮る合図を出してから、ここまで待っても撮り終わりの知らせが来なければ、失敗として扱う */
    val captureTimeoutMs: Long = 8000,
    val matcher: MatcherConfig = MatcherConfig(),
)

/**
 * カメラの映像のコマを順に受け取り、「いま撮る」を決める。
 *
 * 考え方: スマホは台に固定されているので、映像が変わるのはページをめくるときと手が入るとき。
 * 映像が止まったら、最後に撮った 1 枚と比べ、違うページなら撮る。同じページなら次に動くまで何もしない。
 *
 * カメラにも時計にも依存しない。時刻はコマに付いてくる値だけを使うので、
 * 合成した映像を流して同じ結果を何度でも再現できる。スレッドは 1 本から呼ぶこと。
 */
class CaptureEngine(private val config: EngineConfig = EngineConfig()) {

    private val matcher = PageMatcher(config.matcher)

    private var startedAtMs = -1L

    // 直前のコマ。これと比べて「いま動いているか」を見る
    private var prevFrame: LumaImage? = null
    // 最後に「動いている」と見たとき
    private var strictSinceMs = 0L
    private var looseSinceMs = 0L
    // 止まり始めたときのコマ。ここからゆっくりずれ続けていないかを見る
    private var windowAnchor: LumaImage? = null
    // 判定を済ませたときのコマ。ここから大きく変わったら判定し直す
    private var evalAnchor: LumaImage? = null

    // 止まったあと、この基準のコマについてはもう判定を済ませた（次に動くまで何もしない）
    private var settledStatus: EngineStatus? = null
    private var settledAtMs = 0L

    // 最後に撮ったページ（と、その 1 つ前）
    private val references = ArrayDeque<PageSignature>()

    private var pending: CaptureRequest? = null
    private var pendingSignature: PageSignature? = null
    private var movedDuringCapture = false
    private var nextRequestId = 1L
    private var lastCaptureFinishedMs = Long.MIN_VALUE / 2
    private var blankUntilMs = Long.MIN_VALUE / 2
    private var wasBlank = false

    // 撮り終わりの知らせが来ないまま待つのをやめた合図。遅れて知らせが届いたら、そのページを覚える
    private var timedOutId = -1L
    private var timedOutSignature: PageSignature? = null

    private var retakeArmedAtMs = -1L
    private var retakeSawMotion = false

    private val sharpnessTimes = ArrayDeque<Long>()
    private val sharpnessValues = ArrayDeque<Float>()

    private var lastFrame: LumaImage? = null
    private var lastFrameTimeMs = 0L

    var status: EngineStatus = EngineStatus.STARTING
        private set

    /** 撮ったページを覚えているか。 */
    val hasReference: Boolean get() = references.isNotEmpty()

    /** 撮る合図を出して、撮り終わりを待っているところか。 */
    val isCapturing: Boolean get() = pending != null

    /** 判定用の大きさに縮めたコマを渡す。 */
    fun onFrame(frame: LumaImage, timeMs: Long): FrameResult {
        if (startedAtMs < 0) startedAtMs = timeMs
        lastFrame = frame
        lastFrameTimeMs = timeMs

        val sharpness = Sharpness.gradientEnergy(frame)
        pushSharpness(timeMs, sharpness)

        // 自分の振動でスマホが揺れているあいだは、動きを見ない
        if (timeMs < blankUntilMs) {
            resetMotion(frame, timeMs)
            wasBlank = true
            return result(timeMs, DiffStats(0f, 0f, 1f), sharpness, 0f)
        }
        if (wasBlank) {
            // 振動のあいだは動きを見ていない。そのあいだにめくり終えていても気づけるよう、止まったら 1 回だけ見直す
            wasBlank = false
            if (pending == null) settledStatus = null
        }

        val prev = prevFrame
        if (prev == null) {
            resetMotion(frame, timeMs)
            status = if (timeMs - startedAtMs < config.startupGraceMs) EngineStatus.STARTING else EngineStatus.MOVING
            return result(timeMs, DiffStats(0f, 0f, 1f), sharpness, 0f)
        }

        // 直前のコマからどれだけ変わったか（区画の平均で比べる）
        val velocity = CoarseDiff.compare(prev, frame)
        prevFrame = frame
        val cellFraction = velocity.over[CELL_4]
        var strictBroken = cellFraction > config.stillCellFraction || velocity.max > config.stillCellMax
        val looseBroken = cellFraction > config.looseCellFraction
        if (!strictBroken) {
            // コマごとには止まって見えても、止まり始めからゆっくりずれ続けていないか
            val anchor = windowAnchor
            if (anchor != null && CoarseDiff.compare(anchor, frame).over[CELL_4] > config.driftCellFraction) strictBroken = true
        }
        if (strictBroken) {
            strictSinceMs = timeMs
            windowAnchor = frame
        }
        if (looseBroken) {
            looseSinceMs = timeMs
            // 「撮り直す」を押した指の揺れは数えない
            if (retakeArmedAtMs >= 0 && timeMs - retakeArmedAtMs >= RETAKE_TAP_IGNORE_MS) retakeSawMotion = true
        }
        // 判定を済ませたコマから大きく変わったら（ページをめくった、手が入った）、済ませた判定は捨てる
        if (settledStatus != null) {
            val anchor = evalAnchor
            if (anchor == null || CoarseDiff.compare(anchor, frame).over[CELL_6] > config.changedCellFraction) settledStatus = null
        }
        val strictDiff = DiffStats(cellFraction, velocity.max, velocity.gain)

        pending?.let { p ->
            if (timeMs - p.timeMs > config.captureTimeoutMs) {
                // 撮り終わりの知らせが来ない。撮れなかったものとして、判定に戻る
                timedOutId = p.id
                timedOutSignature = pendingSignature
                if (p.reason == CaptureReason.RETAKE) rearmRetake(timeMs)
                pending = null
                pendingSignature = null
                movedDuringCapture = false
                settledStatus = null
            } else {
                // 撮っている最中に動いたら、ぶれているかもしれない
                if (strictBroken) movedDuringCapture = true
                status = EngineStatus.CAPTURING
                return result(timeMs, strictDiff, sharpness, 1f)
            }
        }

        if (timeMs - startedAtMs < config.startupGraceMs) {
            status = EngineStatus.STARTING
            return result(timeMs, strictDiff, sharpness, 0f)
        }

        // 大きく動くまでは、済ませた判定のまま（小さな揺れでは判定し直さない）
        settledStatus?.let { settled ->
            val recheck = settled == EngineStatus.NOT_A_PAGE && timeMs - settledAtMs >= config.notAPageRecheckMs
            if (!recheck) {
                status = settled
                return result(timeMs, strictDiff, sharpness, 0f)
            }
            settledStatus = null
        }

        val strictAge = timeMs - strictSinceMs
        val looseAge = timeMs - looseSinceMs
        val strictProgress = strictAge.toFloat() / config.strictSettleMs
        val looseProgress = looseAge.toFloat() / config.looseSettleMs
        val progress = maxOf(strictProgress, looseProgress).coerceIn(0f, 1f)

        val settled = strictAge >= config.strictSettleMs || looseAge >= config.looseSettleMs
        if (!settled) {
            // 動いた直後か、止まり始めたところ
            status = if (strictAge >= 100 || looseAge >= 250) EngineStatus.SETTLING else EngineStatus.MOVING
            return result(timeMs, strictDiff, sharpness, progress)
        }

        if (timeMs - lastCaptureFinishedMs < config.refractoryMs) {
            status = EngineStatus.SETTLING
            return result(timeMs, strictDiff, sharpness, progress)
        }

        // ピントが動いている最中は待つ（ただし待ちすぎない）
        val settledFor = maxOf(strictAge - config.strictSettleMs, looseAge - config.looseSettleMs)
        if (!focusStable(timeMs) && settledFor < config.focusTimeoutMs) {
            status = EngineStatus.SETTLING
            return result(timeMs, strictDiff, sharpness, 1f)
        }

        // ここで判定する
        val signature = matcher.prepare(frame)
        val textured = matcher.texturedFraction(signature)

        // 「撮り直す」を頼まれている: 同じページでも撮る
        if (retakeArmedAtMs >= 0) {
            val waited = timeMs - retakeArmedAtMs
            if (retakeSawMotion || waited >= config.retakeTimeoutMs) {
                retakeArmedAtMs = -1
                retakeSawMotion = false
                return request(CaptureReason.RETAKE, timeMs, signature, strictDiff, sharpness, null, textured)
            }
            status = EngineStatus.SETTLING
            return result(timeMs, strictDiff, sharpness, 1f, null, textured)
        }

        if (frame.mean() < config.minMeanLuma || textured < config.minTexturedFraction) {
            settledStatus = EngineStatus.NOT_A_PAGE
            settledAtMs = timeMs
            evalAnchor = frame
            status = EngineStatus.NOT_A_PAGE
            return result(timeMs, strictDiff, sharpness, 0f, null, textured)
        }

        if (references.isEmpty()) {
            return request(CaptureReason.AUTO, timeMs, signature, strictDiff, sharpness, null, textured)
        }
        // 最近撮った何ページかと比べる（新しい順）。前のページに戻って読み直しても、撮り直さない
        var match: MatchResult? = null
        for (i in references.indices.reversed()) {
            val m = matcher.compare(references[i], signature)
            if (match == null) match = m
            if (m.same) {
                settledStatus = EngineStatus.WAITING_NEXT_PAGE
                evalAnchor = frame
                status = EngineStatus.WAITING_NEXT_PAGE
                return result(timeMs, strictDiff, sharpness, 0f, m, textured)
            }
        }
        return request(CaptureReason.AUTO, timeMs, signature, strictDiff, sharpness, match, textured)
    }

    /**
     * シャッターや音量ボタンで撮る。いま映っているものをそのまま撮る。
     * まだコマを 1 つも受け取っていない、または撮っている最中なら null。
     */
    fun requestManual(timeMs: Long): CaptureRequest? {
        if (pending != null) return null
        val frame = lastFrame ?: return null
        retakeArmedAtMs = -1
        retakeSawMotion = false
        val req = CaptureRequest(nextRequestId++, CaptureReason.MANUAL, timeMs)
        pending = req
        pendingSignature = matcher.prepare(frame)
        movedDuringCapture = false
        status = EngineStatus.CAPTURING
        return req
    }

    /**
     * 撮り終えた（または失敗した）ことを伝える。
     * @param saved 写真が保存できたか。失敗なら、このページはまだ撮っていない扱いに戻す
     */
    fun onCaptureFinished(requestId: Long, timeMs: Long, saved: Boolean): CaptureOutcome {
        val req = pending
        if (req == null || req.id != requestId) {
            // 待つのをやめたあとで届いた知らせ。撮れていたなら、そのページは覚えておく（もう一度撮らないように）
            if (requestId == timedOutId) {
                if (saved) timedOutSignature?.let { pushReference(it) }
                timedOutId = -1
                timedOutSignature = null
                if (saved) settledStatus = null
            }
            return CaptureOutcome(movedDuringCapture = false)
        }
        val moved = movedDuringCapture
        pending = null
        movedDuringCapture = false
        lastCaptureFinishedMs = timeMs
        if (saved) {
            pendingSignature?.let {
                pushReference(it)
                evalAnchor = it.image
            }
            if (moved) {
                // 撮っているあいだにめくり始めていた。止まったら、いま映っているものを見直す
                settledStatus = null
                status = EngineStatus.MOVING
            } else {
                settledStatus = EngineStatus.WAITING_NEXT_PAGE
                status = EngineStatus.WAITING_NEXT_PAGE
            }
        } else {
            settledStatus = null
            status = EngineStatus.SETTLING
            // 「撮り直す」の 1 枚が撮れなかったら、もう一度「撮り直す」の待ちに戻す
            if (req.reason == CaptureReason.RETAKE) rearmRetake(timeMs)
        }
        pendingSignature = null
        return CaptureOutcome(moved)
    }

    /**
     * 直前の 1 枚を消した。
     *
     * 消した 1 枚が、その前の 1 枚と同じページ（二度撮り）だったなら、覚えているページを 1 つ戻す。
     * 違うページだったなら、そのページは「もう扱った」ものとして覚えたままにする
     * （消した直後に、同じページを勝手に撮り直さないため）。撮り直したいときは [armRetake] かシャッター。
     */
    fun onLastDeleted() {
        if (references.size >= 2) {
            val last = references[references.size - 1]
            val previous = references[references.size - 2]
            if (matcher.compare(previous, last).same) references.removeLast()
        }
    }

    /**
     * 「撮り直す」。直前の 1 枚を消したうえで、次に止まったら同じページでも撮る。
     * 本を動かして直す時間を取るため、動きがあってから止まるのを待つ。動きが無ければ少し待って撮る。
     */
    fun armRetake(timeMs: Long) {
        retakeArmedAtMs = timeMs
        retakeSawMotion = false
        settledStatus = null
    }

    /** 振動させた。そのあいだの映像の揺れは、めくった動きとして数えない。 */
    fun notifyVibration(timeMs: Long, durationMs: Long) {
        blankUntilMs = maxOf(blankUntilMs, timeMs + durationMs)
        wasBlank = true
    }

    /** 最後に撮ったページの、判定用の画像。アプリを開き直しても二度撮りしないよう、保存しておくのに使う。 */
    fun lastReferenceImage(): LumaImage? = references.lastOrNull()?.image

    /** 保存しておいた「最後に撮ったページ」を戻す。 */
    fun restoreReference(image: LumaImage) {
        references.clear()
        references.addLast(matcher.prepare(image.copy()))
    }

    /** 本を切り替えた、画面を開き直した、など。覚えているページを忘れる。 */
    fun reset() {
        startedAtMs = -1
        prevFrame = null
        windowAnchor = null
        evalAnchor = null
        settledStatus = null
        references.clear()
        pending = null
        pendingSignature = null
        movedDuringCapture = false
        retakeArmedAtMs = -1
        retakeSawMotion = false
        sharpnessTimes.clear()
        sharpnessValues.clear()
        lastFrame = null
        status = EngineStatus.STARTING
    }

    /** カメラを開き直した。覚えているページは残し、露出とピントの落ち着きだけ待ち直す。 */
    fun onCameraRestarted() {
        startedAtMs = -1
        prevFrame = null
        windowAnchor = null
        evalAnchor = null
        settledStatus = null
        pending = null
        pendingSignature = null
        movedDuringCapture = false
        sharpnessTimes.clear()
        sharpnessValues.clear()
        lastFrame = null
        status = EngineStatus.STARTING
    }

    // ---- 内部 ----

    private companion object {
        const val RETAKE_TAP_IGNORE_MS = 400L

        // CoarseDiff.thresholds の中の位置（4 段階・6 段階）
        const val CELL_4 = 1
        const val CELL_6 = 2

        /** 覚えておくページの数。行きつ戻りつ読んでも撮り直さないため */
        const val REMEMBERED_PAGES = 3
    }

    private fun request(
        reason: CaptureReason,
        timeMs: Long,
        signature: PageSignature,
        diff: DiffStats,
        sharpness: Float,
        match: MatchResult?,
        textured: Float,
    ): FrameResult {
        val req = CaptureRequest(nextRequestId++, reason, timeMs)
        pending = req
        pendingSignature = signature
        movedDuringCapture = false
        status = EngineStatus.CAPTURING
        return FrameResult(
            status,
            req,
            1f,
            FrameMetrics(timeMs, diff.strongFraction, diff.meanAbs, diff.gain, sharpness, match, textured),
        )
    }

    private fun result(
        timeMs: Long,
        diff: DiffStats,
        sharpness: Float,
        progress: Float,
        match: MatchResult? = null,
        textured: Float? = null,
    ) = FrameResult(
        status,
        null,
        progress,
        FrameMetrics(timeMs, diff.strongFraction, diff.meanAbs, diff.gain, sharpness, match, textured),
    )

    private fun resetMotion(frame: LumaImage, timeMs: Long) {
        prevFrame = frame
        windowAnchor = frame
        strictSinceMs = timeMs
        looseSinceMs = timeMs
    }

    private fun pushReference(signature: PageSignature) {
        references.addLast(signature)
        while (references.size > REMEMBERED_PAGES) references.removeFirst()
    }

    private fun rearmRetake(timeMs: Long) {
        retakeArmedAtMs = timeMs
        // 本を直す動きはもう済んでいるので、次に止まったらすぐ撮る
        retakeSawMotion = true
    }

    private fun pushSharpness(timeMs: Long, value: Float) {
        sharpnessTimes.addLast(timeMs)
        sharpnessValues.addLast(value)
        while (sharpnessTimes.isNotEmpty() && timeMs - sharpnessTimes.first() > config.focusWindowMs) {
            sharpnessTimes.removeFirst()
            sharpnessValues.removeFirst()
        }
    }

    /**
     * ピントを合わせている最中でないか。
     * 止まってからの鮮明さを見て、いまが最大に近く、しかも上がり続けていなければ、合っている。
     */
    private fun focusStable(timeMs: Long): Boolean {
        val from = maxOf(strictSinceMs, timeMs - config.focusWindowMs)
        val values = ArrayList<Float>()
        for (i in sharpnessTimes.indices) if (sharpnessTimes[i] >= from) values += sharpnessValues[i]
        if (values.size < 6) return false
        val n = values.size
        val recent = (values[n - 1] + values[n - 2] + values[n - 3]) / 3f
        val earlier = (values[n - 4] + values[n - 5] + values[n - 6]) / 3f
        val max = values.max()
        if (max <= 1e-3f) return true
        return recent >= max * config.focusNearMax && recent <= earlier * config.focusRising
    }
}
