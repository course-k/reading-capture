package app.readingcapture.core.detect

import app.readingcapture.core.frame.LumaImage

/** 画面に出す、いまの様子。 */
enum class EngineStatus {
    /** 始めたばかりで、露出やピントが落ち着くのを待っている */
    STARTING,

    /** 動いている（スマホが動いている、めくっている、手が入っている） */
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
    /**
     * いまのコマに何が写っているかを見てほしい（文字認識で指紋を作ってほしい）。
     * true のときは、同じコマで [CaptureEngine.onPageObserved] を続けて呼ぶ。
     */
    val needsPageCheck: Boolean = false,
)

/** 合わせ込み用の数値。画像の中身は含まない。 */
data class FrameMetrics(
    val timeMs: Long,
    /** 直前のコマから変わった区画の割合 */
    val cellFraction: Float,
    /** 区画の平均の変化の最大 */
    val cellMax: Float,
    val gain: Float,
    val sharpness: Float,
    /** スマホ自体の動き（回る速さ）。分からなければ null */
    val deviceMotion: Float? = null,
    /** 覚えているページとの重なりの最大（見分けたコマだけ） */
    val similarity: Float? = null,
    val lineCount: Int? = null,
    val charCount: Int? = null,
)

/** 撮り終えたときにエンジンが返す。 */
data class CaptureOutcome(
    /** 撮っているあいだに動いた（ぶれているかもしれない） */
    val movedDuringCapture: Boolean,
)

data class EngineConfig(
    /** 判定に使う画像の長いほうの辺（画素） */
    val workLongSide: Int = 320,
    /**
     * 「スマホが止まっている」とみなす、回る速さの上限（ラジアン毎秒）。
     * 実機（Pixel 6a）を手に持って止めたときが 0.03〜0.08、動かしているときが 0.15 以上だった。
     */
    val steadyDeviceMotion: Float = 0.12f,
    /** これを超えて動いたら、ページをめくった・構え直したかもしれないので、止まったら見分け直す */
    val bigDeviceMotion: Float = 0.5f,
    /**
     * 「写っているものが止まっている」とみなす、直前のコマからの変化の上限。区画（8×8 画素）の平均が 4 段階を超えて変わった区画の割合。
     * 本物のカメラの映像は、止まっていても細かく揺れ続けるので、画素ごとではなく区画の平均で比べる。
     */
    val stillCellFraction: Float = 0.045f,
    /** 「止まっている」とみなす、区画の平均の変化の最大（1 か所でも大きく変われば、手や指が動いている） */
    val stillCellMax: Float = 16f,
    /**
     * 「はっきり動いている」とみなす値。これを 1 コマでも超えたら、止まるのを待ち直す。
     * 手に持っていると、止めているつもりでも、ときどき [stillCellFraction] を超えるコマが混じる。
     * そこで「止まっている」は、直近 [settleMs] のあいだの中央値で見て、はっきり動いたコマが無いこと、で判断する。
     */
    val movingCellFraction: Float = 0.15f,
    val movingCellMax: Float = 45f,
    val movingDeviceMotion: Float = 0.3f,
    /** これを超える割合の区画が変わったら、ページをめくった・手が入ったとみなして、止まったら見分け直す */
    val bigCellFraction: Float = 0.25f,
    /** 止まってから撮るまでの時間。手持ちでは長く止めていられないので短くする */
    val settleMs: Long = 350,
    /** 撮り終えてから次を撮るまでに最低あける時間 */
    val refractoryMs: Long = 400,
    /** 始めてから判定を始めるまでの時間 */
    val startupGraceMs: Long = 700,
    /**
     * ピントが合ったとみなす条件 1: いまの鮮明さが、止まってからの最大のこの割合以上。
     * 本物の映像の鮮明さは、止まっていても 1〜2 割は揺れるので、揺れの幅では見ない。
     */
    val focusNearMax: Float = 0.8f,
    /** ピントが合ったとみなす条件 2: 鮮明さが、直前（0.1 秒ほど前）からこの割合を超えて上がり続けていない（合わせている最中でない） */
    val focusRising: Float = 1.12f,
    /** 鮮明さを見る時間 */
    val focusWindowMs: Long = 1000,
    /** ピントが落ち着かなくても、ここまで待ったら進める */
    val focusTimeoutMs: Long = 800,
    /** ページとみなすのに要る、読めた行と文字の数 */
    val minLines: Int = 4,
    val minChars: Int = 40,
    /**
     * 同じページとみなす、指紋の重なり。
     * 端末の文字認識で実測した値（合成したページ、乱数の文字）: 同じページを位置・大きさ・傾きを変えて見たとき 0.31〜0.43、
     * 違うページ 0.00。乱数の文字は読み違いが多いので、本物の文章なら同じページの重なりはこれより高くなる見込み（未確認）。
     * 実機（Pixel 6a、本物の本、手持ち）で実測した値: 同じページ 0.88〜0.94、違うページ 0.05〜0.12。
     */
    val sameSimilarity: Float = 0.3f,
    /** 覚えておくページの数。行きつ戻りつ読んでも撮り直さないため */
    val rememberedPages: Int = 6,
    /** 見分けたあと、次に見分け直すまでに最低あける時間（止まっているあいだ、文字認識を回し続けない） */
    val recheckMinMs: Long = 700,
    /** 大きな動きが無くても、止まっているあいだはこの間隔で見分け直す（めくったのを見逃したときの保険） */
    val recheckMaxMs: Long = 2500,
    /** 「ページらしくない」と判断したあとの、見分け直しの間隔 */
    val notAPageRecheckMs: Long = 1000,
    /** 「撮り直す」を押してから、構え直す時間として最低あける時間 */
    val retakeDelayMs: Long = 700,
    /** 撮る合図を出してから、ここまで待っても撮り終わりの知らせが来なければ、失敗として扱う */
    val captureTimeoutMs: Long = 8000,
    /** シャッターで撮ったあと、そのページの指紋を取れるまで待つ時間 */
    val adoptWindowMs: Long = 3000,
)

