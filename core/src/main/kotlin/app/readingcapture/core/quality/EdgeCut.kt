package app.readingcapture.core.quality

import kotlin.math.max
import kotlin.math.min

enum class Side { LEFT, TOP, RIGHT, BOTTOM }

/** 文字の行を囲む枠（画像の画素）。文字そのものは持たない。 */
data class TextBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

data class EdgeCutConfig(
    /**
     * 行の先（文字の並ぶ向き）が画像の端からこの距離以内なら「端にかかっている」とみなす（行の太さに対する割合）。
     * 半分切れた文字は認識されないので、切れていると行の先は端から 1 文字ぶん以内に来る。
     */
    val touchAlongRatio: Float = 1.0f,
    /**
     * 行の横腹が画像の端からこの距離以内なら「端にかかっている」とみなす。
     * 行と行のあいだで切れると、残った行は端から 1 行ぶん（行送り）以内に来る。
     */
    val touchAcrossRatio: Float = 1.7f,
    /** 端にかかっているとみなす最小の距離（画像の短い辺に対する割合） */
    val touchMinFraction: Float = 0.004f,
    /** 本文の行とみなす長さ（いちばん長い行に対する割合） */
    val bodyLineRatio: Float = 0.6f,
    /** 端にかかった行が、本文の幅のこの割合より長ければ、本文そのものが切れている */
    val bodyCutRatio: Float = 0.4f,
    /** 欄外の注とみなす文字の小ささ（本文の行の太さに対する割合の上限） */
    val noteThicknessRatio: Float = 0.87f,
    /** 欄外の注が切れているとみなすのに要る、端にかかった行の長さ（行の太さに対する割合）と本数 */
    val noteMinLengthRatio: Float = 3f,
    val noteMinLines: Int = 2,
)

data class EdgeCutResult(val sides: Set<Side>) {
    val isCut: Boolean get() = sides.isNotEmpty()
}

/**
 * 「このページの文字が画像の端にかかっているか」を、文字の行の枠だけから判断する。
 *
 * 本を開いて 1 ページずつ撮ると、となりのページの行の端が、画像の左右の端に写り込む。
 * 綴じ目の近くは紙が曲がっていて、本文とのあいだが詰まって見えるので、離れ具合では見分けられない
 * （実機で、3 文字ぶんの写り込みを「左が切れている」と知らせてしまった）。そこで、左右の端については、
 *
 * - 端にかかった行が、本文の幅に近い長さなら、本文そのものが切れている
 * - ページの外側（綴じ目の反対側）の端に、本文より小さな字の行が、ある程度の長さで何行か続いていれば、欄外の注が切れている
 * - それ以外は、となりのページの写り込みとして数えない
 *
 * と判断する。外側がどちらかは、ページ番号の位置で分かる（外側の隅にある）。分からないときは、注の切れは知らせない。
 * 綴じ目の側には、となりのページの小さな字（注や図の中の字）も写り込むので、字の大きさだけでは見分けられなかった
 * （実機で、左が綴じ目のページ 2 枚に「左が切れている」と誤って知らせた）。迷うときは知らせない側に倒す（持ち主の判断）。
 * 上下の端は、本文の幅の中にある行（本文、柱、ページ番号）だけを見る。
 */
object EdgeCutCheck {

    /**
     * @param outerSide ページの外側（綴じ目の反対側）。[Side.LEFT] か [Side.RIGHT]。分からなければ null
     */
    fun evaluate(
        lines: List<TextBox>,
        imageWidth: Int,
        imageHeight: Int,
        config: EdgeCutConfig = EdgeCutConfig(),
        outerSide: Side? = null,
    ): EdgeCutResult {
        var boxes = lines.filter { it.width >= 3f && it.height >= 3f }
        if (boxes.isEmpty()) return EdgeCutResult(emptySet())

        // 縦書きの本（行が縦に長い）は、縦横を入れ替えて同じ決まりで見る
        val vertical = boxes.count { it.height > it.width * 1.5f } > boxes.size / 2
        var w = imageWidth.toFloat()
        var h = imageHeight.toFloat()
        if (vertical) {
            boxes = boxes.map { TextBox(it.top, it.left, it.bottom, it.right) }
            w = imageHeight.toFloat()
            h = imageWidth.toFloat()
        }

        val thickness = boxes.map { it.height }.sorted()
        val unit = thickness[thickness.size / 2]
        val minTouch = min(w, h) * config.touchMinFraction
        val along = max(unit * config.touchAlongRatio, minTouch)
        val across = max(unit * config.touchAcrossRatio, minTouch)

        // 本文: 長い行が占める左右の範囲
        val maxLength = boxes.maxOf { it.width }
        val body = boxes.filter { it.width >= maxLength * config.bodyLineRatio }
        val bodyLeft = body.minOf { it.left }
        val bodyRight = body.maxOf { it.right }
        val bodyWidth = bodyRight - bodyLeft
        val bodyUnit = body.map { it.height }.sorted()[body.size / 2]

        fun sideCut(touching: List<TextBox>, isOuter: Boolean): Boolean {
            if (touching.any { it.width >= bodyWidth * config.bodyCutRatio }) return true
            // 欄外の注の切れは、外側の端でだけ見る
            if (!isOuter) return false
            val notes = touching.count { it.height < bodyUnit * config.noteThicknessRatio && it.width >= it.height * config.noteMinLengthRatio }
            return notes >= config.noteMinLines
        }

        val found = mutableSetOf<Side>()
        // 縦書きの本は、外側の決め方が違うので、注の切れは見ない
        val outer = if (vertical) null else outerSide
        if (sideCut(boxes.filter { it.left <= along }, outer == Side.LEFT)) found += Side.LEFT
        if (sideCut(boxes.filter { it.right >= w - along }, outer == Side.RIGHT)) found += Side.RIGHT

        // 上下: 本文の幅の中にある行だけ（となりのページの切れ端は数えない）
        fun inBody(b: TextBox): Boolean {
            val overlap = min(b.right, bodyRight) - max(b.left, bodyLeft)
            return overlap >= b.width * 0.5f
        }
        if (boxes.any { it.top <= across && inBody(it) }) found += Side.TOP
        if (boxes.any { it.bottom >= h - across && inBody(it) }) found += Side.BOTTOM

        if (!vertical) return EdgeCutResult(found)
        // 入れ替えた縦横を戻す
        return EdgeCutResult(found.map {
            when (it) {
                Side.LEFT -> Side.TOP
                Side.TOP -> Side.LEFT
                Side.RIGHT -> Side.BOTTOM
                Side.BOTTOM -> Side.RIGHT
            }
        }.toSet())
    }
}
