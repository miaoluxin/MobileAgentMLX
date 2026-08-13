package com.mlx.app.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp

/**
 * MLX 品牌标志：黑底白字 —— M 大写 + LX 小写竖排（Make Learn Extraordinary!）。
 * 与 launcher 图标（ic_launcher_foreground.png）同构。
 */
@Composable
fun MlxLogo(
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Black,
    accentColor: Color = Color.White,
    glow: Boolean = false,
) {
    val textMeasurer = rememberTextMeasurer()
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            // 黑底圆角方块（占画布 72%）
            val box = w * 0.14f
            val boxSize = w * 0.72f
            drawRoundRect(
                color = containerColor,
                topLeft = Offset(box, box),
                size = Size(boxSize, boxSize),
                cornerRadius = CornerRadius(w * 0.12f),
            )
            // M 大写（大字）
            val mSize = with(density) { (w * 0.34f).toSp() }
            val mLayout = textMeasurer.measure(
                "M",
                TextStyle(color = accentColor, fontSize = mSize, fontWeight = FontWeight.Bold),
            )
            // LX（小字，竖排在 M 下方）
            val lxSize = with(density) { (w * 0.16f).toSp() }
            val lxLayout = textMeasurer.measure(
                "LX",
                TextStyle(color = accentColor, fontSize = lxSize, fontWeight = FontWeight.Bold),
            )
            val centerY = h / 2f
            drawText(mLayout, topLeft = Offset((w - mLayout.size.width) / 2f, centerY - mLayout.size.height - w * 0.03f))
            drawText(lxLayout, topLeft = Offset((w - lxLayout.size.width) / 2f, centerY + w * 0.03f))
        }
    }
}
