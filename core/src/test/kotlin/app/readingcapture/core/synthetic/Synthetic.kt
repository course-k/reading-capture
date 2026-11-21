package app.readingcapture.core.synthetic

import app.readingcapture.core.frame.LumaImage
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.RadialGradientPaint
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.awt.image.ConvolveOp
import java.awt.image.DataBufferByte
import java.awt.image.Kernel
import java.util.Random
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * テスト用に合成した「本のページ」。本物の本の文字は使わず、乱数で選んだ文字を並べる。
 */
class SyntheticPage(
    val id: Int,
    val seed: Long = id.toLong(),
    val options: PageOptions = PageOptions(),
) {
    /** 紙いっぱいに描いたページ（[PAGE_W]×[PAGE_H]、灰色）。 */
    val full: BufferedImage by lazy { renderFull() }

    /** 行の枠（ページ座標）。端の切れの判定のテストに使う。 */
    val lineBoxes: MutableList<FloatArray> = mutableListOf()

    /** 行ごとの文字（乱数で選んだもの）。ページを文字で見分けるテストに使う。[full] を一度読むと入る。 */
    val textLines: MutableList<String> = mutableListOf()

    private val scaledCache = HashMap<Int, BufferedImage>()

    fun scaled(width: Int): BufferedImage = scaledCache.getOrPut(width) {
        val h = width * PAGE_H / PAGE_W
        val out = BufferedImage(width, h, BufferedImage.TYPE_BYTE_GRAY)
        val g = out.createGraphics()
        g.drawImage(full.getScaledInstance(width, h, java.awt.Image.SCALE_AREA_AVERAGING), 0, 0, null)
        g.dispose()
        out
    }

    private fun renderFull(): BufferedImage {
        val rnd = Random(seed * 7919 + 13)
        val img = BufferedImage(PAGE_W, PAGE_H, BufferedImage.TYPE_BYTE_GRAY)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(options.paper, options.paper, options.paper)
        g.fillRect(0, 0, PAGE_W, PAGE_H)
        g.color = Color(28, 28, 28)

        val bodyLeft = 96
        val bodyRight = if (options.marginNotes) PAGE_W - 250 else PAGE_W - 96
        val top = 110
        val bottom = PAGE_H - 110
        val charSize = options.charSize
        val pitch = (charSize * 1.6f).roundToInt()
        val font = textFont(charSize)
        g.font = font
        val charsPerLine = (bodyRight - bodyLeft) / charSize

        var y = top
        var figureDone = !options.figure
        var headingDone = !options.heading
        if (options.blankish) {
            // 章の扉のような、文字の少ないページ
            g.font = textFont(charSize * 2)
            drawLine(g, rnd, bodyLeft + 60, PAGE_H / 3, charSize * 2, 8, lineBoxes, textLines)
            g.dispose()
            return img
        }
        while (y + charSize < bottom) {
            if (!headingDone && rnd.nextInt(6) == 0) {
                g.font = textFont((charSize * 1.5f).roundToInt())
                drawLine(g, rnd, bodyLeft, y + 8, (charSize * 1.5f).roundToInt(), 6 + rnd.nextInt(8), lineBoxes, textLines)
                g.font = font
                y += pitch * 2
                headingDone = true
                continue
            }
            if (!figureDone && y > PAGE_H / 3 && rnd.nextInt(4) == 0) {
                val fh = 200 + rnd.nextInt(120)
                if (y + fh < bottom) {
                    drawFigure(g, rnd, bodyLeft + 30, y, bodyRight - bodyLeft - 60, fh)
                    y += fh + pitch
                    figureDone = true
                    continue
                }
            }
            // 段落の終わりは行が短い。段落の頭は 1 字下げ
            val paragraphEnd = rnd.nextInt(5) == 0
            val n = if (paragraphEnd) 4 + rnd.nextInt(charsPerLine - 6) else charsPerLine
            val indent = if (rnd.nextInt(6) == 0) charSize else 0
            drawLine(g, rnd, bodyLeft + indent, y, charSize, n - indent / charSize, lineBoxes, textLines)
            y += pitch
        }
        if (options.marginNotes) {
            val noteSize = (charSize * 0.72f).roundToInt()
            g.font = textFont(noteSize)
            val noteLeft = bodyRight + 26
            val noteChars = (PAGE_W - options.marginNoteRightGap - noteLeft) / noteSize
            var ny = top + rnd.nextInt(200)
            repeat(2 + rnd.nextInt(2)) {
                repeat(4 + rnd.nextInt(5)) {
                    if (ny + noteSize < bottom) drawLine(g, rnd, noteLeft, ny, noteSize, noteChars, lineBoxes, textLines)
                    ny += (noteSize * 1.5f).roundToInt()
                }
                ny += 120 + rnd.nextInt(160)
            }
        }
        if (options.pageNumber) {
            g.font = textFont(charSize)
            // 欄外に注のあるページは、注のある側（右）が外側。番号も外側の隅に刷る（実物の本の並び）
            g.drawString((id + 30).toString(), if (options.marginNotes) PAGE_W - 150 else PAGE_W / 2 - 10, PAGE_H - 50)
        }
        g.dispose()
        return img
    }

    companion object {
        const val PAGE_W = 960
        const val PAGE_H = 1280

        private val fontName: String by lazy {
            val names = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
            listOf("Hiragino Mincho ProN", "Hiragino Sans", "Noto Serif CJK JP", "Noto Sans CJK JP", "Yu Mincho")
                .firstOrNull { it in names } ?: Font.SERIF
        }

        /** 日本語の字形が描けるか。描けない環境では、文字の代わりに線の集まりを描く。 */
        private val canDrawJapanese: Boolean by lazy { Font(fontName, Font.PLAIN, 20).canDisplay('漢') }

        private fun textFont(size: Int) = Font(fontName, Font.PLAIN, size)

        private fun randomChar(rnd: Random): Char = when (rnd.nextInt(10)) {
            in 0..3 -> (0x3042 + rnd.nextInt(80)).toChar() // ひらがな
            4 -> (0x30A2 + rnd.nextInt(80)).toChar() // カタカナ
            else -> (0x4E00 + rnd.nextInt(0x3000)).toChar() // 漢字
        }

        private fun drawLine(
            g: java.awt.Graphics2D,
            rnd: Random,
            x: Int,
            y: Int,
            size: Int,
            count: Int,
            boxes: MutableList<FloatArray>,
            texts: MutableList<String>,
        ) {
            if (count <= 0) return
            val sb = StringBuilder()
            repeat(count) { sb.append(randomChar(rnd)) }
            texts.add(sb.toString())
            if (canDrawJapanese) {
                // 1 字ずつ等間隔に置く（和文の組み方）
                for (i in 0 until count) g.drawString(sb[i].toString(), x + i * size, y + size)
            } else {
                for (i in 0 until count) {
                    val cx = x + i * size
                    repeat(3 + rnd.nextInt(5)) {
                        val x1 = cx + 2 + rnd.nextInt(size - 4)
                        val y1 = y + 2 + rnd.nextInt(size - 4)
                        if (rnd.nextBoolean()) g.fillRect(x1, y1, 2, 3 + rnd.nextInt(size / 2))
                        else g.fillRect(x1, y1, 3 + rnd.nextInt(size / 2), 2)
                    }
                }
            }
            boxes.add(floatArrayOf(x.toFloat(), y.toFloat(), (x + count * size).toFloat(), (y + size * 1.15f)))
        }

        private fun drawFigure(g: java.awt.Graphics2D, rnd: Random, x: Int, y: Int, w: Int, h: Int) {
            g.stroke = BasicStroke(2f)
            g.drawRect(x, y, w, h)
            repeat(4 + rnd.nextInt(4)) {
                val bx = x + 10 + rnd.nextInt(w - 160)
                val by = y + 10 + rnd.nextInt(h - 70)
                val bw = 80 + rnd.nextInt(60)
                val bh = 30 + rnd.nextInt(30)
                g.drawRoundRect(bx, by, bw, bh, 8, 8)
                if (rnd.nextBoolean()) {
                    g.color = Color(190, 190, 190)
                    g.fillRect(bx + 3, by + 3, bw - 6, bh - 6)
                    g.color = Color(28, 28, 28)
                }
                g.drawLine(bx + bw, by + bh / 2, bx + bw + 20 + rnd.nextInt(40), by + bh / 2 + rnd.nextInt(40) - 20)
            }
        }
    }
}

