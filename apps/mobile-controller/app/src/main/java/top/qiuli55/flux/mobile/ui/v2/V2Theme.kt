package top.qiuli55.flux.mobile.ui.v2

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * Flux 移动控制台 v2 设计令牌。
 *
 * 单色体系：浅色是「白底 + 墨黑」，深色是「近黑底 + 白」。主色 [accent] 就是墨色本身
 * （浅色=黑、深色=白），因此所有按钮/选中态直接黑白互换，不再出现蓝紫青。
 * 琥珀/玫红/翠绿仅保留给状态语义（警告/错误/成功），不参与品牌色。
 *
 * 深浅色由 [FluxV2Theme] 按系统设置注入，各 token 用 getter 按 [isDark] 分支取色。
 */
object V2Colors {
    private var dark by mutableStateOf(false)

    /** 当前是否深色，供需要分支取色的组件判断。 */
    val isDark: Boolean get() = dark

    internal fun applyDark(value: Boolean) {
        dark = value
    }

    val bg: Color get() = if (dark) Color(0xFF050506) else Color(0xFFF4F4F5)
    val shellTop: Color get() = if (dark) Color(0xFF0B0B0C) else Color(0xFFFFFFFF)
    val shellBottom: Color get() = if (dark) Color(0xFF070708) else Color(0xFFF7F7F8)

    /** 纯白，只用于彩色底上的前景文字/图标，不跟随主题。 */
    val white = Color(0xFFFFFFFF)

    /** 卡片、弹层的实底。 */
    val surface: Color get() = if (dark) Color(0xFF161618) else Color(0xFFFFFFFF)

    /**
     * 半透明玻璃面板：浅色是白底叠层，深色改成更淡的白叠层，叠加后同样是「比背景亮一点」。
     */
    fun surface(alpha: Float): Color =
        if (dark) Color(0xFFF4F4F5).copy(alpha = alpha * .09f) else Color.White.copy(alpha = alpha)

    /** 正文墨色：浅色近黑，深色近白。 */
    val ink: Color get() = if (dark) Color(0xFFF4F4F5) else Color(0xFF0A0A0B)

    fun ink(alpha: Float): Color = ink.copy(alpha = alpha)

    val line: Color get() = if (dark) Color(0x24F4F4F5) else Color(0x170A0A0B)
    val lineSoft: Color get() = if (dark) Color(0x12F4F4F5) else Color(0x0F0A0A0B)

    /** 主色 = 墨色：浅色下是黑，深色下是白。旧 token 名保留，语义统一到单色。 */
    val accent: Color get() = if (dark) Color(0xFFF4F4F5) else Color(0xFF111112)
    val cyan500: Color get() = accent
    val cyan700: Color get() = if (dark) Color(0xFFD4D4D8) else Color(0xFF3F3F46)
    val cyan800: Color get() = if (dark) Color(0xFFFAFAFA) else Color(0xFF18181B)
    val cyan400: Color get() = if (dark) Color(0xFFE4E4E7) else Color(0xFF27272A)
    val avatarBlue: Color get() = if (dark) Color(0xFFF4F4F5) else Color(0xFF111112)
    val avatarInk: Color get() = if (dark) Color(0xFF0A0A0B) else Color(0xFFFFFFFF)
    /** 主色底上的前景：与 [accent] 反相，保证对比度。 */
    val actionInk: Color get() = if (dark) Color(0xFF0A0A0B) else Color(0xFFFFFFFF)

    val amber: Color get() = if (dark) Color(0xFFF59E0B) else Color(0xFFD97706)
    val amber500 = Color(0xFFF59E0B)
    val amber700: Color get() = if (dark) Color(0xFFFBBF24) else Color(0xFFB45309)
    val rose: Color get() = if (dark) Color(0xFFF43F5E) else Color(0xFFE11D48)
    val rose500 = Color(0xFFF43F5E)
    val rose700: Color get() = if (dark) Color(0xFFFDA4AF) else Color(0xFFBE123C)
    val emerald: Color get() = Color(0xFF10B981)
    val emerald700: Color get() = if (dark) Color(0xFF34D399) else Color(0xFF047857)
    // 这两个旧名字原先是紫/靛，现在统一成中性灰阶（浅色深灰、深色浅灰）。
    val indigo: Color get() = if (dark) Color(0xFFA1A1AA) else Color(0xFF52525B)
    val violet: Color get() = if (dark) Color(0xFFD4D4D8) else Color(0xFF3F3F46)
    val scrim: Color get() = if (dark) Color(0x99000000) else Color(0x66000000)
}

private fun v2ColorScheme(dark: Boolean) = if (dark) {
    darkColorScheme(
        primary = V2Colors.accent,
        onPrimary = V2Colors.actionInk,
        secondary = V2Colors.cyan700,
        background = V2Colors.shellTop,
        onBackground = V2Colors.ink,
        surface = V2Colors.surface,
        onSurface = V2Colors.ink,
        outline = V2Colors.line,
        error = V2Colors.rose,
    )
} else {
    lightColorScheme(
        primary = V2Colors.accent,
        onPrimary = V2Colors.actionInk,
        secondary = V2Colors.cyan700,
        background = V2Colors.shellTop,
        onBackground = V2Colors.ink,
        surface = Color.White,
        onSurface = V2Colors.ink,
        outline = V2Colors.line,
        error = V2Colors.rose,
    )
}

private val V2Typography = Typography(
    bodySmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    bodyLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium),
    titleLarge = TextStyle(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 23.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold),
)

val V2SheetShape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)

@Composable
fun FluxV2Theme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    // 先落令牌再组合子树：子组件同一帧就能取到正确色值，不会闪一帧浅色。
    if (V2Colors.isDark != dark) V2Colors.applyDark(dark)

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    MaterialTheme(
        colorScheme = v2ColorScheme(dark),
        typography = V2Typography,
        content = content,
    )
}
