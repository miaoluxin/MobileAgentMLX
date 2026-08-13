package com.mlx.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mlx.app.core.commands.CommandDef

/**
 * 指令面板（对应文档 5.4 节）：50+ 斜杠命令的移动形态 ——
 * 搜索 + 分类 chips + 最近使用，参数型命令转弹表单（budget）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandPalette(
    commands: List<CommandDef>,
    onDismiss: () -> Unit,
    onExecute: (CommandDef) -> Unit,
    onBudgetRequest: () -> Unit,
    onTodoRequest: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("全部") }
    val categories = listOf("全部") + commands.map { it.category }.distinct()

    val filtered = remember(query, category, commands) {
        commands.filter { def ->
            (category == "全部" || def.category == category) &&
                (query.isBlank() ||
                    def.name.contains(query, ignoreCase = true) ||
                    def.zhName.contains(query) ||
                    def.description.contains(query))
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
            Text(
                "指令面板",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索命令（/ 开头自动进入）…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (c in categories) {
                    FilterChip(
                        selected = category == c,
                        onClick = { category = c },
                        label = { Text(c) },
                    )
                }
            }
            LazyColumn(Modifier.weight(1f)) {
                items(filtered, key = { it.id }) { def ->
                    CommandRow(def, onExecute = {
                        when {
                            def.id == "budget" -> onBudgetRequest()
                            def.id == "todo" -> onTodoRequest()
                            else -> onExecute(def)
                        }
                    })
                }
                if (filtered.isEmpty()) {
                    item { Text("无匹配命令", modifier = Modifier.padding(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun CommandRow(def: CommandDef, onExecute: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickableRow(onExecute)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Row {
                    Text(
                        def.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "  ${def.zhName}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    def.description + (if (def.available) "" else "（规划中）"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (def.usage.isNotBlank()) {
                    Text(
                        "怎么用：${def.usage}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
        HorizontalDivider(Modifier.padding(start = 16.dp))
    }
}

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)
