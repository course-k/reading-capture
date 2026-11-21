package app.readingcapture.core.quality

import app.readingcapture.core.frame.LumaImage
import kotlin.math.abs

/**
 * 写真の端に、文字が食い込んでいないかを見る。文字は読まない。
 *
 * 写真を升目に分け、升目ごとに「明るさが細かく入れ替わる画素の割合」を数える。文字のある升目は割合が高く、
 * 余白・紙の端の線・指・影は低い。端の升目と、その内側の升目が続けて文字なら、文字が端まで届いている。
 *
 * 文字認識の行の枠を使う方法（[EdgeCutCheck]）は、手持ちで行が傾いて写ると、枠が縦に伸びて上下の端にかかり、
 * 実機で 13 枚中 6 枚に誤った知らせを出した。升目で見るこの方法は、傾きの影響を受けない
 * （同じ 13 枚で、ふつうに撮れた 12 枚は 0 枚、ページの一部しか入っていない 1 枚だけを拾った）。
 *
 * 分かるのは「写っている文字が端に届いているか」まで。欄外の注が丸ごと映像の外にあるときは、何も端に届かないので分からない。
 */
object MarginCheck {

    const val COLS = 30
    const val ROWS = 40

    /** 升目を「文字がある」とみなす、明るさが細かく入れ替わる画素の割合 */
    const val TEXT_DENSITY = 0.10f

    /** 隣の画素との差が、その升目の明るさのこの割合を超えたら「入れ替わり」と数える（影の中でも同じように数えるため、明るさに対する割合で見る） */
    const val STEP_RELATIVE = 0.07f
    const val STEP_MIN = 4f

    /** 端の升目から内側へ文字が続く列（上下）・行（左右）が、これだけあれば「端に届いている」 */
    const val MIN_TOUCHING = 3

    /**
     * 端から内側へ、この升目の数以上つながっていて、しかもその行（列）の文字の大半を占めていれば、本文そのもの。
     * 1 ページずつ撮ると、隣のページが映像の幅の 3 割ほどまで写り込むことがある（実機の写真で、端から 9 升以上つながる行が 3〜5 行あった）。
     * 隣のページは、綴じ目の余白をはさんで、もっと大きな本文が続くので、「大半を占めるか」で見分ける。
     */
    const val BODY_RUN = 9
    const val BODY_SHARE = 0.6f

    /** 升目ごとの、文字があるかどうか（行ごとに [COLS] 個）。 */
    fun textCells(image: LumaImage): BooleanArray {
        val w = image.width
        val h = image.height
        val d = image.data
        val out = BooleanArray(COLS * ROWS)
        for (r in 0 until ROWS) for (c in 0 until COLS) {
            val x0 = c * w / COLS
            val x1 = (c + 1) * w / COLS
            val y0 = r * h / ROWS
            val y1 = (r + 1) * h / ROWS
            var sum = 0L
            for (y in y0 until y1) {
                val base = y * w
                for (x in x0 until x1) sum += d[base + x].toInt() and 0xFF
            }
            val n = (x1 - x0) * (y1 - y0)
            if (n <= 0) continue
            val step = maxOf(STEP_MIN, sum.toFloat() / n * STEP_RELATIVE)
            var strong = 0
            var pairs = 0
            for (y in y0 until y1) {
                val base = y * w
                for (x in x0 until x1) {
                    val v = d[base + x].toInt() and 0xFF
                    if (x + 1 < x1) {
                        pairs++
                        if (abs((d[base + x + 1].toInt() and 0xFF) - v) > step) strong++
                    }
                    if (y + 1 < y1) {
                        pairs++
                        if (abs((d[base + w + x].toInt() and 0xFF) - v) > step) strong++
                    }
                }
            }
            out[r * COLS + c] = pairs > 0 && strong.toFloat() / pairs >= TEXT_DENSITY
        }
        return out
    }

    /** 端ごとの、文字が端から内側へ続いている列・行の数と、そのうち本文までつながっている数。 */
    data class Touch(val count: Int, val bodyCount: Int)

    fun touches(cells: BooleanArray): Map<Side, Touch> {
        fun at(r: Int, c: Int) = cells[r * COLS + c]
        fun run(length: Int, cell: (Int) -> Boolean): Int {
            var k = 0
            while (k < length && cell(k)) k++
            return k
        }
        fun side(lines: Int, depth: Int, cell: (line: Int, inward: Int) -> Boolean): Touch {
            var count = 0
            var body = 0
            for (line in 0 until lines) {
                val length = run(depth) { cell(line, it) }
                if (length >= 2) count++
                if (length >= BODY_RUN) {
                    var total = 0
                    for (k in 0 until depth) if (cell(line, k)) total++
                    if (length >= total * BODY_SHARE) body++
                }
            }
            return Touch(count, body)
        }
        return mapOf(
            Side.TOP to side(COLS, ROWS) { c, k -> at(k, c) },
            Side.BOTTOM to side(COLS, ROWS) { c, k -> at(ROWS - 1 - k, c) },
            Side.LEFT to side(ROWS, COLS) { r, k -> at(r, k) },
            Side.RIGHT to side(ROWS, COLS) { r, k -> at(r, COLS - 1 - k) },
        )
    }

    /**
     * @param outerSide ページの外側（綴じ目の反対側）。分からなければ null。
     *        左右の端に届いた文字が本文までつながっていないとき、外側なら欄外の注、反対側なら隣のページなので、外側だけ知らせる
     */
    fun evaluate(image: LumaImage, outerSide: Side? = null): EdgeCutResult {
        val t = touches(textCells(image))
        val sides = LinkedHashSet<Side>()
        for (side in listOf(Side.LEFT, Side.RIGHT)) {
            val touch = t.getValue(side)
            // 本文が切れているなら、端に届いた行の大半が本文までつながっている。隣のページの写り込みでは、つながるのは
            // 本文側が空いている数行だけ（実機の写真で 27 行中 3 行、17 行中 3 行、20 行中 3 行）
            if (touch.bodyCount >= MIN_TOUCHING && touch.bodyCount * 2 >= touch.count) sides += side
            else if (touch.count >= MIN_TOUCHING && side == outerSide) sides += side
        }
        for (side in listOf(Side.TOP, Side.BOTTOM)) if (t.getValue(side).count >= MIN_TOUCHING) sides += side
        return EdgeCutResult(sides)
    }
}
