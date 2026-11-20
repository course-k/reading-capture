package app.readingcapture.core.quality

import app.readingcapture.core.frame.LumaImage
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 撮った 1 枚から測った値。
 *
 * @property sharpness 鮮明さ（0..1、大きいほど鮮明）。文字らしい部分が少なくて測れないときは null
 * @property sharpnessX 横方向だけの鮮明さ（横に流れるぶれで下がる）
 * @property sharpnessY 縦方向だけの鮮明さ
 * @property paperLuma 紙の白さにあたる明るさ（明るいほうから 10% の位置、0..255）
 */
data class PhotoMeasure(
    val sharpness: Float?,
    val sharpnessX: Float?,
    val sharpnessY: Float?,
    val paperLuma: Int,
)

object PhotoMeasurer {

    /** 測るときの画像の長いほうの辺。写真をこの大きさに縮めてから [measure] に渡す。 */
    const val MEASURE_LONG_SIDE = 1024

    private const val BLOCK = 32
    private const val BLOCK_CONTRAST = 60
    private const val REBLUR_RADIUS = 2
    private const val MIN_BLOCKS = 6
    private const val NOISE_FLOOR = 5

    /**
     * 鮮明さは「わざとぼかしたら、どれだけ輪郭が弱まるか」で測る。
     * もともと鮮明な写真はぼかすと輪郭が大きく弱まり、もともとぼけている写真はあまり変わらない。
     * 文字の量や濃さに左右されにくい。文字らしい区画（明暗の差が大きい区画）だけで測る。
     */
    fun measure(image: LumaImage): PhotoMeasure {
        val w = image.width
        val h = image.height
        val d = image.data

        val hist = IntArray(256)
        var i = 0
        val n = w * h
        while (i < n) {
            hist[d[i].toInt() and 0xFF]++
            i += 3
        }
        val total = hist.sum()
        var acc = 0
        var paper = 255
        for (v in 255 downTo 0) {
            acc += hist[v]
            if (acc >= total / 10) {
                paper = v
                break
            }
        }

        val bw = w / BLOCK
        val bh = h / BLOCK
        var origX = 0L
        var blurX = 0L
        var origY = 0L
        var blurY = 0L
        var blocks = 0
        val row = IntArray(BLOCK + 2 * REBLUR_RADIUS + 1)
        for (by in 0 until bh) for (bx in 0 until bw) {
            val x0 = bx * BLOCK
            val y0 = by * BLOCK
            // 区画の中に、文字らしい明暗の差があるか
            var lo = 255
            var hi = 0
            var y = y0
            while (y < y0 + BLOCK) {
                var x = x0
                while (x < x0 + BLOCK) {
                    val v = d[y * w + x].toInt() and 0xFF
                    if (v < lo) lo = v
                    if (v > hi) hi = v
                    x += 2
                }
                y += 2
            }
            if (hi - lo < BLOCK_CONTRAST) continue
            // 端の区画は、ぼかす幅が取れないので飛ばす
            if (x0 < REBLUR_RADIUS + 1 || y0 < REBLUR_RADIUS + 1 || x0 + BLOCK + REBLUR_RADIUS + 1 >= w || y0 + BLOCK + REBLUR_RADIUS + 1 >= h) continue
            blocks++
            // 横方向
            for (yy in y0 until y0 + BLOCK) {
                val base = yy * w
                val (o, b) = lineGradients(row) { k -> d[base + x0 - REBLUR_RADIUS - 1 + k].toInt() and 0xFF }
                origX += o
                blurX += b
            }
            // 縦方向
            for (xx in x0 until x0 + BLOCK) {
                val (o, b) = lineGradients(row) { k -> d[(y0 - REBLUR_RADIUS - 1 + k) * w + xx].toInt() and 0xFF }
                origY += o
                blurY += b
            }
        }
        if (blocks < MIN_BLOCKS || origX == 0L || origY == 0L) return PhotoMeasure(null, null, null, paper)
        val sx = (1f - blurX.toFloat() / origX).coerceIn(0f, 1f)
        val sy = (1f - blurY.toFloat() / origY).coerceIn(0f, 1f)
        return PhotoMeasure(min(sx, sy), sx, sy, paper)
    }

