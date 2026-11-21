package app.readingcapture.core.detect

import app.readingcapture.core.synthetic.Hand
import app.readingcapture.core.synthetic.PageOptions
import app.readingcapture.core.synthetic.SceneState
import app.readingcapture.core.synthetic.Shadow
import app.readingcapture.core.synthetic.SimCapture
import app.readingcapture.core.synthetic.Simulation
import app.readingcapture.core.synthetic.SyntheticPage
import app.readingcapture.core.synthetic.VideoScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 合成した映像を流して、「いつ撮るか」を確かめる。
 * 見るのは、撮り逃しが無いこと、二度撮りが無いこと、めくりの途中や手が入っているあいだに撮らないこと。
 */
class CaptureEngineTest {

    private val pages = (1..8).map { SyntheticPage(it) }

    private fun run(script: VideoScript, configure: Simulation.() -> Unit = {}): Simulation {
        val sim = Simulation(CaptureEngine())
        sim.configure()
        sim.run(script)
        return sim
    }

    private fun List<SimCapture>.pageIds() = map { it.pageId }

    private fun describe(sim: Simulation) = sim.captures.joinToString("\n") { it.toString() }

    @Test
    fun `ふつうにめくると、1 ページにつき 1 回ずつ撮る`() {
        val script = VideoScript()
        script.hold(SceneState(pages[0]), 2500)
        for (i in 1 until 6) {
            script.turn(pages[i - 1], pages[i])
            script.hold(SceneState(pages[i]), 2200)
        }
        val sim = run(script)
        assertEquals(describe(sim), listOf<Int?>(1, 2, 3, 4, 5, 6), sim.captures.pageIds())
        assertTrue("手が入っているあいだに撮った:\n${describe(sim)}", sim.captures.none { it.handPresent })
        assertTrue("動いているあいだに撮った:\n${describe(sim)}", sim.captures.none { it.moved })
        assertTrue(sim.captures.all { it.reason == CaptureReason.AUTO })
    }

    @Test
    fun `止まってから 1 秒半以内に撮る`() {
        val script = VideoScript()
        script.hold(SceneState(pages[0]), 2500)
        script.turn(pages[0], pages[1])
        val settledAt = script.durationMs
        script.hold(SceneState(pages[1]), 3000)
        val sim = run(script)
        val second = sim.captures.single { it.pageId == 2 }
        val waited = second.timeMs - settledAt
        assertTrue("止まってから ${waited}ms", waited in 0..1500)
    }

    @Test
    fun `明るさが変わっても撮り直さない`() {
        val script = VideoScript()
        script.hold(SceneState(pages[0]), 2500)
        script.animate(800) { t -> SceneState(pages[0], gain = 1f - 0.3f * t) }
        script.hold(SceneState(pages[0], gain = 0.7f), 1500)
        script.animate(300) { t -> SceneState(pages[0], gain = 0.7f + 0.35f * t) }
        script.hold(SceneState(pages[0], gain = 1.05f), 2000)
        val sim = run(script)
        assertEquals(describe(sim), listOf<Int?>(1), sim.captures.pageIds())
    }

    @Test
    fun `影が通っても撮り直さない`() {
        val script = VideoScript()
        script.hold(SceneState(pages[0]), 2500)
        script.animate(1500) { t -> SceneState(pages[0], shadow = Shadow(cx = -200f + 900f * t, cy = 300f, radius = 320f, strength = 0.5f)) }
        script.hold(SceneState(pages[0]), 1500)
        // 影が居座る（頭の影が落ちたまま読む）
        script.animate(600) { t -> SceneState(pages[0], shadow = Shadow(cx = 240f, cy = 200f, radius = 300f, strength = 0.4f * t)) }
        script.hold(SceneState(pages[0], shadow = Shadow(240f, 200f, 300f, 0.4f)), 2500)
        val sim = run(script)
        assertEquals(describe(sim), listOf<Int?>(1), sim.captures.pageIds())
    }