data class PageOptions(
    val charSize: Int = 21,
    val paper: Int = 232,
    val marginNotes: Boolean = false,
    /** 欄外の注の右端と紙の端のあいだ（ページ座標の画素） */
    val marginNoteRightGap: Int = 40,
    val figure: Boolean = true,
    val heading: Boolean = true,
    val pageNumber: Boolean = true,
    val blankish: Boolean = false,
)

data class Shadow(val cx: Float, val cy: Float, val radius: Float, val strength: Float)

/** 手。指先が (tipX, tipY) にあり、[fromRight] なら右から、そうでなければ下から入る。 */
data class Hand(val tipX: Float, val tipY: Float, val fromRight: Boolean = true, val size: Float = 1f)

/** ある瞬間にカメラに映っているもの。 */
data class SceneState(
    val page: SyntheticPage?,
    /** めくっている途中の、次のページ */
    val nextPage: SyntheticPage? = null,
    /** めくりの進み具合（0 = まだ、1 = めくり終わり） */
    val turnProgress: Float = 0f,
    val dx: Float = 0f,
    val dy: Float = 0f,
    val rotationDeg: Float = 0f,
    val scale: Float = 1f,
    /** ページの反り（横方向のずれの最大、画素） */
    val curl: Float = 0f,
    val gain: Float = 1f,
    val shadow: Shadow? = null,
    val hand: Hand? = null,
    /** ピントのぼけ（半径、画素） */
    val blurRadius: Int = 0,
    val noiseSigma: Float = 1.5f,
    /** カメラ全体の揺れ（振動など） */
    val shakeX: Float = 0f,
    val shakeY: Float = 0f,
    /** スマホ自体の動き（回る速さ、ラジアン毎秒）。null なら、手に持って止めているときの小さな揺れ */
    val gyro: Float? = null,
) {
    /** 「いま映っているページ」の正解。めくりの途中は null。 */
    val truthPageId: Int?
        get() = when {
            nextPage != null && turnProgress > 0.02f && turnProgress < 0.98f -> null
            nextPage != null && turnProgress >= 0.98f -> nextPage.id
            else -> page?.id
        }
}

