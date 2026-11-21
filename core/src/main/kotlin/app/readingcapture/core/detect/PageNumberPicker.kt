package app.readingcapture.core.detect

import app.readingcapture.core.quality.TextBox
import kotlin.math.max

/** 数字だけの短い語（ページ番号の候補）。 */
data class NumberCandidate(
    val value: Int,
    val box: TextBox,
    /** その語が入っている行の文字数（空白を除く） */
    val lineChars: Int,
)

/** 読めた行（枠と文字数）。文字そのものは持たない。 */
data class ReadLine(val box: TextBox, val chars: Int)

/**
 * 読めた数字の中から、ページ番号を選ぶ。
 *
 * ページ番号は、数字だけの短い行で、本文のいちばん下（または上）のさらに外にある。
 * 実機の写真 8 枚では、7 枚でそういう数字がちょうど 1 つ読めた（右下、行の文字数は 2）。
 * 本文の中の数字（「20 床」の 20、年、図の番号）は、長い行の中にあるか、上下に本文があるので外れる。
 * となりのページの番号が写り込んだときは、本文の左右の範囲に近いほうを選ぶ。
 */
object PageNumberPicker {

    /** ページ番号の行とみなす文字数の上限（「- 13 -」のような飾りも入るように） */
    private const val MAX_LINE_CHARS = 4

    /** 本文の行とみなす文字数の下限 */
    private const val BODY_LINE_CHARS = 8

    /**
     * @param edgeBandOnly 読んだのが、ページの上下の帯だけか。帯だけなら、本文が写っていなくても
     *   （ページの下が図や余白で、帯に番号しか入らなかったとき）、短い数字の行をページ番号とみなす。
     *   読み違えても、撮ったあとの写真全体から読み直した番号で直る
     */
    fun pick(candidates: List<NumberCandidate>, lines: List<ReadLine>, edgeBandOnly: Boolean = false): Int? {
        val body = lines.filter { it.chars >= BODY_LINE_CHARS }
        if (body.isEmpty()) {
            if (!edgeBandOnly) return null
            // いちばん下にある短い数字
            return candidates.filter { it.value in 1..999 && it.lineChars <= MAX_LINE_CHARS }.maxByOrNull { it.box.bottom }?.value
        }
        val bodyLeft = body.minOf { it.box.left }
        val bodyRight = body.maxOf { it.box.right }

        var best: NumberCandidate? = null
        var bestScore = Float.MAX_VALUE
        for (c in candidates) {
            if (c.value !in 1..999 || c.lineChars > MAX_LINE_CHARS) continue
            val mid = (c.box.top + c.box.bottom) / 2
            val below = body.none { it.box.top > mid }  // これより下に本文が無い
            val above = body.none { it.box.bottom < mid } // これより上に本文が無い
            if (!below && !above) continue
            val centerX = (c.box.left + c.box.right) / 2
            // 本文の左右の範囲からどれだけ外れているか（となりのページの番号は、綴じ目の向こうにある）
            val outside = max(0f, max(bodyLeft - centerX, centerX - bodyRight))
            // 下にあるものを少し優先する（上は、柱の章番号と紛れやすい）
            val score = outside + if (below) 0f else 1f
            if (score < bestScore) {
                bestScore = score
                best = c
            }
        }
        return best?.value
    }
}
