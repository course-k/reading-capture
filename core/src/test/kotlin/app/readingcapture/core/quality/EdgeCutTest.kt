package app.readingcapture.core.quality

import app.readingcapture.core.synthetic.PageOptions
import app.readingcapture.core.synthetic.SyntheticPage
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「文字が画像の端にかかっているか」のテスト。
 * 合成したページの行の枠を、カメラの枠で切り取って、文字認識が返すはずの枠を作る。
 */
class EdgeCutTest {

    private val w = SyntheticPage.PAGE_W.toFloat()
    private val h = SyntheticPage.PAGE_H.toFloat()

    /**
     * ページ座標の枠 [x0,y0,x1,y1] をカメラの画像として切り取ったときの、行の枠。
     * @param pages ページと、その左上の位置（ページ座標）
     */
    private fun frame(x0: Float, y0: Float, x1: Float, y1: Float, vararg pages: Pair<SyntheticPage, Float>): Triple<List<TextBox>, Int, Int> {
        val out = mutableListOf<TextBox>()
        for ((page, offsetX) in pages) {
            page.full // 行の枠を作らせる
            for (b in page.lineBoxes) {
                val l = b[0] + offsetX
                val r = b[2] + offsetX
                val cl = maxOf(l, x0)
                val cr = minOf(r, x1)
                val ct = maxOf(b[1], y0)
                val cb = minOf(b[3], y1)
                // 半分も見えない文字は、認識されない
                if (cr - cl < 8f || cb - ct < (b[3] - b[1]) * 0.5f) continue
                out += TextBox(cl - x0, ct - y0, cr - x0, cb - y0)
            }
        }
        return Triple(out, (x1 - x0).toInt(), (y1 - y0).toInt())
    }

    /** @param outer ページの外側（合成したページは、注が右にあるので、注のあるページは右が外側） */
    private fun sides(f: Triple<List<TextBox>, Int, Int>, outer: Side? = null) = EdgeCutCheck.evaluate(f.first, f.second, f.third, outerSide = outer).sides

    private val plain = SyntheticPage(1)
    private val notes = SyntheticPage(2, options = PageOptions(marginNotes = true))

    @Test
    fun `余白ごと全部入っていれば、切れていない`() {
        assertEquals(emptySet<Side>(), sides(frame(-30f, -30f, w + 30f, h + 30f, plain to 0f)))
        assertEquals(emptySet<Side>(), sides(frame(0f, 0f, w, h, notes to 0f)))
    }

    @Test
    fun `欄外の注の右端が切れていれば、右が切れている`() {
        // 前回の手作業で起きた切れ方。注の最後の 1〜2 文字が画像の外に出る
        assertEquals(setOf(Side.RIGHT), sides(frame(0f, 0f, w - 60f, h, notes to 0f), outer = Side.RIGHT))
        assertEquals(setOf(Side.RIGHT), sides(frame(-40f, -20f, w - 90f, h + 20f, notes to 0f), outer = Side.RIGHT))
        // 外側がどちらか分からないときは、知らせない（迷うときは知らせない側に倒す）
        assertEquals(emptySet<Side>(), sides(frame(0f, 0f, w - 60f, h, notes to 0f)))
        // 綴じ目の側に見える小さな字は、となりのページのもの
        assertEquals(emptySet<Side>(), sides(frame(0f, 0f, w - 60f, h, notes to 0f), outer = Side.LEFT))
    }

    @Test
    fun `本文の左が切れていれば、左が切れている`() {
        assertEquals(setOf(Side.LEFT), sides(frame(120f, 0f, w, h, plain to 0f)))
    }

    @Test
    fun `上や下が切れていれば、そう知らせる`() {
        assertEquals(setOf(Side.TOP), sides(frame(0f, 150f, w, h, plain to 0f)))
        assertEquals(setOf(Side.BOTTOM), sides(frame(0f, 0f, w, h - 250f, plain to 0f)))
    }

    @Test
    fun `となりのページの文字が端に写り込んでいても、切れているとは言わない`() {
        // 右どなりのページ（のどをはさんで 190 画素先から本文が始まる）が、右の端に少し写る
        val facing = SyntheticPage(5)
        assertEquals(emptySet<Side>(), sides(frame(-20f, 0f, w + 180f, h, plain to 0f, facing to w)))
        // 左どなりのページが左の端に写る
        assertEquals(emptySet<Side>(), sides(frame(-200f, 0f, w + 20f, h, plain to 0f, facing to -w)))
    }

