package com.mlx.app.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mlx.app.core.cost.CostTreeNode
import com.mlx.app.core.cost.TurnPoint
import com.mlx.app.ui.UiFormats

/**
 * 成本高级视图组件（架构级 14 / 文档 2.6.2）：
 * - CostTree：模型 → 日期 → 会话 → 轮次 双树状图（递归可展开，叶子单轮三档明细）
 * - TurnLineChart：会话明细双轴折线图（命中率 + 每轮成本 + 模型切换点 + 高 miss 高亮）
 */

/** 双树状图（Pro/Flash 各一棵；递归可展开） */
@Composable
fun CostTree(nodes: List<CostTreeNode>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        nodes.forEach { node -> CostTreeNodeRow(node, depth = 0) }
    }
}

@Composable
private fun CostTreeNodeRow(node: CostTreeNode, depth: Int) {
    var expanded by rememberSaveable(node.id) { mutableStateOf(true) }
    val hasChildren = node.children.isNotEmpty()
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = hasChildren) { expanded = !expanded }
            .padding(start = (depth * 14).dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (hasChildren) (if (expanded) "▾ " else "▸ ") else "  ",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            node.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (depth == 0) androidx.compose.ui.text.font.FontWeight.Bold else null,
            color = if (depth == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        Text(
            UiFormats.usd(node.value),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (expanded) {
        node.detail?.let { detail ->
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = (depth * 14 + 16).dp),
            )
        }
        node.children.forEach { child -> CostTreeNodeRow(child, depth + 1) }
    }
}

/** 折线图视图模式 */
enum class TurnChartMode(val label: String) { HIT_RATE("命中率"), MISS_TOKENS("未命中量"), COST("每轮成本") }

/**
 * 会话明细双轴折线图：
 * - 第一 Y 轴：命中率 0~100%（Pro/Flash 双线）
 * - 第二 Y 轴：每轮成本（柱状，右侧刻度）
 * - 模型切换点竖线标注 + 高 miss 轮次红点高亮
 */
