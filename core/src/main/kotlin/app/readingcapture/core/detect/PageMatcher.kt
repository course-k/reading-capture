package app.readingcapture.core.detect

import app.readingcapture.core.frame.LumaImage
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 「同じページかどうか」を比べるための下ごしらえ済みの画像。
 *
 * @property image 元の明るさの画像（判定用の大きさに縮めたもの）
 * @property detail 行や列の縞を取り除いた、文字の粒の模様（[image] と同じ大きさ）
 * @property coarse 4 分の 1 に縮めた画像（全体のずれを見積もるため）
 */
class PageSignature internal constructor(
    val image: LumaImage,
    internal val detail: FloatArray,
    internal val coarse: FloatArray,
    internal val coarseW: Int,
    internal val coarseH: Int,
)

/**
 * @property same 同じページと判断したか
 * @property identical ほぼ動いていない（画素の差だけで同じと分かった）
 * @property matchedBlocks 模様が一致した区画の数
 * @property eligibleBlocks 比べられた（模様のある）区画の数
 * @property shiftX 見積もった全体のずれ（画素、判定用の大きさで）
 */
data class MatchResult(
    val same: Boolean,
    val identical: Boolean,
    val matchedBlocks: Int,
    val eligibleBlocks: Int,
    val shiftX: Int,
    val shiftY: Int,
) {
    val matchFraction: Float get() = if (eligibleBlocks == 0) 0f else matchedBlocks.toFloat() / eligibleBlocks
}

data class MatcherConfig(
    /** 区画の一辺（画素） */
    val block: Int = 40,
    /** 区画ごとに探すずれの幅（±画素） */
    val searchRadius: Int = 6,
    /** 全体のずれを探す幅（画像の辺に対する割合） */
    val globalSearchFraction: Float = 0.25f,
    /** 区画の模様が一致したとみなす相関 */
    val blockCorrelation: Float = 0.5f,
    /** 模様があるとみなす、区画内のばらつき */
    val minTexture: Float = 4.0f,
    /** 同じページとみなす、一致した区画の割合 */
    val sameFraction: Float = 0.3f,
    /** 比べるのに必要な区画の数。これより少ないと「違うページ」に倒す（撮り逃しより二度撮りを選ぶ） */
    val minEligibleBlocks: Int = 4,
    /** 画素の差だけで「動いていない」とみなす上限 */
    val identicalStrongFraction: Float = 0.01f,
)

/**
 * 2 枚の画像が同じページかどうかを判断する。
 *
 * 同じ本のページは行の位置がそろっているので、縮めた画像どうしをそのまま比べると、
 * 違うページでも「行の縞」が重なって似て見える。そこで行方向・列方向の縞を取り除き、
 * 残った「文字の粒の模様」を区画ごとに少しずつずらして比べる。
 * 区画ごとに比べるので、ページが少しずれた・反りが変わった・手が一部を隠した、には強い。
 */
class PageMatcher(private val config: MatcherConfig = MatcherConfig()) {

    fun prepare(image: LumaImage): PageSignature {
        val w = image.width
        val h = image.height
        val detail = removeStripes(image, config.block)
        val cw = max(8, w / 4)
        val ch = max(8, h / 4)
        val coarseImg = image.downsample(cw, ch)
        val coarse = FloatArray(cw * ch) { (coarseImg.data[it].toInt() and 0xFF).toFloat() }
        return PageSignature(image, detail, coarse, cw, ch)
    }

