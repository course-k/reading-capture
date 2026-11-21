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

    fun status(status: EngineStatus, justCaptured: Boolean, pageNumber: Int?): String = when (status) {
        EngineStatus.STARTING -> "カメラを準備中"
        // 動いている・止まりかけている、は同じ言葉にする（手に持つと行き来するので、言葉が変わるとちらつく）
        EngineStatus.MOVING, EngineStatus.SETTLING -> "ページに向けて静止すると自動で撮影します"
        EngineStatus.CAPTURING -> "撮影中"
        EngineStatus.WAITING_NEXT_PAGE -> when {
            pageNumber != null -> "$pageNumber ページを撮影しました"
            justCaptured -> "撮影しました"
            else -> "次のページを待機中"
        }
        EngineStatus.NOT_A_PAGE -> "ページを検出できません"
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
            val sides = listOf(Side.LEFT, Side.RIGHT, Side.TOP, Side.BOTTOM).filter { it in w.cut }.joinToString("・") { side(it) }
            parts += "${sides}端で文字が見切れています"
        }
        if (w.blurred) parts += if (parts.isEmpty()) "ブレている可能性があります" else "ブレあり"
        if (w.dark) parts += if (parts.isEmpty()) "暗すぎます。ライトを点けるか、明るい場所で撮影してください" else "暗い"
        return when (parts.size) {
            0 -> ""
            1 -> parts[0]
            else -> parts[0] + "（" + parts.drop(1).joinToString("・") + "）"
        }
    }

    fun syncProblem(p: SyncProblem): String = when (p) {
        SyncProblem.NO_KEY -> "アクセストークンが未設定です"
        SyncProblem.BAD_KEY -> "トークンが無効です。再設定してください"
        SyncProblem.NO_PERMISSION -> "このトークンにはリポジトリへの書き込み権限がありません"
        SyncProblem.NOT_FOUND -> "リポジトリが見つかりません"
        SyncProblem.OFFLINE -> "オフラインです。接続が回復したらアップロードします"
        SyncProblem.BUSY -> "サーバーが混雑しています。しばらくしてからリトライします"
        SyncProblem.OTHER -> "アップロードに失敗しました。後でリトライします"
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
