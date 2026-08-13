package com.mlx.app.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mlx.app.core.agent.PlanReviewDecision

/**
 * 计划审批层（架构级 13，对齐 Claude Plan 按钮）：
 * 方案全文 + 批准并执行 / 驳回（带意见输入）/ 关闭并停止。
 * 不可关闭覆盖层（DecisionOverlay）：点窗外/返回键/下滑均无效，必须显式决策
 * —— 否则 ModalBottomSheet 视觉消失后窗口仍拦截全屏触摸（引擎挂起 = 全屏假死）。
 */
@Composable
fun PlanReviewSheet(
    planText: String,
    onApprove: () -> Unit,
    onRevise: (String) -> Unit,
    onReject: () -> Unit,
) {
    var revising by remember { mutableStateOf(false) }
    var comment by remember { mutableStateOf("") }
    DecisionOverlay {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("🛡 计划审批 · 规划完成待批准", style = MaterialTheme.typography.titleMedium)
            Text(
                "批准后引擎解除写工具拦截，Agent 按方案执行；驳回可附修改意见重新规划；关闭即取消本回合。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.height(8.dp))
            // 方案全文
            Text(
                planText,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .verticalScroll(rememberScrollState()),
            )
            Spacer(Modifier.height(10.dp))
            if (revising) {
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    placeholder = { Text("修改意见（可选，直接发送给 Agent 重新规划）…") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { revising = false; comment = "" }) { Text("取消驳回") }
                    OutlinedButton(onClick = { onRevise(comment) }) { Text("发送意见并驳回") }
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onReject() },
                        modifier = Modifier.weight(1f),
                    ) { Text("关闭并停止", color = MaterialTheme.colorScheme.error) }
                    OutlinedButton(
                        onClick = { revising = true },
                        modifier = Modifier.weight(1f),
                    ) { Text("驳回修改") }
                    androidx.compose.material3.Button(
                        onClick = onApprove,
                        modifier = Modifier.weight(1f),
                    ) { Text("批准并执行") }
                }
            }
        }
    }
}