    /**
     * 1 本の線（長さ BLOCK + 前後の余白）について、もとの勾配の合計と、ぼかしたあとの勾配の合計を返す。
     * [at] は線上の k 番目（0 が区画の手前 REBLUR_RADIUS+1 画素）の明るさ。
     */
    private inline fun lineGradients(buffer: IntArray, at: (Int) -> Int): Pair<Long, Long> {
        val len = BLOCK + 2 * REBLUR_RADIUS + 1
        for (k in 0 until len) buffer[k] = at(k)
        val win = 2 * REBLUR_RADIUS + 1
        var orig = 0L
        var blur = 0L
        // 区画の中の位置 p（0..BLOCK-1）は buffer[p + REBLUR_RADIUS + 1]
        var prevBlur = 0
        for (k in 0 until win) prevBlur += buffer[k] // 位置 -1 のまわりの合計
        for (p in 0 until BLOCK) {
            val c = p + REBLUR_RADIUS + 1
            // ノイズの分は数えない（ぼけた写真ではノイズのほうが輪郭より大きくなるため）
            orig += max(0, abs(buffer[c] - buffer[c - 1]) - NOISE_FLOOR)
            val curBlur = prevBlur - buffer[c - REBLUR_RADIUS - 1] + buffer[c + REBLUR_RADIUS]
            blur += max(0, abs(curBlur - prevBlur) - NOISE_FLOOR * win / 2)
            prevBlur = curBlur
        }
        return orig to (blur / win)
    }
}

data class QualityConfig(
    /** これより鮮明さが低ければ、ほかと比べるまでもなくぶれ・ぼけ */
    val blurAbsolute: Float = 0.20f,
    /** この回に撮ったほかの写真の中央値に対して、この割合を下回ったらぶれ・ぼけ */
    val blurRelative: Float = 0.62f,
    /** 比べるのに必要な、これまでの枚数 */
    val blurHistoryMin: Int = 3,
    val blurHistoryMax: Int = 15,
    /** 紙の明るさがこれより低ければ暗すぎる */
    val darkPaperLuma: Int = 95,
)

/** 持ち主に知らせる内容。 */
data class PhotoWarnings(
    val cut: Set<Side> = emptySet(),
    val blurred: Boolean = false,
    val dark: Boolean = false,
) {
    val any: Boolean get() = cut.isNotEmpty() || blurred || dark

    /** 保存用にまとめた値。 */
    fun toBits(): Int {
        var bits = 0
        for (s in cut) bits = bits or (1 shl s.ordinal)
        if (blurred) bits = bits or BIT_BLUR
        if (dark) bits = bits or BIT_DARK
        return bits
    }

    companion object {
        private const val BIT_BLUR = 1 shl 4
        private const val BIT_DARK = 1 shl 5

        fun fromBits(bits: Int) = PhotoWarnings(
            cut = Side.entries.filter { bits and (1 shl it.ordinal) != 0 }.toSet(),
            blurred = bits and BIT_BLUR != 0,
            dark = bits and BIT_DARK != 0,
        )
    }
}

/**
 * 測った値から、知らせるかどうかを決める。
 * 鮮明さは本や台や明るさで変わるので、この回に撮ったほかの写真とも比べる。
 */
class QualityJudge(private val config: QualityConfig = QualityConfig()) {

    private val history = ArrayDeque<Float>()

    fun judge(measure: PhotoMeasure, cut: EdgeCutResult, movedDuringCapture: Boolean): PhotoWarnings {
        val s = measure.sharpness
        var blurred = false
        if (s != null) {
            if (s < config.blurAbsolute) blurred = true
            if (history.size >= config.blurHistoryMin) {
                val median = history.sorted()[history.size / 2]
                if (s < median * config.blurRelative) blurred = true
            }
            // 撮っているあいだに動いた写真は、少しでも鮮明さが落ちていれば知らせる
            if (movedDuringCapture && history.size >= config.blurHistoryMin) {
                val median = history.sorted()[history.size / 2]
                if (s < median * (config.blurRelative + (1f - config.blurRelative) * 0.6f)) blurred = true
            }
            if (!blurred) {
                history.addLast(s)
                while (history.size > config.blurHistoryMax) history.removeFirst()
            }
        }
        return PhotoWarnings(cut = cut.sides, blurred = blurred, dark = measure.paperLuma < config.darkPaperLuma)
    }

    fun reset() = history.clear()
}
