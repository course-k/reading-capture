package app.readingcapture.core.detect

/**
 * ページを見分けるための指紋。
 *
 * 端末の中の文字認識で読めた行から、続く 3 文字ずつの組を作り、それぞれを数値（ハッシュ）にした集まり。
 * 持つのは数値だけで、文字は持たない（文字から数値を作ったら、文字はすぐ捨てる）。
 * スマホを手に持って撮ると、同じページでも位置・大きさ・傾きが毎回変わるので、画像どうしを重ねて比べることはできない。
 * 書かれている文字の並びは変わらないので、これで見分ける。
 */
class PageFingerprint(
    /** 重なりの無い、小さい順の数値 */
    val hashes: IntArray,
    val lineCount: Int,
    val charCount: Int,
) {
    /** 同じページを見た 2 つの指紋を合わせる。読み落としや読み違いが、見るたびに補われていく。 */
    fun mergedWith(other: PageFingerprint): PageFingerprint {
        val set = HashSet<Int>(hashes.size + other.hashes.size)
        for (h in hashes) set += h
        for (h in other.hashes) set += h
        val sorted = set.toIntArray()
        sorted.sort()
        return PageFingerprint(sorted, maxOf(lineCount, other.lineCount), maxOf(charCount, other.charCount))
    }

    companion object {
        /**
         * 読めた行から指紋を作る。
         * 記号や空白は読むたびに揺れやすいので、文字と数字だけを使う。行をまたいだ組は作らない。
         */
        fun of(lines: List<String>, gram: Int = 3): PageFingerprint {
            val set = HashSet<Int>()
            var chars = 0
            var used = 0
            val buf = IntArray(256)
            for (line in lines) {
                var n = 0
                var i = 0
                while (i < line.length && n < buf.size) {
                    val cp = line.codePointAt(i)
                    i += Character.charCount(cp)
                    if (Character.isLetterOrDigit(cp)) buf[n++] = cp
                }
                if (n > 0) used++
                chars += n
                if (gram == 2) for (k in 0..n - 2) set += mix(buf[k], buf[k + 1], 0)
                else for (k in 0..n - 3) set += mix(buf[k], buf[k + 1], buf[k + 2])
            }
            val sorted = set.toIntArray()
            sorted.sort()
            return PageFingerprint(sorted, used, chars)
        }

        private fun mix(a: Int, b: Int, c: Int): Int {
            var h = a * -0x61c88647 + b
            h = (h xor (h ushr 15)) * -0x7a143589
            h += c
            h = (h xor (h ushr 13)) * -0x3d4d51cb
            return h xor (h ushr 16)
        }

        /**
         * 2 つの指紋がどのくらい重なるか（0..1）。少ないほうの何割が、もう一方にもあるか。
         * 片方が一部しか写っていなくても（手で隠れた、端が切れた）、同じページなら高くなる。
         */
        fun similarity(a: PageFingerprint, b: PageFingerprint): Float {
            val x = a.hashes
            val y = b.hashes
            val small = minOf(x.size, y.size)
            if (small == 0) return 0f
            var i = 0
            var j = 0
            var common = 0
            while (i < x.size && j < y.size) {
                when {
                    x[i] == y[j] -> { common++; i++; j++ }
                    x[i] < y[j] -> i++
                    else -> j++
                }
            }
            return common.toFloat() / small
        }
    }
}
