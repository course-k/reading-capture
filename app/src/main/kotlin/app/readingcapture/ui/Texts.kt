package app.readingcapture.ui

import app.readingcapture.core.detect.EngineStatus
import app.readingcapture.core.quality.PhotoWarnings
import app.readingcapture.core.quality.Side
import app.readingcapture.sync.SyncProblem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 画面の言葉。短く、次にすることが分かる一言にする。 */
object Texts {

    fun status(status: EngineStatus, justCaptured: Boolean): String = when (status) {
        EngineStatus.STARTING -> "準備しています"
        // 動いている・止まりかけている、は同じ言葉にする（手に持つと行き来するので、言葉が変わるとちらつく）
        EngineStatus.MOVING, EngineStatus.SETTLING -> "ページに向けて止めると、撮ります"
        EngineStatus.CAPTURING -> "撮っています"
        EngineStatus.WAITING_NEXT_PAGE -> if (justCaptured) "撮りました。めくってください" else "次のページを待っています"
        EngineStatus.NOT_A_PAGE -> "ページを映してください"
    }

    private fun side(s: Side) = when (s) {
        Side.LEFT -> "左"
        Side.TOP -> "上"
        Side.RIGHT -> "右"
        Side.BOTTOM -> "下"
    }

    /** 知らせの一言。切れ → ぶれ → 暗い の順に、いちばん大事なものを先に言う。 */
    fun warning(w: PhotoWarnings): String {
        val parts = ArrayList<String>()
        if (w.cut.isNotEmpty()) {
            val sides = listOf(Side.LEFT, Side.RIGHT, Side.TOP, Side.BOTTOM).filter { it in w.cut }.joinToString("と") { side(it) }
            parts += "${sides}の端に文字がかかっています"
        }
        if (w.blurred) parts += if (parts.isEmpty()) "ぶれているかもしれません" else "ぶれも"
        if (w.dark) parts += if (parts.isEmpty()) "暗すぎます。ライトを点けるか、明るい場所で" else "暗さも"
        return when (parts.size) {
            0 -> ""
            1 -> parts[0]
            else -> parts[0] + "（" + parts.drop(1).joinToString("・") + "）"
        }
    }

    fun syncProblem(p: SyncProblem): String = when (p) {
        SyncProblem.NO_KEY -> "取り込み箱の鍵がまだ入っていません"
        SyncProblem.BAD_KEY -> "鍵が無効です。入れ直してください"
        SyncProblem.NO_PERMISSION -> "この鍵では取り込み箱に書き込めません"
        SyncProblem.NOT_FOUND -> "取り込み箱が見つかりません"
        SyncProblem.OFFLINE -> "つながっていません。つながったら上げます"
        SyncProblem.BUSY -> "混み合っています。少し待って上げます"
        SyncProblem.OTHER -> "上げられませんでした。あとでもう一度試します"
    }

    private val TIME = DateTimeFormatter.ofPattern("H:mm")
    private val DAY_TIME = DateTimeFormatter.ofPattern("M/d H:mm")

    /** 今日なら時刻だけ、それより前なら日付も。 */
    fun time(ms: Long): String {
        val at = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
        return if (at.toLocalDate() == LocalDate.now()) TIME.format(at) else DAY_TIME.format(at)
    }

    fun startOfToday(): Long = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