    @Test
    fun `手が入って止まり、出ていっても撮り直さない`() {
        val script = VideoScript()
        script.hold(SceneState(pages[0]), 2500)
        script.animate(400) { t -> SceneState(pages[0], hand = Hand(tipX = 520f - 260f * t, tipY = 380f)) }
        script.hold(SceneState(pages[0], hand = Hand(260f, 380f)), 2500) // 指でなぞって止まる
        script.animate(400) { t -> SceneState(pages[0], hand = Hand(tipX = 260f + 300f * t, tipY = 380f)) }
        script.hold(SceneState(pages[0]), 2500)
        val sim = run(script)
        assertEquals(describe(sim), listOf<Int?>(1), sim.captures.pageIds())
    }

    @Test
    fun `めくりかけて戻しても撮り直さない`() {
        val script = VideoScript()
        script.hold(SceneState(pages[0]), 2500)
        script.animate(300) { t -> SceneState(pages[0], hand = Hand(tipX = 520f - 140f * t, tipY = 330f)) }
        script.animate(500) { t -> SceneState(pages[0], nextPage = pages[1], turnProgress = 0.45f * t, hand = Hand(450f - 190f * t, 330f)) }
        script.hold(SceneState(pages[0], nextPage = pages[1], turnProgress = 0.45f, hand = Hand(260f, 330f)), 300)
        script.animate(500) { t -> SceneState(pages[0], nextPage = pages[1], turnProgress = 0.45f * (1 - t), hand = Hand(260f + 190f * t, 330f)) }
        script.animate(300) { t -> SceneState(pages[0], curl = 4f * (1 - t), hand = Hand(450f + 100f * t, 330f)) }
        script.hold(SceneState(pages[0]), 2500)
        val sim = run(script)
        assertEquals(describe(sim), listOf<Int?>(1), sim.captures.pageIds())
    }

    @Test
    fun `本が少しずれても撮り直さない`() {
        val script = VideoScript()
        script.hold(SceneState(pages[0]), 2500)
        script.animate(300) { t -> SceneState(pages[0], dx = 14f * t, dy = -9f * t, rotationDeg = 0.8f * t) }
        script.hold(SceneState(pages[0], dx = 14f, dy = -9f, rotationDeg = 0.8f), 2500)
        val sim = run(script)
        assertEquals(describe(sim), listOf<Int?>(1), sim.captures.pageIds())
    }

    @Test
    fun `机だけが映っているあいだは撮らず、本を置いたら撮る`() {
        val script = VideoScript()
        script.hold(SceneState(null), 3000)
        script.animate(700) { t -> SceneState(pages[0], dx = 300f * (1 - t), dy = 200f * (1 - t), rotationDeg = 10f * (1 - t)) }
        script.hold(SceneState(pages[0]), 2500)
        val sim = run(script)
        assertEquals(describe(sim), listOf<Int?>(1), sim.captures.pageIds())
        assertTrue(sim.captures.single().timeMs > 3700)
    }

    @Test
    fun `手でページを押さえたままでも撮り、手を離しても撮り直さない`() {
        // 新しいページを、指で押さえたまま読む（手はわずかに震える）
        val script = VideoScript()
        script.hold(SceneState(pages[0]), 2500)
        script.turn(pages[0], pages[1], handOutMs = 1, relaxMs = 1)
        var k = 0
        script.animate(3500) { _ ->
            k++
            val jitter = if (k % 4 < 2) 0.6f else -0.6f
            SceneState(pages[1], hand = Hand(tipX = 395f + jitter, tipY = 560f + jitter, fromRight = false))
        }
        script.animate(400) { t -> SceneState(pages[1], hand = Hand(395f, 560f + 200f * t, fromRight = false)) }
        script.hold(SceneState(pages[1]), 2500)
        val sim = run(script)
        assertEquals(describe(sim), listOf<Int?>(1, 2), sim.captures.pageIds())
    }

    @Test
    fun `反りがゆっくり戻っていても、そのページは 1 回だけ撮る`() {
        val script = VideoScript()
        script.hold(SceneState(pages[0]), 2500)
        script.turn(pages[0], pages[1], relaxMs = 1)
        script.animate(1500) { t -> SceneState(pages[1], curl = 10f * (1 - t)) }
        script.hold(SceneState(pages[1]), 2500)
        val sim = run(script)
        assertEquals(describe(sim), listOf<Int?>(1, 2), sim.captures.pageIds())
    }