    fun compare(reference: PageSignature, current: PageSignature): MatchResult {
        val w = reference.image.width
        val h = reference.image.height
        require(w == current.image.width && h == current.image.height) { "大きさが違う" }

        // まず画素の差だけで済むか（めくっていない、明るさが変わっただけ、など）
        val diff = FrameDiff.compare(reference.image, current.image)
        if (diff.strongFraction < config.identicalStrongFraction) {
            return MatchResult(same = true, identical = true, matchedBlocks = 0, eligibleBlocks = 0, shiftX = 0, shiftY = 0)
        }

        val (gx, gy) = estimateGlobalShift(reference, current)
        val shiftX = gx * w / reference.coarseW
        val shiftY = gy * h / reference.coarseH

        val b = config.block
        val cols = w / b
        val rows = h / b
        // 端の余りは左右・上下に均等に割り振る
        val x0 = (w - cols * b) / 2
        val y0 = (h - rows * b) / 2
        var eligible = 0
        var matched = 0
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val bx = x0 + c * b
                val by = y0 + r * b
                val refStd = blockStd(reference.detail, w, bx, by, b)
                if (refStd < config.minTexture) continue
                // ずらした先が画像の外に出る区画は比べられない
                val cx = bx + shiftX
                val cy = by + shiftY
                if (cx - config.searchRadius < 0 || cy - config.searchRadius < 0 ||
                    cx + b + config.searchRadius > w || cy + b + config.searchRadius > h
                ) continue
                eligible++
                if (blockMatches(reference.detail, current.detail, w, bx, by, cx, cy)) matched++
            }
        }
        val same = eligible >= config.minEligibleBlocks && matched.toFloat() / eligible >= config.sameFraction
        return MatchResult(same, identical = false, matchedBlocks = matched, eligibleBlocks = eligible, shiftX = shiftX, shiftY = shiftY)
    }

    /** 模様のある区画の割合（0..1）。「ページらしさ」の目安に使う。 */
    fun texturedFraction(signature: PageSignature): Float {
        val w = signature.image.width
        val h = signature.image.height
        val b = config.block
        val cols = w / b
        val rows = h / b
        if (cols == 0 || rows == 0) return 0f
        val x0 = (w - cols * b) / 2
        val y0 = (h - rows * b) / 2
        var textured = 0
        for (r in 0 until rows) for (c in 0 until cols) {
            if (blockStd(signature.detail, w, x0 + c * b, y0 + r * b, b) >= config.minTexture) textured++
        }
        return textured.toFloat() / (cols * rows)
    }

    // ---- 内部 ----

    /**
     * 行方向・列方向の平均を引いて、縞（行の並び、段の並び、影のなだらかな変化）を取り除く。
     * 残るのは、文字の濃い・薄いの並びのような細かい模様。
     */
    private fun removeStripes(image: LumaImage, window: Int): FloatArray {
        val w = image.width
        val h = image.height
        val src = FloatArray(w * h) { (image.data[it].toInt() and 0xFF).toFloat() }
        val rowMean = boxFilterRows(src, w, h, window)
        val colMean = boxFilterCols(src, w, h, window)
        val boxMean = boxFilterCols(rowMean, w, h, window)
        val out = FloatArray(w * h)
        for (i in 0 until w * h) out[i] = src[i] - rowMean[i] - colMean[i] + boxMean[i]
        return out
    }

    private fun boxFilterRows(src: FloatArray, w: Int, h: Int, window: Int): FloatArray {
        val out = FloatArray(w * h)
        val half = window / 2
        val prefix = DoubleArray(w + 1)
        for (y in 0 until h) {
            val base = y * w
            prefix[0] = 0.0
            for (x in 0 until w) prefix[x + 1] = prefix[x] + src[base + x]
            for (x in 0 until w) {
                val a = max(0, x - half)
                val b = min(w, x + half + 1)
                out[base + x] = ((prefix[b] - prefix[a]) / (b - a)).toFloat()
            }
        }
        return out
    }

    private fun boxFilterCols(src: FloatArray, w: Int, h: Int, window: Int): FloatArray {
        val out = FloatArray(w * h)
        val half = window / 2
        val prefix = DoubleArray(h + 1)
        for (x in 0 until w) {
            prefix[0] = 0.0
            for (y in 0 until h) prefix[y + 1] = prefix[y] + src[y * w + x]
            for (y in 0 until h) {
                val a = max(0, y - half)
                val b = min(h, y + half + 1)
                out[y * w + x] = ((prefix[b] - prefix[a]) / (b - a)).toFloat()
            }
        }
        return out
    }

    private fun blockStd(data: FloatArray, w: Int, bx: Int, by: Int, b: Int): Float {
        var s = 0.0
        var ss = 0.0
        for (y in by until by + b) {
            val base = y * w
            for (x in bx until bx + b) {
                val v = data[base + x]
                s += v
                ss += v * v
            }
        }
        val n = b * b
        val mean = s / n
        return sqrt(max(0.0, ss / n - mean * mean)).toFloat()
    }

    /**
     * 参照の区画 (bx,by) と、今の画像の (cx,cy) 付近を、中心から外へ順にずらして比べる。
     * どこかで相関がしきい値を超えれば一致。
     */
    private fun blockMatches(ref: FloatArray, cur: FloatArray, w: Int, bx: Int, by: Int, cx: Int, cy: Int): Boolean {
        val b = config.block
        // 1 つおきの画素で比べる（速さのため。区画あたり 400 点あれば足りる）
        val step = 2
        val side = (b + step - 1) / step
        val n = side * side
        var sr = 0f
        var srr = 0f
        var y = 0
        while (y < b) {
            val base = (by + y) * w + bx
            var x = 0
            while (x < b) {
                val v = ref[base + x]
                sr += v
                srr += v * v
                x += step
            }
            y += step
        }
        val varR = srr - sr * sr / n
        if (varR <= 1e-3f) return false
        val threshold = config.blockCorrelation
        val radius = config.searchRadius
        for (ring in 0..radius) {
            for (dy in -ring..ring) {
                for (dx in -ring..ring) {
                    if (max(kotlin.math.abs(dx), kotlin.math.abs(dy)) != ring) continue
                    var sc = 0f
                    var scc = 0f
                    var src = 0f
                    y = 0
                    while (y < b) {
                        val rb = (by + y) * w + bx
                        val cb = (cy + dy + y) * w + cx + dx
                        var x = 0
                        while (x < b) {
                            val rv = ref[rb + x]
                            val cv = cur[cb + x]
                            sc += cv
                            scc += cv * cv
                            src += rv * cv
                            x += step
                        }
                        y += step
                    }
                    val varC = scc - sc * sc / n
                    if (varC <= 1e-3f) continue
                    val cov = src - sr * sc / n
                    if (cov > 0f && cov * cov >= threshold * threshold * varR * varC) return true
                }
            }
        }
        return false
    }

    /** 縮めた画像どうしで、いちばんよく重なるずれを探す（粗い画像の画素単位）。 */
    private fun estimateGlobalShift(reference: PageSignature, current: PageSignature): Pair<Int, Int> {
        val w = reference.coarseW
        val h = reference.coarseH
        val a = reference.coarse
        val b = current.coarse
        val rx = (w * config.globalSearchFraction).toInt()
        val ry = (h * config.globalSearchFraction).toInt()
        var best = -2.0
        var bestDx = 0
        var bestDy = 0
        for (dy in -ry..ry) {
            for (dx in -rx..rx) {
                // 参照の (x,y) と、今の (x+dx, y+dy) を重ねる
                val xa = max(0, -dx)
                val xb = min(w, w - dx)
                val ya = max(0, -dy)
                val yb = min(h, h - dy)
                val n = (xb - xa) * (yb - ya)
                if (n <= 0) continue
                var sa = 0.0
                var sb = 0.0
                var saa = 0.0
                var sbb = 0.0
                var sab = 0.0
                for (y in ya until yb) {
                    val ia = y * w
                    val ib = (y + dy) * w + dx
                    for (x in xa until xb) {
                        val va = a[ia + x]
                        val vb = b[ib + x]
                        sa += va
                        sb += vb
                        saa += va * va
                        sbb += vb * vb
                        sab += va * vb
                    }
                }
                val varA = saa - sa * sa / n
                val varB = sbb - sb * sb / n
                if (varA <= 1e-6 || varB <= 1e-6) continue
                val ncc = (sab - sa * sb / n) / sqrt(varA * varB)
                // ずれが大きい候補は重なりが小さく、たまたま高くなりやすいので少し割り引く
                val score = ncc - 0.002 * (kotlin.math.abs(dx) + kotlin.math.abs(dy))
                if (score > best) {
                    best = score
                    bestDx = dx
                    bestDy = dy
                }
            }
        }
        return bestDx to bestDy
    }
}
