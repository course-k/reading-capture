package app.readingcapture.core.inbox

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.Base64

/**
 * テスト用の、GitHub のふりをするサーバー。取り込み箱 1 つ（owner/inbox）だけを持つ。
 * blob・ツリー・コミット・ブランチを実物と同じ決まりで持つので、上げた結果を中身で確かめられる。
 */
class FakeGitHub : AutoCloseable {

    class Recorded(val method: String, val path: String, val headers: Map<String, String>)

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    val validToken = "test-token-abc"
    var pushAllowed = true

    val blobs = HashMap<String, ByteArray>()
    private val trees = HashMap<String, Map<String, String>>()
    private val commits = HashMap<String, Pair<String, String?>>() // sha → (tree, parent)
    val commitMessages = ArrayList<String>()
    var headCommit: String
        private set

    val requests = ArrayList<Recorded>()

    /** 次の呼び出しに、この番号で失敗を返す（処理はしない）。 */
    var failNext: Triple<Int, String, Map<String, String>>? = null

    /** ブランチを進める呼び出しを、進めたうえで「返事が届かなかった」ことにする回数。 */
    var loseRefResponse = 0

    /** ブランチを進めたあと、先頭の読み取りにこの回数だけ古い内容を返す（実物で起きる遅れ）。 */
    var staleReadsAfterUpdate = 0
    private var staleReadsLeft = 0
    private var staleHead: String? = null

    /** ブランチを進める呼び出しの直前に呼ぶ（ほかの誰かの push を割り込ませる）。 */
    var beforeRefUpdate: (() -> Unit)? = null

    init {
        val emptyTree = storeTree(mapOf("README.md" to storeBlob("# reading-inbox\n".toByteArray())))
        headCommit = storeCommit(emptyTree, null, "最初")
        server.createContext("/") { handle(it) }
        server.start()
    }

    /** いまのブランチの先頭にあるファイル（パス → 中身）。 */
    fun files(): Map<String, ByteArray> = trees.getValue(commits.getValue(headCommit).first).mapValues { blobs.getValue(it.value) }

    /** 先頭までのコミットの数。 */
    fun commitCount(): Int {
        var n = 0
        var c: String? = headCommit
        while (c != null) {
            n++
            c = commits.getValue(c).second
        }
        return n
    }

    /** ほかの誰か（Mac 側の Claude）が、ファイルを足して push した。 */
    fun externalCommit(path: String, content: String?) {
        val tree = HashMap(trees.getValue(commits.getValue(headCommit).first))
        if (content == null) tree.remove(path) else tree[path] = storeBlob(content.toByteArray())
        headCommit = storeCommit(storeTree(tree), headCommit, "外から")
    }

    fun count(method: String, suffix: String) = requests.count { it.method == method && it.path.substringBefore('?').endsWith(suffix) }

    override fun close() = server.stop(0)

    // ---- 内部 ----

    private fun storeBlob(bytes: ByteArray): String = GitBlob.sha(bytes).also { blobs[it] = bytes }

    private fun sha1(text: String) = MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun storeTree(files: Map<String, String>): String {
        val sha = sha1("tree " + files.toSortedMap().entries.joinToString("\n") { "${it.key}=${it.value}" })
        trees[sha] = HashMap(files)
        return sha
    }

    private fun storeCommit(tree: String, parent: String?, message: String): String {
        val sha = sha1("commit $tree $parent $message ${commits.size}")
        commits[sha] = tree to parent
        commitMessages += message
        return sha
    }

