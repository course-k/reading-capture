package app.readingcapture.core.detect

import app.readingcapture.core.frame.LumaImage

object Sharpness {

    /**
     * となりの画素との差の 2 乗の平均。ピントが合うほど大きくなる。
     * 映像のコマどうしで比べて、ピントが動いている最中かどうかを見るのに使う。
     */
    fun gradientEnergy(image: LumaImage): Float {
        val w = image.width
        val h = image.height
        val d = image.data
        var sum = 0L
        var n = 0
        var y = 1
        while (y < h - 1) {
            val base = y * w
            var x = 1
            while (x < w - 1) {
                val c = d[base + x].toInt() and 0xFF
                val dx = (d[base + x + 1].toInt() and 0xFF) - c
                val dy = (d[base + x + w].toInt() and 0xFF) - c
                sum += dx * dx + dy * dy
                n++
                x += 2
            }
            y += 2
        }
        return if (n == 0) 0f else sum.toFloat() / n
    }
}