/** [SceneState] をカメラのコマ（縦長）に描く。 */
class SceneRenderer(val width: Int = 480, val height: Int = 640, private val seed: Long = 1) {

    private val desk: BufferedImage by lazy { renderDesk() }
    private val pageW = (width * 0.875f).roundToInt()
    private val pageH = pageW * SyntheticPage.PAGE_H / SyntheticPage.PAGE_W
    private val pageX = (width - pageW) / 2
    private val pageY = (height - pageH) / 2

    fun render(state: SceneState, frameIndex: Int = 0): LumaImage {
        val img = BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val camera = AffineTransform.getTranslateInstance(state.shakeX.toDouble(), state.shakeY.toDouble())
        g.transform = camera
        g.drawImage(desk, -20, -20, null)

        val page = state.page
        if (page != null) {
            val t = AffineTransform(camera)
            t.translate(width / 2.0 + state.dx, height / 2.0 + state.dy)
            t.rotate(Math.toRadians(state.rotationDeg.toDouble()))
            t.scale(state.scale.toDouble(), state.scale.toDouble())
            t.translate(-width / 2.0, -height / 2.0)
            g.transform = t
            val next = state.nextPage
            if (next == null || state.turnProgress <= 0f) {
                drawPage(g, page, state.curl, 0, pageW)
            } else if (state.turnProgress >= 1f) {
                drawPage(g, next, state.curl, 0, pageW)
            } else {
                // 右から左へ、境目が動いていく。境目の右は次のページ、左はまだ前のページ
                val boundary = (pageW * (1f - state.turnProgress)).roundToInt()
                drawPage(g, page, state.curl, 0, boundary)
                drawPage(g, next, state.curl, boundary, pageW)
                // めくっている紙そのもの（明るい帯と影）
                val band = 46
                g.color = Color(244, 244, 244)
                g.fillRect(pageX + boundary - band / 2, pageY - 6, band, pageH + 12)
                g.color = Color(60, 60, 60, 110)
                g.fillRect(pageX + boundary + band / 2, pageY, 18, pageH)
            }
            g.transform = camera
        }

        state.shadow?.let { s ->
            val paint = RadialGradientPaint(
                s.cx, s.cy, s.radius, floatArrayOf(0f, 1f),
                arrayOf(Color(0, 0, 0, (255 * s.strength).roundToInt().coerceIn(0, 255)), Color(0, 0, 0, 0)),
            )
            g.paint = paint
            g.fillRect(-20, -20, width + 40, height + 40)
        }

        state.hand?.let { drawHand(g, it) }
        g.dispose()

        var out = img
        if (state.blurRadius > 0) out = blur(out, state.blurRadius)
        val data = (out.raster.dataBuffer as DataBufferByte).data.copyOf(width * height)
        val rnd = Random(seed * 31 + frameIndex)
        for (i in data.indices) {
            var v = (data[i].toInt() and 0xFF) * state.gain
            if (state.noiseSigma > 0f) v += (rnd.nextGaussian() * state.noiseSigma).toFloat()
            data[i] = v.roundToInt().coerceIn(0, 255).toByte()
        }
        return LumaImage(width, height, data)
    }

