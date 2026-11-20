package app.readingcapture.core.inbox

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64

/** 取り込み箱の場所と鍵。 */
data class InboxConfig(
    val owner: String,
    val repo: String,
    val token: String,
    val branch: String = "main",
    val apiBase: String = "https://api.github.com",
) {
    // 鍵をログや例外に出さない
    override fun toString(): String = "InboxConfig($owner/$repo@$branch)"

    companion object {
        /** 「持ち主/リポジトリ」の形から作る。形が違えば null。 */
        fun parseRepo(text: String): Pair<String, String>? {
            val m = Regex("^([A-Za-z0-9][A-Za-z0-9-]{0,38})/([A-Za-z0-9._-]{1,100})$").matchEntire(text.trim()) ?: return null
            return m.groupValues[1] to m.groupValues[2]
        }
    }
}

/** 取り込み箱とのやりとりで起きる失敗。画面の言葉に直せるよう、種類で分ける。 */
sealed class InboxException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    /** 鍵が無効（期限切れ、取り消し済み、打ち間違い） */
    class Unauthorized : InboxException("鍵が無効です")

    /** 鍵は通るが、この取り込み箱に書き込めない（対象のリポジトリや権限の選び間違い） */
    class NoPermission(detail: String) : InboxException("取り込み箱に書き込めません: $detail")

    /** 取り込み箱が見つからない（名前の間違い、または鍵の対象に入っていない） */
    class NotFound(what: String) : InboxException("見つかりません: $what")

    /** 回数の上限。[retryAfterSeconds] 秒待つ */
    class RateLimited(val retryAfterSeconds: Long) : InboxException("混み合っています。${retryAfterSeconds} 秒待ちます")

    /** つながらない、途中で切れた */
    class Network(cause: IOException) : InboxException("つながりません: ${cause.message}", cause)

    /** GitHub 側の一時的な不調 */
    class Server(val code: Int) : InboxException("GitHub が応答しません（$code）")

    /** 想定していない応答 */
    class Unexpected(val code: Int, detail: String) : InboxException("想定外の応答（$code）: $detail")
}

/** いちばん新しいコミットと、その中身（ツリー）。 */
data class Head(val commitSha: String, val treeSha: String)

/** 1 コミットに入れる変更。[blobSha] が null なら、そのファイルを消す。 */
data class TreeChange(val path: String, val blobSha: String?)

sealed interface RawFile {
    /** 前に読んだときから変わっていない */
    data object NotModified : RawFile

    data object Missing : RawFile

    class Found(val bytes: ByteArray, val etag: String?) : RawFile
}

/**
 * GitHub の REST API のうち、取り込み箱に必要な分だけ。
 *
 * 使うのは Git Database API（blob → tree → commit → ref）。写真をまとめて 1 コミットで上げられ、
 * ブランチに触るのは最後の 1 回だけなので、Mac 側の Claude が同じ取り込み箱に push していても壊さない
 * （ref の更新は fast-forward だけ。競り負けたらやり直す）。
 *
 * 2026-11-20 に確かめた公式の記述:
 * - https://docs.github.com/en/rest/git/blobs / trees / commits / refs
 * - https://docs.github.com/en/rest/guides/using-the-rest-api-to-interact-with-your-git-database
 * - https://docs.github.com/en/rest/using-the-rest-api/getting-started-with-the-rest-api （User-Agent は必須）
 * - 必要な権限は fine-grained token の Contents (write) だけ
 */
class GitHubClient(private val http: OkHttpClient, private val config: InboxConfig) {

    private val json = Json { ignoreUnknownKeys = true }
    private val repoUrl = "${config.apiBase.trimEnd('/')}/repos/${config.owner}/${config.repo}"

    /** 鍵が通り、この取り込み箱に書き込めるかを確かめる。だめなら [InboxException]。 */
    fun checkAccess() {
        val body = getJson(repoUrl, notFound = "取り込み箱（${config.owner}/${config.repo}）")
        val push = body["permissions"]?.jsonObject?.get("push")?.jsonPrimitive?.boolean
        if (push == false) throw InboxException.NoPermission("鍵の権限で Contents を Read and write にしてください")
        // ブランチがあること（空のリポジトリでないこと）も見ておく
        head()
    }