@Composable
fun TurnLineChart(
    series: List<TurnPoint>,
    switchTurns: List<Int>,
    highMissTurns: List<Int>,
    mode: TurnChartMode,
    modifier: Modifier = Modifier,
) {
    val proColor = MaterialTheme.colorScheme.primary
    val flashColor = Color(0xFF7A6CFF)
    val missColor = Color(0xFFF59E0B)
    val costColor = Color(0xFF34A853) // 八批：与 SuccessGreen 统一
    val switchColor = MaterialTheme.colorScheme.onSurfaceVariant
    if (series.isEmpty()) {
        Text("（该会话无成本数据）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val maxCost = series.maxOf { it.costUsd }.coerceAtLeast(0.0001)
    val maxMiss = series.maxOf { it.missTokens }.coerceAtLeast(1L)
    val maxTurn = series.maxOf { it.turn }.coerceAtLeast(1)
    // Canvas draw 块非 Composable 上下文：颜色提前取出
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    Canvas(modifier.fillMaxWidth().height(120.dp)) {
        val w = size.width
        val h = size.height
        val padL = 34f
        val padR = 40f
        val padT = 6f
        val padB = 14f
        val plotW = w - padL - padR
        val plotH = h - padT - padB

        fun xOf(turn: Int): Float = padL + plotW * (turn - 1).toFloat() / (maxTurn - 1).coerceAtLeast(1)
        fun yHit(rate: Double?): Float = padT + plotH * (1f - (rate ?: 0.0).toFloat())

        // 网格线（25%/50%/75%）
        for (g in listOf(0.25f, 0.5f, 0.75f)) {
            drawLine(
                color = gridColor,
                start = Offset(padL, padT + plotH * (1 - g)),
                end = Offset(w - padR, padT + plotH * (1 - g)),
                strokeWidth = 1f,
            )
        }

        when (mode) {
            TurnChartMode.HIT_RATE -> {
                // Pro / Flash 双折线（命中率）
                listOf(proColor to series.filter { it.model.contains("pro") }, flashColor to series.filter { it.model.contains("flash") })
                    .forEach { (color, pts) ->
                        if (pts.isEmpty()) return@forEach
                        val path = Path()
                        pts.forEachIndexed { i, p ->
                            val x = xOf(p.turn)
                            val y = yHit(p.hitRate)
                            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(path, color, style = Stroke(width = 2f))
                        pts.forEach { p ->
                            drawCircle(color, radius = 2.5f, center = Offset(xOf(p.turn), yHit(p.hitRate)))
                        }
                    }
            }
            TurnChartMode.MISS_TOKENS -> {
                // 未命中绝对量（柱状，Pro 蓝 / Flash 紫）
                series.forEach { p ->
                    val frac = p.missTokens.toFloat() / maxMiss
                    val x = xOf(p.turn)
                    val barW = (plotW / maxTurn * 0.6f).coerceAtLeast(3f)
                    drawRect(
                        color = if (p.model.contains("pro")) proColor else flashColor,
                        topLeft = Offset(x - barW / 2, padT + plotH * (1 - frac)),
                        size = androidx.compose.ui.geometry.Size(barW, plotH * frac),
                    )
                }
            }
            TurnChartMode.COST -> {
                // 每轮成本（柱状，绿色）
                series.forEach { p ->
                    val frac = (p.costUsd / maxCost).toFloat()
                    val x = xOf(p.turn)
                    val barW = (plotW / maxTurn * 0.6f).coerceAtLeast(3f)
                    drawRect(
                        color = costColor.copy(alpha = 0.75f),
                        topLeft = Offset(x - barW / 2, padT + plotH * (1 - frac)),
                        size = androidx.compose.ui.geometry.Size(barW, plotH * frac),
                    )
                }
            }
        }

        // 模型切换点竖线
        switchTurns.forEach { t ->
            val x = xOf(t)
            drawLine(switchColor, Offset(x, padT), Offset(x, padT + plotH), strokeWidth = 1f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 4f)))
        }
        // 高 miss 轮次红点高亮（三视图通用）
        highMissTurns.forEach { t ->
            drawCircle(Color(0xFFEF4444), radius = 4f, center = Offset(xOf(t), padT + plotH))
        }
        // Y 轴标签（审查修复：按视图模式动态 —— 命中率 100/0、未命中量/成本显示实际最大值）
        val leftTop = when (mode) {
            TurnChartMode.HIT_RATE -> "100"
            TurnChartMode.MISS_TOKENS -> UiFormats.tokens(maxMiss)
            TurnChartMode.COST -> UiFormats.usd(maxCost)
        }
        drawContext.canvas.nativeCanvas.drawText(
            leftTop, padL - 34f, padT + 8f,
            android.graphics.Paint().apply { textSize = 22f; color = android.graphics.Color.GRAY },
        )
        drawContext.canvas.nativeCanvas.drawText(
            "0", padL - 20f, padT + plotH + 4f,
            android.graphics.Paint().apply { textSize = 22f; color = android.graphics.Color.GRAY },
        )
        // 右侧成本刻度仅 COST 模式显示
        if (mode == TurnChartMode.COST) {
            drawContext.canvas.nativeCanvas.drawText(
                UiFormats.usd(maxCost), w - padR + 6f, padT + 8f,
                android.graphics.Paint().apply { textSize = 22f; color = android.graphics.Color.GRAY },
            )
            drawContext.canvas.nativeCanvas.drawText(
                "0", w - padR + 20f, padT + plotH + 4f,
                android.graphics.Paint().apply { textSize = 22f; color = android.graphics.Color.GRAY },
            )
        }
    }
    // 图例
    Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
        LegendDot(proColor, "Pro 命中率")
        LegendDot(flashColor, "Flash 命中率")
        LegendDot(missColor, "模型切换点")
        LegendDot(Color(0xFFEF4444), "高 miss 轮")
        if (mode == TurnChartMode.COST) LegendDot(costColor, "每轮成本")
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.width(8.dp).height(8.dp)) { drawCircle(color) }
        Spacer(Modifier.width(3.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
