package app.readingcapture.core.inbox

import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.OffsetDateTime
import java.util.Random

class InboxUploaderTest {

    private val server = FakeGitHub()
    private val http = OkHttpClient()

    private fun client(token: String = server.validToken, repo: String = "inbox") =
        GitHubClient(http, InboxConfig("owner", repo, token, apiBase = server.baseUrl))

    private fun uploader(client: GitHubClient = client()) = InboxUploader(client, pauseBetweenWritesMs = 0, retryPauseMs = 0)

    private fun photoBytes(seed: Int, size: Int = 300_000) = ByteArray(size).also { Random(seed.toLong()).nextBytes(it) }

    private val at = OffsetDateTime.parse("2026-11-20T21:34:55.123+09:00")
    private fun photoPath(i: Int) = InboxLayout.photo("book-a", at.plusSeconds(i.toLong()), PhotoKind.CAMERA)

    @After
    fun tearDown() = server.close()

    @Test
    fun `写真と言葉を、まとめて 1 コミットで置く`() {
        val photos = (1..3).map { UploadItem("p$it", photoPath(it), { photoBytes(it) }) }
        val note = NoteFile(bookId = "book-a", bookTitle = "本", chapter = "第2章", section = "1 病院とは", spokenAt = at.toString(), text = "話した言葉\n2 行目")
        val noteItem = UploadItem("n1", InboxLayout.note("book-a", at), { note.encode() })
        val before = server.commitCount()

        val result = uploader().upload(photos + noteItem, emptyList(), "取り込み")

        assertEquals(setOf("p1", "p2", "p3", "n1"), result.doneKeys)
        assertEquals(before + 1, server.commitCount())
        val files = server.files()
        for (i in 1..3) assertArrayEquals(photoBytes(i), files[photoPath(i)])
        assertEquals(note, NoteFile.decode(files.getValue("books/book-a/notes/2026-11-20/213455-123.json")))
        assertTrue("もとからあるファイルを消していない", files.containsKey("README.md"))
        assertEquals("books/book-a/photos/2026-11-20/213456-123-cam.jpg", photoPath(1))
    }

    @Test
    fun `同じものをもう一度上げても、何も増えない`() {
        val items = (1..2).map { UploadItem("p$it", photoPath(it), { photoBytes(it) }) }
        uploader().upload(items, emptyList(), "1 回目")
        val commits = server.commitCount()
        val blobPosts = server.count("POST", "/git/blobs")

        val again = uploader().upload(items, emptyList(), "2 回目")

        assertEquals(setOf("p1", "p2"), again.doneKeys)
        assertNull(again.commitSha)
        assertEquals(commits, server.commitCount())
        assertEquals("中身も上げ直さない", blobPosts, server.count("POST", "/git/blobs"))
    }

    @Test
    fun `置けたのに返事が届かなかったあと、やり直しても二重に置かない`() {
        val items = (1..2).map { UploadItem("p$it", photoPath(it), { photoBytes(it) }) }
        server.loseRefResponse = 1
        try {
            uploader().upload(items, emptyList(), "取り込み")
            fail("失敗するはず")
        } catch (e: InboxException.Server) {
            assertEquals(502, e.code)
        }
        val commits = server.commitCount()

        val retry = uploader().upload(items, emptyList(), "取り込み")

        assertEquals(setOf("p1", "p2"), retry.doneKeys)
        assertEquals(commits, server.commitCount())
        assertArrayEquals(photoBytes(1), server.files()[photoPath(1)])
    }

    @Test
    fun `Mac 側が先に push していても、その上に載せる`() {
        val items = listOf(UploadItem("p1", photoPath(1), { photoBytes(1) }))
        server.beforeRefUpdate = { server.externalCommit("books.json", """{"books":[]}""") }

        val result = uploader().upload(items, emptyList(), "取り込み")

        assertEquals(setOf("p1"), result.doneKeys)
        val files = server.files()
        assertArrayEquals(photoBytes(1), files[photoPath(1)])
        assertEquals("Mac 側の変更を消していない", """{"books":[]}""", files.getValue("books.json").toString(Charsets.UTF_8))
        assertEquals("中身は上げ直さない", 1, server.count("POST", "/git/blobs"))
    }

