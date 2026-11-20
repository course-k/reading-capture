package app.readingcapture.core.inbox

/** 取り込み箱に置くもの 1 つ。 */
class UploadItem(
    /** 呼び出し側が結果を突き合わせるための名前（写真や言葉の id） */
    val key: String,
    /** 取り込み箱の中のパス */
    val path: String,
    /** 中身を読む。大きいので、要るときに 1 つずつ読む */
    val load: () -> ByteArray,
    /** 前回の途中まで進んでいて、中身（blob）だけ上げ終わっているときの sha */
    val knownBlobSha: String? = null,
)

/** 取り込み箱から消すもの 1 つ。 */
class DeleteItem(val key: String, val path: String)

/**
 * @property doneKeys 取り込み箱がこの状態になったと確かめられたもの（置いた・消した）
 * @property commitSha 新しく作ったコミット。もう全部その状態だったら null
 */
data class UploadResult(val doneKeys: Set<String>, val commitSha: String?)

/**
 * 写真と言葉を、まとめて 1 コミットで取り込み箱に置く。
 *
 * 途中で切れても、やり直せば同じ結果になるようにしてある:
 * - 中身（blob）の名前は中身から決まるので、上げ直しても増えない
 * - 置く前に取り込み箱の今の中身を見て、もう同じものがあれば何もしない
 *   （「置けたのに返事が届かなかった」あとにやり直しても、二重に置かない）
 * - ブランチは上書きしない。Mac 側が先に進めていたら、その上に載せ直す
 */
class InboxUploader(
    private val client: GitHubClient,
    private val maxAttempts: Int = 5,
    /** 書き込みの呼び出しのあいだにあける時間。公式の勧めは 1 秒以上 */
    private val pauseBetweenWritesMs: Long = 1000,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {

    /**
     * @param onBlobUploaded 中身を 1 つ上げ終えるたびに呼ぶ（sha を覚えておけば、やり直しで上げ直さずに済む）
     * @throws InboxException つながらない、鍵が無効、など。やり直せば続きから進む
     */
    fun upload(
        puts: List<UploadItem>,
        deletes: List<DeleteItem>,
        message: String,
        onBlobUploaded: (key: String, sha: String) -> Unit = { _, _ -> },
    ): UploadResult {
        if (puts.isEmpty() && deletes.isEmpty()) return UploadResult(emptySet(), null)

        var head = client.head()
        var files = client.listFiles(head.treeSha)

        // 中身を上げる（もう取り込み箱にあるもの、上げ終わっているものは飛ばす）
        val blobShas = HashMap<String, String>()
        val reused = HashSet<String>()
        for (item in puts) {
            val bytes = item.load()
            val sha = GitBlob.sha(bytes)
            when {
                files[item.path] == sha -> Unit
                item.knownBlobSha == sha -> reused += item.key
                else -> {
                    client.createBlob(bytes)
                    onBlobUploaded(item.key, sha)
                    pause()
                }
            }
            blobShas[item.key] = sha
        }

        var reuploaded = false
        var attempt = 0
        while (true) {
            attempt++
            val changes = ArrayList<TreeChange>()
            for (item in puts) {
                val sha = blobShas.getValue(item.key)
                if (files[item.path] != sha) changes += TreeChange(item.path, sha)
            }
            for (item in deletes) {
                // 無いものを消そうとするとエラーになるので、あるものだけ
                if (files.containsKey(item.path)) changes += TreeChange(item.path, null)
            }
            val allKeys = puts.mapTo(HashSet()) { it.key }.apply { deletes.forEach { add(it.key) } }
            if (changes.isEmpty()) return UploadResult(allKeys, null)

            val treeSha = try {
                client.createTree(head.treeSha, changes)
            } catch (e: InboxException.Unexpected) {
                // 前に上げておいた中身が、使われないまま片付けられていた場合。上げ直して一度だけやり直す
                if (e.code != 422 || reused.isEmpty() || reuploaded) throw e
                for (item in puts) if (item.key in reused) {
                    client.createBlob(item.load())
                    onBlobUploaded(item.key, blobShas.getValue(item.key))
                    pause()
                }
                reuploaded = true
                continue
            }
            pause()
            if (treeSha == head.treeSha) return UploadResult(allKeys, null)
            val commitSha = client.createCommit(message, treeSha, head.commitSha)
            pause()
            if (client.advanceBranch(commitSha)) return UploadResult(allKeys, commitSha)

            // ほかの誰かが先にブランチを進めていた。今の中身を取り直して、その上に載せ直す
            if (attempt >= maxAttempts) throw InboxException.Unexpected(422, "取り込み箱がほかから続けて書き換えられていて、載せられません")
            pause()
            head = client.head()
            files = client.listFiles(head.treeSha)
        }
    }

    private fun pause() {
        if (pauseBetweenWritesMs > 0) sleep(pauseBetweenWritesMs)
    }
}
