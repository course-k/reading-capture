package app.readingcapture.core.detect

import app.readingcapture.core.frame.LumaImage
import kotlin.math.abs

/**
 * 文字のかたまり（本文）の範囲。座標は、画像の向きのままの 0..1。
 *
 * @property textFraction 画像全体のうち、文字らしい区画の割合
 * @property energy50 区画の「細かさ」の中央の値
 * @property energy90 区画の「細かさ」の上位 1 割の値
 */
data class TextBlock(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val textFraction: Float,
    val energy50: Float,
    val energy90: Float,
)

/**
 * 判定用の小さな画像から、文字のかたまりの範囲を探す。文字は読まない（明るさの細かな変化の多い区画を数えるだけ）。
 *
 * ページが映像に収まっているか（本文が端で切れていないか、位置が動いていないか）を見るための部品。
 * いまは記録するだけで、撮る・撮らないの判断には使っていない。実機の数値を見てから、しきい値を決める。
 */
object TextBlockFinder {

    /** これより細かさの小さい画像は、文字が写っていないとみなす（明るさの段階） */
    const val MIN_ENERGY = 3f

    fun find(frame: LumaImage, cell: Int = 8): TextBlock? {
        val w = frame.width
        val h = frame.height
        val cols = w / cell
        val rows = h / cell
        if (cols < 4 || rows < 4) return null
        val d = frame.data
        val energy = FloatArray(cols * rows)
        val bright = FloatArray(cols * rows)
        for (r in 0 until rows) for (c in 0 until cols) {
            var sum = 0
            var lum = 0
            for (y in r * cell until (r + 1) * cell) {
                val base = y * w
                for (x in c * cell until (c + 1) * cell) {
                    val v = d[base + x].toInt() and 0xFF
                    lum += v
                    if (x + 1 < w) sum += abs((d[base + x + 1].toInt() and 0xFF) - v)
                    if (y + 1 < h) sum += abs((d[base + w + x].toInt() and 0xFF) - v)
                }
            }
            energy[r * cols + c] = sum / (2f * cell * cell)
            bright[r * cols + c] = lum / (cell * cell).toFloat()
        }
        val sortedEnergy = energy.sortedArray()
        val e50 = sortedEnergy[sortedEnergy.size / 2]
        val e90 = sortedEnergy[sortedEnergy.size * 9 / 10]
        if (e90 < MIN_ENERGY) return null
        val paper = bright.sortedArray()[bright.size * 9 / 10]
        // 文字らしい区画: 細かな変化が多く、紙の明るさに近い（机や手の影は外す）
        val energyFloor = maxOf(MIN_ENERGY, e90 * 0.45f)
        val text = BooleanArray(cols * rows) { energy[it] >= energyFloor && bright[it] >= paper * 0.5f }
        val textCount = text.count { it }

        // 文字らしい区画が続く列のうち、いちばん量の多いまとまりを本文とする（1 列の切れ目は、つながっているとみなす）
        val colCount = IntArray(cols) { c -> (0 until rows).count { r -> text[r * cols + c] } }
        val colOn = BooleanArray(cols) { colCount[it] >= rows * 0.2f }
        var best = -1 to -1
        var bestMass = 0
        var c = 0
        while (c < cols) {
            if (!colOn[c]) {
                c++
                continue
            }
            var end = c
            var mass = 0
            var k = c
            while (k < cols && (colOn[k] || (k + 1 < cols && colOn[k + 1]))) {
                if (colOn[k]) {
                    end = k
                    mass += colCount[k]
                }
                k++
            }
            if (mass > bestMass) {
                bestMass = mass
                best = c to end
            }
            c = k + 1
        }
        if (bestMass == 0) return null
        val (c0, c1) = best
        val span = c1 - c0 + 1
        val rowOn = BooleanArray(rows) { r -> (c0..c1).count { text[r * cols + it] } >= span * 0.25f }
        val r0 = rowOn.indexOfFirst { it }
        val r1 = rowOn.indexOfLast { it }
        if (r0 < 0) return null
        return TextBlock(
            left = c0.toFloat() / cols,
            top = r0.toFloat() / rows,
            right = (c1 + 1).toFloat() / cols,
            bottom = (r1 + 1).toFloat() / rows,
            textFraction = textCount.toFloat() / (cols * rows),
            energy50 = e50,
            energy90 = e90,
        )
    }

    /** 2 つの範囲の重なり（共通部分 ÷ 合わせた部分）。位置が動いていないかを見るのに使う。 */
    fun overlap(a: TextBlock, b: TextBlock): Float {
        val iw = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val ih = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (iw <= 0f || ih <= 0f) return 0f
        val inter = iw * ih
        val union = (a.right - a.left) * (a.bottom - a.top) + (b.right - b.left) * (b.bottom - b.top) - inter
        return if (union <= 0f) 0f else inter / union
    }
}
