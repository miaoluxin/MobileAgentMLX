package com.mlx.app.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mlx.app.data.store.ToolCallRecord
import com.mlx.app.data.store.ToolStatus

/**
 * 子代理详情弹窗（十一批：过程可视化三层之②）：
 * 标题 = intent；内容 = prompt 摘要 + 实时思考链尾部 + 实时正文（执行中）+ 最终结果（完成后）。
 * 数据全部来自 uiState（rec + subagentStreams），无需 VM 新接口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubAgentDetailSheet(
    rec: ToolCallRecord,
    stream: SubAgentStreamState?,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                if (rec.name == "planner") "规划者" else "子代理",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                rec.intent.ifBlank { toolActionLabel(rec.name) },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                rec.status.label,
                style = MaterialTheme.typography.labelSmall,
                color = when (rec.status) {
                    ToolStatus.RUNNING -> MaterialTheme.colorScheme.primary
                    ToolStatus.SUCCESS -> androidx.compose.ui.graphics.Color(0xFF34A853)
                    else -> MaterialTheme.colorScheme.error
                },
            )
            Spacer(Modifier.height(6.dp))
            HorizontalDivider()

            // 任务描述（prompt 摘要）
            Text(
                "任务描述",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
            )
            Text(
                rec.argsJson.take(200),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            if (rec.status == ToolStatus.RUNNING) {
                // 实时思考链尾部（子代理默认开思考模式：30-120s 无正文期间可见，不黑屏）
                val reasoning = stream?.reasoning.orEmpty()
                if (reasoning.isNotBlank()) {
                    Text(
                        "💭 思考中",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                    Text(
                        reasoning.takeLast(600),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 10,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                }
                // 实时正文
                Text(
                    "实时输出",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
                val content = stream?.content.orEmpty()
                Text(
                    if (content.isNotBlank()) content + "▍" else "等待输出…",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            } else {
                // 最终结果（resultText 由 ToolStatusChanged 回填）
                Text(
                    "最终结果",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
                Text(
                    rec.resultText.ifBlank { "（无输出）" },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }
        }
    }
}
