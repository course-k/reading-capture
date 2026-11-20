package app.readingcapture.diag

import android.content.Context
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.time.LocalDate
import java.util.concurrent.Executors

/**
 * 検知の合わせ込みに使う記録。1 行 1 件の数値だけで、写真の中身や文字は入れない。
 *
 * 置き場はアプリ専用の領域の `diag/<年-月-日>.jsonl`。Mac から
 * `adb exec-out run-as app.readingcapture cat files/diag/<年-月-日>.jsonl` で取り出して、
 * 撮り逃し・二度撮りが起きたときの数値を見て、しきい値を直す。14 日より古いものは消す。
 */
class Diagnostics(context: Context) {

    private val dir = File(context.filesDir, "diag").apply { mkdirs() }
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "diag").apply { isDaemon = true } }

    init {
        executor.execute {
            val cutoff = LocalDate.now().minusDays(14).toString()
            dir.listFiles()?.filter { it.name < "$cutoff.jsonl" }?.forEach { it.delete() }
        }
    }

    fun log(event: String, vararg fields: Pair<String, Any?>) {
        val now = System.currentTimeMillis()
        executor.execute {
            val map = LinkedHashMap<String, JsonPrimitive>()
            map["t"] = JsonPrimitive(now)
            map["e"] = JsonPrimitive(event)
            for ((k, v) in fields) {
                map[k] = when (v) {
                    null -> continue
                    is Number -> JsonPrimitive(v)
                    is Boolean -> JsonPrimitive(v)
                    else -> JsonPrimitive(v.toString())
                }
            }
            runCatching { File(dir, "${LocalDate.now()}.jsonl").appendText(JsonObject(map).toString() + "\n") }
        }
    }
}
