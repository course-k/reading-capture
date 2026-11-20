package app.readingcapture.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.readingcapture.AppContainer
import app.readingcapture.data.BookRepository
import app.readingcapture.data.SyncCounts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class Tab { CAPTURE, SPEAK }

/** 上に重ねて開いている画面。 */
enum class Overlay { NONE, BOOKS, SETTINGS, PHOTOS }

/**
 * 画面の土台。上に本の名前、その下に「撮る」「話す」の切り替え。
 * 上から下へ積むだけの単純な並びにしてある。
 */
@Composable
fun AppRoot(container: AppContainer, tab: Tab, onTab: (Tab) -> Unit, overlay: Overlay, onOverlay: (Overlay) -> Unit) {
    val c = LocalColors.current
    val books by container.books.books.collectAsStateWithLifecycle()
    val selectedId by container.settings.currentBookId.collectAsStateWithLifecycle()
    val book = books.firstOrNull { it.id == selectedId } ?: books.firstOrNull() ?: BookRepository.UNSORTED
    val dbVersion by container.db.version.collectAsStateWithLifecycle()
    val status by container.syncRunner.status.collectAsStateWithLifecycle()
    val counts by produceState(initialValue = SyncCounts(0, 0), dbVersion) {
        value = withContext(Dispatchers.IO) { container.db.syncCounts(Texts.startOfToday()) }
    }

    BackHandler(enabled = overlay != Overlay.NONE) { onOverlay(Overlay.NONE) }

    Box(Modifier.fillMaxSize().background(c.bg).systemBarsPadding().imePadding()) {
        when (overlay) {
            Overlay.SETTINGS -> SettingsScreen(container, counts, onBack = { onOverlay(Overlay.NONE) })
            Overlay.PHOTOS -> PhotosScreen(container, book.id, onBack = { onOverlay(Overlay.NONE) })
            else -> Column(Modifier.fillMaxSize()) {
                // 本の名前。押すと本を切り替えられる
                Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).quietClickable { onOverlay(Overlay.BOOKS) }.padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(book.title, style = Type.bodyStrong, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Text(" ▾", style = Type.small, color = c.text3)
                    }
                    // 上げる状況。押すと設定（鍵・上げる状況）へ
                    val problem = status.problem
                    val (label, color) = when {
                        problem != null && (counts.pending > 0 || problem.blocksOwner()) -> problemLabel(problem) to c.warn
                        counts.pending > 0 -> "まだ ${counts.pending}" to c.accent
                        counts.uploadedToday > 0 -> "上げ終わり" to c.text2
                        else -> "" to c.text2
                    }
                    if (label.isNotEmpty()) TextAction(label, onClick = { onOverlay(Overlay.SETTINGS) }, color = color, style = Type.small)
                }
                TabSwitch(tab, onTab)
                when (tab) {
                    Tab.CAPTURE -> CaptureScreen(container, book.id, counts, onOpenPhotos = { onOverlay(Overlay.PHOTOS) })
                    Tab.SPEAK -> SpeakScreen(container, book)
                }
            }
        }
    }

    if (overlay == Overlay.BOOKS) {
        BookSheet(
            container, currentBookId = book.id,
            onDismiss = { onOverlay(Overlay.NONE) },
            onSelect = { id ->
                if (id != container.settings.currentBookId.value) {
                    container.settings.setCurrentBook(id)
                    container.capture.onBookChanged()
                }
                onOverlay(Overlay.NONE)
            },
            onOpenSettings = { onOverlay(Overlay.SETTINGS) },
        )
    }
}

private fun app.readingcapture.sync.SyncProblem.blocksOwner() = when (this) {
    app.readingcapture.sync.SyncProblem.NO_KEY, app.readingcapture.sync.SyncProblem.BAD_KEY,
    app.readingcapture.sync.SyncProblem.NO_PERMISSION, app.readingcapture.sync.SyncProblem.NOT_FOUND,
    -> true
    else -> false
}

private fun problemLabel(p: app.readingcapture.sync.SyncProblem) = when (p) {
    app.readingcapture.sync.SyncProblem.NO_KEY -> "鍵がまだ"
    app.readingcapture.sync.SyncProblem.BAD_KEY -> "鍵が無効"
    app.readingcapture.sync.SyncProblem.NO_PERMISSION, app.readingcapture.sync.SyncProblem.NOT_FOUND -> "上げられません"
    app.readingcapture.sync.SyncProblem.OFFLINE -> "つながり待ち"
    else -> "少し待っています"
}

@Composable
private fun TabSwitch(tab: Tab, onTab: (Tab) -> Unit) {
    val c = LocalColors.current
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 10.dp).clip(RoundedCornerShape(14.dp)).background(c.sunken).padding(3.dp)) {
        for ((t, label) in listOf(Tab.CAPTURE to "撮る", Tab.SPEAK to "話す")) {
            val on = tab == t
            Box(
                Modifier.weight(1f)
                    .then(if (on && !c.isDark) Modifier.shadow(1.dp, RoundedCornerShape(11.dp)) else Modifier)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (on) c.raised else androidx.compose.ui.graphics.Color.Transparent)
                    .quietClickable { onTab(t) }
                    .heightIn(min = 42.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = if (on) Type.labelStrong else Type.label, color = if (on) c.text else c.text2)
            }
        }
    }
}