    @Test
    fun `となりのページが写り込み、しかも欄外の注が切れていれば、切れていると言う`() {
        val facing = SyntheticPage(5)
        // 左にとなりのページ、右は注が切れている
        assertEquals(setOf(Side.RIGHT), sides(frame(-200f, 0f, w - 70f, h, notes to 0f, facing to -w), outer = Side.RIGHT))
    }

    @Test
    fun `文字が無ければ、切れていない`() {
        assertEquals(emptySet<Side>(), EdgeCutCheck.evaluate(emptyList(), 1000, 1300).sides)
    }

    @Test
    fun `となりのページの行の端が、本文のすぐそばに写り込んでいても、切れているとは言わない`() {
        // 実機（Pixel 6a、手持ち）で「左の端に文字がかかっています」と誤って知らせたときの、行の枠の並び。
        // 左端の 3 つ（幅 17〜21）が、となりのページの行の端。綴じ目の近くは紙が曲がって、本文（左端 59）との間が詰まって見える
        assertEquals(emptySet<Side>(), EdgeCutCheck.evaluate(realFacingPageIntrusion, 1152, 1536).sides)
    }

    @Test
    fun `縦書きの本でも、切れている側を知らせる`() {
        // 横書きの合成ページを 90 度回して、縦書きの並びにする
        val (boxes, w, h) = frame(120f, 0f, this.w, this.h, plain to 0f)
        val rotated = boxes.map { TextBox(it.top, it.left, it.bottom, it.right) }
        assertEquals(setOf(Side.TOP), EdgeCutCheck.evaluate(rotated, h, w).sides)
    }

    private val realFacingPageIntrusion = listOf(
        TextBox(5f, 518f, 26f, 531f), TextBox(5f, 555f, 22f, 568f), TextBox(7f, 592f, 28f, 606f),
        TextBox(59f, 82f, 337f, 116f), TextBox(78f, 115f, 735f, 151f), TextBox(61f, 152f, 737f, 184f),
        TextBox(63f, 186f, 736f, 216f), TextBox(66f, 222f, 613f, 247f), TextBox(84f, 252f, 736f, 283f),
        TextBox(59f, 286f, 742f, 317f), TextBox(69f, 323f, 739f, 347f), TextBox(68f, 351f, 360f, 378f),
        TextBox(80f, 387f, 744f, 413f), TextBox(69f, 420f, 737f, 443f), TextBox(76f, 455f, 733f, 476f),
        TextBox(68f, 485f, 741f, 510f), TextBox(76f, 520f, 734f, 540f), TextBox(78f, 552f, 736f, 571f),
        TextBox(80f, 586f, 137f, 603f), TextBox(90f, 613f, 742f, 637f), TextBox(74f, 645f, 659f, 668f),
        TextBox(84f, 709f, 354f, 731f), TextBox(93f, 736f, 740f, 762f), TextBox(88f, 766f, 724f, 792f),
        TextBox(89f, 798f, 572f, 823f), TextBox(106f, 824f, 729f, 855f), TextBox(90f, 854f, 731f, 886f),
        TextBox(88f, 884f, 738f, 917f), TextBox(94f, 921f, 481f, 946f), TextBox(112f, 948f, 555f, 979f),
        TextBox(94f, 974f, 664f, 1011f), TextBox(725f, 70f, 905f, 85f), TextBox(89f, 1002f, 732f, 1039f),
        TextBox(97f, 1042f, 292f, 1069f), TextBox(91f, 1069f, 557f, 1098f), TextBox(100f, 1100f, 515f, 1126f),
        TextBox(111f, 1120f, 721f, 1159f), TextBox(101f, 1162f, 349f, 1186f), TextBox(103f, 1187f, 550f, 1215f),
        TextBox(104f, 1213f, 623f, 1244f), TextBox(127f, 1235f, 735f, 1277f), TextBox(102f, 1275f, 546f, 1303f),
        TextBox(858f, 1329f, 882f, 1342f),
    )