    @Test
    fun `ピントが動いているあいだは待つ`() {
        val script = VideoScript()
        script.hold(SceneState(pages[0]), 2500)
        script.turn(pages[0], pages[1])
        // めくったあと、ピントが合い直すまでぼける
        script.hold(SceneState(pages[1], blurRadius = 5), 200)
        script.hold(SceneState(pages[1], blurRadius = 4), 130)
        script.hold(SceneState(pages[1], blurRadius = 3), 130)
        script.hold(SceneState(pages[1], blurRadius = 2), 130)
        script.hold(SceneState(pages[1], blurRadius = 1), 130)
        script.hold(SceneState(pages[1]), 2500)
        val sim = run(script)
        assertEquals(describe(sim), listOf<Int?>(1, 2), sim.captures.pageIds())
        // 手に持つ前提では、合うのを長くは待たない。はっきりぼけているうち（半径 2 以上）には撮らないこと
        assertTrue("ぼけているうちに撮った: ${sim.captures[1]}", sim.captures[1].blurRadius <= 1)
    }

    @Test
    fun `ぱらぱらめくっているあいだは撮らず、止めたページは撮る`() {
        // めくって探す動き。止めずに次へめくるページは撮らない
        val script = VideoScript()
        script.hold(SceneState(pages[0]), 2500)
        script.turn(pages[0], pages[1], handOutMs = 100, relaxMs = 30)
        script.hold(SceneState(pages[1], gyro = 0.2f), 150)
        script.turn(pages[1], pages[2], handInMs = 100, handOutMs = 100, relaxMs = 30)
        script.hold(SceneState(pages[2], gyro = 0.2f), 150)
        script.turn(pages[2], pages[3], handInMs = 100)
        script.hold(SceneState(pages[3]), 2500)
        val sim = run(script)
        assertEquals(describe(sim), listOf<Int?>(1, 4), sim.captures.pageIds())
    }

    @Test
    fun `撮れたときの振動で揺れても二度撮りしない`() {
        val script = VideoScript()
        script.hold(SceneState(pages[0]), 4000)
        val sim = Simulation(CaptureEngine(), vibrationMs = 200, vibrationShakePx = 4f)
        sim.run(script)
        assertEquals(describe(sim), listOf<Int?>(1), sim.captures.pageIds())
    }

    @Test
    fun `シャッターで撮ったページは自動で撮り直さない`() {
        val script = VideoScript()
        script.hold(SceneState(null), 1500)
        script.animate(500) { t -> SceneState(pages[0], dx = 200f * (1 - t)) }
        script.hold(SceneState(pages[0]), 200)
        script.act { it.manual() }
        script.hold(SceneState(pages[0]), 3000)
        val sim = run(script)
        assertEquals(describe(sim), listOf<Int?>(1), sim.captures.pageIds())
        assertEquals(CaptureReason.MANUAL, sim.captures.single().reason)
    }

    @Test
    fun `同じページをシャッターで何度でも撮れる`() {
        val script = VideoScript()
        script.hold(SceneState(pages[0]), 2500)
        script.act { it.manual() }
        script.hold(SceneState(pages[0]), 1500)
        script.act { it.manual() }
        script.hold(SceneState(pages[0]), 1500)
        val sim = run(script)
        assertEquals(describe(sim), listOf<Int?>(1, 1, 1), sim.captures.pageIds())
    }

    @Test
    fun `直前の 1 枚を消しても、同じページを勝手に撮り直さない`() {
        val script = VideoScript()
        script.hold(SceneState(pages[0]), 2500)
        script.turn(pages[0], pages[1])
        script.hold(SceneState(pages[1]), 2500)
        script.act { it.engine.onLastDeleted() }
        // 消すボタンを押した揺れ
        script.hold(SceneState(pages[1], shakeX = 3f, shakeY = 2f), 100)
        script.hold(SceneState(pages[1]), 3000)
        val sim = run(script)
        assertEquals(describe(sim), listOf<Int?>(1, 2), sim.captures.pageIds())
    }

