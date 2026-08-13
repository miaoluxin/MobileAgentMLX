package com.mlx.app.ui.sessions

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mlx.app.data.store.Session
import com.mlx.app.data.store.WorkspaceRepo
import com.mlx.app.ui.AppViewModel
import com.mlx.app.ui.UiFormats

/**
 * 会话主页面 —— 树状工程结构（工程目录 → 会话）：
 * - 工程节点可折叠/展开（点击工程行切换，默认展开当前工程）
 * - 新建会话时弹层选择目标工程（新建/从本机目录导入）
 * - 关键词搜索跨工程定位会话
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SessionListScreen(vm: AppViewModel) {
    var keyword by remember { mutableStateOf("") }
    // 折叠状态持久化：切 tab/重启后恢复上次展开/折叠（Set 经 Saver 存 ArrayList）
    val expandedSaver = remember {
        Saver<Set<String>, ArrayList<String>>(
            save = { ArrayList(it) },
            restore = { it.toSet() },
        )
    }
    var expandedProjects by rememberSaveable(stateSaver = expandedSaver) { mutableStateOf(emptySet()) }
    // 用户主动折叠/展开过 → 初始展开逻辑不再干预（防止"全部折叠后刷新又自动展开"）
    var userToggledProjects by rememberSaveable { mutableStateOf(false) }
    var showNewSessionSheet by remember { mutableStateOf(false) }
    var showCreateProjectDialog by remember { mutableStateOf(false) }
    var newProjectName by remember { mutableStateOf("") }
    var pickedDirUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var pickedDirName by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<WorkspaceRepo.Project?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<WorkspaceRepo.Project?>(null) }
    // 会话删除确认（删除不可恢复：级联清除备份缓存与 token 历史，防误删）
    var deleteSessionTarget by remember { mutableStateOf<Session?>(null) }
    val dirPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            pickedDirUri = uri
            pickedDirName = uri.lastPathSegment?.substringAfterLast(':') ?: "所选目录"
        }
    }

    // 数据聚合：工程列表 + 各工程会话 + 无归属会话
    val projects = vm.realProjects
    val allSessions = remember(vm.refreshTick, vm.projectName, vm.projectType, keyword) {
        vm.container.sessionStore.list()
    }
    val sessionsOf = { pid: String -> allSessions.filter { it.projectId == pid } }
    // 未分组兜底：projectId 为空、旧 "saf" 标记、或指向已不存在工程的会话（注册表还原后工程永不缺位，
    // 此兜底仅在注册表本身丢失的极端场景生效）—— 修复"重启后会话列表丢失"
    val knownIds = projects.map { it.id }.toSet()
    val orphanSessions = allSessions.filter {
        it.projectId.isEmpty() || it.projectId == "saf" || it.projectId !in knownIds
    }
    val searchResults = if (keyword.isBlank()) emptyList() else allSessions.filter { s ->
        s.title.contains(keyword, ignoreCase = true) ||
            s.messages.any { it.content.contains(keyword, ignoreCase = true) }
    }

    // 按工程目录折叠：默认只展开当前工程，其余折叠（点工程行展开/收起）；
    // 用户主动操作过则不再干预（防"全部折叠后项目刷新又自动展开"）
    LaunchedEffect(projects.size) {
        if (!userToggledProjects && expandedProjects.isEmpty()) {
            expandedProjects = projects.filter { it.path == vm.currentRealPath }.map { it.id }.toSet()
        }
    }

    // 工程操作结果反馈（新建/删除/重命名等；与 WorkspaceScreen 的 fileOpMsg 消费模式一致）
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(vm.projectOpMsg) {
        vm.projectOpMsg?.let { msg ->
            // 先消费再展示：showSnackbar 是挂起函数，后置消费在 Snackbar 未消失时切走会残留 → 每次返回重复弹
            vm.consumeProjectOpMsg()
            snackbarHostState.showSnackbar(msg, withDismissAction = true)
        }
    }

    Box(Modifier.fillMaxSize().statusBarsPadding()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
            item {
                // 页签标题（文档 2.11：页头主标题 + 副标题）
                com.mlx.app.ui.components.PageHeader(
                    title = "会话",
                    subtitle = "工程树状结构 · ${allSessions.size} 个会话",
                )
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    placeholder = { Text("搜索会话（跨工程定位）…") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
            }

            if (searchResults.isNotEmpty()) {
                // 搜索模式：跨工程结果（标注工程归属）
                item {
                    Text("搜索结果（${searchResults.size}）", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 4.dp))
                }
                items(searchResults, key = { "s_${it.id}" }) { session ->
                    SessionCard(
                        session, vm, showProjectLabel = true, projectName = projectNameOf(vm, session),
                        onRequestDelete = { deleteSessionTarget = it },
                    )
                }
            } else if (keyword.isNotBlank()) {
                item {
                    Text("未找到匹配会话", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
                }
            } else {
                // 树状结构：工程节点 → 会话
                if (projects.isEmpty() && orphanSessions.isEmpty()) {
                    item {
                        Text(
                            "尚未创建工程\n点击右下角 + 新建会话并选择工程\n或从手机磁盘导入项目目录\n（已有工程：点击工程行右侧 + 直接新建会话）",
                            modifier = Modifier.fillMaxWidth().padding(top = 96.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                projects.forEach { p ->
                    val expanded = p.id in expandedProjects
                    val sessions = sessionsOf(p.id)
                    item(key = "p_${p.id}") {
                        ProjectHeader(
                            project = p,
                            sessionCount = sessions.size,
                            expanded = expanded,
                            isCurrent = p.path == vm.currentRealPath,
                            onToggle = {
                                userToggledProjects = true
                                expandedProjects = if (expanded) expandedProjects - p.id else expandedProjects + p.id
                            },
                            // 在已有工程下直接新建会话
                            onNewSession = { vm.newSession(p.id) },
                            onRename = {
                                renameTarget = p
                                renameValue = p.name
                            },
                            onDelete = { deleteTarget = p },
                        )
                    }
                    if (expanded) {
                        items(sessions, key = { "ps_${it.id}" }) { session ->
                            SessionCard(session, vm, showProjectLabel = false, onRequestDelete = { deleteSessionTarget = it })
                        }
                    }
                }
                if (orphanSessions.isNotEmpty()) {
                    item(key = "p_orphan") {
                        ProjectHeader(
                            project = null,
                            sessionCount = orphanSessions.size,
                            expanded = true,
                            isCurrent = false,
                            onToggle = {},
                        )
                    }
                    items(orphanSessions, key = { "po_${it.id}" }) { session ->
                        SessionCard(session, vm, showProjectLabel = false, onRequestDelete = { deleteSessionTarget = it })
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = { showNewSessionSheet = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = "新建会话")
        }
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }

    // 新建会话弹层：选择目标工程（或新建/导入）
    if (showNewSessionSheet) {
        ModalBottomSheet(onDismissRequest = { showNewSessionSheet = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("新建会话 —— 选择目标工程", style = MaterialTheme.typography.titleMedium)
                Text("会话将归属所选工程（Agent 文件操作范围随之切换）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                projects.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            showNewSessionSheet = false
                            vm.newSession(p.id)
                        }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("📁 ", style = MaterialTheme.typography.titleSmall)
                        Text(
                            p.name,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                        Text(
                            "${sessionsOf(p.id).size} 会话",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                OutlinedButton(
                    onClick = { showNewSessionSheet = false; showCreateProjectDialog = true },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) { Text("＋ 新建工程（选择手机目录）") }
            }
        }
    }

    // F4：工程重命名
    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名工程") },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.renameProject(target.id, renameValue.trim())
                    renameTarget = null
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } },
        )
    }
    // 会话删除确认（不可恢复：级联清除 .mlx-backup 备份缓存与 token 历史）
    deleteSessionTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteSessionTarget = null },
            title = { Text("删除会话？") },
            text = {
                Text(
                    "「${target.title.take(20)}」\n\n将删除该会话及工程目录 .mlx-backup 下的对应备份缓存与 token 历史，不可恢复。\n\n工程中的实际文件不受影响。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteSession(target.id)
                    vm.refreshSessions()
                    deleteSessionTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteSessionTarget = null }) { Text("取消") } },
        )
    }

    // F5：删除工程（仅镜像与会话，磁盘源目录不受影响）
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除工程？") },
            text = {
                Text(
                    "将删除工程「${target.name}」在应用内的镜像与会话。\n\n磁盘源目录「${target.sourceDir.ifBlank { "（应用内目录）" }}」的文件不受影响。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteRealProject(target.id)
                    deleteTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }

    // F3：新建工程 = 项目名 + 选择磁盘目录（两步）
    if (showCreateProjectDialog) {
        AlertDialog(
            onDismissRequest = { showCreateProjectDialog = false },
            title = { Text("新建工程") },
            text = {
                Column {
                    OutlinedTextField(
                        value = newProjectName,
                        onValueChange = { newProjectName = it },
                        label = { Text("项目名称") },
                        placeholder = { Text("如 我的博客") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { dirPicker.launch(null) },
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                    ) {
                        Text(if (pickedDirName != null) "✓ ${pickedDirName}" else "选择磁盘目录", maxLines = 1)
                    }
                    Text(
                        "选择后该目录即工程工作区，Agent 的改动会自动写回目录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = newProjectName.trim().ifBlank { pickedDirName ?: "工程${projects.size + 1}" }
                        val uri = pickedDirUri
                        showCreateProjectDialog = false
                        newProjectName = ""
                        pickedDirUri = null
                        pickedDirName = null
                        if (uri != null) {
                            vm.createProjectFromDir(name, uri) { pid ->
                                if (pid != null) vm.newSession(pid)
                            }
                        }
                    },
                    enabled = pickedDirUri != null,
                ) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showCreateProjectDialog = false }) { Text("取消") } },
        )
    }
}

/** 工程节点行：项目名 + 磁盘目录名（两级）+ 会话数 + 新建会话 + 长按菜单 */
@Composable
private fun ProjectHeader(
    project: WorkspaceRepo.Project?,
    sessionCount: Int,
    expanded: Boolean,
    isCurrent: Boolean,
    onToggle: () -> Unit,
    onNewSession: (() -> Unit)? = null,
    onRename: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            // 单击折叠/展开 + 长按菜单统一探测（原 clickable + detectTapGestures 双探测器竞争，点击被吞）
            .combinedClickable(onClick = onToggle, onLongClick = { menuOpen = true })
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (expanded) "▾" else "▸",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f).padding(start = 6.dp)) {
            Text(
                if (project != null) "📁 ${project.name}" else "📁 未分组",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            if (project != null) {
                Text(
                    if (project.sourceDir.isNotBlank()) "磁盘: ${project.sourceDir}" else "应用内目录",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        if (isCurrent) {
            Text(
                "当前",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
        Text(
            "$sessionCount 会话",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 4.dp),
        )
        // 在已有工程下直接新建会话（独立点击区，不与折叠/菜单冲突）
        if (project != null && onNewSession != null) {
            IconButton(onClick = onNewSession, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.Add, contentDescription = "在该工程下新建会话", modifier = Modifier.size(18.dp))
            }
        }
        if (project != null && onRename != null) {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(26.dp)) {
                Icon(Icons.Filled.MoreVert, contentDescription = "工程操作", modifier = Modifier.size(16.dp))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("重命名") }, onClick = { menuOpen = false; onRename() })
                DropdownMenuItem(text = { Text("删除工程") }, onClick = { menuOpen = false; onDelete?.invoke() })
            }
        }
    }
    HorizontalDivider(Modifier.padding(horizontal = 6.dp))
}

