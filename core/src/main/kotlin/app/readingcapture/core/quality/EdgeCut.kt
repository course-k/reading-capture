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
    /** 行どうしをひとかたまりとみなす間隔（行の太さに対する割合） */
    val clusterGapRatio: Float = 2.2f,
    /** 本文からこれより横に離れたかたまりは、となりのページの文字とみなす（行の太さに対する割合） */
    val facingPageGapRatio: Float = 4.0f,
)

data class EdgeCutResult(val sides: Set<Side>) {
    val isCut: Boolean get() = sides.isNotEmpty()
}

/**
 * 「文字が画像の端にかかっているか」を、文字の行の枠だけから判断する。
 *
 * 1 ページずつ撮ると、となりのページの文字が画像の左右の端に写り込む。これは切れていて当たり前なので、
 * 本文のかたまりからのど（綴じ目）の幅ぶん離れているかたまりは、となりのページとして数えない。
 * 欄外の注は本文のすぐ横にあるので、このページのものとして数える。
 */
object EdgeCutCheck {

    fun evaluate(lines: List<TextBox>, imageWidth: Int, imageHeight: Int, config: EdgeCutConfig = EdgeCutConfig()): EdgeCutResult {
        val boxes = lines.filter { it.width >= 3f && it.height >= 3f }
        if (boxes.isEmpty()) return EdgeCutResult(emptySet())

        val thickness = boxes.map { min(it.width, it.height) }.sorted()
        val unit = thickness[thickness.size / 2]
        val minTouch = min(imageWidth, imageHeight) * config.touchMinFraction
        val along = max(unit * config.touchAlongRatio, minTouch)
        val across = max(unit * config.touchAcrossRatio, minTouch)
        val gap = unit * config.clusterGapRatio

        // 近い行どうしをまとめる
        val parent = IntArray(boxes.size) { it }
        fun find(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            var c = i
            while (parent[c] != r) {
                val n = parent[c]
                parent[c] = r
                c = n
            }
            return r
        }
        for (i in boxes.indices) for (j in i + 1 until boxes.size) {
            val a = boxes[i]
            val b = boxes[j]
            val gx = max(0f, max(a.left, b.left) - min(a.right, b.right))
            val gy = max(0f, max(a.top, b.top) - min(a.bottom, b.bottom))
            if (gx <= gap && gy <= gap) parent[find(i)] = find(j)
        }
        class Cluster(val members: List<Int>) {
            val bounds: TextBox
            val area: Float

            init {
                var l = Float.MAX_VALUE
                var t = Float.MAX_VALUE
                var r = -Float.MAX_VALUE
                var b = -Float.MAX_VALUE
                var a = 0f
                for (m in members) {
                    val x = boxes[m]
                    l = min(l, x.left); t = min(t, x.top); r = max(r, x.right); b = max(b, x.bottom)
                    a += x.width * x.height
                }
                bounds = TextBox(l, t, r, b)
                area = a
            }
        }
        val clusters = boxes.indices.groupBy { find(it) }.values.map { Cluster(it) }
        val main = clusters.maxByOrNull { it.area }!!

        val sides = mutableSetOf<Side>()
        for (c in clusters) {
            if (c !== main) {
                val cb = c.bounds
                val gx = max(0f, max(cb.left, main.bounds.left) - min(cb.right, main.bounds.right))
                val hugsSide = cb.left <= along || cb.right >= imageWidth - along
                // 本文から横に大きく離れ、左右の端に張り付いている: となりのページ
                if (gx > unit * config.facingPageGapRatio && hugsSide) continue
            }
            for (m in c.members) {
                val box = boxes[m]
                // 横に長い枠は横書きの行、縦に長い枠は縦書きの行
                val horizontal = box.width >= box.height
                val limitX = if (horizontal) along else across
                val limitY = if (horizontal) across else along
                if (box.left <= limitX) sides += Side.LEFT
                if (box.right >= imageWidth - limitX) sides += Side.RIGHT
                if (box.top <= limitY) sides += Side.TOP
                if (box.bottom >= imageHeight - limitY) sides += Side.BOTTOM
            }
        }
        return EdgeCutResult(sides)
    }
}
