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

/** エンジンが、いまのコマについて見てほしいもの。 */
enum class PageNeed {
    NONE,

    /** ページ番号のあたりだけを読んでほしい（[CaptureEngine.onPageGlimpsed] で答える） */
    GLIMPSE,
}

/**
 * ページ番号のあたりだけを読んだ結果。
 *
 * @property pageNumber 読めたページ番号。見つからなければ null
 * @property hasText 読んだ範囲に文字があったか（机や手を映しているだけでないか）
 */
data class PageGlimpse(val pageNumber: Int?, val hasText: Boolean)

/** 1 コマを見た結果。 */
data class FrameResult(
    val status: EngineStatus,
    val request: CaptureRequest?,
    /** 止まってから撮るまでの進み具合（0..1） */
    val settleProgress: Float,
    val metrics: FrameMetrics,
    /** true のときは、同じコマで、求められたものを続けて答える */
    val need: PageNeed = PageNeed.NONE,
    /** いまの状態に関わるページ番号（撮ったページ、もう撮ってあったページ）。分からなければ null */
    val pageNumber: Int? = null,
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
    /** これを超えて動いたら、ページをめくった・別のページへ移したとみなして、次に止まったら撮る構えに戻る */
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
    /** これを超える割合の区画が変わったら、ページをめくった・手が入ったとみなして、次に止まったら撮る構えに戻る */
    val bigCellFraction: Float = 0.25f,
    /**
     * はっきり動いているコマが、合わせてこの時間続いたら、ページをめくった・別のページへ移したとみなす。
     * めくる動きは 1 コマごとの変化は小さくても、0.5 秒以上続く。手に持って止めているときの揺れは続かない。
     */
    val sustainedMotionMs: Long = 250,
    /** コマとコマのあいだが空いた（文字を読んでいた）ときに、そのあいだに大きく変わったとみなす割合 */
    val bigCellFractionAfterGap: Float = 0.4f,
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
    val focusTimeoutMs: Long = 600,
    /**
     * ページ番号を読んでいるあいだ（0.15〜0.9 秒。そのあいだコマは届かない）のスマホの動きが、これを超えたら、
     * 読んだ結果を捨てて、止まるのを待ち直す。実機で、読んでいるあいだに動き出していたのに撮り、ぶれた写真になった。
     */
    val readMotionLimit: Float = 0.2f,
    /**
     * 撮っている最中と、撮り終えてからこの時間は、映像の変化を「動いた」に数えない（スマホの動きだけで見る）。
     * 実機で、写真を撮るときに判定用の映像が乱れ（変わった区画の割合の中央値が 0.34）、スマホは止まっているのに
     * 「別のページへ移した」と数えて、同じ場所でもう一度撮っていた。
     */
    val postCaptureQuietMs: Long = 600,
    /** 覚えておくページの数。行きつ戻りつ読んでも撮り直さないため */
    val rememberedPages: Int = 12,
    /** 「ページらしくない」と判断したあとの、見直しの間隔 */
    val notAPageRecheckMs: Long = 1000,
    /**
     * 撮ったあと、大きな動きが無いまま止まっているあいだも、この間隔でページ番号だけ見直す。
     * めくったのを見逃したときの保険。新しい番号が見えたときだけ撮る。
     */
    val insuranceGlimpseMs: Long = 3000,
    /** 「撮り直す」を押してから、構え直す時間として最低あける時間 */
    val retakeDelayMs: Long = 700,
    /** 撮る合図を出してから、ここまで待っても撮り終わりの知らせが来なければ、失敗として扱う */
    val captureTimeoutMs: Long = 8000,
)