/**
 * カメラの映像のコマを順に受け取り、「いま撮る」を決める。スマホを手に持って使う前提。
 *
 * 1. スマホが止まり、写っているものも止まるのを待つ（動きセンサーと、コマどうしの差）
 * 2. 止まったら、そのコマに何が写っているかを見てもらう（文字認識。呼び出し側が行う）
 * 3. ページが写っていて、最近撮ったどのページとも違えば、撮る
 *
 * 文字認識を回すのは、止まったときだけ。同じページを映し続けているあいだは、間隔をあける。
 *
 * カメラにも時計にも文字認識にも依存しない。時刻・動き・指紋は外から受け取るので、
 * 合成した映像を流して同じ結果を何度でも再現できる。スレッドは 1 本から呼ぶこと。
 */
class CaptureEngine(private val config: EngineConfig = EngineConfig()) {

    private var startedAtMs = -1L

    // 直前のコマ。これと比べて「写っているものが動いているか」を見る
    private var prevFrame: LumaImage? = null
    // 最後に「はっきり動いている」と見たとき
    private var unsteadySinceMs = 0L
    // 直近のコマの動き（時刻、変わった区画の割合、区画の変化の最大、スマホの動き）
    private val recentTimes = ArrayDeque<Long>()
    private val recentFraction = ArrayDeque<Float>()
    private val recentMax = ArrayDeque<Float>()
    private val recentDevice = ArrayDeque<Float>()

    // 見分けた結果。次に見分け直すまで、この状態を出し続ける
    private var decided: EngineStatus? = null
    private var decidedAtMs = 0L
    // 見分けたあとに、めくった・構え直したと思われる大きな動きがあったか
    private var bigMotionSinceDecision = false

    // 最近撮ったページの指紋（古い順）。どの撮影のものかも覚えておく（あとで写真から読んだ指紋で補うため）
    private class Remembered(val requestId: Long, var fingerprint: PageFingerprint)

    private val references = ArrayDeque<Remembered>()
    private var lastFrameMs = -1L

    // 「新しいページかもしれない」と見た 1 回目の指紋。続けてもう 1 回同じものが見えたら撮る
    private var candidate: PageFingerprint? = null
    private var candidateAtMs = 0L

    private var pending: CaptureRequest? = null
    private var pendingFingerprint: PageFingerprint? = null
    private var movedDuringCapture = false
    private var nextRequestId = 1L
    private var lastCaptureFinishedMs = Long.MIN_VALUE / 2
    private var blankUntilMs = Long.MIN_VALUE / 2
    private var wasBlank = false

