package app.readingcapture.ui

import app.readingcapture.core.detect.EngineStatus
import app.readingcapture.core.quality.PhotoWarnings
import app.readingcapture.core.quality.Side
import app.readingcapture.sync.SyncProblem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 画面の文言。状態・操作のラベル・エラーだけを出し、使い方の説明は出さない。 */
object Texts {

    /** 撮影画面の状態表示。空文字のときは何も出さない。 */
    fun status(status: EngineStatus, pageNumber: Int?): String = when (status) {
        // いま向けているページは撮影済み（めくるまで撮影しない）
        EngineStatus.WAITING_NEXT_PAGE -> if (pageNumber != null) "p.$pageNumber 撮影済み" else "撮影済み"
        // 自動撮影しない理由
        EngineStatus.NOT_A_PAGE -> "ページを検出できません"
        else -> ""
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
            parts += "見切れ（$sides）"
        }
        if (w.blurred) parts += "ブレ"
        if (w.dark) parts += "暗い"
        return parts.joinToString("・")
    }

    fun syncProblem(p: SyncProblem): String = when (p) {
        SyncProblem.NO_KEY -> "アクセストークンが未設定です"
        SyncProblem.BAD_KEY -> "トークンが無効です"
        SyncProblem.NO_PERMISSION -> "トークンに書き込み権限がありません"
        SyncProblem.NOT_FOUND -> "リポジトリが見つかりません"
        SyncProblem.OFFLINE -> "オフライン"
        SyncProblem.BUSY -> "サーバーが混雑中（自動でリトライ）"
        SyncProblem.OTHER -> "アップロード失敗（自動でリトライ）"
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