/**
 * カメラの映像のコマを順に受け取り、「いま撮る」を決める。スマホを手に持って使う前提。
 *
 * 1. スマホが止まり、写っているものも止まるのを待つ（動きセンサーと、コマどうしの差）
 * 2. 止まったら、ページ番号のあたりだけを読んでもらう（文字認識。呼び出し側が行う）。
 *    読むのは一部なので速い（ページ全体を読むと、実機で 1 回 0.5〜1.5 秒かかった）
 * 3. 番号が読めて、もう撮った番号なら、撮らない。まだの番号なら、すぐ撮る
 * 4. 番号が読めなければ、文字が写っていれば撮る
 * 5. 撮ったあとは、大きな動き（めくる、別のページへ移す）があるまで撮らない
 *
 * 同じページかどうかは、ページ番号だけで見分ける。文字の中身は比べない（実機で、隣のページが写り込んだ写真どうしを
 * 「同じページ」と見なして、撮れていた右ページを捨てていた）。番号の読めないページを二度撮ることはあるが、
 * 撮れた写真を捨てるよりよい。
 *
 * カメラにも時計にも文字認識にも依存しない。時刻・動き・読んだ結果は外から受け取るので、
 * 合成した映像を流して同じ結果を何度でも再現できる。スレッドは 1 本から呼ぶこと。
 */
class CaptureEngine(private val config: EngineConfig = EngineConfig()) {

    /** 撮ったページの覚え。 */
    private class Remembered(val requestId: Long, val reason: CaptureReason, var number: Int?)

    private var startedAtMs = -1L
    private var lastFrameMs = -1L

    // 直前のコマ。これと比べて「写っているものが動いているか」を見る
    private var prevFrame: LumaImage? = null
    // 最後に「はっきり動いている」と見たとき
    private var unsteadySinceMs = 0L
    // はっきり動いているコマが続いた時間（止まると減っていく）
    private var motionAccumMs = 0L
    // 直近のコマの動き（時刻、変わった区画の割合、区画の変化の最大、スマホの動き）
    private val recentTimes = ArrayDeque<Long>()
    private val recentFraction = ArrayDeque<Float>()
    private val recentMax = ArrayDeque<Float>()
    private val recentDevice = ArrayDeque<Float>()

    // 次に止まったら撮る構えか。撮ったあとは、大きな動きがあるまで false
    private var armed = true
    // 最後に撮って（または「もう撮った」と分かって）から、スマホ自体が動いたか。
    // 番号の読めないページは見分けがつかないので、スマホを動かしたあとでだけ撮る（影や手が通っただけでは撮り直さない）
    private var deviceMovedSinceCapture = true
    // ぶれた写真だったので「まだ撮っていない」に戻した番号。同じ番号で繰り返さない
    private val retriedNumbers = ArrayDeque<Int>()
    private var lastGlimpseMs = Long.MIN_VALUE / 2
    private var notAPageUntilMs = Long.MIN_VALUE / 2
    // いま頼んでいる「番号だけ読む」は、保険の見直しか
    private var glimpseIsInsurance = false
    // 最近撮ったページ（古い順）
    private val pages = ArrayDeque<Remembered>()

    private var pending: CaptureRequest? = null
    private var pendingNumber: Int? = null
    private var movedDuringCapture = false
    private var nextRequestId = 1L
    private var lastCaptureFinishedMs = Long.MIN_VALUE / 2
    private var blankUntilMs = Long.MIN_VALUE / 2
    private var wasBlank = false

    // 撮り終わりの知らせが来ないまま待つのをやめた撮影。遅れて知らせが届いたら、そのページを覚える
    private var timedOut: CaptureRequest? = null
    private var timedOutNumber: Int? = null

    private var retakeArmedAtMs = -1L

    private val sharpnessTimes = ArrayDeque<Long>()
    private val sharpnessValues = ArrayDeque<Float>()

    private var lastMetrics = FrameMetrics(0, 0f, 0f, 1f, 0f)