    @Synchronized
    private fun handle(ex: HttpExchange) {
        val method = ex.requestMethod
        val uri = ex.requestURI
        val path = uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: "")
        val headers = ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() }
        requests += Recorded(method, path, headers)
        val body = ex.requestBody.readBytes()
        try {
            failNext?.let {
                failNext = null
                return respond(ex, it.first, """{"message":"${it.second}"}""", it.third)
            }
            if (headers["authorization"] != "Bearer $validToken") return respond(ex, 401, """{"message":"Bad credentials"}""")
            if (headers["user-agent"].isNullOrBlank()) return respond(ex, 403, """{"message":"User-Agent required"}""")
            val prefix = "/repos/owner/inbox"
            if (!uri.path.startsWith(prefix)) return respond(ex, 404, """{"message":"Not Found"}""")
            val rest = uri.path.removePrefix(prefix)
            val json = if (body.isNotEmpty()) Json.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject else null
            when {
                method == "GET" && rest == "" ->
                    respond(ex, 200, buildJsonObject { put("permissions", buildJsonObject { put("push", pushAllowed) }) }.toString())

                method == "GET" && rest == "/git/ref/heads/main" -> {
                    val sha = if (staleReadsLeft > 0 && staleHead != null) {
                        staleReadsLeft--
                        staleHead!!
                    } else headCommit
                    respond(ex, 200, buildJsonObject { put("object", buildJsonObject { put("sha", sha) }) }.toString())
                }

                method == "GET" && rest.startsWith("/git/commits/") -> {
                    val c = commits[rest.substringAfterLast('/')] ?: return respond(ex, 404, """{"message":"Not Found"}""")
                    respond(ex, 200, buildJsonObject { put("tree", buildJsonObject { put("sha", c.first) }) }.toString())
                }

                method == "GET" && rest.startsWith("/git/trees/") -> {
                    val t = trees[rest.substringAfterLast('/')] ?: return respond(ex, 404, """{"message":"Not Found"}""")
                    respond(ex, 200, buildJsonObject {
                        put("truncated", false)
                        put("tree", buildJsonArray {
                            // 実物と同じく、途中のフォルダも並ぶ
                            t.keys.flatMap { p -> p.split('/').dropLast(1).runningReduce { a, b -> "$a/$b" } }.toSet().forEach { dir ->
                                add(buildJsonObject { put("path", dir); put("type", "tree"); put("sha", sha1(dir)) })
                            }
                            t.forEach { (p, s) -> add(buildJsonObject { put("path", p); put("type", "blob"); put("sha", s) }) }
                        })
                    }.toString())
                }

                method == "POST" && rest == "/git/blobs" -> {
                    require(json!!["encoding"]!!.jsonPrimitive.content == "base64")
                    val bytes = Base64.getDecoder().decode(json["content"]!!.jsonPrimitive.content)
                    respond(ex, 201, buildJsonObject { put("sha", storeBlob(bytes)) }.toString())
                }

                method == "POST" && rest == "/git/trees" -> {
                    val base = trees[json!!["base_tree"]!!.jsonPrimitive.content] ?: return respond(ex, 422, """{"message":"base_tree not found"}""")
                    val next = HashMap(base)
                    for (e in json["tree"]!!.jsonArray) {
                        val o = e.jsonObject
                        val p = o["path"]!!.jsonPrimitive.content
                        val s = o["sha"]
                        if (s == null || s is JsonNull) {
                            if (next.remove(p) == null) return respond(ex, 422, """{"message":"cannot delete a file that does not exist"}""")
                        } else {
                            if (!blobs.containsKey(s.jsonPrimitive.content)) return respond(ex, 422, """{"message":"blob not found"}""")
                            next[p] = s.jsonPrimitive.content
                        }
                    }
                    respond(ex, 201, buildJsonObject { put("sha", storeTree(next)) }.toString())
                }

                method == "POST" && rest == "/git/commits" -> {
                    val tree = json!!["tree"]!!.jsonPrimitive.content
                    val parent = json["parents"]!!.jsonArray.single().jsonPrimitive.content
                    if (!trees.containsKey(tree) || !commits.containsKey(parent)) return respond(ex, 422, """{"message":"invalid"}""")
                    respond(ex, 201, buildJsonObject { put("sha", storeCommit(tree, parent, json["message"]!!.jsonPrimitive.content)) }.toString())
                }

                method == "PATCH" && rest == "/git/refs/heads/main" -> {
                    beforeRefUpdate?.let {
                        beforeRefUpdate = null
                        it()
                    }
                    val sha = json!!["sha"]!!.jsonPrimitive.content
                    val c = commits[sha] ?: return respond(ex, 422, """{"message":"Object does not exist"}""")
                    if (c.second != headCommit) return respond(ex, 422, """{"message":"Update is not a fast forward"}""")
                    staleHead = headCommit
                    staleReadsLeft = staleReadsAfterUpdate
                    headCommit = sha
                    if (loseRefResponse > 0) {
                        loseRefResponse--
                        return respond(ex, 502, """{"message":"Bad Gateway"}""")
                    }
                    respond(ex, 200, buildJsonObject { put("object", buildJsonObject { put("sha", sha) }) }.toString())
                }

                method == "GET" && rest.startsWith("/contents/") -> {
                    val p = java.net.URLDecoder.decode(rest.removePrefix("/contents/"), "UTF-8")
                    val sha = trees.getValue(commits.getValue(headCommit).first)[p] ?: return respond(ex, 404, """{"message":"Not Found"}""")
                    val etag = "\"$sha\""
                    if (headers["if-none-match"] == etag) {
                        ex.responseHeaders.add("ETag", etag)
                        ex.sendResponseHeaders(304, -1)
                        ex.close()
                    } else {
                        ex.responseHeaders.add("ETag", etag)
                        val bytes = blobs.getValue(sha)
                        ex.sendResponseHeaders(200, bytes.size.toLong())
                        ex.responseBody.use { it.write(bytes) }
                    }
                }

                else -> respond(ex, 404, """{"message":"Not Found"}""")
            }
        } catch (e: Exception) {
            respond(ex, 500, """{"message":"fake server error: ${e.message}"}""")
        }
    }

    private fun respond(ex: HttpExchange, code: Int, body: String, headers: Map<String, String> = emptyMap()) {
        val bytes = body.toByteArray()
        headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
}