    @Test
    fun `二度撮りを消したあとも、そのページは撮り直さず、次のページは撮る`() {
        val script = VideoScript()
        script.hold(SceneState(pages[0]), 2500)
        script.act { it.manual() } // わざと二度撮り
        script.hold(SceneState(pages[0]), 1500)
        script.act { it.engine.onLastDeleted() }
        script.hold(SceneState(pages[0]), 2000)
        script.turn(pages[0], pages[1])
        script.hold(SceneState(pages[1]), 2500)
        val sim = run(script)
        assertEquals(describe(sim), listOf<Int?>(1, 1, 2), sim.captures.pageIds())
    }

    @Test
    fun `撮り直すと、構え直して止まったところで同じページをもう一度撮る`() {
        val script = VideoScript()
        script.hold(SceneState(pages[0]), 2500)
        script.act { it.engine.armRetake(it.nowMs) }
        script.hold(SceneState(pages[0], shakeX = 2f, gyro = 0.4f), 100) // ボタンを押した揺れ
        // スマホを少し引いて、右へ寄せて構え直す
        script.animate(900) { t -> SceneState(pages[0], dx = 18f * t, scale = 1f - 0.06f * t, gyro = 0.5f) }
        val adjustedAt = script.durationMs
        script.hold(SceneState(pages[0], dx = 18f, scale = 0.94f), 3000)
        val sim = run(script)
        assertEquals(describe(sim), listOf<Int?>(1, 1), sim.captures.pageIds())
        assertEquals(CaptureReason.RETAKE, sim.captures[1].reason)
        assertTrue("構え直す前に撮った: ${sim.captures[1]}", sim.captures[1].timeMs >= adjustedAt)
    }

    @Test
    fun `撮り直すを押して何も動かさなければ、少し待ってから撮る`() {
        val script = VideoScript()
        script.hold(SceneState(pages[0]), 2500)
        script.act { it.engine.armRetake(it.nowMs) }
        val armedAt = script.durationMs
        script.hold(SceneState(pages[0]), 5000)
        val sim = run(script)
        assertEquals(describe(sim), listOf<Int?>(1, 1), sim.captures.pageIds())
        assertTrue(describe(sim), sim.captures[1].timeMs - armedAt in 600..2000)
    }

    @Test
    fun `撮るのに失敗したら、同じページをもう一度撮る`() {
        val script = VideoScript()
        script.hold(SceneState(pages[0]), 5000)
        val sim = run(script) { failFirstCaptures = 1 }
        assertEquals(describe(sim), listOf<Int?>(1), sim.captures.pageIds())
    }

    @Test
    fun `撮っているあいだに動いたら、そう知らせる`() {
        val script = VideoScript()
        script.hold(SceneState(pages[0]), 1700)
        // 撮る合図が出るころにめくり始める
        script.turn(pages[0], pages[1], handInMs = 150)
        script.hold(SceneState(pages[1]), 2500)
        val sim = run(script)
        val first = sim.captures.first()
        if (first.timeMs > 1400) assertTrue("動いたのに知らせていない: $first", first.moved)
        assertEquals(describe(sim), 2, sim.captures.size)
    }

    @Test
    fun `欄外に注のあるページ、文字だけのページでも 1 回ずつ撮る`() {
        val notes = (0 until 4).map { SyntheticPage(500 + it, options = PageOptions(marginNotes = true)) }
        val plain = (0 until 4).map { SyntheticPage(600 + it, options = PageOptions(figure = false, heading = false)) }
        for (set in listOf(notes, plain)) {
            val script = VideoScript()
            script.hold(SceneState(set[0]), 2500)
            for (i in 1 until set.size) {
                script.turn(set[i - 1], set[i])
                script.hold(SceneState(set[i]), 2000)
            }
            val sim = run(script)
            assertEquals(describe(sim), set.map { it.id as Int? }, sim.captures.pageIds())
        }
    }

    @Test
    fun `暗い部屋でノイズが多くても 1 回ずつ撮る`() {
        val script = VideoScript()
        val dim = SceneState(pages[0], gain = 0.45f, noiseSigma = 5f)
        script.hold(dim, 3000)
        script.turn(pages[0], pages[1], base = SceneState(null, gain = 0.45f, noiseSigma = 5f))
        script.hold(dim.copy(page = pages[1]), 3000)
        val sim = run(script)
        assertEquals(describe(sim), listOf<Int?>(1, 2), sim.captures.pageIds())
    }