    // 画面に出すページ番号（最後に撮った、または「もう撮ってある」と分かった番号）
    private var shownNumber: Int? = null

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
            lastFrameMs = timeMs
            unsteadySinceMs = timeMs
            clearRecent()
            wasBlank = true
            lastMetrics = FrameMetrics(timeMs, 0f, 0f, 1f, sharpness, deviceMotion)
            return result(0f)
        }
        val afterBlank = wasBlank
        wasBlank = false

        // 文字を読んでいるあいだはコマが届かない。あいだの空いたコマどうしを比べると、手のふらつきが積もって
        // 動いたように見えるので、「止まっているか」には使わない。大きく変わったか（めくり終えていたか）だけを見る
        val gap = afterBlank || (lastFrameMs >= 0 && timeMs - lastFrameMs > FRAME_GAP_MS)
        val previousFrameMs = if (lastFrameMs >= 0) lastFrameMs else timeMs
        lastFrameMs = timeMs
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

        // 撮っている最中と撮った直後は、写真を撮る処理で映像が乱れる。そのあいだは、スマホの動きだけで見る
        val quiet = deviceMotion != null && (pending != null || timeMs - lastCaptureFinishedMs < config.postCaptureQuietMs)

        if (gap) {
            if (!quiet && cellFraction > config.bigCellFractionAfterGap) rearm()
            if (pending != null) status = EngineStatus.CAPTURING
            return result(0f)
        }

        // はっきり動いたコマ
        val deviceUnsteady = deviceMotion != null && deviceMotion > config.movingDeviceMotion
        if (deviceUnsteady || deviceMotion == null) deviceMovedSinceCapture = true
        val unsteady = if (quiet) deviceUnsteady
        else cellFraction > config.movingCellFraction || velocity.max > config.movingCellMax || deviceUnsteady
        if (unsteady) unsteadySinceMs = timeMs
        val dt = (timeMs - previousFrameMs).coerceIn(0, 100)
        motionAccumMs = if (unsteady) minOf(motionAccumMs + dt, config.sustainedMotionMs * 2) else maxOf(0, motionAccumMs - dt / 2)
        if (motionAccumMs >= config.sustainedMotionMs) rearm()
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
        // 大きな動き: ページをめくった、別のページへ移した
        if ((deviceMotion != null && deviceMotion > config.bigDeviceMotion) || (!quiet && cellFraction > config.bigCellFraction)) rearm()

        pending?.let { p ->
            if (timeMs - p.timeMs > config.captureTimeoutMs) {
                // 撮り終わりの知らせが来ない。撮れなかったものとして、判定に戻る
                timedOut = p
                timedOutNumber = pendingNumber
                if (p.reason == CaptureReason.RETAKE) retakeArmedAtMs = timeMs - config.retakeDelayMs
                pending = null
                pendingNumber = null
                movedDuringCapture = false
                armed = true
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
        val calm = steadyFor >= config.settleMs && recentTimes.size >= 4 &&
            median(recentFraction) <= config.stillCellFraction &&
            median(recentMax) <= config.stillCellMax &&
            median(recentDevice) <= config.steadyDeviceMotion
        val retake = retakeDue(timeMs)
        if (!calm) {
            status = when {
                !armed && !retake -> EngineStatus.WAITING_NEXT_PAGE
                steadyFor >= 120 -> EngineStatus.SETTLING
                else -> EngineStatus.MOVING
            }
            return result(if (armed || retake) progress else 0f)
        }

        // ここから先は、止まっている
        if (retakeArmedAtMs >= 0 && !retake) {
            // 「撮り直す」を押したばかり。構え直すのを待つ
            status = EngineStatus.SETTLING
            return result(progress)
        }
        if (!armed && !retake) {
            status = EngineStatus.WAITING_NEXT_PAGE
            // 保険: めくったのを見逃していないか、ときどき番号だけ見る
            if (timeMs - lastGlimpseMs >= config.insuranceGlimpseMs && timeMs - lastCaptureFinishedMs >= config.insuranceGlimpseMs) {
                glimpseIsInsurance = true
                lastGlimpseMs = timeMs
                return result(0f, PageNeed.GLIMPSE)
            }
            return result(0f)
        }
        if (timeMs < notAPageUntilMs && !retake) {
            status = EngineStatus.NOT_A_PAGE
            return result(0f)
        }
        if (timeMs - lastCaptureFinishedMs < config.refractoryMs) {
            status = EngineStatus.SETTLING
            return result(progress)
        }
        // ピントを合わせている最中は待つ（ただし待ちすぎない）
        if (!focusStable(timeMs) && steadyFor - config.settleMs < config.focusTimeoutMs) {
            status = EngineStatus.SETTLING
            return result(1f)
        }

        status = EngineStatus.SETTLING
        glimpseIsInsurance = false
        lastGlimpseMs = timeMs
        return result(1f, PageNeed.GLIMPSE)
    }

    /**
     * [onFrame] が [PageNeed.GLIMPSE] を返したコマについて、ページ番号のあたりを読んだ結果を伝える。
     * @param motionDuringRead 読んでいるあいだの、スマホの回る速さの最大（ラジアン毎秒）。センサーが無ければ null
     */
    fun onPageGlimpsed(glimpse: PageGlimpse, timeMs: Long, motionDuringRead: Float? = null): FrameResult {
        if (pending != null) return result(1f)
        val insurance = glimpseIsInsurance
        glimpseIsInsurance = false
        val number = glimpse.pageNumber

        // 読んでいるあいだに動き出していた。いま映っているものは、読んだコマとは違う。止まるのを待ち直す
        if (motionDuringRead != null && motionDuringRead > config.readMotionLimit) {
            unsteadySinceMs = timeMs
            clearRecent()
            if (motionDuringRead > config.movingDeviceMotion) deviceMovedSinceCapture = true
            if (motionDuringRead > config.bigDeviceMotion) rearm()
            status = if (armed || retakeArmedAtMs >= 0) EngineStatus.MOVING else EngineStatus.WAITING_NEXT_PAGE
            return result(0f)
        }

        // 「撮り直す」を頼まれている: 同じページでも撮る
        if (retakeDue(timeMs)) {
            retakeArmedAtMs = -1
            return request(CaptureReason.RETAKE, timeMs, number)
        }

        if (number != null) {
            if (pages.none { it.number == number }) return request(CaptureReason.AUTO, timeMs, number)
            return alreadyCaptured(number)
        }

        // 番号が読めなかった
        if (insurance) {
            // 保険の見直しでは、新しい番号が見えたときだけ撮る（番号が読めないたびに撮ると、同じページを撮り続ける）
            status = EngineStatus.WAITING_NEXT_PAGE
            return result(0f)
        }
        if (!glimpse.hasText) {
            notAPageUntilMs = timeMs + config.notAPageRecheckMs
            status = EngineStatus.NOT_A_PAGE
            return result(0f)
        }
        if (!deviceMovedSinceCapture) {
            // スマホは動いていない（影が通った、手が入った、など）。番号が読めないだけで、同じページとみなす
            armed = false
            status = EngineStatus.WAITING_NEXT_PAGE
            return result(0f)
        }
        return request(CaptureReason.AUTO, timeMs, null)
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
        pendingNumber = null
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
            val late = timedOut
            if (late != null && late.id == requestId) {
                if (saved) {
                    remember(Remembered(late.id, late.reason, timedOutNumber))
                    armed = false
                }
                timedOut = null
            }
            return CaptureOutcome(movedDuringCapture = false)
        }
        val moved = movedDuringCapture
        pending = null
        movedDuringCapture = false
        lastCaptureFinishedMs = timeMs
        if (saved) {
            remember(Remembered(req.id, req.reason, pendingNumber))
            shownNumber = pendingNumber
            // 撮っているあいだに動いていたら（めくり始めていたら）、次に止まったときに撮る構えのままにする
            armed = moved
            deviceMovedSinceCapture = moved
            // ここまでの動き（このページへ移してきた動き）は数え終えた。持ち越すと、撮った直後に「また動いた」と数えてしまう
            motionAccumMs = 0
            status = EngineStatus.WAITING_NEXT_PAGE
        } else {
            armed = true
            status = EngineStatus.SETTLING
            // 「撮り直す」の 1 枚が撮れなかったら、もう一度「撮り直す」の待ちに戻す
            if (req.reason == CaptureReason.RETAKE) retakeArmedAtMs = timeMs - config.retakeDelayMs
        }
        pendingNumber = null
        return CaptureOutcome(moved)
    }

    /**
     * 撮った写真の点検の結果を伝える。
     *
     * @param pageNumber 写真から読めたページ番号。撮る前に番号が読めていなかったときだけ使う
     *        （実機で、撮る前の読み取りは合っていて、写真からの読み取りが違っていたことが 4 回あった）
     * @param usable 読める写真か。ぶれていて読めないなら false。そのページは「まだ撮っていない」に戻す
     *        （実機で、ぶれた写真だけが残り、番号は「撮った」になっていて、向け直しても撮り直されなかった）
     */
    fun onPhotoInspected(requestId: Long, pageNumber: Int?, usable: Boolean) {
        val entry = pages.firstOrNull { it.requestId == requestId } ?: return
        if (!usable && entry.reason == CaptureReason.AUTO) {
            val number = entry.number
            // 同じ番号で 2 回続けてぶれたら、それ以上は繰り返さない（暗い部屋で、撮っては知らせる、が続かないように）
            if (number != null && number in retriedNumbers) return
            if (number != null) {
                retriedNumbers.addLast(number)
                while (retriedNumbers.size > config.rememberedPages) retriedNumbers.removeFirst()
            }
            pages.remove(entry)
            if (shownNumber == number) shownNumber = null
            return
        }
        if (entry.number == null && pageNumber != null) {
            entry.number = pageNumber
            if (shownNumber == null && pages.lastOrNull() === entry) shownNumber = pageNumber
        }
    }

    /**
     * 直前の 1 枚を消した。そのページは「もう扱った」ものとして覚えたままにする
     * （消した直後に、同じページを勝手に撮り直さないため）。撮り直したいときは [armRetake] かシャッター。
     */
    fun onLastDeleted() = Unit

    /** 「撮り直す」。直前の 1 枚を消したうえで、構え直して止まったら、同じページでも撮る。 */
    fun armRetake(timeMs: Long) {
        retakeArmedAtMs = timeMs
        // 消した 1 枚の覚えは外す
        if (pages.isNotEmpty()) pages.removeLast()
    }

    /** 振動させた。そのあいだの揺れは、動きとして数えない。 */
    fun notifyVibration(timeMs: Long, durationMs: Long) {
        blankUntilMs = maxOf(blankUntilMs, timeMs + durationMs)
        wasBlank = true
    }

    /** 覚えているページ番号（古い順）。アプリを開き直しても二度撮りしないよう、保存しておくのに使う。 */
    fun rememberedPages(): List<Int> = pages.mapNotNull { it.number }

    /** 保存しておいた覚えを戻す。 */
    fun restorePages(saved: List<Int>) {
        pages.clear()
        for (number in saved.takeLast(config.rememberedPages)) pages.addLast(Remembered(0, CaptureReason.AUTO, number))
    }

    /** 本を切り替えた。覚えているページを忘れる。 */
    fun reset() {
        pages.clear()
        retriedNumbers.clear()
        retakeArmedAtMs = -1
        shownNumber = null
        onCameraRestarted()
    }

    /** カメラを開き直した。覚えているページは残し、露出とピントの落ち着きだけ待ち直す。 */
    fun onCameraRestarted() {
        startedAtMs = -1
        lastFrameMs = -1
        prevFrame = null
        clearRecent()
        motionAccumMs = 0
        armed = true
        deviceMovedSinceCapture = true
        glimpseIsInsurance = false
        notAPageUntilMs = Long.MIN_VALUE / 2
        pending = null
        pendingNumber = null
        movedDuringCapture = false
        sharpnessTimes.clear()
        sharpnessValues.clear()
        status = EngineStatus.STARTING
    }

    // ---- 内部 ----

    private companion object {
        // CoarseDiff.thresholds の中の位置（4 段階）
        const val CELL_4 = 1

        /** コマとコマのあいだがこれより空いたら、「止まっているか」の比較には使わない */
        const val FRAME_GAP_MS = 200L
    }

    private fun rearm() {
        armed = true
        notAPageUntilMs = Long.MIN_VALUE / 2
    }

    private fun alreadyCaptured(number: Int?): FrameResult {
        armed = false
        deviceMovedSinceCapture = false
        motionAccumMs = 0
        shownNumber = number
        status = EngineStatus.WAITING_NEXT_PAGE
        return result(0f)
    }

    private fun retakeDue(timeMs: Long) = retakeArmedAtMs >= 0 && timeMs - retakeArmedAtMs >= config.retakeDelayMs

    private fun request(reason: CaptureReason, timeMs: Long, number: Int?): FrameResult {
        val req = CaptureRequest(nextRequestId++, reason, timeMs)
        pending = req
        pendingNumber = number
        movedDuringCapture = false
        status = EngineStatus.CAPTURING
        return FrameResult(status, req, 1f, lastMetrics, PageNeed.NONE, number)
    }

    private fun result(progress: Float, need: PageNeed = PageNeed.NONE) =
        FrameResult(status, null, progress, lastMetrics, need, shownNumber)

    private fun remember(entry: Remembered) {
        pages.addLast(entry)
        while (pages.size > config.rememberedPages) pages.removeFirst()
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
