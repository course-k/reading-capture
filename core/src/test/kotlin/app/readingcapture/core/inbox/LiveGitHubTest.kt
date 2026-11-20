package app.readingcapture.core.inbox

import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.util.Random

/**
 * 本物の GitHub に対して、上げる処理が通るかを確かめる。ふだんのテストでは動かない。
 *
 * 環境変数 INBOX_LIVE_REPO（持ち主/リポジトリ）と INBOX_LIVE_TOKEN を渡したときだけ動く。
 * 置くのは乱数で作った中身だけで、`selftest/` の下に置き、最後に消す。
 */
class LiveGitHubTest {

    @Test
    fun `本物の GitHub で、置く・載せ直す・読む・消すが通る`() {
        val repo = System.getenv("INBOX_LIVE_REPO")
        val token = System.getenv("INBOX_LIVE_TOKEN")
        Assume.assumeTrue(!repo.isNullOrBlank() && !token.isNullOrBlank())
        val (owner, name) = InboxConfig.parseRepo(repo!!)!!
        val client = GitHubClient(OkHttpClient(), InboxConfig(owner, name, token!!))
        val uploader = InboxUploader(client)
        val run = System.currentTimeMillis()
        val pathA = "selftest/$run/a.bin"
        val pathB = "selftest/$run/b.bin"
        val bytesA = ByteArray(700_000).also { Random(run).nextBytes(it) }
        val bytesB = "話した言葉のつもりの文字\n".toByteArray()

        client.checkAccess()
        val before = client.head()

        // 置く
        val first = uploader.upload(listOf(UploadItem("a", pathA, { bytesA }), UploadItem("b", pathB, { bytesB })), emptyList(), "selftest: 置く")
        assertEquals(setOf("a", "b"), first.doneKeys)
        // 書いた直後の読み取りは、少し古い内容が返ることがある（実測）。追いつくまで待つ
        val afterFirst = awaitHead(client) { it.commitSha == first.commitSha }
        assertEquals(first.commitSha, afterFirst.commitSha)
        val files = client.listFiles(afterFirst.treeSha)
        assertEquals(GitBlob.sha(bytesA), files[pathA])
        assertEquals(GitBlob.sha(bytesB), files[pathB])
        assertTrue("もとからあるファイルが残っている", files.containsKey("README.md"))

        // 同じものをもう一度: 何も増えない
        val again = uploader.upload(listOf(UploadItem("a", pathA, { bytesA })), emptyList(), "selftest: もう一度")
        assertNull(again.commitSha)

        // 読む。変わっていなければ読み直さない
        val raw = client.getRaw(pathB) as RawFile.Found
        assertArrayEquals(bytesB, raw.bytes)
        assertEquals(RawFile.NotModified, client.getRaw(pathB, raw.etag))
        assertEquals(RawFile.Missing, client.getRaw("selftest/$run/none.bin"))

        // 古いコミットの上に載せたコミットでは、ブランチを進められない（上書きしない）
        val staleTree = client.createTree(before.treeSha, listOf(TreeChange("selftest/$run/stale.bin", client.createBlob(byteArrayOf(1, 2, 3)))))
        val staleCommit = client.createCommit("selftest: 古い先頭に載せたコミット", staleTree, before.commitSha)
        assertFalse("fast-forward でない更新は通らない", client.advanceBranch(staleCommit))
        Thread.sleep(3000)
        assertEquals(afterFirst.commitSha, client.head().commitSha)

        // 消す。無いものを消しても失敗しない
        val deleted = uploader.upload(emptyList(), listOf(DeleteItem("a", pathA), DeleteItem("b", pathB), DeleteItem("x", "selftest/$run/none.bin")), "selftest: 消す")
        assertEquals(setOf("a", "b", "x"), deleted.doneKeys)
        val end = client.listFiles(awaitHead(client) { it.commitSha == deleted.commitSha }.treeSha)
        assertFalse(end.keys.any { it.startsWith("selftest/$run/") })
        assertTrue(end.containsKey("books.json"))
    }

    private fun awaitHead(client: GitHubClient, done: (Head) -> Boolean): Head {
        var head = client.head()
        repeat(20) {
            if (done(head)) return head
            Thread.sleep(500)
            head = client.head()
        }
        return head
    }
}
