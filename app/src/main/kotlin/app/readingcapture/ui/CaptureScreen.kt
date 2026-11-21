package app.readingcapture.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.readingcapture.AppContainer
import app.readingcapture.core.inbox.PhotoKind
import app.readingcapture.core.quality.Side
import app.readingcapture.data.Photo
import app.readingcapture.data.PhotoState
import app.readingcapture.data.SyncCounts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 「撮る」。カメラの映像が主役。重ねるのは状態の一言だけ。 */
@Composable
fun CaptureScreen(container: AppContainer, bookId: String, counts: SyncCounts, onOpenPhotos: () -> Unit) {
    val c = LocalColors.current
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    // 撮っているあいだは画面を消さない
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    if (!granted) {
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("ページを撮影するには、カメラへのアクセスを許可してください", style = Type.bodyStrong, color = c.text)
            Spacer(Modifier.height(8.dp))
            Text("写真は、端末内と自分の GitHub リポジトリにのみ保存されます。", style = Type.small, color = c.text2)
            Spacer(Modifier.height(24.dp))
            PrimaryButton("カメラへのアクセスを許可", onClick = { launcher.launch(Manifest.permission.CAMERA) })
        }
        return
    }

    val ui by container.capture.ui.collectAsStateWithLifecycle()
    val dbVersion by container.db.version.collectAsStateWithLifecycle()
    val today = remember(dbVersion) { Texts.startOfToday() }
    val summary by produceState(initialValue = CaptureSummary(0, null), bookId, dbVersion) {
        value = withContext(Dispatchers.IO) {
            CaptureSummary(
                count = container.db.countPhotosOfBook(bookId, today),
                last = container.db.photosOfBook(bookId, today, limit = 1).firstOrNull(),
            )
        }
    }
    val last = summary.last
    val lastWarnings = last?.warnings?.takeIf { it.any }

    // 「消す」「撮り直す」を押すと、直前の 1 枚が 1 つ前の写真に入れ替わる。
    // 続けて押した分でそれまで消してしまわないよう、押したあと少しのあいだは受け付けない
    var lastRemoveAt by remember { mutableStateOf(0L) }
    var removeLocked by remember { mutableStateOf(false) }
    LaunchedEffect(lastRemoveAt) {
        if (lastRemoveAt > 0) {
            removeLocked = true
            delay(1500)
            removeLocked = false
        }
    }

    // 「撮りました」は少しのあいだだけ出す
    var justCaptured by remember { mutableStateOf(false) }
    LaunchedEffect(ui.capturedAtMs) {
        if (ui.capturedAtMs > 0 && SystemClock.elapsedRealtime() - ui.capturedAtMs < 2500) {
            justCaptured = true
            delay(2500)
            justCaptured = false
        }
    }
    val flash = remember { Animatable(0f) }
    LaunchedEffect(ui.capturedAtMs) {
        if (ui.capturedAtMs > 0 && SystemClock.elapsedRealtime() - ui.capturedAtMs < 500) {
            flash.snapTo(0.45f)
            flash.animateTo(0f, tween(320))
        }
    }

    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
            // 映像は 3:4 のまま、切らずに全部見せる（見えている範囲が、そのまま写真になる）
            val reserved = 176.dp + if (lastWarnings != null) 60.dp else 0.dp
            val maxPreviewHeight = (maxHeight - reserved).coerceAtLeast(200.dp)
            val widthByHeight = maxPreviewHeight * 3f / 4f
            val previewWidth = minOf(maxWidth - 20.dp, widthByHeight)
            val previewHeight = previewWidth * 4f / 3f

            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(previewWidth, previewHeight).clip(RoundedCornerShape(14.dp)).background(Color(0xFF0C0E10)),
                ) {
                    CameraPreview(container)
                    // 撮れた瞬間に、ごく短く光らせる
                    if (flash.value > 0f) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flash.value)))
                    // 切れている側の端を色で示す
                    if (lastWarnings != null) EdgeMarks(lastWarnings.cut, c.warn)
                    StatusChip(
                        text = ui.cameraError ?: Texts.status(ui.status, justCaptured, ui.pageNumber),
                        dot = if (justCaptured && lastWarnings != null) Color(0xFFF0B35C) else Color(0xFF7FBDEE),
                        modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
                    )
                    ui.numbers?.let {
                        Text(
                            it, color = Color.White, fontFamily = FontFamily.Monospace, style = Type.tiny,
                            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }

                if (lastWarnings != null && last != null) {
                    Row(
                        Modifier.padding(top = 10.dp).width(previewWidth).clip(RoundedCornerShape(12.dp)).background(c.warnBg).padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(Texts.warning(lastWarnings), style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.warn, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        OutlineButton("撮り直す", onClick = {
                            if (!removeLocked) {
                                lastRemoveAt = SystemClock.elapsedRealtime()
                                container.capture.retake(last)
                            }
                        }, color = c.warn)
                    }
                }

                // 操作。台に置いたまま片手で押せる大きさにする
                Row(
                    Modifier.fillMaxWidth().weight(1f).padding(horizontal = 22.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        LastThumb(container, last, summary.count, warned = lastWarnings != null, onClick = onOpenPhotos)
                        if (last != null && last.kind == PhotoKind.CAMERA) {
                            TextAction("削除", onClick = {
                                lastRemoveAt = SystemClock.elapsedRealtime()
                                container.capture.deleteLast(last)
                            }, modifier = Modifier.padding(start = 6.dp), enabled = !removeLocked)
                        }
                    }
                    Shutter(enabled = ui.cameraReady, onClick = { container.capture.shutter() })
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        if (ui.hasTorch) {
                            TextAction(
                                if (ui.torchOn) "ライト ON" else "ライト",
                                onClick = { container.capture.toggleTorch() },
                                color = if (ui.torchOn) c.accent else c.text2,
                            )
                        }
                    }
                }
            }
        }
        // 上がったもの・まだのもの
        val footer = buildString {
            if (counts.uploadedToday > 0) append("アップロード済み ${counts.uploadedToday}")
            if (counts.pending > 0) {
                if (isNotEmpty()) append(" ・ ")
                append("未アップロード ${counts.pending}")
            }
        }
        Text(
            footer, style = Type.tiny, color = c.text3,
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).height(18.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

private data class CaptureSummary(val count: Int, val last: Photo?)

@Composable
private fun CameraPreview(container: AppContainer) {
    val owner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val previewView = remember {
        PreviewView(context).apply {
            // 映像を切らずに全部見せる
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }
    DisposableEffect(owner) {
        container.capture.bind(owner, previewView)
        onDispose { container.capture.unbind() }
    }
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

@Composable
private fun StatusChip(text: String, dot: Color, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(Color(0xA60C0E10)).padding(start = 10.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(7.dp))
        Text(text, style = Type.small, color = Color.White)
    }
}

@Composable
private fun EdgeMarks(sides: Set<Side>, color: Color) {
    val bar = 6.dp
    Box(Modifier.fillMaxSize()) {
        if (Side.LEFT in sides) Box(Modifier.align(Alignment.CenterStart).width(bar).fillMaxSize().background(color))
        if (Side.RIGHT in sides) Box(Modifier.align(Alignment.CenterEnd).width(bar).fillMaxSize().background(color))
        if (Side.TOP in sides) Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(bar).background(color))
        if (Side.BOTTOM in sides) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(bar).background(color))
    }
}