    @Test
    fun `Mac 側が同じ写真を先に消していたら、消えたままにせず置き直す`() {
        // アプリが上げる途中で、Mac 側が別の写真を消した（骨子を作り終えた分の片付け）
        uploader().upload(listOf(UploadItem("p1", photoPath(1), { photoBytes(1) })), emptyList(), "1 枚目")
        server.beforeRefUpdate = { server.externalCommit(photoPath(1), null) }
        uploader().upload(listOf(UploadItem("p2", photoPath(2), { photoBytes(2) })), emptyList(), "2 枚目")
        val files = server.files()
        assertFalse("片付けた写真は戻らない", files.containsKey(photoPath(1)))
        assertArrayEquals(photoBytes(2), files[photoPath(2)])
    }

    @Test
    fun `途中まで上げてあった中身は上げ直さない`() {
        val bytes = photoBytes(7)
        val c = client()
        val sha = c.createBlob(bytes)
        val posts = server.count("POST", "/git/blobs")

        uploader(c).upload(listOf(UploadItem("p7", photoPath(7), { bytes }, knownBlobSha = sha)), emptyList(), "取り込み")

        assertEquals(posts, server.count("POST", "/git/blobs"))
        assertArrayEquals(bytes, server.files()[photoPath(7)])
    }

    @Test
    fun `上げてあったはずの中身が片付けられていたら、上げ直して置く`() {
        val bytes = photoBytes(8)
        val sha = GitBlob.sha(bytes) // サーバーには無い
        val uploaded = ArrayList<String>()
        uploader().upload(listOf(UploadItem("p8", photoPath(8), { bytes }, knownBlobSha = sha)), emptyList(), "取り込み") { key, _ -> uploaded += key }
        assertEquals(listOf("p8"), uploaded)
        assertArrayEquals(bytes, server.files()[photoPath(8)])
    }

    @Test
    fun `中身を上げ終えるたびに知らせる`() {
        val seen = ArrayList<Pair<String, String>>()
        val items = (1..3).map { UploadItem("p$it", photoPath(it), { photoBytes(it) }) }
        uploader().upload(items, emptyList(), "取り込み") { key, sha -> seen += key to sha }
        assertEquals(listOf("p1", "p2", "p3"), seen.map { it.first })
        assertEquals(GitBlob.sha(photoBytes(2)), seen[1].second)
    }

    @Test
    fun `消す。もう無いものを消しても失敗しない`() {
        uploader().upload((1..2).map { UploadItem("p$it", photoPath(it), { photoBytes(it) }) }, emptyList(), "置く")

        val result = uploader().upload(
            emptyList(),
            listOf(DeleteItem("p1", photoPath(1)), DeleteItem("gone", "books/book-a/photos/2026-11-20/000000-000-cam.jpg")),
            "消す",
        )

        assertEquals(setOf("p1", "gone"), result.doneKeys)
        val files = server.files()
        assertFalse(files.containsKey(photoPath(1)))
        assertTrue(files.containsKey(photoPath(2)))
    }

    @Test
    fun `置いた直後に古い内容を返されても、消し忘れない`() {
        // 実物の GitHub は、書いた直後の読み取りに少し前の内容を返すことがある
        server.staleReadsAfterUpdate = 3
        val u = uploader()
        u.upload(listOf(UploadItem("p1", photoPath(1), { photoBytes(1) })), emptyList(), "置く")
        val result = u.upload(emptyList(), listOf(DeleteItem("p1", photoPath(1))), "消す")
        assertEquals(setOf("p1"), result.doneKeys)
        assertFalse("置いたばかりの写真を、無いものと見て消し忘れた", server.files().containsKey(photoPath(1)))
    }

    @Test
    fun `置いた直後に古い内容を返されても、続けて置ける`() {
        server.staleReadsAfterUpdate = 3
        val u = uploader()
        u.upload(listOf(UploadItem("p1", photoPath(1), { photoBytes(1) })), emptyList(), "1 枚目")
        val commits = server.commitCount()
        u.upload(listOf(UploadItem("p2", photoPath(2), { photoBytes(2) })), emptyList(), "2 枚目")
        assertEquals("余分なコミットを作らない", commits + 1, server.commitCount())
        assertTrue(server.files().containsKey(photoPath(1)) && server.files().containsKey(photoPath(2)))
    }

    @Test
    fun `置いた言葉を書き換えられる`() {
        val path = InboxLayout.note("book-a", at)
        fun note(section: String) = NoteFile(bookId = "book-a", bookTitle = "本", section = section, spokenAt = at.toString(), text = "言葉")
        uploader().upload(listOf(UploadItem("n", path, { note("1 病院とは").encode() })), emptyList(), "置く")
        uploader().upload(listOf(UploadItem("n", path, { note("2 病院業務と情報システム").encode() })), emptyList(), "節を変える")
        assertEquals("2 病院業務と情報システム", NoteFile.decode(server.files().getValue(path)).section)
    }