    @Test
    fun `長く流しても二度撮りも撮り逃しも無い`() {
        // 20 ページを、間合いを変えながらめくる
        val many = (1..20).map { SyntheticPage(700 + it, options = PageOptions(marginNotes = it % 3 == 0, figure = it % 2 == 0)) }
        val script = VideoScript()
        script.hold(SceneState(many[0]), 2000)
        for (i in 1 until many.size) {
            script.turn(many[i - 1], many[i], turnMs = 600 + (i % 4) * 200, handOutMs = 250 + (i % 3) * 100)
            script.hold(SceneState(many[i], dx = (i % 5) - 2f, dy = (i % 3) - 1f), 1500 + (i % 3) * 400)
        }
        val sim = run(script)
        assertEquals(describe(sim), many.map { it.id as Int? }, sim.captures.pageIds())
        assertTrue(sim.captures.none { it.handPresent })
    }

    @Test
    fun `暗くてページと分からなかったあと、明るくなったら動きが無くても撮る`() {
        // 露出がゆっくり追いつく場合。全体の明るさの変化は「動き」に数えないので、見直しが要る
        val script = VideoScript()
        script.hold(SceneState(pages[0], gain = 0.05f, noiseSigma = 0.5f), 3000)
        script.animate(2000) { t -> SceneState(pages[0], gain = 0.05f + 0.95f * t) }
        script.hold(SceneState(pages[0]), 4000)
        val sim = run(script)
        assertEquals(describe(sim), listOf<Int?>(1), sim.captures.pageIds())
    }

    @Test
    fun `撮り終わりの知らせが来なくても固まらず、次のページを撮る`() {
        val script = VideoScript()
        script.hold(SceneState(pages[0]), 12000)
        script.turn(pages[0], pages[1])
        script.hold(SceneState(pages[1]), 3000)
        val sim = run(script) { dropFirstFinishes = 1 }
        assertTrue("固まった: ${sim.requests}", sim.requests.size >= 2)
        assertEquals("最初の合図はページ 1", 1, sim.requests.first().second)
        assertTrue("知らせが来なかったあと、待ってからもう一度合図する: ${sim.requests}", sim.requests[1].first - sim.requests[0].first >= 8000)
        assertTrue("次のページも撮る: ${sim.requests}", sim.requests.any { it.second == 2 })
    }

    @Test
    fun `前回の最後のページを覚えていれば、開き直しても同じページを撮り直さない`() {
        val first = Simulation(CaptureEngine())
        first.run(VideoScript().hold(SceneState(pages[0]), 3000))
        assertEquals(1, first.captures.size)
        val saved = first.engine.rememberedPages()

        val engine = CaptureEngine()
        engine.restorePages(saved)
        val script = VideoScript()
        script.hold(SceneState(pages[0], dx = 2f), 3000)
        script.turn(pages[0], pages[1])
        script.hold(SceneState(pages[1]), 2500)
        val second = Simulation(engine)
        second.run(script)
        assertEquals(describe(second), listOf<Int?>(2), second.captures.pageIds())
    }

    @Test
    fun `撮っている最中にさっとめくり終えても、新しいページを撮る`() {
        // 撮る合図の直後にめくり始め、撮れたときの振動が終わる前にめくり終える。
        // 振動のあいだは動きを見ていないので、見直さないと新しいページを撮らないまま止まる
        val script = VideoScript()
        script.hold(SceneState(pages[0]), 1500)
        script.turn(pages[0], pages[1], turnMs = 300, handInMs = 100, handOutMs = 100, relaxMs = 30)
        script.hold(SceneState(pages[1]), 4000)
        val sim = Simulation(CaptureEngine(), captureLatencyMs = 600, vibrationMs = 400)
        sim.run(script)
        assertEquals(describe(sim), 2, sim.captures.size)
        assertEquals(describe(sim), 2, sim.captures.last().pageId)
    }

