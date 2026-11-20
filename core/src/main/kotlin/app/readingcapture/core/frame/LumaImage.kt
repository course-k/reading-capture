package app.readingcapture.core.frame

/**
 * 明るさだけの画像。左上から行ごとに 1 画素 1 バイト（0..255）。
 *
 * 判定の部品はすべてこの形を受け取る。カメラにも Android にも依存しない。
 */
class LumaImage(val width: Int, val height: Int, val data: ByteArray) {

    init {
        require(width > 0 && height > 0) { "大きさが不正: ${width}x$height" }
        require(data.size >= width * height) { "データが足りない: ${data.size} < ${width * height}" }
    }

    fun at(x: Int, y: Int): Int = data[y * width + x].toInt() and 0xFF

    fun mean(): Double {
        var sum = 0L
        val n = width * height
        for (i in 0 until n) sum += data[i].toInt() and 0xFF
        return sum.toDouble() / n
    }

    fun copy(): LumaImage = LumaImage(width, height, data.copyOf(width * height))

    /**
     * 面積平均で縮める。カメラのコマ（行の詰め物つき）からも直接作れるよう、
     * 元データは [fromPlane] で受ける。
     */
    fun downsample(dstW: Int, dstH: Int): LumaImage =
        fromPlane(data, width, height, width, 1, dstW, dstH)

    /** 長いほうの辺が [longSide] になるように縮める。すでに小さければそのまま返す。 */
    fun downsampleToLongSide(longSide: Int): LumaImage {
        val long = maxOf(width, height)
        if (long <= longSide) return this
        val (w, h) = scaledSize(width, height, longSide)
        return downsample(w, h)
    }

    companion object {

        fun scaledSize(srcW: Int, srcH: Int, longSide: Int): Pair<Int, Int> {
            val long = maxOf(srcW, srcH)
            if (long <= longSide) return srcW to srcH
            val w = maxOf(1, Math.round(srcW.toDouble() * longSide / long).toInt())
            val h = maxOf(1, Math.round(srcH.toDouble() * longSide / long).toInt())
            return w to h
        }

        /**
         * 行の幅が [rowStride]、画素の間隔が [pixelStride] の面から、面積平均で縮めた画像を作る。
         * [reuse] に同じ大きさの配列を渡すと、それに書き込む（コマごとの確保を避ける）。
         */
        fun fromPlane(
            src: ByteArray,
            srcW: Int,
            srcH: Int,
            rowStride: Int,
            pixelStride: Int,
            dstW: Int,
            dstH: Int,
            reuse: ByteArray? = null,
        ): LumaImage {
            require(dstW in 1..srcW && dstH in 1..srcH) { "拡大はしない: ${srcW}x$srcH → ${dstW}x$dstH" }
            val out = if (reuse != null && reuse.size == dstW * dstH) reuse else ByteArray(dstW * dstH)
            // 列の区切りを先に作っておく
            val xEdges = IntArray(dstW + 1) { (it.toLong() * srcW / dstW).toInt() }
            for (j in 0 until dstH) {
                val y0 = (j.toLong() * srcH / dstH).toInt()
                val y1 = maxOf(y0 + 1, ((j + 1).toLong() * srcH / dstH).toInt())
                for (i in 0 until dstW) {
                    val x0 = xEdges[i]
                    val x1 = maxOf(x0 + 1, xEdges[i + 1])
                    var sum = 0
                    for (y in y0 until y1) {
                        var p = y * rowStride + x0 * pixelStride
                        for (x in x0 until x1) {
                            sum += src[p].toInt() and 0xFF
                            p += pixelStride
                        }
                    }
                    val count = (y1 - y0) * (x1 - x0)
                    out[j * dstW + i] = ((sum + count / 2) / count).toByte()
                }
            }
            return LumaImage(dstW, dstH, out)
        }
    }
}