    @Test
    fun `綴じ目の側に、となりのページの小さな字が何行も写り込んでいても、切れているとは言わない`() {
        // 実機（Pixel 6a、手持ち）で「左の端に文字がかかっています」と誤って知らせた 2 枚の、行の枠の並び。
        // どちらも右ページ（ページ番号は右下、左が綴じ目）。左端に、幅 80〜100・本文より小さな字の行が 9 行と 45 行。
        // 持ち主が写真を見て、このページの文字は切れていないと確かめた
        assertEquals(emptySet<Side>(), EdgeCutCheck.evaluate(realGutterSideSmallText1, 1152, 1536, outerSide = Side.RIGHT).sides)
        assertEquals(emptySet<Side>(), EdgeCutCheck.evaluate(realGutterSideSmallText2, 1152, 1536, outerSide = Side.RIGHT).sides)
        // 外側が分からなくても、知らせない
        assertEquals(emptySet<Side>(), EdgeCutCheck.evaluate(realGutterSideSmallText2, 1152, 1536).sides)
    }

    private val realGutterSideSmallText1 = listOf(
        TextBox(-1f, 489f, 99f, 523f), TextBox(-1f, 512f, 80f, 537f), TextBox(-3f, 530f, 98f, 560f),
        TextBox(0f, 551f, 100f, 579f), TextBox(-3f, 593f, 98f, 616f), TextBox(4f, 616f, 32f, 631f),
        TextBox(0f, 831f, 55f, 858f), TextBox(0f, 883f, 70f, 907f), TextBox(0f, 907f, 70f, 931f),
        TextBox(132f, 85f, 414f, 115f), TextBox(144f, 118f, 805f, 150f), TextBox(130f, 150f, 810f, 182f),
        TextBox(134f, 184f, 806f, 214f), TextBox(135f, 219f, 687f, 244f), TextBox(154f, 249f, 817f, 278f),
        TextBox(138f, 284f, 812f, 308f), TextBox(139f, 317f, 813f, 341f), TextBox(138f, 344f, 434f, 373f),
        TextBox(161f, 382f, 815f, 404f), TextBox(141f, 415f, 813f, 437f), TextBox(144f, 448f, 811f, 471f),
        TextBox(142f, 480f, 815f, 502f), TextBox(143f, 513f, 815f, 535f), TextBox(144f, 545f, 815f, 567f),
        TextBox(146f, 582f, 207f, 600f), TextBox(166f, 607f, 816f, 631f), TextBox(138f, 637f, 737f, 665f),
        TextBox(147f, 702f, 427f, 730f), TextBox(159f, 727f, 818f, 761f), TextBox(151f, 760f, 815f, 792f),
        TextBox(148f, 794f, 656f, 825f), TextBox(162f, 820f, 822f, 859f), TextBox(152f, 851f, 822f, 891f),
        TextBox(146f, 881f, 823f, 925f), TextBox(155f, 923f, 561f, 954f), TextBox(173f, 950f, 639f, 987f),
        TextBox(794f, 73f, 971f, 87f), TextBox(154f, 977f, 742f, 1020f), TextBox(155f, 1005f, 819f, 1053f),
        TextBox(156f, 1058f, 364f, 1080f), TextBox(157f, 1076f, 634f, 1117f), TextBox(152f, 1111f, 598f, 1149f),
        TextBox(168f, 1131f, 817f, 1182f), TextBox(161f, 1188f, 425f, 1209f), TextBox(157f, 1203f, 635f, 1246f),
        TextBox(158f, 1230f, 710f, 1277f), TextBox(172f, 1254f, 833f, 1310f), TextBox(164f, 1299f, 625f, 1340f),
        TextBox(959f, 1353f, 985f, 1368f),
    )

