package app.readingcapture.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 文字だけのボタン。台に置いたまま片手で押せるよう、押せる範囲は 48dp 以上取る。 */
@Composable
fun TextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = LocalColors.current.text2,
    style: TextStyle = Type.label,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = style, color = if (enabled) color else LocalColors.current.text3)
    }
}

/** 丸い、塗りのボタン。画面に 1 つだけの「いま押すもの」に使う。 */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = LocalColors.current
    Box(
        modifier
            .defaultMinSize(minWidth = 96.dp, minHeight = 46.dp)
            .clip(RoundedCornerShape(50))
            .background(if (enabled) c.accent else c.sunken)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 26.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Type.bodyStrong, color = if (enabled) c.onAccent else c.text3)
    }
}

/** 輪郭だけのボタン。 */
@Composable
fun OutlineButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = LocalColors.current.text) {
    Box(
        modifier
            .defaultMinSize(minHeight = 40.dp)
            .clip(RoundedCornerShape(50))
            .border(1.dp, color, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = color)
    }
}

/** 一覧の 1 行。 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    trailing: String? = null,
    onClick: () -> Unit,
) {
    val c = LocalColors.current
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) c.accentBg else Color.Transparent)
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = 52.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = if (selected) Type.bodyStrong else Type.body,
            color = c.text,
            modifier = Modifier.weight(1f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (trailing != null) Text(trailing, style = Type.small, color = c.text3, modifier = Modifier.padding(start = 12.dp))
    }
}

/** 波紋を出さない押下。映像の上など、静かにしたいところに使う。 */
@Composable
fun Modifier.quietClickable(onClick: () -> Unit): Modifier =
    clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)

/** ファイルの画像を読む。[key] が変わったら読み直す。まだ無ければ null。 */
@Composable
fun rememberImage(file: File?, key: Any?, maxSide: Int = 0): State<ImageBitmap?> =
    produceState<ImageBitmap?>(initialValue = null, file?.path, key) {
        value = if (file == null) null else withContext(Dispatchers.IO) {
            if (!file.exists()) null else runCatching {
                val opts = BitmapFactory.Options()
                if (maxSide > 0) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.path, bounds)
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
                    opts.inSampleSize = sample
                }
                BitmapFactory.decodeFile(file.path, opts)?.asImageBitmap()
            }.getOrNull()
        }
    }
