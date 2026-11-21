package app.readingcapture.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.readingcapture.AppContainer
import app.readingcapture.core.toc.TocNavigator
import app.readingcapture.core.toc.TocPosition

/** 目次から章・節を選ぶ。章の名前の下に、その章の節が並ぶ。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TocSheet(navigator: TocNavigator, current: TocPosition?, title: String?, onDismiss: () -> Unit, onSelect: (TocPosition) -> Unit) {
    val c = LocalColors.current
    // 章の見出しと節を、1 本の並びにする
    val rows = remember(navigator) {
        buildList<Pair<String?, TocPosition?>> {
            navigator.toc.chapters.forEachIndexed { ci, chapter ->
                if (chapter.sections.isEmpty()) add(null to TocPosition(ci, -1))
                else {
                    add(chapter.title to null)
                    chapter.sections.indices.forEach { add(null to TocPosition(ci, it)) }
                }
            }
        }
    }
    val currentIndex = rows.indexOfFirst { it.second != null && it.second == current }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 2).coerceAtLeast(0))
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.bg) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            title?.let { Text(it, style = Type.labelStrong, color = c.text, modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp)) }
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
                itemsIndexed(rows) { _, row ->
                    val (heading, pos) = row
                    if (heading != null) {
                        Text(heading, style = Type.small, color = c.text2, modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 4.dp))
                    } else if (pos != null) {
                        val section = navigator.section(pos)
                        val label = section?.title ?: navigator.chapter(pos).title
                        val page = section?.page ?: navigator.chapter(pos).page
                        ListRow(label, selected = pos == current, trailing = page?.let { "$it" }) { onSelect(pos) }
                    }
                }
                item { Box(Modifier.height(24.dp)) }
            }
        }
    }
}

/** 本を切り替える。設定への入口もここ。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookSheet(container: AppContainer, currentBookId: String, onDismiss: () -> Unit, onSelect: (String) -> Unit, onOpenSettings: () -> Unit) {
    val c = LocalColors.current
    val books by container.books.books.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.bg) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            if (books.isEmpty()) {
                Text(
                    if (container.vault.hasToken) "本が未登録" else "トークン未設定",
                    style = Type.body, color = c.text2, modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
                )
            }
            for (book in books) {
                ListRow(book.title, selected = book.id == currentBookId) { onSelect(book.id) }
            }
            Box(Modifier.height(10.dp))
            message?.let { Text(it, style = Type.small, color = c.text2, modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp)) }
            ListRow("再読み込み") {
                message = "読み込み中…"
                container.refreshBooks(force = true) { result ->
                    message = result.fold(
                        onSuccess = { null },
                        onFailure = { "読み込みに失敗しました" },
                    )
                }
            }
            ListRow("設定") { onOpenSettings() }
        }
    }
}
