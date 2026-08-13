package com.mlx.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.mlx.app.core.policy.Decision
import com.mlx.app.data.store.AppStore
import com.mlx.app.ui.AppViewModel
import kotlinx.coroutines.launch

// Provider 配置对话框：API Key / Base URL / 模型（原 SettingsScreen 后半拆分至此文件）
@Composable
internal fun KeyConfigDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    var apiKey by rememberSaveable { mutableStateOf("") }
    var baseUrl by rememberSaveable { mutableStateOf(AppStore.DEFAULT_BASE_URL) }
    var flashModel by rememberSaveable { mutableStateOf(vm.flashModel) }
    var proModel by rememberSaveable { mutableStateOf(vm.proModel) }
    var visible by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var testResult by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Provider 配置") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, label = { Text("API Key（留空则不修改）") }, singleLine = true,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = flashModel, onValueChange = { flashModel = it }, label = { Text("Flash 模型 ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = proModel, onValueChange = { proModel = it }, label = { Text("Pro 模型 ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                testResult?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val key = apiKey.trim()
                if (key.isNotBlank()) vm.setApiKeyPlain(key)
                vm.setBaseUrlEx(baseUrl)
                vm.setFlashModelEx(flashModel)
                vm.setProModelEx(proModel)
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = {
                scope.launch {
                    testResult = vm.testConnection(apiKey.ifBlank { "" }, baseUrl, flashModel)
                        .fold({ "OK: $it" }, { "X: ${it.message}" })
                }
            }) { Text("测试连接") }
        },
    )
}

// 新增策略规则对话框
@Composable
internal fun RuleDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    var pattern by remember { mutableStateOf("") }
    var decision by remember { mutableStateOf(Decision.ASK) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增策略规则") },
        text = {
            Column {
                OutlinedTextField(value = pattern, onValueChange = { pattern = it },
                    label = { Text("格式：工具名:路径glob") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (d in listOf(Decision.ALLOW, Decision.ASK, Decision.DENY)) {
                        FilterChip(selected = decision == d, onClick = { decision = d }, label = { Text(d.name) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (pattern.isNotBlank()) vm.addPolicyRule(pattern, decision)
                onDismiss()
            }) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// 新建技能对话框
@Composable
internal fun SkillDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建技能") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("技能名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("用途说明") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = content, onValueChange = { content = it }, label = { Text("技能正文") }, minLines = 5, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank()) {
                    // 架构级 12：技能模型扩展（新字段走默认值；保存为用户技能）
                    vm.container.skillStore.save(
                        com.mlx.app.core.skills.Skill(
                            name = name.trim(),
                            description = description.trim(),
                            content = content.trim(),
                            category = "用户注册",
                        )
                    )
                }
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// 添加 MCP 服务器对话框
@Composable
internal fun McpDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加 MCP 服务器") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("HTTP 端点 URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    status = "连接测试中…"
                    com.mlx.app.core.mcp.McpClient.listTools(url.trim()).fold(
                        onSuccess = { list ->
                            vm.container.mcpRegistry.save(com.mlx.app.core.mcp.McpRegistry.McpServer(name.trim(), url.trim()))
                            status = "OK: " + list.size + " 工具"
                            onDismiss()
                        },
                        onFailure = { status = "X: " + (it.message ?: "网络错误") },
                    )
                }
            }, enabled = name.isNotBlank() && url.isNotBlank()) { Text("连接并添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// 内置文档查看（assets/docs，Markdown 渲染）
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DocViewerDialog(docName: String, onDismiss: () -> Unit) {
    var content by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(docName) {
        content = runCatching {
            context.assets.open("docs/$docName")
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull()
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(if (docName == "product.md") "产品介绍" else "开发文档", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (content != null) {
                com.mlx.app.ui.chat.MarkdownText(content!!)
            } else {
                Text("文档加载失败", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 崩溃日志查看（core/diagnose/CrashLog 落盘；滚动显示最近一份 + 清空） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CrashLogDialog(onDismiss: () -> Unit) {
    // 十二批修正：mutableStateOf 而非 remember 一次性快照 —— 清空后 files/text 即时刷新（原实现标题与按钮残留旧值）
    var files by remember { mutableStateOf(com.mlx.app.core.diagnose.CrashLog.files()) }
    var text by remember { mutableStateOf(files.firstOrNull()?.let { runCatching { it.readText() }.getOrNull() }) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text("崩溃日志（${files.size} 份，最近 ${files.size.coerceAtMost(5)} 份保留）", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (text.isNullOrBlank()) {
                Text("暂无崩溃日志", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(
                    text!!,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (files.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = {
                    com.mlx.app.core.diagnose.CrashLog.clear()
                    files = emptyList()
                    text = null
                }) { Text("清空日志", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

// 设置页组件（从原 SettingsScreen 拆分）
@Composable
internal fun SectionTitle(t: String) {
    Text(t, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp))
}

@Composable
internal fun SettingCard(content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) { content() }
    }
}

@Composable
internal fun SettingRow(label: String, value: String, onEdit: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.weight(1.2f))
        if (onEdit != null) {
            TextButton(onClick = onEdit) { Text("编辑") }
        }
    }
}
