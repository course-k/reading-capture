package app.readingcapture.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.readingcapture.AppContainer
import app.readingcapture.core.inbox.InboxLayout
import app.readingcapture.core.toc.BookEntry
import app.readingcapture.core.toc.TocNavigator
import app.readingcapture.core.toc.TocPosition
import app.readingcapture.data.Note
import app.readingcapture.data.NoteState
import app.readingcapture.data.SectionChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * 「話す」。入力欄に音声入力で話し、「残す」で、選んでいる章・節に付けて残す。
 * 章・節は入力欄のすぐ上で選び、変えるまでそのまま。残した言葉は、その節の下にすぐ並ぶ。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SpeakScreen(container: AppContainer, book: BookEntry) {
    val c = LocalColors.current
    val tocVersion by container.books.tocVersion.collectAsStateWithLifecycle()
    val dbVersion by container.db.version.collectAsStateWithLifecycle()

    val navigator = remember(book.id, tocVersion) { container.books.toc(book.id)?.let { TocNavigator(it) }?.takeIf { !it.isEmpty } }
    var position by remember(book.id, tocVersion) {
        val saved = container.settings.section(book.id)
        mutableStateOf(navigator?.let { it.find(saved.chapterId, saved.sectionId) ?: it.first() })
    }
    fun select(p: TocPosition) {
        val nav = navigator ?: return
        position = p
        container.settings.setSection(book.id, SectionChoice(nav.chapter(p).id, nav.section(p)?.id))
    }
    val chapter = position?.let { navigator?.chapter(it) }
    val section = position?.let { navigator?.section(it) }

    val notes by produceState(initialValue = emptyList<Note>(), book.id, chapter?.id, section?.id, dbVersion) {
        value = withContext(Dispatchers.IO) { container.db.notesAt(book.id, chapter?.id, section?.id) }
    }

    // 入力欄の中身は、変わるたびに端末に書く（「残す」の前に閉じても残る）
    var draft by remember(book.id) { mutableStateOf(container.settings.draft(book.id)) }

    var showToc by remember { mutableStateOf(false) }
    var movingNote by remember { mutableStateOf<Note?>(null) }

    Column(Modifier.fillMaxSize()) {
        // この節で話したこと
        val listState = rememberLazyListState()
        LaunchedEffect(notes.size, chapter?.id, section?.id) {
            if (notes.isNotEmpty()) listState.scrollToItem(notes.size)
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 20.dp), state = listState) {
            item {
                Text(
                    if (notes.isEmpty()) "この節で話したことは、ここに並びます" else "この節で話したこと",
                    style = Type.tiny, color = c.text3, modifier = Modifier.padding(top = 14.dp, bottom = 10.dp),
                )
            }
            items(notes, key = { it.id }) { note ->
                var menu by remember { mutableStateOf(false) }
                Box {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .combinedClickable(onClick = {}, onLongClick = { menu = true })
                            .padding(vertical = 7.dp),
                    ) {
                        Text(note.text, style = Type.body, color = c.text)
                        val waiting = note.state == NoteState.PENDING
                        Text(
                            Texts.time(note.spokenAt) + if (waiting) " ・ これから上げます" else "",
                            style = Type.tiny, color = if (waiting) c.accent else c.text3, modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = c.raised) {
                        if (navigator != null) {
                            DropdownMenuItem(text = { Text("節を変える", color = c.text) }, onClick = {
                                menu = false
                                movingNote = note
                            })
                        }
                        DropdownMenuItem(text = { Text("消す", color = c.danger) }, onClick = {
                            menu = false
                            container.db.deleteNote(note.id)
                            container.haptics.tick()
                            container.sync.kick()
                        })
                    }
                }
            }
            item { Box(Modifier.height(8.dp)) }
        }

        // 章・節。入力欄のすぐ上
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            val nav = navigator
            val pos = position
            if (nav != null && pos != null) {
                val prev = nav.previous(pos)
                val next = nav.next(pos)
                TextAction("‹", onClick = { prev?.let { select(it) } }, enabled = prev != null, style = Type.body.copy(fontSize = Type.body.fontSize * 1.5f))
                Row(
                    Modifier.weight(1f).heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp)).quietClickable { showToc = true }.padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        nav.label(pos), style = Type.labelStrong, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                    )
                    Text(" ▾", style = Type.small, color = c.text3)
                }
                TextAction("›", onClick = { next?.let { select(it) } }, enabled = next != null, style = Type.body.copy(fontSize = Type.body.fontSize * 1.5f))
            } else {
                Text(
                    "この本の目次は、まだありません。章・節なしで残します",
                    style = Type.small, color = c.text3, modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                )
            }
        }

        // 入力欄。キーボードの横の音声入力ボタンから話す
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp).clip(RoundedCornerShape(14.dp)).background(c.sunken).padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            BasicTextField(
                value = draft,
                onValueChange = {
                    draft = it
                    container.settings.setDraft(book.id, it)
                },
                textStyle = Type.body.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (draft.isEmpty()) Text("ここに話す", style = Type.body, color = c.text3)
                    inner()
                },
            )
        }
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f))
            PrimaryButton(
                "残す",
                enabled = draft.isNotBlank(),
                onClick = {
                    val now = System.currentTimeMillis()
                    val at = OffsetDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault())
                    val id = "%013d-%04x".format(now, (Math.random() * 0xFFFF).toInt())
                    // 話したままの文字で残す（前後の空白だけ落とす）
                    container.db.insertNote(
                        Note(
                            id = id, bookId = book.id, bookTitle = book.title,
                            chapterId = chapter?.id, chapterTitle = chapter?.title,
                            sectionId = section?.id, sectionTitle = section?.title,
                            text = draft.trim(), spokenAt = now, state = NoteState.PENDING,
                            remotePath = InboxLayout.note(book.id, at), everUploaded = false,
                        ),
                    )
                    draft = ""
                    container.settings.setDraft(book.id, "")
                    container.haptics.tick()
                    container.sync.kick()
                },
            )
        }
    }

    if (showToc && navigator != null) {
        TocSheet(navigator, current = position, title = "章・節を選ぶ", onDismiss = { showToc = false }) {
            select(it)
            showToc = false
        }
    }
    val moving = movingNote
    if (moving != null && navigator != null) {
        TocSheet(navigator, current = navigator.find(moving.chapterId, moving.sectionId), title = "入れる節を選ぶ", onDismiss = { movingNote = null }) { p ->
            val ch = navigator.chapter(p)
            val se = navigator.section(p)
            container.db.moveNote(moving.id, ch.id, ch.title, se?.id, se?.title)
            container.haptics.tick()
            container.sync.kick()
            movingNote = null
        }
    }
}