    fun head(): Head {
        val ref = getJson("$repoUrl/git/ref/heads/${config.branch}", notFound = "ブランチ ${config.branch}")
        val commitSha = ref["object"]!!.jsonObject["sha"]!!.jsonPrimitive.content
        val commit = getJson("$repoUrl/git/commits/$commitSha", notFound = "コミット")
        return Head(commitSha, commit["tree"]!!.jsonObject["sha"]!!.jsonPrimitive.content)
    }

    /** ツリーの中の全ファイル（パス → blob の sha）。 */
    fun listFiles(treeSha: String): Map<String, String> {
        val body = getJson("$repoUrl/git/trees/$treeSha?recursive=1", notFound = "ツリー")
        if (body["truncated"]?.jsonPrimitive?.boolean == true) {
            throw InboxException.Unexpected(200, "取り込み箱のファイルが多すぎて一覧を取り切れません。骨子を作り終えた写真を消してください")
        }
        val out = HashMap<String, String>()
        for (e in body["tree"]!!.jsonArray) {
            val o = e.jsonObject
            if (o["type"]?.jsonPrimitive?.content == "blob") out[o["path"]!!.jsonPrimitive.content] = o["sha"]!!.jsonPrimitive.content
        }
        return out
    }

    /**
     * 中身を blob として上げる。返る sha は中身から決まるので、同じ中身を何度上げても同じになる。
     * 手元で計算した sha と違えば、途中で壊れたとみなして失敗にする。
     */
    fun createBlob(bytes: ByteArray): String {
        val request = Request.Builder().url("$repoUrl/git/blobs").post(BlobBody(bytes))
        val sha = executeJson(request, notFound = "取り込み箱")["sha"]!!.jsonPrimitive.content
        val expected = GitBlob.sha(bytes)
        if (sha != expected) throw InboxException.Unexpected(201, "上げた中身が手元と一致しません")
        return sha
    }

    fun createTree(baseTreeSha: String, changes: List<TreeChange>): String {
        val payload = buildJsonObject {
            put("base_tree", baseTreeSha)
            put("tree", buildJsonArray {
                for (c in changes) add(buildJsonObject {
                    put("path", c.path)
                    put("mode", "100644")
                    put("type", "blob")
                    if (c.blobSha == null) put("sha", JsonNull) else put("sha", c.blobSha)
                })
            })
        }
        return postJson("$repoUrl/git/trees", payload)["sha"]!!.jsonPrimitive.content
    }

    fun createCommit(message: String, treeSha: String, parentSha: String): String {
        val payload = buildJsonObject {
            put("message", message)
            put("tree", treeSha)
            put("parents", JsonArray(listOf(JsonPrimitive(parentSha))))
        }
        return postJson("$repoUrl/git/commits", payload)["sha"]!!.jsonPrimitive.content
    }

    /**
     * ブランチを新しいコミットへ進める。上書きはしない（fast-forward だけ）。
     * @return 進められたら true。ほかの誰かが先に進めていて進められなかったら false
     */
    fun advanceBranch(commitSha: String): Boolean {
        val payload = buildJsonObject {
            put("sha", commitSha)
            put("force", false)
        }
        val request = Request.Builder().url("$repoUrl/git/refs/heads/${config.branch}")
            .patch(payload.toString().toRequestBody(JSON_TYPE))
        return try {
            executeJson(request, notFound = "ブランチ ${config.branch}")
            true
        } catch (e: InboxException.Unexpected) {
            // fast-forward でないときの番号は公式に明記が無い（表は 409 と 422）。どちらも「競り負け」として扱う
            if (e.code == 422 || e.code == 409) false else throw e
        }
    }

    /**
     * ファイルの中身をそのまま読む。[etag] に前回の値を渡すと、変わっていなければ [RawFile.NotModified]。
     */
    fun getRaw(path: String, etag: String? = null): RawFile {
        val encoded = path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        val request = Request.Builder().url("$repoUrl/contents/$encoded?ref=${config.branch}")
            .header("Accept", "application/vnd.github.raw+json")
        if (etag != null) request.header("If-None-Match", etag)
        return call(request.build()) { code, bytes, headers ->
            when (code) {
                200 -> RawFile.Found(bytes, headers("ETag"))
                304 -> RawFile.NotModified
                404 -> RawFile.Missing
                else -> throw failure(code, bytes, headers, "ファイル $path")
            }
        }
    }