    private fun drawPage(g: java.awt.Graphics2D, page: SyntheticPage, curl: Float, fromX: Int, toX: Int) {
        if (toX <= fromX) return
        val src = page.scaled(pageW)
        val strips = 20
        val stripH = pageH / strips
        val oldClip = g.clip
        for (i in 0 until strips) {
            val sy = i * stripH
            val sh = if (i == strips - 1) pageH - sy else stripH
            val offset = (curl * sin(PI * (i + 0.5) / strips)).toFloat()
            g.clipRect(pageX + fromX, pageY + sy, toX - fromX, sh)
            val t = AffineTransform.getTranslateInstance((pageX + offset).toDouble(), pageY.toDouble())
            g.drawImage(src, t, null)
            g.clip = oldClip
        }
    }

    private fun drawHand(g: java.awt.Graphics2D, hand: Hand) {
        val s = hand.size
        g.color = Color(168, 168, 168)
        val fingerW = (26 * s).roundToInt()
        val fingerL = (120 * s).roundToInt()
        val palm = (150 * s).roundToInt()
        val x = hand.tipX.roundToInt()
        val y = hand.tipY.roundToInt()
        if (hand.fromRight) {
            for (i in 0 until 4) {
                val fy = y + i * (fingerW + 6) - fingerW * 2
                val l = fingerL - kotlin.math.abs(i - 1) * 14
                g.fillRoundRect(x + (fingerL - l), fy, l + 20, fingerW, fingerW, fingerW)
            }
            g.fillRoundRect(x + fingerL, y - palm / 2, width, palm, 40, 40)
            g.color = Color(120, 120, 120)
            g.drawLine(x + fingerL + 10, y - 20, x + fingerL + 60, y + 30)
        } else {
            for (i in 0 until 4) {
                val fx = x + i * (fingerW + 6) - fingerW * 2
                val l = fingerL - kotlin.math.abs(i - 1) * 14
                g.fillRoundRect(fx, y + (fingerL - l), fingerW, l + 20, fingerW, fingerW)
            }
            g.fillRoundRect(x - palm / 2, y + fingerL, palm, height, 40, 40)
        }
    }

    private fun renderDesk(): BufferedImage {
        val w = width + 40
        val h = height + 40
        val img = BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY)
        val g = img.createGraphics()
        g.color = Color(96, 96, 96)
        g.fillRect(0, 0, w, h)
        // 木目のような、ゆるい濃淡
        val rnd = Random(seed)
        var y = 0
        while (y < h) {
            val v = 90 + rnd.nextInt(14)
            g.color = Color(v, v, v)
            val bandH = 3 + rnd.nextInt(9)
            g.fillRect(0, y, w, bandH)
            y += bandH
        }
        g.dispose()
        return img
    }

    private fun blur(src: BufferedImage, radius: Int): BufferedImage {
        val size = radius * 2 + 1
        val k = FloatArray(size) { 1f / size }
        val tmp = ConvolveOp(Kernel(size, 1, k), ConvolveOp.EDGE_NO_OP, null).filter(src, null)
        return ConvolveOp(Kernel(1, size, k), ConvolveOp.EDGE_NO_OP, null).filter(tmp, null)
    }
}

/**
 * 「撮れた写真」を合成する。ページを紙いっぱいに写した 960×1280 の画像に、ぼけ・ぶれ・暗さを足す。
 * @param blurRadius ピントのぼけ（半径）
 * @param motionX 横に流れるぶれ（長さ、画素）
 * @param motionY 縦に流れるぶれ
 */
fun SyntheticPage.photo(blurRadius: Int = 0, motionX: Int = 0, motionY: Int = 0, gain: Float = 1f, noiseSigma: Float = 1.5f): LumaImage {
    var img = full
    fun conv(kw: Int, kh: Int) {
        val k = FloatArray(kw * kh) { 1f / (kw * kh) }
        img = ConvolveOp(Kernel(kw, kh, k), ConvolveOp.EDGE_NO_OP, null).filter(img, null)
    }
    if (blurRadius > 0) {
        conv(blurRadius * 2 + 1, 1)
        conv(1, blurRadius * 2 + 1)
    }
    if (motionX > 1) conv(motionX, 1)
    if (motionY > 1) conv(1, motionY)
    val w = img.width
    val h = img.height
    val data = (img.raster.dataBuffer as DataBufferByte).data.copyOf(w * h)
    val rnd = Random(seed + 99)
    for (i in data.indices) {
        val v = (data[i].toInt() and 0xFF) * gain + (rnd.nextGaussian() * noiseSigma).toFloat()
        data[i] = v.roundToInt().coerceIn(0, 255).toByte()
    }
    return LumaImage(w, h, data)
}
