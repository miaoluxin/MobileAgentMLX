package com.mlx.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp

/**
 * 任务清单弹层（对应 PC todo 工具的可视化）：
 * 展示当前会话 todos，可添加；勾选仅 Agent 空闲时可用（执行中只读展示，防误触改动 Agent 维护的进度）
 * —— 与 Agent 的 todo_add/todo_list/todo_complete 共享同一存储。
 * 实时数据源：chatVm.uiState.todos（Agent 执行 todo_add 时 TodoUpdated 事件驱动刷新，弹层打开期间也实时可见）。
 * 注：执行中的实时进展统一由底部 AgentStatusBar 承担（十五批起不再在本弹层重复展示工具/子代理状态）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoSheet(chatVm: ChatViewModel, onDismiss: () -> Unit) {
    val uiState by chatVm.uiState.collectAsState()
    val items = uiState.todos
    var newText by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text("任务清单（${items.count { !it.done }} 待办 / ${items.size} 总）", style = MaterialTheme.typography.titleMedium)
            // 十八批修复（审计）：执行中只读提示（禁点原因显式化）
            if (uiState.running) {
                Text(
                    "执行中：清单由 Agent 管理，勾选只读",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = newText,
                    onValueChange = { newText = it },
                    placeholder = { Text("添加待办…（Agent 也可通过 todo_add 添加）") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Button(
                    onClick = {
                        if (newText.isNotBlank()) {
                            chatVm.todoAdd(newText)
                            newText = ""
                        }
                    },
                    enabled = newText.isNotBlank(),
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text("添加") }
            }
            LazyColumn(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                items(items, key = { it.id }) { todo ->
                    val isCurrent = !todo.done && items.firstOrNull { !it.done }?.id == todo.id && uiState.running
                    Row(
                        Modifier
                            .fillMaxWidth()
                            // 执行中禁点（只读展示，防误触改动 Agent 共享的进度）；Agent 空闲时可手动勾选。
                            // 十八批修复（审计）：禁点加视觉反馈（半透明 + 只读标注），避免用户误以为卡顿
                            .alpha(if (uiState.running) 0.55f else 1f)
                            .clickable(enabled = !uiState.running) {
                                chatVm.todoToggle(todo.id, !todo.done)
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (todo.done) "✅" else "⬜", style = MaterialTheme.typography.titleSmall)
                        if (isCurrent) {
                            // ③ 当前执行项：脉冲高亮（"正在做哪一步"一目了然）
                            CurrentTodoRow(text = todo.text, modifier = Modifier.padding(start = 8.dp))
                        } else {
                            Text(
                                todo.text,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f).padding(start = 8.dp),
                            )
                        }
                    }
                    HorizontalDivider()
                }
                if (items.isEmpty()) {
                    item { Text("暂无待办", modifier = Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}