    // 撮り終わりの知らせが来ないまま待つのをやめた合図。遅れて知らせが届いたら、そのページを覚える
    private var timedOutId = -1L
    private var timedOutFingerprint: PageFingerprint? = null

    // シャッターで撮ったページの指紋を、次に見分けたときに覚える
    private var adoptUntilMs = -1L
    private var adoptRequestId = 0L

    private var retakeArmedAtMs = -1L

    private val sharpnessTimes = ArrayDeque<Long>()
    private val sharpnessValues = ArrayDeque<Float>()

    private var lastMetrics = FrameMetrics(0, 0f, 0f, 1f, 0f)

    var status: EngineStatus = EngineStatus.STARTING
        private set

    /** 撮る合図を出して、撮り終わりを待っているところか。 */
    val isCapturing: Boolean get() = pending != null

    /**
     * 判定用の大きさに縮めたコマを渡す。
     * @param deviceMotion 前のコマからいままでの、スマホの回る速さの最大（ラジアン毎秒）。センサーが無ければ null
     */
    fun onFrame(frame: LumaImage, timeMs: Long, deviceMotion: Float? = null): FrameResult {
        if (startedAtMs < 0) startedAtMs = timeMs

        val sharpness = Sharpness.gradientEnergy(frame)
        pushSharpness(timeMs, sharpness)

        // 自分の振動でスマホが揺れているあいだは、動きを見ない
        if (timeMs < blankUntilMs) {
            prevFrame = frame
            unsteadySinceMs = timeMs
            clearRecent()
            wasBlank = true
            lastMetrics = FrameMetrics(timeMs, 0f, 0f, 1f, sharpness, deviceMotion)
            return result(0f)
        }
        if (wasBlank) {
            // 振動のあいだは動きを見ていない。そのあいだにめくり終えていても気づけるよう、止まったら見分け直す
            wasBlank = false
            bigMotionSinceDecision = true
        }

        // 文字を読んでいるあいだはコマが届かない。あいだの空いたコマどうしを比べると、手のふらつきが積もって
        // 「大きく動いた」ように見えるので、空いたあとの最初のコマは、比べる元にするだけにする
        val gap = lastFrameMs >= 0 && timeMs - lastFrameMs > FRAME_GAP_MS
        lastFrameMs = timeMs
        if (gap && prevFrame != null) {
            prevFrame = frame
            lastMetrics = FrameMetrics(timeMs, 0f, 0f, 1f, sharpness, deviceMotion)
            if (pending != null) status = EngineStatus.CAPTURING
            return result(if (pending != null || candidate != null) 1f else 0f)
        }

        val prev = prevFrame
        prevFrame = frame
        if (prev == null) {
            unsteadySinceMs = timeMs
            status = if (timeMs - startedAtMs < config.startupGraceMs) EngineStatus.STARTING else EngineStatus.MOVING
            lastMetrics = FrameMetrics(timeMs, 0f, 0f, 1f, sharpness, deviceMotion)
            return result(0f)
        }

        // 直前のコマからどれだけ変わったか（区画の平均で比べる）
        val velocity = CoarseDiff.compare(prev, frame)
        val cellFraction = velocity.over[CELL_4]
        lastMetrics = FrameMetrics(timeMs, cellFraction, velocity.max, velocity.gain, sharpness, deviceMotion)

        // はっきり動いたコマ
        val unsteady = cellFraction > config.movingCellFraction || velocity.max > config.movingCellMax ||
            (deviceMotion != null && deviceMotion > config.movingDeviceMotion)
        if (unsteady) {
            unsteadySinceMs = timeMs
            candidate = null
        }
        recentTimes.addLast(timeMs)
        recentFraction.addLast(cellFraction)
        recentMax.addLast(velocity.max)
        recentDevice.addLast(deviceMotion ?: 0f)
        while (recentTimes.isNotEmpty() && timeMs - recentTimes.first() > config.settleMs) {
            recentTimes.removeFirst()
            recentFraction.removeFirst()
            recentMax.removeFirst()
            recentDevice.removeFirst()
        }
        if ((deviceMotion != null && deviceMotion > config.bigDeviceMotion) || cellFraction > config.bigCellFraction) {
            bigMotionSinceDecision = true
        }

        pending?.let { p ->
            if (timeMs - p.timeMs > config.captureTimeoutMs) {
                // 撮り終わりの知らせが来ない。撮れなかったものとして、判定に戻る
                timedOutId = p.id
                timedOutFingerprint = pendingFingerprint
                if (p.reason == CaptureReason.RETAKE) retakeArmedAtMs = timeMs - config.retakeDelayMs
                pending = null
                pendingFingerprint = null
                movedDuringCapture = false
                decided = null
            } else {
                // 撮っている最中に動いたら、ぶれているかもしれない
                if (unsteady) movedDuringCapture = true
                status = EngineStatus.CAPTURING
                return result(1f)
            }
        }

        if (timeMs - startedAtMs < config.startupGraceMs) {
            status = EngineStatus.STARTING
            return result(0f)
        }

        val steadyFor = timeMs - unsteadySinceMs
        val progress = (steadyFor.toFloat() / config.settleMs).coerceIn(0f, 1f)
        // 止まっている: はっきり動いたコマがしばらく無く、そのあいだの動きの中央値が小さい
        // 新しいページかもしれないと見たあとの 2 回目は、文字を読んでいたぶんコマが少ないので、少ないコマで判断する
        val calm = steadyFor >= config.settleMs && recentTimes.size >= (if (candidate != null) 2 else 4) &&
            median(recentFraction) <= config.stillCellFraction &&
            median(recentMax) <= config.stillCellMax &&
            median(recentDevice) <= config.steadyDeviceMotion
        if (!calm) {
            // 動いている。見分けた結果は、大きく動くまではそのまま出す（小さな手ぶれで表示をちらつかせない）
            val keep = decided
            status = when {
                keep != null && !bigMotionSinceDecision -> keep
                steadyFor >= 120 -> EngineStatus.SETTLING
                else -> EngineStatus.MOVING
            }
            return result(if (keep != null && !bigMotionSinceDecision) 0f else minOf(progress, 0.9f))
        }

        // ここから先は、止まっている
        // 新しいページかもしれないと見た直後は、少しあけてもう 1 回見てもらう
        if (candidate != null && decided == null) {
            if (timeMs - candidateAtMs > CANDIDATE_MAX_AGE_MS) candidate = null
            else if (timeMs - candidateAtMs < CANDIDATE_GAP_MS) {
                status = EngineStatus.SETTLING
                return result(1f)
            } else {
                status = EngineStatus.SETTLING
                return result(1f, needsPageCheck = true)
            }
        }

        decided?.let { d ->
            val age = timeMs - decidedAtMs
            val interval = if (d == EngineStatus.NOT_A_PAGE) config.notAPageRecheckMs else config.recheckMaxMs
            val recheck = retakeDue(timeMs) || age >= interval || (bigMotionSinceDecision && age >= config.recheckMinMs)
            if (!recheck) {
                status = d
                return result(0f)
            }
        }

        if (timeMs - lastCaptureFinishedMs < config.refractoryMs) {
            status = EngineStatus.SETTLING
            return result(progress)
        }
        if (retakeArmedAtMs >= 0 && !retakeDue(timeMs)) {
            // 「撮り直す」を押したばかり。構え直すのを待つ
            status = EngineStatus.SETTLING
            return result(progress)
        }

        // ピントを合わせている最中は待つ（ただし待ちすぎない）
        if (!focusStable(timeMs) && steadyFor - config.settleMs < config.focusTimeoutMs) {
            status = EngineStatus.SETTLING
            return result(1f)
        }

        status = decided ?: EngineStatus.SETTLING
        return result(1f, needsPageCheck = true)
    }