    private val realGutterSideSmallText2 = listOf(
        TextBox(-2f, 280f, 79f, 316f), TextBox(-2f, 301f, 81f, 336f), TextBox(-4f, 322f, 78f, 359f),
        TextBox(2f, 345f, 80f, 377f), TextBox(6f, 370f, 80f, 396f), TextBox(-1f, 387f, 82f, 421f),
        TextBox(-2f, 409f, 81f, 439f), TextBox(-3f, 430f, 84f, 461f), TextBox(0f, 454f, 82f, 480f),
        TextBox(2f, 474f, 82f, 502f), TextBox(3f, 499f, 80f, 522f), TextBox(1f, 518f, 81f, 543f),
        TextBox(4f, 544f, 63f, 561f), TextBox(0f, 585f, 82f, 604f), TextBox(-3f, 604f, 83f, 625f),
        TextBox(-2f, 781f, 84f, 800f), TextBox(-2f, 802f, 83f, 820f), TextBox(0f, 821f, 83f, 844f),
        TextBox(1f, 844f, 77f, 864f), TextBox(1f, 864f, 86f, 886f), TextBox(-1f, 883f, 86f, 908f),
        TextBox(1f, 904f, 86f, 930f), TextBox(4f, 934f, 55f, 950f), TextBox(0f, 964f, 65f, 989f),
        TextBox(-1f, 981f, 88f, 1015f), TextBox(-2f, 1002f, 89f, 1034f), TextBox(0f, 1022f, 91f, 1055f),
        TextBox(2f, 1043f, 91f, 1076f), TextBox(1f, 1065f, 91f, 1097f), TextBox(2f, 1083f, 92f, 1120f),
        TextBox(4f, 1107f, 83f, 1140f), TextBox(0f, 1122f, 92f, 1164f), TextBox(3f, 1145f, 91f, 1183f),
        TextBox(-1f, 1178f, 52f, 1203f), TextBox(6f, 1227f, 94f, 1269f), TextBox(1f, 1246f, 95f, 1291f),
        TextBox(3f, 1268f, 94f, 1311f), TextBox(0f, 1319f, 54f, 1347f), TextBox(-1f, 1321f, 97f, 1374f),
        TextBox(5f, 1349f, 89f, 1389f), TextBox(-2f, 1361f, 97f, 1415f), TextBox(0f, 1383f, 98f, 1435f),
        TextBox(4f, 1402f, 100f, 1455f), TextBox(0f, 1431f, 87f, 1476f), TextBox(0f, 1444f, 100f, 1498f),
        TextBox(218f, 161f, 795f, 244f), TextBox(169f, 302f, 1011f, 350f), TextBox(172f, 381f, 900f, 412f),
        TextBox(161f, 417f, 776f, 447f), TextBox(183f, 452f, 892f, 482f), TextBox(164f, 491f, 891f, 514f),
        TextBox(165f, 526f, 890f, 549f), TextBox(164f, 561f, 891f, 583f), TextBox(156f, 594f, 898f, 619f),
        TextBox(165f, 631f, 892f, 651f), TextBox(168f, 664f, 295f, 685f), TextBox(154f, 731f, 738f, 760f),
        TextBox(158f, 765f, 898f, 792f), TextBox(191f, 801f, 890f, 824f), TextBox(181f, 836f, 455f, 861f),
        TextBox(158f, 864f, 896f, 896f), TextBox(181f, 898f, 885f, 932f), TextBox(191f, 933f, 711f, 964f),
        TextBox(169f, 1006f, 541f, 1034f), TextBox(220f, 1059f, 566f, 1090f), TextBox(220f, 1099f, 564f, 1122f),
        TextBox(231f, 1120f, 491f, 1137f), TextBox(198f, 1164f, 245f, 1180f), TextBox(240f, 1192f, 286f, 1206f),
        TextBox(207f, 1243f, 245f, 1258f), TextBox(281f, 1252f, 319f, 1265f), TextBox(227f, 1313f, 298f, 1325f),
        TextBox(378f, 1160f, 419f, 1169f), TextBox(395f, 1182f, 431f, 1193f), TextBox(396f, 1197f, 433f, 1208f),
        TextBox(341f, 1304f, 451f, 1322f), TextBox(234f, 1373f, 355f, 1382f), TextBox(333f, 1321f, 449f, 1338f),
        TextBox(347f, 1359f, 417f, 1369f), TextBox(539f, 1235f, 589f, 1244f), TextBox(517f, 1261f, 566f, 1272f),
        TextBox(516f, 1288f, 569f, 1299f), TextBox(509f, 1303f, 575f, 1315f), TextBox(507f, 1318f, 578f, 1329f),
        TextBox(671f, 1053f, 1011f, 1076f), TextBox(675f, 1084f, 1013f, 1104f), TextBox(675f, 1100f, 850f, 1117f),
        TextBox(677f, 1112f, 948f, 1132f), TextBox(648f, 1150f, 694f, 1168f), TextBox(660f, 1204f, 727f, 1218f),
        TextBox(655f, 1242f, 776f, 1261f), TextBox(515f, 1347f, 729f, 1371f), TextBox(786f, 1159f, 817f, 1171f),
        TextBox(828f, 1231f, 953f, 1246f), TextBox(782f, 1318f, 842f, 1330f), TextBox(979f, 1149f, 1026f, 1157f),
        TextBox(909f, 1255f, 1026f, 1275f), TextBox(940f, 1319f, 991f, 1329f), TextBox(826f, 1355f, 1035f, 1370f),
        TextBox(1041f, 1447f, 1066f, 1464f),
    )
}