@Composable
private fun LastThumb(container: AppContainer, last: Photo?, count: Int, warned: Boolean, onClick: () -> Unit) {
    val c = LocalColors.current
    // 仕上げが済むと小さな写しができるので、状態が変わったら読み直す
    val image by rememberImage(last?.let { container.store.thumbFile(it.id) }, key = last?.state)
    Box(Modifier.size(width = 58.dp, height = 72.dp).quietClickable(onClick), contentAlignment = Alignment.BottomStart) {
        Box(
            Modifier.size(width = 48.dp, height = 64.dp).clip(RoundedCornerShape(6.dp)).background(c.sunken)
                .border(if (warned) 2.dp else 1.dp, if (warned) c.warn else c.line, RoundedCornerShape(6.dp)),
        ) {
            image?.let { Image(it, contentDescription = "直前の写真", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        if (count > 0) {
            Box(
                Modifier.align(Alignment.TopEnd).offset(x = 0.dp, y = 0.dp).clip(RoundedCornerShape(50)).background(c.text).padding(horizontal = 7.dp, vertical = 1.dp),
            ) {
                Text("$count", style = Type.tiny.copy(fontWeight = FontWeight.SemiBold), color = c.bg)
            }
        }
        // まだ上がっていない印
        if (last != null && last.state != PhotoState.UPLOADED) {
            Box(Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 2.dp).size(8.dp).clip(CircleShape).background(c.accent))
        }
    }
}

@Composable
private fun Shutter(enabled: Boolean, onClick: () -> Unit) {
    val c = LocalColors.current
    val color = if (enabled) c.text else c.text3
    Box(
        Modifier.size(76.dp).clip(CircleShape).border(3.dp, color, CircleShape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(60.dp).clip(CircleShape).background(color))
    }
}
