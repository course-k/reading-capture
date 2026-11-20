package app.readingcapture.core.detect

import app.readingcapture.core.frame.LumaImage
import kotlin.math.abs

/**
 * 2 枚の画像の差。
 *
 * @property strongFraction はっきり変わった画素の割合（0..1）
 * @property meanAbs 差の絶対値の平均（0..255）
 * @property gain 全体の明るさの比（後 ÷ 前）。露出や照明が一様に変わった分で、差を数える前に打ち消してある
 */
data class DiffStats(val strongFraction: Float, val meanAbs: Float, val gain: Float)

object FrameDiff {

    /**
     * [before] から [after] への変化を数える。
     *
     * 全体が一様に明るく・暗くなった分（露出の追従、雲、照明のちらつき）は [DiffStats.gain] として
     * 先に打ち消すので、ページが動いていなければ strongFraction はほぼ 0 のままになる。
     *
     * @param strongAbs これを超える差を「はっきり変わった」と数える（明るさの段階）
     * @param strongRel 明るい画素ほどノイズも大きいので、明るさに比例して上乗せする割合
     */
    fun compare(
        before: LumaImage,
        after: LumaImage,
        strongAbs: Int = 10,
        strongRel: Float = 0.05f,
    ): DiffStats {
        require(before.width == after.width && before.height == after.height) { "大きさが違う" }
        val n = before.width * before.height
        val a = before.data
        val b = after.data
        var sumA = 0L
        var sumB = 0L
        for (i in 0 until n) {
            sumA += a[i].toInt() and 0xFF
            sumB += b[i].toInt() and 0xFF
        }
        // 真っ暗に近いときは比が暴れるので打ち消さない
        val gain = if (sumA < n.toLong() * 4 || sumB < n.toLong() * 4) 1f else sumB.toFloat() / sumA.toFloat()
        val gainFx = (gain * 1024f).toInt()
        val relFx = (strongRel * 1024f).toInt()
        var strong = 0
        var sumDiff = 0L
        for (i in 0 until n) {
            val va = ((a[i].toInt() and 0xFF) * gainFx) shr 10
            val vb = b[i].toInt() and 0xFF
            val d = abs(vb - va)
            sumDiff += d
            if (d > strongAbs + ((vb * relFx) shr 10)) strong++
        }
        return DiffStats(strong.toFloat() / n, sumDiff.toFloat() / n, gain)
    }
}