    /**
     * [onFrame] が「見てほしい」と返したコマに、何が写っていたかを伝える。
     * @param fingerprint そのコマから読めた文字の指紋
     */
    fun onPageObserved(fingerprint: PageFingerprint, timeMs: Long): FrameResult {
        var best = 0f
        var bestIndex = -1
        for ((i, ref) in references.withIndex()) {
            val sim = PageFingerprint.similarity(ref.fingerprint, fingerprint)
            if (sim > best) {
                best = sim
                bestIndex = i
            }
        }
        lastMetrics = lastMetrics.copy(similarity = best, lineCount = fingerprint.lineCount, charCount = fingerprint.charCount)

        if (pending != null) return result(1f)
        bigMotionSinceDecision = false
        decidedAtMs = timeMs

        val isPage = fingerprint.lineCount >= config.minLines && fingerprint.charCount >= config.minChars && fingerprint.hashes.size >= MIN_HASHES

        // シャッターで撮ったページ: 自動では撮り直さないよう、ここで覚える
        if (adoptUntilMs >= 0) {
            val inTime = timeMs <= adoptUntilMs
            adoptUntilMs = -1
            if (inTime && isPage && best < config.sameSimilarity) {
                remember(fingerprint, adoptRequestId)
                return decide(EngineStatus.WAITING_NEXT_PAGE)
            }
        }

        // 「撮り直す」を頼まれている: 同じページでも撮る
        if (retakeDue(timeMs)) {
            retakeArmedAtMs = -1
            candidate = null
            return request(CaptureReason.RETAKE, timeMs, fingerprint.takeIf { isPage })
        }

        if (!isPage) {
            candidate = null
            return decide(EngineStatus.NOT_A_PAGE)
        }
        if (best >= config.sameSimilarity) {
            // もう撮ったページ。見るたびに指紋を合わせて、次からもっと確かに見分けられるようにする
            candidate = null
            references[bestIndex].fingerprint = references[bestIndex].fingerprint.mergedWith(fingerprint)
            return decide(EngineStatus.WAITING_NEXT_PAGE)
        }
        // 新しいページに見える。文字認識は 1 回だけだと読み違いで別のページに見えることがあるので、
        // 続けて 2 回、同じものが見えたときだけ撮る
        val first = candidate
        if (first != null && PageFingerprint.similarity(first, fingerprint) >= config.sameSimilarity) {
            candidate = null
            return request(CaptureReason.AUTO, timeMs, first.mergedWith(fingerprint))
        }
        candidate = fingerprint
        candidateAtMs = timeMs
        decided = null
        status = EngineStatus.SETTLING
        return result(1f)
    }