    @Test
    fun `前のページに戻って読み直しても、撮り直さない`() {
        val script = VideoScript()
        script.hold(SceneState(pages[0]), 2500)
        script.turn(pages[0], pages[1])
        script.hold(SceneState(pages[1]), 2500)
        script.turn(pages[1], pages[0]) // 戻る
        script.hold(SceneState(pages[0], dx = 3f), 2500)
        script.turn(pages[0], pages[1]) // 進み直す
        script.hold(SceneState(pages[1], dy = -2f), 2500)
        script.turn(pages[1], pages[2])
        script.hold(SceneState(pages[2]), 2500)
        val sim = run(script)
        assertEquals(describe(sim), listOf<Int?>(1, 2, 3), sim.captures.pageIds())
    }

    @Test
    fun `撮り直すの 1 枚が撮れなかったら、もう一度撮る`() {
        val script = VideoScript()
        script.hold(SceneState(pages[0]), 2500)
        script.act {
            it.failFirstCaptures = 1
            it.engine.armRetake(it.nowMs)
        }
        script.hold(SceneState(pages[0]), 8000)
        val sim = run(script)
        assertEquals(describe(sim), listOf<Int?>(1, 1), sim.captures.pageIds())
        assertEquals(CaptureReason.RETAKE, sim.captures[1].reason)
    }

    @Test
    fun `番号が読めていれば、手に持って構え直しても同じページは撮り直さない`() {
        // 位置・大きさ・傾きが変わると、画像を重ねて比べる方法では別のページに見える。ページ番号で見分けること
        val script = VideoScript()
        script.hold(SceneState(pages[0]), 2500)
        script.animate(600) { t -> SceneState(pages[0], dx = 40f * t, dy = -30f * t, scale = 1f + 0.18f * t, rotationDeg = 7f * t, gyro = 0.9f) }
        script.hold(SceneState(pages[0], dx = 40f, dy = -30f, scale = 1.18f, rotationDeg = 7f), 3000)
        script.animate(600) { t -> SceneState(pages[0], dx = 40f - 70f * t, dy = -30f + 50f * t, scale = 1.18f - 0.3f * t, rotationDeg = 7f - 12f * t, gyro = 0.9f) }
        script.hold(SceneState(pages[0], dx = -30f, dy = 20f, scale = 0.88f, rotationDeg = -5f), 3000)
        val sim = Simulation(CaptureEngine(), numberMiss = 0.0)
        sim.run(script)
        assertEquals(describe(sim), listOf<Int?>(1), sim.captures.pageIds())
    }

    @Test
    fun `スマホを動かしているあいだは撮らず、止めたら撮る`() {
        val script = VideoScript()
        // ページは映っているが、スマホが動き続けている
        script.animate(3000) { t -> SceneState(pages[0], dx = 25f * kotlin.math.sin(t * 20f), dy = 15f * kotlin.math.cos(t * 17f), gyro = 0.35f) }
        val movingUntil = script.durationMs
        script.hold(SceneState(pages[0]), 2500)
        val sim = run(script)
        assertEquals(describe(sim), listOf<Int?>(1), sim.captures.pageIds())
        assertTrue("動かしているあいだに撮った: ${sim.captures}", sim.captures.single().timeMs >= movingUntil)
    }

    @Test
    fun `同じページを映し続けているあいだ、文字認識を回し続けない`() {
        val script = VideoScript()
        script.hold(SceneState(pages[0]), 20000)
        val sim = run(script)
        assertEquals(describe(sim), listOf<Int?>(1), sim.captures.pageIds())
        // 20 秒で、撮る前の 1 回と、保険の見直し（3 秒おき、番号だけ）
        assertTrue("番号を読んだ回数 ${sim.glimpses}", sim.glimpses <= 9)
    }

    @Test
    fun `台に固定していても撮れる`() {
        val script = VideoScript()
        script.hold(SceneState(pages[0]), 2500)
        script.turn(pages[0], pages[1], gyro = 0.01f)
        script.hold(SceneState(pages[1]), 2500)
        val sim = Simulation(CaptureEngine(), handheld = 0f)
        sim.run(script)
        assertEquals(describe(sim), listOf<Int?>(1, 2), sim.captures.pageIds())
    }

