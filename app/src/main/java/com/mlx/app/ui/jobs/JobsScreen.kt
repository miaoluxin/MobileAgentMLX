package com.mlx.app.ui.jobs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mlx.app.core.tasks.TaskManager
import com.mlx.app.ui.AppViewModel
import com.mlx.app.ui.UiFormats

/**
 * 后台任务页（对应 PC /jobs /kill /logs）：
 * 任务列表（状态/进度/时长）+ 日志尾部 + 终止/重试；运行中任务由前台服务在通知栏常驻。
 */
@Composable
fun JobsScreen(vm: AppViewModel) {
    var tasks by remember { mutableStateOf<List<TaskManager.Task>>(emptyList()) }
    var projectNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var sessionTitles by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var expandedId by remember { mutableStateOf<String?>(null) }
    // 工程折叠/展开记忆（跨 tab 切换/重启恢复；文档 2.10 补充需求：树状折叠交互）
    val collapsedSaver = remember {
        androidx.compose.runtime.saveable.Saver<Set<String>, ArrayList<String>>(
            save = { ArrayList(it) },
            restore = { it.toSet() },
        )
    }
    var collapsedProjects by androidx.compose.runtime.saveable.rememberSaveable(stateSaver = collapsedSaver) {
        mutableStateOf(emptySet())
    }
    // 超龄清理只执行一次（pruneByAge 30 天；修复：原每次进页 prune(10) 删掉最新 10 条以外的任务）
    // 审查修复：rememberSaveable 跨组合保留 —— 原 remember 在 tab 切换（composition 销毁）后重置，
    // 每次进任务页都重复执行超龄清理（语义不符且多余 IO）
    var prunedOnce by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    // 加载：最新在前（修复：原 take(20) 取最旧 20 条）+ 工程/会话名一次读盘解析；
    // 放宽到 take(200)（修复：任务页"时有时无"——列表截断 + prune(10) 双重丢失）
    fun reload() {
        val list = vm.container.taskStore.list().sortedByDescending { it.createdAt }.take(200)
        tasks = list
        // 十四批：首个失败任务默认展开日志（报错明细 + 尾部"（退出码 N）"立即可见）
        if (expandedId == null && list.any { it.status == TaskManager.Status.FAILED }) {
            expandedId = list.firstOrNull { it.status == TaskManager.Status.FAILED }?.id
        }
        projectNames = list.mapNotNull { it.projectId.ifBlank { null } }.distinct().associateWith { pid ->
            if (pid == "saf") "SAF 工程"
            // 任务创建时快照的工程名优先（注册表缺失/改名场景仍显示正确名字）
            else list.firstOrNull { it.projectId == pid }?.projectName?.ifBlank { null }
                ?: vm.container.workspaceRepo.find(pid)?.name
                ?: pid
        }
        sessionTitles = list.mapNotNull { it.sessionId.ifBlank { null } }.distinct().associateWith { sid ->
            vm.container.sessionStore.load(sid)?.title ?: "无会话"
        }
    }
    LaunchedEffect(vm.refreshTick, vm.tab) {
        if (!prunedOnce) {
            vm.container.taskStore.pruneByAge(30)
            prunedOnce = true
        }
        reload()
    }
    // 运行中任务实时刷新：1s 轮询（shell/python 执行日志逐行可见，不再"点了日志一片空白"）
    LaunchedEffect(tasks.any { it.status == TaskManager.Status.RUNNING }) {
        while (vm.container.taskStore.list().any { it.status == TaskManager.Status.RUNNING }) {
            kotlinx.coroutines.delay(1000)
            reload()
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
    ) {
        item {
            // 页签标题（文档 2.11：页头主标题 + 副标题）
            com.mlx.app.ui.components.PageHeader(
                title = "任务",
                subtitle = "脚本/命令执行与后台长任务（PC /jobs 对应）；普通文件操作在会话内 ⚙ 工具调用 查看",
            )
            Spacer(Modifier.height(4.dp))
        }
        if (tasks.isEmpty()) {
            item {
                Column(Modifier.padding(vertical = 24.dp)) {
                    Text("暂无任务", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "脚本/命令执行（bash、python 等）会登记在这里，运行中由前台服务常驻通知；普通工具调用在会话内查看",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        // P4：按工程 → 会话 树状归属展示（未归属任务归入"未分组/无会话"；工程节点可折叠/展开并记忆）
        val grouped = tasks.groupBy { it.projectId.ifBlank { "未分组" } }
        grouped.forEach { (pid, pTasks) ->
            val collapsed = pid in collapsedProjects
            item(key = "pj_$pid") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            collapsedProjects = if (collapsed) collapsedProjects - pid else collapsedProjects + pid
                        }
                        .padding(top = 10.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (collapsed) "▶ " else "▾ ",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "📁 ${projectNames[pid] ?: pid}（${pTasks.size}）",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (!collapsed) {
            pTasks.groupBy { it.sessionId.ifBlank { "无会话" } }.forEach { (sid, sTasks) ->
                item(key = "se_$pid/$sid") {
                    Text(
                        "  💬 ${sessionTitles[sid] ?: "无会话"}（${sTasks.size}）",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
                    )
                }
                items(sTasks, key = { it.id }) { task ->
                // 十四批：FAILED 红色边框高亮（状态醒目 + 报错归属）
                Card(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    border = if (task.status == TaskManager.Status.FAILED) {
                        androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                    } else {
                        null
                    },
                ) {
                Column(Modifier.padding(12.dp)) {
                    // 标题行可点击跳回所属会话（sessionId 为空则不可点）
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .then(
                                if (task.sessionId.isNotBlank()) {
                                    Modifier.clickable { vm.openSession(task.sessionId) }
                                } else {
                                    Modifier
                                }
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "⚙ ${task.name}",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            statusLabel(task.status),
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor(task.status),
                        )
                        if (task.sessionId.isNotBlank()) {
                            Text(
                                "↗",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                    Text(
                        "${task.type} · ${UiFormats.time(task.createdAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 十四批：实际执行的命令（数据一直在 Task.command，此前未渲染 —— 用户第一诉求"看不到命令"）
                    if (task.command.isNotBlank()) {
                        Text(
                            task.command,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = if (task.status == TaskManager.Status.FAILED) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 4,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    if (task.status == TaskManager.Status.RUNNING) {
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { task.progress },
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                        )
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        TextButton(onClick = { expandedId = if (expandedId == task.id) null else task.id }) {
                            Text(if (expandedId == task.id) "收起日志" else "日志（${task.log.size} 行）")
                        }
                        Spacer(Modifier.width(8.dp))
                        if (task.status == TaskManager.Status.RUNNING) {
                            OutlinedButton(onClick = { vm.killTask(task.id) }) { Text("终止") }
                        } else {
                            // P10：已完成任务可清空日志/删除
                            OutlinedButton(onClick = { vm.clearTaskLog(task.id) }) { Text("清空日志") }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = { vm.killTask(task.id) }) { Text("删除") }
                        }
                    }
                    if (expandedId == task.id) {
                        Text(
                            task.logTail(200).ifBlank { "（暂无输出）" },
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp)
                                .verticalScroll(androidx.compose.foundation.rememberScrollState()),
                        )
                    }
                }
            }
                }
            }
            }
        }
    }
}

private fun statusLabel(s: TaskManager.Status) = when (s) {
    TaskManager.Status.RUNNING -> "运行中"
    TaskManager.Status.SUCCESS -> "成功"
    TaskManager.Status.FAILED -> "失败"
    TaskManager.Status.KILLED -> "已终止"
}

@Composable
private fun statusColor(s: TaskManager.Status) = when (s) {
    TaskManager.Status.RUNNING -> MaterialTheme.colorScheme.primary
    TaskManager.Status.SUCCESS -> androidx.compose.ui.graphics.Color(0xFF34A853)
    TaskManager.Status.FAILED -> MaterialTheme.colorScheme.error
    TaskManager.Status.KILLED -> MaterialTheme.colorScheme.onSurfaceVariant
}
