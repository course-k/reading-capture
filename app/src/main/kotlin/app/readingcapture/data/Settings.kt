package app.readingcapture.data

import android.content.Context
import app.readingcapture.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** いま選んでいる章・節。 */
data class SectionChoice(val chapterId: String?, val sectionId: String?)

/**
 * 小さな覚え書き。選んでいる本、本ごとの章・節、入力欄の下書き、取り込み箱の名前。
 * 「変えるまでそのまま」「閉じても残る」を守るため、変わるたびにすぐ書く。
 */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _currentBookId = MutableStateFlow(prefs.getString(KEY_BOOK, null))
    val currentBookId: StateFlow<String?> = _currentBookId

    fun setCurrentBook(id: String) {
        prefs.edit().putString(KEY_BOOK, id).apply()
        _currentBookId.value = id
    }

    /** 取り込み箱の「持ち主/リポジトリ」。 */
    var inboxRepo: String
        get() = prefs.getString(KEY_REPO, null) ?: BuildConfig.DEFAULT_INBOX_REPO
        set(value) = prefs.edit().putString(KEY_REPO, value.trim()).apply()

    fun section(bookId: String): SectionChoice =
        SectionChoice(prefs.getString("$KEY_CHAPTER$bookId", null), prefs.getString("$KEY_SECTION$bookId", null))

    fun setSection(bookId: String, choice: SectionChoice) {
        prefs.edit()
            .putString("$KEY_CHAPTER$bookId", choice.chapterId)
            .putString("$KEY_SECTION$bookId", choice.sectionId)
            .apply()
    }

    fun draft(bookId: String): String = prefs.getString("$KEY_DRAFT$bookId", "") ?: ""

    /** 入力のたびに呼ぶ。「残す」を押す前にアプリが落ちても残るよう、すぐ書き込む。 */
    fun setDraft(bookId: String, text: String) {
        prefs.edit().putString("$KEY_DRAFT$bookId", text).apply()
    }

    fun etag(path: String): String? = prefs.getString("$KEY_ETAG$path", null)

    fun setEtag(path: String, etag: String?) {
        prefs.edit().putString("$KEY_ETAG$path", etag).apply()
    }

    var torchOn: Boolean
        get() = prefs.getBoolean(KEY_TORCH, false)
        set(value) = prefs.edit().putBoolean(KEY_TORCH, value).apply()

    var showNumbers: Boolean
        get() = prefs.getBoolean(KEY_NUMBERS, false)
        set(value) = prefs.edit().putBoolean(KEY_NUMBERS, value).apply()

    private companion object {
        const val KEY_BOOK = "book"
        const val KEY_REPO = "repo"
        const val KEY_CHAPTER = "chapter:"
        const val KEY_SECTION = "section:"
        const val KEY_DRAFT = "draft:"
        const val KEY_ETAG = "etag:"
        const val KEY_TORCH = "torch"
        const val KEY_NUMBERS = "numbers"
    }
}