    @Test
    fun `文字を読むのに時間がかかっても、待ちをやり直さずに撮る`() {
        // 実機で、文字を読むのに 0.6〜1.5 秒かかり、そのあとのコマを「大きく動いた」と数えて、
        // 待ちを最初からやり直し続けていた
        val script = VideoScript()
        script.hold(SceneState(pages[0]), 6000)
        script.turn(pages[0], pages[1])
        val turned = script.durationMs
        script.hold(SceneState(pages[1]), 6000)
        val sim = Simulation(CaptureEngine(), glimpseLatencyMs = 900)
        sim.run(script)
        assertEquals(describe(sim), listOf<Int?>(1, 2), sim.captures.pageIds())
        assertTrue("最初の 1 枚まで ${sim.captures[0].timeMs}ms", sim.captures[0].timeMs < 3000)
        assertTrue("めくってから ${sim.captures[1].timeMs - turned}ms", sim.captures[1].timeMs - turned < 2500)
    }

    @Test
    fun `番号が読めていれば、同じページの上のほうや下のほうを映しても撮り直さない`() {
        val script = VideoScript()
        script.hold(SceneState(pages[0]), 4000)
        script.animate(500) { t -> SceneState(pages[0], dy = 150f * t, gyro = 0.6f) }
        script.hold(SceneState(pages[0], dy = 150f), 3000)
        script.animate(500) { t -> SceneState(pages[0], dy = 150f - 300f * t, gyro = 0.6f) }
        script.hold(SceneState(pages[0], dy = -150f), 5000)
        val sim = Simulation(CaptureEngine(), numberMiss = 0.0)
        sim.run(script)
        assertEquals(describe(sim), listOf<Int?>(1), sim.captures.pageIds())
    }

    @Test
    fun `止めてから 1 秒以内に撮る`() {
        // 持ち主: 「撮るまでの時間がちょっとかかりすぎています」。番号だけ読む方式で、止めてからすぐ撮る
        val script = VideoScript()
        script.hold(SceneState(pages[0]), 3000)
        for (i in 1 until 5) {
            script.turn(pages[i - 1], pages[i])
            script.hold(SceneState(pages[i]), 3000)
        }
        val sim = run(script)
        assertEquals(describe(sim), listOf<Int?>(1, 2, 3, 4, 5), sim.captures.pageIds())
        // 台本では、めくり終わりから 250ms かけて反りが戻る。そこから数える
        var t = 3000
        for (i in 1 until 5) {
            t += 300 + 900 + 350 + 250
            val waited = sim.captures[i].timeMs - t
            assertTrue("${i + 1} 枚目: 止めてから ${waited}ms", waited < 1000)
            t += 3000
        }
    }

    @Test
    fun `ページ番号の無いページも撮る。同じページに戻ると、もう 1 枚撮ることはあるが、撮り逃しは無い`() {
        val plain = (0 until 3).map { SyntheticPage(800 + it, options = PageOptions(pageNumber = false)) }
        val script = VideoScript()
        script.hold(SceneState(plain[0]), 3500)
        script.turn(plain[0], plain[1])
        script.hold(SceneState(plain[1]), 3500)
        // スマホを離して、同じページに戻る。番号が無いので見分けられず、もう 1 枚撮る（撮れた写真は捨てない）
        script.animate(700) { t -> SceneState(plain[1], dx = 200f * kotlin.math.sin(t * 3.14f), gyro = 1.2f) }
        script.hold(SceneState(plain[1], dx = 8f), 4000)
        script.turn(plain[1], plain[2])
        script.hold(SceneState(plain[2]), 3500)
        val sim = run(script)
        assertEquals(describe(sim), listOf<Int?>(800, 801, 801, 802), sim.captures.pageIds())
    }

    @Test
    fun `番号の無いページを映し続けても、撮るのは 1 回`() {
        // 実機で、写真を撮るときの映像の乱れを「別のページへ移した」と数え、番号が読めないと同じ場所でもう一度撮っていた
        val plain = SyntheticPage(810, options = PageOptions(pageNumber = false))
        val script = VideoScript()
        script.hold(SceneState(plain), 12000)
        val sim = run(script)
        assertEquals(describe(sim), listOf<Int?>(810), sim.captures.pageIds())
    }

