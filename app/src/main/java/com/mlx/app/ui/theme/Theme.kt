package com.mlx.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * MLX Design System —— 按 Anthropic frontend-design skill 原则重设计：
 * - 60/30/10 配色：品牌蓝主色 + 蓝紫副色 + 冷青绿锐利点缀
 * - 中性色向品牌蓝偏移（冷灰蓝，无纯黑纯白）
 * - 圆角 4/8/12/16/24 体系（small=8 medium=12 large=16 extraLarge=24）
 * - 标题与正文字号高对比（display 40sp vs body 14sp）
 * - 展示字体 Outfit（标题/品牌元素），正文系统字体
 */

// ---------- 品牌色板 ----------
val BrandBlue = Color(0xFF2E5BFF)          // 主导色（60%）
val BrandBlueDeep = Color(0xFF1A3FD6)      // 按压/深色态
val BrandBlueSoft = Color(0xFFDCE5FF)      // 主色容器（浅）
val BrandIndigo = Color(0xFF7A6CFF)        // 副色（30%）
val AccentTeal = Color(0xFF12B8A6)         // 锐利点缀（10%）
// 八批：统一为代码实际广泛使用的成功绿（原 0xFF22C55E 未使用，工具/步骤/任务页均用 0xFF34A853）
val SuccessGreen = Color(0xFF34A853)
val WarnAmber = Color(0xFFF59E0B)
val ErrorRed = Color(0xFFEF4444)

// 浅色（向品牌蓝偏移的中性）
private val LightColors = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = BrandBlueSoft,
    onPrimaryContainer = Color(0xFF10277A),
    secondary = BrandIndigo,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE5E1FF),
    onSecondaryContainer = Color(0xFF2A1E66),
    tertiary = AccentTeal,
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFC9F4EE),
    onTertiaryContainer = Color(0xFF0B4A42),
    background = Color(0xFFF6F8FD),        // 偏蓝白（非纯白）
    onBackground = Color(0xFF131A2E),      // 偏蓝黑（非纯黑）
    surface = Color(0xFFFDFDFF),
    onSurface = Color(0xFF131A2E),
    surfaceVariant = Color(0xFFE9EDF7),    // 冷灰蓝
    onSurfaceVariant = Color(0xFF4A5468),
    outline = Color(0xFFC9D0E0),
    outlineVariant = Color(0xFFE2E7F2),
    error = ErrorRed,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF5F0A08),
    surfaceContainerHighest = Color(0xFFE4E9F4),
    surfaceContainerHigh = Color(0xFFEAEEF7),
    surfaceContainer = Color(0xFFEFF2F9),
    surfaceContainerLow = Color(0xFFF5F7FC),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceTint = BrandBlue,
)

// 深色（偏蓝黑）
private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FACFF),
    onPrimary = Color(0xFF10277A),
    primaryContainer = Color(0xFF2145C8),
    onPrimaryContainer = Color(0xFFDCE5FF),
    secondary = Color(0xFFB3A8FF),
    onSecondary = Color(0xFF2A1E66),
    secondaryContainer = Color(0xFF453A85),
    onSecondaryContainer = Color(0xFFE5E1FF),
    tertiary = Color(0xFF3ED4C0),
    onTertiary = Color(0xFF0B4A42),
    tertiaryContainer = Color(0xFF0E5F55),
    onTertiaryContainer = Color(0xFFC9F4EE),
    background = Color(0xFF0E1118),        // 偏蓝黑（非纯黑）
    onBackground = Color(0xFFE4E8F2),
    surface = Color(0xFF141824),
    onSurface = Color(0xFFE4E8F2),
    surfaceVariant = Color(0xFF232A3C),
    onSurfaceVariant = Color(0xFFA3ADC2),
    outline = Color(0xFF4A5468),
    outlineVariant = Color(0xFF2C3448),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF5F0A08),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFFFDAD6),
    surfaceContainerHighest = Color(0xFF2A3145),
    surfaceContainerHigh = Color(0xFF232A3C),
    surfaceContainer = Color(0xFF1E2534),
    surfaceContainerLow = Color(0xFF191F2C),
    surfaceContainerLowest = Color(0xFF0B0E14),
    surfaceTint = Color(0xFF8FACFF),
)

// ---------- 圆角体系（frontend-design 4/8/12/16/24） ----------
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),   // 小按钮/内联
    small = RoundedCornerShape(10.dp),        // 标准按钮/次要交互
    medium = RoundedCornerShape(14.dp),       // 卡片/输入框
    large = RoundedCornerShape(20.dp),        // 主按钮/导航/特色容器
    extraLarge = RoundedCornerShape(28.dp),   // 大卡片/Hero/弹层
)

// ---------- 排版（标题 Outfit 展示字体 + 正文系统；字号高对比） ----------
private fun appTypography(display: FontFamily): Typography = Typography(
    displayLarge = TextStyle(fontFamily = display, fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 46.sp, letterSpacing = (-0.6).sp),
    displayMedium = TextStyle(fontFamily = display, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.4).sp),
    displaySmall = TextStyle(fontFamily = display, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp),
    headlineLarge = TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
    headlineMedium = TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    headlineSmall = TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 23.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 23.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 21.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 15.sp),
)

/** 展示字体：Outfit variable（res/font/outfit_variable.ttf，全字重） */
val OutfitDisplay: FontFamily = FontFamily(
    androidx.compose.ui.text.font.Font(com.mlx.app.R.font.outfit_variable, FontWeight.Bold),
    androidx.compose.ui.text.font.Font(com.mlx.app.R.font.outfit_variable, FontWeight.SemiBold),
    androidx.compose.ui.text.font.Font(com.mlx.app.R.font.outfit_variable, FontWeight.Normal),
)

@Composable
fun MLXTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = AppShapes,
        typography = appTypography(OutfitDisplay),
        content = content,
    )
}
