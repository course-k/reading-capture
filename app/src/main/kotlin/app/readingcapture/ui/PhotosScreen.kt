package app.readingcapture.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.readingcapture.AppContainer
import app.readingcapture.data.Photo
import app.readingcapture.data.PhotoState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** この本の、今日の写真。上がったもの・まだのものが分かる。押すと大きく見られる。 */
@Composable
fun PhotosScreen(container: AppContainer, bookId: String, onBack: () -> Unit) {
    val c = LocalColors.current
    val dbVersion by container.db.version.collectAsStateWithLifecycle()
    val photos by produceState(initialValue = emptyList<Photo>(), bookId, dbVersion) {
        value = withContext(Dispatchers.IO) { container.db.photosOfBook(bookId, Texts.startOfToday()) }
    }
    var openId by remember { mutableStateOf<String?>(null) }
    val open = photos.firstOrNull { it.id == openId }

    if (open != null) {
        PhotoViewer(container, open, onBack = { openId = null })
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextAction("‹ 戻る", onClick = onBack)
            Text("今日の写真 ${photos.size} 枚", style = Type.labelStrong, color = c.text, modifier = Modifier.padding(start = 6.dp))
        }
        if (photos.isEmpty()) {
            Text("今日は、まだ撮っていません", style = Type.body, color = c.text3, modifier = Modifier.padding(22.dp))
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(photos, key = { it.id }) { photo ->
                val image by rememberImage(container.store.thumbFile(photo.id), key = photo.state)
                val warned = photo.warnings.any
                Column(Modifier.quietClickable { openId = photo.id }) {
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(8.dp)).background(c.sunken)
                            .border(if (warned) 2.dp else 1.dp, if (warned) c.warn else c.line, RoundedCornerShape(8.dp)),
                    ) {
                        image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    }
                    val uploaded = photo.state == PhotoState.UPLOADED
                    Text(
                        Texts.time(photo.capturedAt) + if (uploaded) " ・ 上げた" else " ・ まだ",
                        style = Type.tiny, color = if (uploaded) c.text3 else c.accent, modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/** 1 枚を大きく見る。2 本指で拡大して、欄外まで入っているかを確かめられる。 */
@Composable
private fun PhotoViewer(container: AppContainer, photo: Photo, onBack: () -> Unit) {
    val c = LocalColors.current
    val file = photo.fileName?.let { container.store.photoFile(it) }?.takeIf { it.exists() } ?: container.store.thumbFile(photo.id)
    val image by rememberImage(file, key = photo.state, maxSide = 2400)
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextAction("‹ 戻る", onClick = onBack)
            Box(Modifier.weight(1f))
            TextAction("この 1 枚を消す", onClick = {
                container.capture.delete(photo)
                onBack()
            }, color = c.danger)
        }
        if (photo.warnings.any) {
            Text(Texts.warning(photo.warnings), style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.warn, modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp))
        }
        Box(
            Modifier.fillMaxSize().padding(10.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF0C0E10))
                .pointerInput(photo.id) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        offset = if (scale == 1f) Offset.Zero else offset + pan
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            image?.let {
                Image(
                    it, contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
                )
            }
        }
    }
}
