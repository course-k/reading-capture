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
        assertTrue("止まってから ${waited}ms", waited in 300..1500)
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
    fun `反りがゆっくり戻るあいだは待ち、落ち着いてから 1 回だけ撮る`() {
        val script = VideoScript()
        script.hold(SceneState(pages[0]), 2500)
        script.turn(pages[0], pages[1], relaxMs = 1)
        script.animate(1500) { t -> SceneState(pages[1], curl = 10f * (1 - t)) }
        script.hold(SceneState(pages[1]), 2500)
        val sim = run(script)
        assertEquals(describe(sim), listOf<Int?>(1, 2), sim.captures.pageIds())
        val second = sim.captures[1]
        assertTrue("反りが残っているうちに撮った: $second", second.curl < 1.5f)
        assertFalse(second.moved)
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
        assertEquals("ぼけているうちに撮った", 0, sim.captures[1].blurRadius)
    }

    @Test
    fun `見せた時間が短すぎるページは飛ばし、止めたページは撮る`() {
        // ぱらぱらめくって探す動き。0.3 秒しか止まらないページは撮らない
        val script = VideoScript()
        script.hold(SceneState(pages[0]), 2500)
        script.turn(pages[0], pages[1])
        script.hold(SceneState(pages[1]), 300)
        script.turn(pages[1], pages[2])
        script.hold(SceneState(pages[2]), 300)
        script.turn(pages[2], pages[3])
        script.hold(SceneState(pages[3]), 2500)
        val sim = run(script)
        assertEquals(describe(sim), listOf<Int?>(1, 4), sim.captures.pageIds())
    }

    @Test
    fun `撮れたときの振動で揺れても二度撮りしない`() {
        val script = VideoScript()
        script.hold(SceneState(pages[0]), 4000)
        val sim = Simulation(CaptureEngine(), vibrationMs = 120, vibrationShakePx = 4f)
        sim.run(script)
        assertEquals(describe(sim), listOf<Int?>(1), sim.captures.pageIds())
    }

    @Test
    fun `シャッターで撮ったページは自動で撮り直さない`() {
        val script = VideoScript()
        script.hold(SceneState(null), 1500)
        script.animate(500) { t -> SceneState(pages[0], dx = 200f * (1 - t)) }
        script.hold(SceneState(pages[0]), 200)
        script.act { it.manual(SceneState(pages[0])) }
        script.hold(SceneState(pages[0]), 3000)
        val sim = run(script)
        assertEquals(describe(sim), listOf<Int?>(1), sim.captures.pageIds())
        assertEquals(CaptureReason.MANUAL, sim.captures.single().reason)
    }

    @Test
    fun `同じページをシャッターで何度でも撮れる`() {
        val script = VideoScript()
        script.hold(SceneState(pages[0]), 2500)
        script.act { it.manual(SceneState(pages[0])) }
        script.hold(SceneState(pages[0]), 1500)
        script.act { it.manual(SceneState(pages[0])) }
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
        script.act { it.manual(SceneState(pages[0])) } // わざと二度撮り
        script.hold(SceneState(pages[0]), 1500)
        script.act { it.engine.onLastDeleted() }
        script.hold(SceneState(pages[0]), 2000)
        script.turn(pages[0], pages[1])
        script.hold(SceneState(pages[1]), 2500)
        val sim = run(script)
        assertEquals(describe(sim), listOf<Int?>(1, 1, 2), sim.captures.pageIds())
    }

    @Test
    fun `撮り直すと、本を直して止まったところで同じページをもう一度撮る`() {
        val script = VideoScript()
        script.hold(SceneState(pages[0]), 2500)
        script.act { it.engine.armRetake(it.nowMs) }
        script.hold(SceneState(pages[0], shakeX = 2f), 100) // ボタンを押した揺れ
        script.hold(SceneState(pages[0]), 600)
        // 本を右へ寄せて直す
        script.animate(700) { t -> SceneState(pages[0], dx = 18f * t, hand = Hand(480f - 60f * t, 500f)) }
        script.animate(300) { t -> SceneState(pages[0], dx = 18f, hand = Hand(420f + 120f * t, 500f)) }
        val adjustedAt = script.durationMs
        script.hold(SceneState(pages[0], dx = 18f), 3000)
        val sim = run(script)
        assertEquals(describe(sim), listOf<Int?>(1, 1), sim.captures.pageIds())
        assertEquals(CaptureReason.RETAKE, sim.captures[1].reason)
        assertTrue("直す前に撮った: ${sim.captures[1]}", sim.captures[1].timeMs >= adjustedAt)
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
        assertTrue(sim.captures[1].timeMs - armedAt in 2000..3500)
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
        val engine = CaptureEngine()
        // 1 回目の合図だけ、知らせを返さない
        val sim = Simulation(engine, captureLatencyMs = 350)
        var dropped = false
        val renderer = app.readingcapture.core.synthetic.SceneRenderer()
        val requests = ArrayList<Pair<Long, Int?>>()
        for ((i, state) in script.frames.withIndex()) {
            val now = i * 1000L / script.fps
            val jittered = state.copy(shakeX = ((i * 7919) % 100 - 50) / 100f, shakeY = ((i * 104729) % 100 - 50) / 100f)
            val r = engine.onFrame(renderer.render(jittered, i).downsampleToLongSide(320), now)
            r.request?.let { req ->
                requests += now to state.truthPageId
                if (!dropped) dropped = true else engine.onCaptureFinished(req.id, now + 1, saved = true)
            }
        }
        assertTrue("固まった: $requests", requests.size >= 2)
        assertEquals("最初の合図はページ 1", 1, requests.first().second)
        assertTrue("知らせが来なかったあと、待ってからもう一度合図する: $requests", requests[1].first - requests[0].first >= 8000)
        assertTrue("次のページも撮る: $requests", requests.any { it.second == 2 })
        assertTrue(sim.captures.isEmpty())
    }

    @Test
    fun `前回の最後のページを覚えていれば、開き直しても同じページを撮り直さない`() {
        val first = Simulation(CaptureEngine())
        first.run(VideoScript().hold(SceneState(pages[0]), 3000))
        assertEquals(1, first.captures.size)
        val saved = first.engine.lastReferenceImage()!!

        val engine = CaptureEngine()
        engine.restoreReference(saved)
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
}