    @Test
    fun `鍵が無効なら、そう分かる失敗になる`() {
        try {
            uploader(client(token = "wrong")).upload(listOf(UploadItem("p", photoPath(1), { photoBytes(1) })), emptyList(), "x")
            fail()
        } catch (e: InboxException.Unauthorized) {
            assertFalse("鍵を失敗の文面に出さない", e.message!!.contains("wrong"))
        }
        assertFalse(InboxConfig("owner", "inbox", "secret-token").toString().contains("secret-token"))
    }

    @Test
    fun `取り込み箱の名前が違えば、見つからないと分かる`() {
        try {
            client(repo = "nope").checkAccess()
            fail()
        } catch (e: InboxException.NotFound) {
            assertTrue(e.message!!.contains("owner/nope"))
        }
    }

    @Test
    fun `読むだけの鍵なら、書き込めないと分かる`() {
        server.pushAllowed = false
        try {
            client().checkAccess()
            fail()
        } catch (e: InboxException.NoPermission) {
            // 期待どおり
        }
        server.pushAllowed = true
        client().checkAccess()
    }

    @Test
    fun `回数の上限に当たったら、待つ秒数が分かる`() {
        server.failNext = Triple(403, "You have exceeded a secondary rate limit", mapOf("Retry-After" to "37"))
        try {
            client().head()
            fail()
        } catch (e: InboxException.RateLimited) {
            assertEquals(37, e.retryAfterSeconds)
        }
        server.failNext = Triple(429, "too many", emptyMap())
        try {
            client().head()
            fail()
        } catch (e: InboxException.RateLimited) {
            assertEquals(60, e.retryAfterSeconds)
        }
    }

    @Test
    fun `つながらなければ、つながらないと分かる`() {
        val dead = GitHubClient(http, InboxConfig("owner", "inbox", "t", apiBase = "http://127.0.0.1:9"))
        try {
            dead.head()
            fail()
        } catch (e: InboxException.Network) {
            // 期待どおり
        }
    }

    @Test
    fun `本の一覧は、変わっていなければ読み直さない`() {
        server.externalCommit("books.json", """{"schema":1,"books":[{"id":"book-a","title":"本"}]}""")
        val c = client()
        val first = c.getRaw(InboxLayout.BOOKS) as RawFile.Found
        assertTrue(first.bytes.toString(Charsets.UTF_8).contains("book-a"))
        assertEquals(RawFile.NotModified, c.getRaw(InboxLayout.BOOKS, first.etag))
        assertEquals(RawFile.Missing, c.getRaw("books/none/toc.json"))
        server.externalCommit("books.json", """{"schema":1,"books":[]}""")
        assertTrue(c.getRaw(InboxLayout.BOOKS, first.etag) is RawFile.Found)
    }

    @Test
    fun `どの呼び出しにも、鍵・版・名乗りを付ける`() {
        uploader().upload(listOf(UploadItem("p", photoPath(1), { photoBytes(1, 1000) })), emptyList(), "x")
        assertTrue(server.requests.isNotEmpty())
        for (r in server.requests) {
            assertEquals("Bearer ${server.validToken}", r.headers["authorization"])
            assertEquals(GitHubClient.API_VERSION, r.headers["x-github-api-version"])
            assertEquals("reading-capture", r.headers["user-agent"])
        }
    }

    @Test
    fun `大きな写真も壊さずに上げる`() {
        val big = photoBytes(99, 4_500_001)
        uploader().upload(listOf(UploadItem("big", photoPath(9), { big })), emptyList(), "大きい")
        assertArrayEquals(big, server.files()[photoPath(9)])
    }

    @Test
    fun `本の id に使えない文字ははじく`() {
        assertTrue(InboxLayout.isValidBookId("iryo-joho-system-nyumon-8"))
        assertFalse(InboxLayout.isValidBookId("医療情報"))
        assertFalse(InboxLayout.isValidBookId("../x"))
        assertFalse(InboxLayout.isValidBookId(""))
        assertEquals("owner" to "inbox", InboxConfig.parseRepo(" owner/inbox "))
        assertNull(InboxConfig.parseRepo("https://github.com/owner/inbox"))
    }
}