    @Test
    fun `ページ番号を、もう撮った番号に読み違えたページは自動では撮らないが、シャッターで撮れる`() {
        // 文字の中身は比べないので、番号を読み違えると「もう撮った」と見なす。画面にその番号が出るので、シャッターで撮る
        val script = VideoScript()
        script.hold(SceneState(pages[0]), 4000)
        script.turn(pages[0], pages[1])
        script.hold(SceneState(pages[1]), 2500)
        script.act { it.manual() }
        script.hold(SceneState(pages[1]), 2500)
        val sim = Simulation(CaptureEngine(), numberMiss = 0.0)
        sim.misreadNumber = { 1 }
        sim.run(script)
        assertEquals(describe(sim), listOf<Int?>(1, 2), sim.captures.pageIds())
        assertEquals(CaptureReason.MANUAL, sim.captures[1].reason)
    }

    @Test
    fun `ページ番号を読んでいるあいだに動き出したら、その結果では撮らず、止まってから撮る`() {
        // 実機で、番号を読むのに 0.6〜0.9 秒かかり、そのあいだに次のページへ動き出していたのに撮って、ぶれた写真になった
        val script = VideoScript()
        script.hold(SceneState(pages[0]), 3000)
        script.animate(400) { t -> SceneState(pages[1], dx = 160f - 100f * t, gyro = 0.9f) }
        // 途中で少し止まる（番号を読み始める）が、読み終える前にまた動く
        script.hold(SceneState(pages[1], dx = 60f), 600)
        script.animate(500) { t -> SceneState(pages[1], dx = 60f * (1 - t), gyro = 0.6f) }
        val settledAt = script.durationMs
        script.hold(SceneState(pages[1]), 4000)
        val sim = Simulation(CaptureEngine(), glimpseLatencyMs = 600, numberMiss = 0.0)
        sim.run(script)
        assertEquals(describe(sim), listOf<Int?>(1, 2), sim.captures.pageIds())
        assertTrue("ぶれた写真を撮った:\n${describe(sim)}", sim.captures.none { it.blurred })
        assertTrue("動いているあいだに撮った:\n${describe(sim)}", sim.captures[1].timeMs >= settledAt)
        assertTrue("読んだ結果を捨てた回数 ${sim.rejectedGlimpses}", sim.rejectedGlimpses >= 1)
    }

    @Test
    fun `ぶれて読めない写真だけが残ったページは、向け直すと撮り直す`() {
        // 実機で、番号は読めたがぶれた写真になり、番号だけ「撮った」と覚えていて、向け直しても撮られなかった
        val script = VideoScript()
        script.hold(SceneState(pages[0]), 2500)
        script.act { sim -> sim.engine.onPhotoInspected(sim.captures.last().requestId, null, usable = false) }
        script.hold(SceneState(pages[0]), 2500)
        script.animate(700) { t -> SceneState(pages[0], dx = 200f * kotlin.math.sin(t * 3.14f), gyro = 1.2f) }
        script.hold(SceneState(pages[0], dx = 6f), 4000)
        val sim = Simulation(CaptureEngine(), numberMiss = 0.0)
        sim.run(script)
        // 映したままでも、保険の見直し（3 秒おき）で番号が「まだ撮っていない」と分かって撮り直す。向け直しても撮り直す
        assertEquals(describe(sim), listOf<Int?>(1, 1), sim.captures.pageIds())
        assertTrue("点検の結果が出る前に撮り直した:\n${describe(sim)}", sim.captures[1].timeMs > 2500)
    }

    @Test
    fun `ページ番号が毎回読めなくても、二度撮りも撮り逃しも残らない`() {
        val script = VideoScript()
        script.hold(SceneState(pages[0]), 3500)
        for (i in 1 until 6) {
            script.turn(pages[i - 1], pages[i])
            script.hold(SceneState(pages[i]), 3500)
        }
        val sim = Simulation(CaptureEngine(), numberMiss = 0.6)
        sim.run(script)
        assertEquals(describe(sim), listOf<Int?>(1, 2, 3, 4, 5, 6), sim.captures.pageIds())
    }
}
