package app.readingcapture.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 色の決まり。白・黒・グレーを基調に、差し色は青 1 色。知らせ（切れ・ぶれ・暗い）だけが別の色。
 * 見本は docs/design/mockup.html。
 */
data class AppColors(
    val bg: Color,
    /** 入力欄・切り替えの、ごく薄い面 */
    val sunken: Color,
    val line: Color,
    val text: Color,
    /** 補足の文字 */
    val text2: Color,
    /** さらに控えめな文字 */
    val text3: Color,
    /** 差し色。「いま押すもの」と「これから上がるもの」にだけ使う */
    val accent: Color,
    val accentBg: Color,
    val onAccent: Color,
    val warn: Color,
    val warnBg: Color,
    val danger: Color,
    /** 切り替えの、選ばれている側の面 */
    val raised: Color,
    val isDark: Boolean,
)

val LightColors = AppColors(
    bg = Color(0xFFFFFFFF), sunken = Color(0xFFF4F6F8), line = Color(0xFFE3E8ED),
    text = Color(0xFF10161C), text2 = Color(0xFF5F6B76), text3 = Color(0xFF94A0AB),
    accent = Color(0xFF3D8FD1), accentBg = Color(0xFFE9F3FB), onAccent = Color(0xFFFFFFFF),
    warn = Color(0xFFB96F12), warnBg = Color(0xFFFBF2E3), danger = Color(0xFFC9544A),
    raised = Color(0xFFFFFFFF), isDark = false,
)

val DarkColors = AppColors(
    bg = Color(0xFF111417), sunken = Color(0xFF1A1E22), line = Color(0xFF2A3036),
    text = Color(0xFFE8EDF1), text2 = Color(0xFF9AA6B1), text3 = Color(0xFF6B7680),
    accent = Color(0xFF6FB3E8), accentBg = Color(0xFF17304A), onAccent = Color(0xFF0B1620),
    warn = Color(0xFFE2A34D), warnBg = Color(0xFF33270F), danger = Color(0xFFE58278),
    raised = Color(0xFF262C32), isDark = true,
)

val LocalColors = staticCompositionLocalOf { LightColors }

/** 文字の決まり。書体は端末の標準。行間は広め。 */
object Type {
    val body = TextStyle(fontSize = 16.sp, lineHeight = 27.sp)
    val bodyStrong = body.copy(fontWeight = FontWeight.SemiBold)
    val label = TextStyle(fontSize = 15.sp, lineHeight = 22.sp)
    val labelStrong = label.copy(fontWeight = FontWeight.SemiBold)
    val small = TextStyle(fontSize = 13.sp, lineHeight = 19.sp)
    val tiny = TextStyle(fontSize = 12.sp, lineHeight = 17.sp)
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    val scheme = if (colors.isDark) {
        darkColorScheme(primary = colors.accent, onPrimary = colors.onAccent, background = colors.bg, surface = colors.bg, onBackground = colors.text, onSurface = colors.text, surfaceContainerLow = colors.bg, surfaceContainer = colors.sunken, outline = colors.line, error = colors.danger)
    } else {
        lightColorScheme(primary = colors.accent, onPrimary = colors.onAccent, background = colors.bg, surface = colors.bg, onBackground = colors.text, onSurface = colors.text, surfaceContainerLow = colors.bg, surfaceContainer = colors.sunken, outline = colors.line, error = colors.danger)
    }
    CompositionLocalProvider(LocalColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
