package top.qiuli55.flux.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Flux 固定深色配色：不跟随系统浅色（产品只有这一个主题，和桌面端一致）。 */
private val FluxDarkScheme = darkColorScheme(
    primary = FluxColors.accent,
    onPrimary = FluxColors.text,
    primaryContainer = FluxColors.accent2,
    onPrimaryContainer = FluxColors.text,
    secondary = FluxColors.blue,
    onSecondary = FluxColors.text,
    background = FluxColors.bg,
    onBackground = FluxColors.text,
    surface = FluxColors.panel,
    onSurface = FluxColors.text,
    surfaceVariant = FluxColors.panel2,
    onSurfaceVariant = FluxColors.text2,
    outline = FluxColors.line,
    outlineVariant = FluxColors.lineSoft,
    error = FluxColors.red,
    onError = FluxColors.text,
)

/**
 * 字号层级刻意收敛为 5 档（12 / 13 / 15 / 18 / 22），
 * 对应桌面端 13px 基准与"减字号种类"的降噪要求。
 */
private val FluxTypography = Typography(
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, color = FluxColors.text2),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 19.sp, color = FluxColors.text),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, color = FluxColors.text),
    titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium),
    titleLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, color = FluxColors.text3),
)

/** 等宽字体：终端输出、Diff、文件路径、UUID 都用它（可辨识度优先）。 */
val FluxMono = FontFamily.Monospace

@Composable
fun FluxTheme(content: @Composable () -> Unit) {
    // isSystemInDarkTheme() 不参与分支：本主题只有深色一种，读它是为了让调用方明确这一点。
    @Suppress("UNUSED_EXPRESSION") isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = FluxDarkScheme,
        typography = FluxTypography,
        content = content,
    )
}