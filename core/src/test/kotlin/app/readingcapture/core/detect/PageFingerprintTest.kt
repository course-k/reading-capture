package app.readingcapture.core.detect

import app.readingcapture.core.synthetic.SyntheticPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class PageFingerprintTest {

    private fun lines(id: Int): List<String> = SyntheticPage(id).also { it.full }.textLines

    /** 文字認識の読み違い: 一部の文字が別の文字になり、一部の行が抜ける。 */
    private fun misread(lines: List<String>, seed: Long, charError: Double, lineDrop: Double): List<String> {
        val rnd = Random(seed)
        return lines.filter { rnd.nextDouble() >= lineDrop }.map { line ->
            buildString { for (c in line) append(if (rnd.nextDouble() < charError) (0x4E00 + rnd.nextInt(2000)).toChar() else c) }
        }
    }

    @Test
    fun `同じページは、読み違いや抜けがあっても重なる`() {
        val original = lines(1)
        val a = PageFingerprint.of(misread(original, 1, charError = 0.10, lineDrop = 0.0))
        val b = PageFingerprint.of(misread(original, 2, charError = 0.10, lineDrop = 0.25))
        val s = PageFingerprint.similarity(a, b)
        assertTrue("重なり $s", s > 0.45f)
    }

    @Test
    fun `半分しか写っていなくても、同じページなら重なる`() {
        val original = lines(3)
        val whole = PageFingerprint.of(misread(original, 1, 0.08, 0.0))
        val half = PageFingerprint.of(misread(original.take(original.size / 2), 2, 0.08, 0.0))
        assertTrue(PageFingerprint.similarity(whole, half) > 0.5f)
    }

    @Test
    fun `違うページは重ならない`() {
        val a = PageFingerprint.of(lines(1))
        for (i in 2..30) {
            val s = PageFingerprint.similarity(a, PageFingerprint.of(lines(i)))
            assertTrue("ページ $i との重なり $s", s < 0.1f)
        }
    }

    @Test
    fun `同じ言い回しが多い本でも、違うページは同じページほどは重ならない`() {
        // 専門書は、同じ用語がどのページにも出てくる。各行の 4 分の 1 が共通の言い回しだとしても、見分けられること
        val phrase = "医療情報システムの標準化と相互運用性"
        fun page(seed: Int) = lines(seed).mapIndexed { i, l -> if (i % 4 == 0) phrase + l.drop(phrase.length) else l }
        val a = PageFingerprint.of(page(1))
        val different = PageFingerprint.similarity(a, PageFingerprint.of(page(2)))
        val same = PageFingerprint.similarity(a, PageFingerprint.of(misread(page(1), 9, 0.10, 0.1)))
        assertTrue("違うページ $different、同じページ $same", different < 0.2f && same > 0.5f)
    }

    @Test
    fun `記号や空白の読み方が違っても同じ`() {
        val a = PageFingerprint.of(listOf("病院とは、20 床以上の入院施設をもつもの。"))
        val b = PageFingerprint.of(listOf("病院とは 20床以上の入院施設をもつもの"))
        assertEquals(1f, PageFingerprint.similarity(a, b), 0.001f)
        assertEquals(0, PageFingerprint.of(listOf("、。", "  ")).hashes.size)
    }
}
