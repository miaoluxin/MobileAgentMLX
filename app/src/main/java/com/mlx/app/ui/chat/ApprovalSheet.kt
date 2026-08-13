package com.mlx.app.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mlx.app.core.agent.ApprovalDecision

/**
 * 工具审批底部弹层（对应文档 5.6 节 + 线框图 W3）：
 * 变更预览、仅本次允许 / 总是允许（写入策略规则）/ 拒绝。
 * 不可关闭覆盖层（DecisionOverlay）：点窗外/返回键/下滑均无效，必须显式决策
 * —— 否则 ModalBottomSheet 视觉消失后窗口仍拦截全屏触摸（引擎挂起 = 全屏假死）。
 */
@Composable
fun ApprovalSheet(
    approvals: List<ApprovalItem>,
    onDecision: (callId: String, decision: ApprovalDecision) -> Unit,
    onChoice: (callId: String, selections: List<List<Int>>) -> Unit,
) {
    DecisionOverlay {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Text(
                "🛡 等待审批 (${approvals.size})",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth(),
            )
            approvals.forEach { item ->
                if (item.name == "choice") {
                    ChoiceCard(item, onChoice)
                } else {
                    ApprovalItemCard(item, onDecision)
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
            }
        }
    }
}

@Composable
private fun ApprovalItemCard(
    item: ApprovalItem,
    onDecision: (callId: String, decision: ApprovalDecision) -> Unit,
) {
    var showArgs by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            // 八批：意图优先 —— 主文案用人类可读动作（对齐 ToolCard 模式），工具名降小字
            Text(
                "✏️ ${toolActionLabel(item.name)}",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                item.name,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
            TextButton(onClick = { showArgs = !showArgs }) {
                Text(if (showArgs) "收起参数" else "查看参数", style = MaterialTheme.typography.labelSmall)
            }
        }
        item.path?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "命中策略：未匹配任何规则 → 需要询问",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        if (showArgs) {
            Text(
                item.argsJson,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { onDecision(item.callId, ApprovalDecision.ALLOW_ONCE) }, modifier = Modifier.weight(1f)) {
                // 短文案防按钮换行变两倍高（原"仅本次允许"在窄屏换行）
                Text("仅本次")
            }
            Button(onClick = { onDecision(item.callId, ApprovalDecision.ALLOW_ALWAYS) }, modifier = Modifier.weight(1f)) {
                Text("总是允许")
            }
            TextButton(onClick = { onDecision(item.callId, ApprovalDecision.DENY) }, modifier = Modifier.weight(1f)) {
                Text("拒绝", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/**
 * choice/ask 决策卡片（P2-7：多题分组、多选复选、推荐前置标注、统一提交）。
 * 单题兼容：questions 为空时回退 question+options。
 */
@Composable
private fun ChoiceCard(
    item: ApprovalItem,
    onChoice: (callId: String, selections: List<List<Int>>) -> Unit,
) {
    val questions = item.questions.ifEmpty {
        listOf(com.mlx.app.core.agent.ChoiceQuestion(question = item.question ?: "请选择", options = item.options))
    }
    // 每题选中索引集合（Compose 状态数组）
    val selections = remember { questions.map { mutableStateOf(setOf<Int>()) } }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            "❓ 需要你决策后继续（${questions.size} 题）",
            style = MaterialTheme.typography.titleSmall,
        )
        questions.forEachIndexed { qi, q ->
            if (q.header.isNotBlank()) {
                Text(
                    q.header,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            Text(
                "${qi + 1}. ${q.question}${if (q.multiSelect) "（可多选）" else ""}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
            )
            q.options.forEachIndexed { oi, option ->
                val selected = oi in selections[qi].value
                OutlinedButton(
                    onClick = {
                        selections[qi].value = if (q.multiSelect) {
                            if (selected) selections[qi].value - oi else selections[qi].value + oi
                        } else {
                            setOf(oi)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    colors = if (selected) {
                        androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        )
                    } else {
                        androidx.compose.material3.ButtonDefaults.outlinedButtonColors()
                    },
                ) {
                    Text(
                        (if (q.recommendedFirst && oi == 0) "⭐ " else if (selected) "✓ " else "") + option,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = { onChoice(item.callId, emptyList()) }) {
                Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(
                onClick = { onChoice(item.callId, selections.map { it.value.toList() }) },
                enabled = selections.all { it.value.isNotEmpty() },
                modifier = Modifier.weight(1f),
            ) { Text("提交选择") }
        }
    }
}
