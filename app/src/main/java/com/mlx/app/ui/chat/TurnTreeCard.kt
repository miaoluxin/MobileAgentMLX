package com.mlx.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mlx.app.data.store.StepKind
import com.mlx.app.data.store.StepRecord
import com.mlx.app.data.store.ToolStatus
import com.mlx.app.data.store.TurnRecord
import com.mlx.app.data.store.TurnStatus
import com.mlx.app.ui.UiFormats

/**
 * 回合执行轨迹卡（架构级 11 / 文档 2.5：执行过程可视化一致 —— 完成后保留步骤树复盘）。
 * 挂在用户消息之后，展示该回合的完整执行轨迹：状态 / 耗时 / 失败红标 / 产物引用，
 * 可展开收起；无 turns 的旧会话保持平铺渲染（双视图并存）。
 */
@Composable
fun TurnTreeCard(turn: TurnRecord, modifier: Modifier = Modifier) {
    // 七批：默认折叠（用户只要结果 —— 复盘按需展开，历史默认干净）
    var expanded by rememberSaveable(turn.id) { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.small,
        modifier = modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            // 头：回合摘要（轮次/状态/耗时/成本）
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "🔄 第 ${turn.turnNumber} 轮 · ${turn.status.label}",
                    style = MaterialTheme.typography.labelMedium,
                    color = turnStatusColor(turn.status),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${UiFormats.usd(turn.costUsd)} · ${fmtMs(turn.finishedAt - turn.startedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(if (expanded) "▾" else "▸", style = MaterialTheme.typography.titleSmall)
            }
            if (expanded) {
                // 用户指令
                Text(
                    "👤 ${turn.userText}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
                )
                // 步骤树（递归渲染；children 为嵌套预留）
                turn.steps.forEach { step ->
                    StepRow(step, depth = 0)
                }
                if (turn.steps.isEmpty()) {
                    Text(
                        "（本回合无工具调用记录）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun StepRow(step: StepRecord, depth: Int) {
    var expanded by rememberSaveable(step.id) { mutableStateOf(false) }
    val indent = depth * 14
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(start = indent.dp, top = 1.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 状态指示：RUNNING 旋转动画 / 成功圆点 / 失败红点
        when (step.status) {
            ToolStatus.RUNNING -> CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 2.dp)
            else -> Text(
                if (step.status == ToolStatus.SUCCESS) "●" else "✖",
                color = stepStatusColor(step.status),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        // 六批：意图优先主文案（对齐 Claude Code"正在做什么"）—— TEXT 步骤显示"助手总结"，TOOL 步骤意图/映射优先
        Text(
            if (step.kind == StepKind.TEXT) "助手总结"
            else step.intent.ifBlank { toolActionLabel(step.name) },
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (step.kind == StepKind.TEXT) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).padding(start = 6.dp),
            maxLines = 1,
        )
        if (step.kind == StepKind.TOOL) {
            Text(
                step.name,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
                maxLines = 1,
            )
        }
        if (step.durationMs > 0 && step.kind == StepKind.TOOL) {
            Text(
                fmtMs(step.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            step.status.label,
            style = MaterialTheme.typography.labelSmall,
            color = stepStatusColor(step.status),
        )
        if (step.kind == StepKind.TOOL) {
            Text(if (expanded) "▾" else "▸", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
        }
    }
    if (expanded && step.kind == StepKind.TOOL) {
        Column(Modifier.padding(start = (indent + 14).dp, bottom = 2.dp)) {
            if (step.argsJson.isNotBlank() && step.argsJson != "{}") {
                Text("参数 ${step.argsJson.take(300)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (step.resultText.isNotBlank()) {
                Text(
                    step.resultText.take(600),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 120.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
            // Diff 展示（审查修复：关键 code review 信息 —— 编辑类工具的改动明细）
            if (step.diffText.isNotBlank()) {
                Text(
                    step.diffText.take(800),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 120.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
            // 产物引用：复盘定位（fileChanged 相对路径）
            step.outputRefs.forEach { ref ->
                Text(
                    "📄 $ref",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
    // 嵌套子步骤（预留：子代理/MCP 轨迹）
    step.children.forEach { child ->
        StepRow(child, depth + 1)
    }
}

@Composable
private fun stepStatusColor(s: ToolStatus): Color = when (s) {
    ToolStatus.RUNNING -> MaterialTheme.colorScheme.primary
    ToolStatus.SUCCESS -> Color(0xFF34A853)
    ToolStatus.FAILED -> MaterialTheme.colorScheme.error
    ToolStatus.APPROVAL_REQUIRED -> Color(0xFFF59E0B)
    ToolStatus.DENIED -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun turnStatusColor(s: TurnStatus): Color = when (s) {
    TurnStatus.RUNNING -> MaterialTheme.colorScheme.primary
    TurnStatus.SUCCESS -> Color(0xFF34A853)
    TurnStatus.FAILED -> MaterialTheme.colorScheme.error
    TurnStatus.ABORTED -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun fmtMs(ms: Long): String = when {
    ms <= 0 -> ""
    ms < 1000 -> "${ms}ms"
    ms < 60_000 -> "%.1fs".format(ms / 1000.0)
    else -> "%dm%02ds".format(ms / 60_000, (ms % 60_000) / 1000)
}