    /**
     * シャッターや音量ボタンで撮る。いま映っているものをそのまま撮る。
     * 撮っている最中なら null。
     */
    fun requestManual(timeMs: Long): CaptureRequest? {
        if (pending != null) return null
        retakeArmedAtMs = -1
        val req = CaptureRequest(nextRequestId++, CaptureReason.MANUAL, timeMs)
        pending = req
        pendingFingerprint = null
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
                if (saved) timedOutFingerprint?.let { remember(it, requestId) }
                timedOutId = -1
                timedOutFingerprint = null
                if (saved) decided = null
            }
            return CaptureOutcome(movedDuringCapture = false)
        }
        val moved = movedDuringCapture
        pending = null
        movedDuringCapture = false
        lastCaptureFinishedMs = timeMs
        if (saved) {
            val fp = pendingFingerprint
            if (fp != null) remember(fp, req.id) else {
                adoptUntilMs = timeMs + config.adoptWindowMs
                adoptRequestId = req.id
            }
            decided = EngineStatus.WAITING_NEXT_PAGE
            decidedAtMs = timeMs
            // 撮っているあいだに動いていたら、止まったときにすぐ見分け直す。
            // シャッターで撮ったページは、まだ指紋を覚えていないので、止まりしだい見分けて覚える
            bigMotionSinceDecision = moved || fp == null
            if (fp == null) decidedAtMs = timeMs - config.recheckMinMs
            status = EngineStatus.WAITING_NEXT_PAGE
        } else {
            decided = null
            status = EngineStatus.SETTLING
            // 「撮り直す」の 1 枚が撮れなかったら、もう一度「撮り直す」の待ちに戻す
            if (req.reason == CaptureReason.RETAKE) retakeArmedAtMs = timeMs - config.retakeDelayMs
        }
        pendingFingerprint = null
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
            val last = references[references.size - 1].fingerprint
            val previous = references[references.size - 2].fingerprint
            if (PageFingerprint.similarity(previous, last) >= config.sameSimilarity) references.removeLast()
        }
    }

    /** 「撮り直す」。直前の 1 枚を消したうえで、構え直して止まったら、同じページでも撮る。 */
    fun armRetake(timeMs: Long) {
        retakeArmedAtMs = timeMs
        adoptUntilMs = -1
    }

    /** 振動させた。そのあいだの揺れは、動きとして数えない。 */
    fun notifyVibration(timeMs: Long, durationMs: Long) {
        blankUntilMs = maxOf(blankUntilMs, timeMs + durationMs)
        wasBlank = true
    }

    /**
     * 撮った写真そのものから読んだ指紋で、覚えているページを補う。
     *
     * 撮る前に見分けるときは、速さのために映像のまん中だけを読む。写真はページ全体が読めるので、
     * これで補っておくと、同じページのどこを映しても「もう撮った」と分かる。シャッターで撮ったページも、ここで覚える。
     */
    fun enrichReference(requestId: Long, fingerprint: PageFingerprint) {
        if (fingerprint.hashes.size < MIN_HASHES) return
        val existing = references.firstOrNull { it.requestId == requestId }
        if (existing != null) {
            existing.fingerprint = existing.fingerprint.mergedWith(fingerprint)
            return
        }
        // まだ覚えていなかった（シャッターで撮った、など）
        if (adoptUntilMs >= 0 && adoptRequestId == requestId) adoptUntilMs = -1
        remember(fingerprint, requestId)
    }

    /** 覚えているページの指紋（古い順）。アプリを開き直しても二度撮りしないよう、保存しておくのに使う。 */
    fun rememberedPages(): List<IntArray> = references.map { it.fingerprint.hashes }

    /** 保存しておいた指紋を戻す。 */
    fun restorePages(pages: List<IntArray>) {
        references.clear()
        for (h in pages.takeLast(config.rememberedPages)) references.addLast(Remembered(0, PageFingerprint(h, config.minLines, config.minChars)))
    }

    /** 本を切り替えた。覚えているページを忘れる。 */
    fun reset() {
        references.clear()
        retakeArmedAtMs = -1
        adoptUntilMs = -1
        onCameraRestarted()
    }

    /** カメラを開き直した。覚えているページは残し、露出とピントの落ち着きだけ待ち直す。 */
    fun onCameraRestarted() {
        startedAtMs = -1
        lastFrameMs = -1
        prevFrame = null
        clearRecent()
        candidate = null
        decided = null
        bigMotionSinceDecision = false
        pending = null
        pendingFingerprint = null
        movedDuringCapture = false
        sharpnessTimes.clear()
        sharpnessValues.clear()
        status = EngineStatus.STARTING
    }

    // ---- 内部 ----

    private companion object {
        // CoarseDiff.thresholds の中の位置（4 段階）
        const val CELL_4 = 1

        /** 比べるのに要る、指紋の数値の数。これより少ないと、たまたまの重なりで決まってしまう */
        const val MIN_HASHES = 20

        /** 新しいページかもしれないと見てから、もう 1 回見るまでにあける時間と、待つ上限 */
        const val CANDIDATE_GAP_MS = 100L
        const val CANDIDATE_MAX_AGE_MS = 2500L

        /** コマとコマのあいだがこれより空いたら、比べずに仕切り直す */
        const val FRAME_GAP_MS = 200L
    }

    private fun clearRecent() {
        recentTimes.clear()
        recentFraction.clear()
        recentMax.clear()
        recentDevice.clear()
    }

    private fun median(values: ArrayDeque<Float>): Float {
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private fun retakeDue(timeMs: Long) = retakeArmedAtMs >= 0 && timeMs - retakeArmedAtMs >= config.retakeDelayMs

    private fun decide(result: EngineStatus): FrameResult {
        decided = result
        status = result
        return result(0f)
    }

    private fun request(reason: CaptureReason, timeMs: Long, fingerprint: PageFingerprint?): FrameResult {
        val req = CaptureRequest(nextRequestId++, reason, timeMs)
        pending = req
        pendingFingerprint = fingerprint
        movedDuringCapture = false
        status = EngineStatus.CAPTURING
        return FrameResult(status, req, 1f, lastMetrics)
    }

    private fun result(progress: Float, needsPageCheck: Boolean = false) =
        FrameResult(status, null, progress, lastMetrics, needsPageCheck)

    private fun remember(fingerprint: PageFingerprint, requestId: Long) {
        references.addLast(Remembered(requestId, fingerprint))
        while (references.size > config.rememberedPages) references.removeFirst()
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
        val from = maxOf(unsteadySinceMs, timeMs - config.focusWindowMs)
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