    // ---- 内部 ----

    private fun getJson(url: String, notFound: String): JsonObject =
        executeJson(Request.Builder().url(url).get(), notFound)

    private fun postJson(url: String, payload: JsonObject): JsonObject =
        executeJson(Request.Builder().url(url).post(payload.toString().toRequestBody(JSON_TYPE)), notFound = "取り込み箱")

    private fun executeJson(builder: Request.Builder, notFound: String): JsonObject =
        call(builder.header("Accept", "application/vnd.github+json").build()) { code, bytes, headers ->
            if (code in 200..299) json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            else throw failure(code, bytes, headers, notFound)
        }

    private fun <T> call(request: Request, handle: (Int, ByteArray, (String) -> String?) -> T): T {
        val full = request.newBuilder()
            .header("Authorization", "Bearer ${config.token}")
            .header("X-GitHub-Api-Version", API_VERSION)
            .header("User-Agent", USER_AGENT)
            .build()
        try {
            http.newCall(full).execute().use { response ->
                val bytes = response.body.bytes()
                return handle(response.code, bytes) { response.header(it) }
            }
        } catch (e: InboxException) {
            throw e
        } catch (e: IOException) {
            throw InboxException.Network(e)
        }
    }

    private fun failure(code: Int, bytes: ByteArray, header: (String) -> String?, notFound: String): InboxException {
        val message = runCatching {
            json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject["message"]?.jsonPrimitive?.content
        }.getOrNull() ?: ""
        val retryAfter = header("Retry-After")?.toLongOrNull()
        val remaining = header("X-RateLimit-Remaining")?.toLongOrNull()
        val reset = header("X-RateLimit-Reset")?.toLongOrNull()
        return when {
            code == 401 -> InboxException.Unauthorized()
            code == 429 || (code == 403 && (retryAfter != null || remaining == 0L || message.contains("rate limit", ignoreCase = true))) -> {
                // 公式の勧め: Retry-After があればその秒数、残り 0 なら reset の時刻まで、どちらも無ければ 1 分以上
                val wait = retryAfter ?: reset?.let { (it - System.currentTimeMillis() / 1000).coerceAtLeast(1) } ?: 60
                InboxException.RateLimited(wait.coerceIn(1, 3600))
            }
            code == 403 -> InboxException.NoPermission(message.ifEmpty { "鍵の対象と権限を確かめてください" })
            code == 404 -> InboxException.NotFound(notFound)
            code >= 500 -> InboxException.Server(code)
            else -> InboxException.Unexpected(code, message.take(200))
        }
    }

    /** blob の本文。大きな写真を何度も写し直さないよう、Base64 にしながら流す。 */
    private class BlobBody(private val bytes: ByteArray) : RequestBody() {
        override fun contentType() = JSON_TYPE

        override fun contentLength(): Long = PREFIX.size + 4L * ((bytes.size + 2) / 3) + SUFFIX.size

        override fun writeTo(sink: BufferedSink) {
            sink.write(PREFIX)
            val encoder = Base64.getEncoder()
            // 3 の倍数ずつ区切れば、途中に詰め物が入らない
            val chunk = 3 * 16 * 1024
            var offset = 0
            while (offset < bytes.size) {
                val end = minOf(bytes.size, offset + chunk)
                sink.write(encoder.encode(bytes.copyOfRange(offset, end)))
                offset = end
            }
            sink.write(SUFFIX)
        }

        companion object {
            private val PREFIX = "{\"encoding\":\"base64\",\"content\":\"".toByteArray()
            private val SUFFIX = "\"}".toByteArray()
        }
    }

    companion object {
        /** 2026-11-20 時点で最新。2022-11-28 との違いは、今回使う範囲には無い */
        const val API_VERSION = "2026-03-10"
        const val USER_AGENT = "reading-capture"
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

object GitBlob {
    /** git が blob に付ける sha（中身から決まる）。 */
    fun sha(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update("blob ${bytes.size}\u0000".toByteArray(Charsets.US_ASCII))
        md.update(bytes)
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