/** 会话卡片（树内二级节点；搜索模式带工程标注） */
@Composable
private fun SessionCard(
    session: Session,
    vm: AppViewModel,
    showProjectLabel: Boolean,
    projectName: String = "",
    onRequestDelete: (Session) -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 2.dp, top = 4.dp, bottom = 4.dp)
            .clickable { vm.openSession(session.id) },
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(36.dp)
                    .background(
                        brush = Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f),
                            )
                        ),
                        shape = MaterialTheme.shapes.small,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    session.title.take(1).uppercase(),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    session.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                )
                val last = session.messages.lastOrNull()
                if (last != null) {
                    Text(
                        last.content.replace('\n', ' ').take(50),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Text(
                    (if (showProjectLabel && projectName.isNotEmpty()) "📁 $projectName · " else "") +
                        "${UiFormats.time(session.updatedAt)} · ${session.model.uppercase()} · 命中 ${UiFormats.percent(session.cacheHitRate())}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.MoreVert, contentDescription = "操作", modifier = Modifier.size(18.dp))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("继续会话") }, onClick = {
                    menuOpen = false
                    vm.openSession(session.id)
                })
                DropdownMenuItem(text = { Text("复制为副本") }, onClick = {
                    menuOpen = false
                    vm.forkSession(session.id)
                })
                DropdownMenuItem(text = { Text("删除") }, onClick = {
                    menuOpen = false
                    onRequestDelete(session)
                })
            }
        }
    }
}

private fun projectNameOf(vm: AppViewModel, session: Session): String =
    if (session.projectId.isNotEmpty() && session.projectId != "saf") {
        // 会话自带工程名快照优先（重启/注册表异常兜底）；否则注册表解析，最后退回 id
        session.projectName.ifBlank {
            vm.realProjects.firstOrNull { it.id == session.projectId }?.name ?: session.projectId
        }
    } else {
        "SAF"
    }
