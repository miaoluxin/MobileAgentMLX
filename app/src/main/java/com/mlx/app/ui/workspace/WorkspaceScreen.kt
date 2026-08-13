package com.mlx.app.ui.workspace

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.mlx.app.core.tools.FileBackend
import com.mlx.app.core.tools.RealBackend
import com.mlx.app.core.tools.SafBackend
import com.mlx.app.ui.AppViewModel
import com.mlx.app.ui.MirrorOp
import com.mlx.app.ui.Tab
import java.io.File
import kotlinx.coroutines.launch

/**
 * 文件工作台（I1）：按当前工程类型渲染 ——
 * real 工程 → 工作区真实目录树（完整环境，shell/git/python 直连）；
 * 无工程/旧 SAF 项目 → 手机磁盘目录树（兼容）。
 * 文件管理：长按 → 预览/重命名/复制/移动/删除/附加到对话。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun WorkspaceScreen(vm: AppViewModel) {
    val scope = rememberCoroutineScope()
    // 统一文件后端：real 工程用真实路径，否则 SAF
    val backend: FileBackend = if (vm.projectType == "real" && vm.currentRealPath != null) {
        RealBackend(File(vm.currentRealPath!!))
    } else {
        SafBackend(vm.container.safRepo)
    }
    val isReal = backend is RealBackend

    /** 当前浏览目录（"" = 根）；文件管理器直觉导航：点目录进入、面包屑/↑ 返回上级 */
    var currentDir by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<FileBackend.Entry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<FileBackend.Entry>>(emptyList()) }
    var preview by remember { mutableStateOf<FileBackend.Entry?>(null) }
    var actionTarget by remember { mutableStateOf<FileBackend.Entry?>(null) }
    var renameDialog by remember { mutableStateOf(false) }
    var renameValue by remember { mutableStateOf("") }
    var pathDialogMode by remember { mutableStateOf("copy") }
    var pathDialogShow by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var createDialogMode by remember { mutableStateOf("file") } // file | dir
    var createDialogShow by remember { mutableStateOf(false) }
    var createDialogValue by remember { mutableStateOf("") }
    /** 剪贴板（复制→粘贴）：记录源相对路径，目录切换/页面切换间保留 */
    var copiedPath by remember { mutableStateOf<String?>(null) }

    /** 加载当前目录内容（进入/返回/增删改后刷新） */
    fun reloadRoot() {
        loading = true
        scope.launch {
            entries = backend.listDir(currentDir)
            loading = false
        }
    }

    /** 进入目录（搜索结果点目录进入时退出搜索态，否则仍停留搜索视图） */
    fun enterDir(relPath: String) {
        query = ""
        currentDir = relPath
        reloadRoot()
    }

    /** 返回上级（当前目录非根时） */
    fun goUp() {
        if (currentDir.isEmpty()) return
        currentDir = currentDir.substringBeforeLast('/', "")
        reloadRoot()
    }

    LaunchedEffect(vm.currentRealPath, vm.projectType, vm.projectName) {
        currentDir = ""
        reloadRoot()
    }

    fun runSearch() {
        scope.launch {
            searching = true
            searchResults = backend.search(query, "", 50)
            searching = false
        }
    }

    Box(Modifier.fillMaxSize()) {
    // 返回键分层：搜索态先清搜索回目录；子目录返回上级；根部（无搜索且根目录）由 AppRoot 弹退出确认
    BackHandler(enabled = query.isNotBlank()) { query = "" }
    BackHandler(enabled = currentDir.isNotEmpty() && query.isBlank()) { goUp() }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // 页签标题（文档 2.11：页头主标题）
        com.mlx.app.ui.components.PageHeader(
            title = "文件",
            subtitle = "工程目录浏览与文件编辑",
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        // 顶部标题 = 工程切换入口（下拉选择工程；删除工程经 refreshRealProjects 联动消失）
        var projectMenuOpen by remember { mutableStateOf(false) }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                Text(
                    "📁 ${vm.projectName.ifBlank { "未选工程" }} ▾",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth().clickable { projectMenuOpen = true },
                    maxLines = 1,
                )
                DropdownMenu(expanded = projectMenuOpen, onDismissRequest = { projectMenuOpen = false }) {
                    vm.realProjects.forEach { p ->
                        DropdownMenuItem(
                            text = { Text(p.name, maxLines = 1) },
                            trailingIcon = {
                                if (p.path == vm.currentRealPath) {
                                    Text("✓", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                }
                            },
                            onClick = {
                                projectMenuOpen = false
                                if (p.path != vm.currentRealPath) {
                                    vm.switchRealProject(p.id) // 切换后 currentRealPath 变化 → 下方 LaunchedEffect 自动重载目录
                                }
                            },
                        )
                    }
                    if (vm.realProjects.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("暂无工程", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            onClick = { projectMenuOpen = false },
                        )
                    }
                }
            }
        }
        if (!isReal && vm.container.safRepo.treeUri == null && vm.projectType != "saf") {
            // I2：无工程引导（跳会话页创建工程）
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            ) {
                Text("尚未创建工程")
                Spacer(Modifier.height(10.dp))
                Button(onClick = { vm.selectTab(Tab.Chat) }) { Text("去会话页创建工程") }
            }
            return
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("搜索文件名…") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                IconButton(onClick = { runSearch() }) { Text("搜索") }
            },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        )
        HorizontalDivider(Modifier.padding(top = 8.dp))
        // P3：新建文件/文件夹（当前目录或根）
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            OutlinedButton(onClick = { createDialogMode = "file"; createDialogValue = ""; createDialogShow = true }, modifier = Modifier.weight(1f).height(38.dp)) {
                Text("＋ 新建文件", maxLines = 1)
            }
            OutlinedButton(onClick = { createDialogMode = "dir"; createDialogValue = ""; createDialogShow = true }, modifier = Modifier.weight(1f).height(38.dp)) {
                Text("＋ 新建文件夹", maxLines = 1)
            }
        }
        if (searching) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (query.isNotBlank()) {
            LazyColumn(Modifier.fillMaxSize()) {
                items(searchResults, key = { "r_${it.relPath}" }) { entry ->
                    FileRow(
                        entry = entry,
                        indent = 0,
                        // 搜索结果与目录列表一致：目录进入、文件打开全屏编辑预览
                        onClick = { if (entry.isDir) enterDir(entry.relPath) else preview = entry },
                        onLongPress = { actionTarget = entry },
                    )
                }
            }
        } else if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            // 面包屑（可点击跳任意上级）+ 返回上级按钮
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "🏠",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.clickable { currentDir = ""; reloadRoot() },
                    )
                    if (currentDir.isNotEmpty()) {
                        var acc = ""
                        currentDir.split('/').forEach { seg ->
                            acc = if (acc.isEmpty()) seg else "$acc/$seg"
                            Text(
                                " / $seg",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                modifier = Modifier.clickable { currentDir = acc; reloadRoot() },
                            )
                        }
                    }
                }
                if (currentDir.isNotEmpty()) {
                    TextButton(onClick = { goUp() }) { Text("↑ 返回上级") }
                }
            }
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                items(entries, key = { "f_${it.relPath}" }) { entry ->
                    FileRow(
                        entry = entry,
                        indent = 0,
                        onClick = { if (entry.isDir) enterDir(entry.relPath) else preview = entry },
                        onLongPress = { actionTarget = entry },
                    )
                }
                if (entries.isEmpty()) {
                    item {
                        Text(
                            "（空目录）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
        }
    }

    preview?.let { entry ->
        FullscreenFileViewer(
            entry = entry,
            backend = backend,
            onAttach = {
                vm.attachPath(entry.relPath)
                preview = null
            },
            onDismiss = { preview = null },
            onSaved = { reloadRoot() },
        )
    }

    actionTarget?.let { target ->
        ModalBottomSheet(onDismissRequest = { actionTarget = null }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(target.relPath, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Spacer(Modifier.height(8.dp))
                if (target.isDir) {
                    ActionRow("📂 进入此目录") {
                        enterDir(target.relPath)
                        actionTarget = null
                    }
                }
                ActionRow("👁 预览") { actionTarget = null; if (target.isDir) enterDir(target.relPath) else preview = target }
                if (!target.isDir) {
                    ActionRow("✏️ 重命名") { renameValue = target.name; renameDialog = true }
                    // 复制 → 剪贴板（两步：复制 → 目标目录点"粘贴"）
                    ActionRow("📋 复制（剪贴板）") {
                        copiedPath = target.relPath
                        actionTarget = null
                    }
                }
                ActionRow("➡️ 移动到…") { pathDialogMode = "move"; pathDialogShow = true }
                // 粘贴到当前目录（剪贴板非空时）
                if (copiedPath != null) {
                    ActionRow("📋 粘贴「${copiedPath!!.substringAfterLast('/')}」到当前目录") {
                        val srcName = copiedPath!!.substringAfterLast('/')
                        val dest = if (currentDir.isEmpty()) srcName else "$currentDir/$srcName"
                        if (backend.copy(copiedPath!!, dest)) {
                            vm.syncMirrorOps(listOf(MirrorOp("write", dest)))
                        }
                        actionTarget = null
                        reloadRoot()
                    }
                }
                ActionRow("🗑 删除") { confirmDelete = true }
                ActionRow("💬 附加到对话") {
                    vm.attachPath(target.relPath)
                    actionTarget = null
                }
            }
        }
    }
    if (renameDialog) {
        AlertDialog(
            onDismissRequest = { renameDialog = false },
            title = { Text("重命名") },
            text = { OutlinedTextField(value = renameValue, onValueChange = { renameValue = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    actionTarget?.let { t ->
                        val oldRel = t.relPath
                        val newName = renameValue.trim()
                        if (backend.rename(oldRel, newName)) {
                            // 目录即工作区：磁盘源目录原子重命名同步（结果经 Snackbar 反馈）
                            vm.syncMirrorOps(listOf(MirrorOp("rename", oldRel, to = newName)))
                        }
                    }
                    actionTarget = null
                    renameDialog = false
                    reloadRoot()
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { renameDialog = false }) { Text("取消") } },
        )
    }
    if (pathDialogShow) {
        PathInputDialog(
            title = if (pathDialogMode == "copy") "复制到（相对路径）" else "移动到（相对路径）",
            onDismiss = { pathDialogShow = false },
            onConfirm = { dest ->
                actionTarget?.let { t ->
                    if (pathDialogMode == "copy") {
                        if (backend.copy(t.relPath, dest)) {
                            vm.syncMirrorOps(listOf(MirrorOp("write", dest)))
                        }
                    } else {
                        if (backend.move(t.relPath, dest)) {
                            vm.syncMirrorOps(listOf(MirrorOp("move", t.relPath, to = dest)))
                        }
                    }
                }
                actionTarget = null
                pathDialogShow = false
                reloadRoot()
            },
        )
    }
    if (createDialogShow) {
        AlertDialog(
            onDismissRequest = { createDialogShow = false },
            title = { Text(if (createDialogMode == "file") "新建文件" else "新建文件夹") },
            text = {
                OutlinedTextField(
                    value = createDialogValue,
                    onValueChange = { createDialogValue = it },
                    placeholder = { Text("相对路径，如 docs/notes.md") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = createDialogValue.trim()
                    if (name.isNotBlank()) {
                        // 输入含 / 视为相对路径；否则落在当前浏览目录
                        val target = if (name.contains('/') || currentDir.isEmpty()) name else "$currentDir/$name"
                        if (createDialogMode == "file") {
                            if (backend.createFile(target)) vm.syncMirrorOps(listOf(MirrorOp("write", target)))
                        } else {
                            if (backend.createDir(target)) vm.syncMirrorOps(listOf(MirrorOp("mkdir", target)))
                        }
                    }
                    createDialogShow = false
                    reloadRoot()
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { createDialogShow = false }) { Text("取消") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除确认") },
            text = { Text("确定删除「${actionTarget?.relPath}」？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    actionTarget?.let { t ->
                        if (backend.delete(t.relPath)) {
                            vm.syncMirrorOps(listOf(MirrorOp("delete", t.relPath)))
                        }
                    }
                    actionTarget = null
                    confirmDelete = false
                    reloadRoot()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }

    // 文件操作磁盘同步结果反馈（rename/move/delete/copy/create 共享）
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(vm.fileOpMsg) {
        vm.fileOpMsg?.let { msg ->
            // 先消费再展示：showSnackbar 挂起期间切走会残留 → 每次返回重复弹（与 projectOpMsg 同修）
            vm.consumeFileOpMsg()
            snackbarHostState.showSnackbar(msg, withDismissAction = true)
        }
    }
    SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun FileRow(entry: FileBackend.Entry, indent: Int, onClick: () -> Unit, onLongPress: () -> Unit = {}) {
    Row(
        Modifier
            .fillMaxWidth()
            // 单击/长按统一探测（原 clickable + detectTapGestures 双探测器竞争，单击不可靠）
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(start = (indent * 16).dp, top = 6.dp, bottom = 6.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (entry.isDir) "📁" else "📄")
        Text(
            entry.name,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
            maxLines = 1,
        )
        if (!entry.isDir) {
            Text(
                formatSize(entry.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // P3：☰ 三横杠管理入口（与长按同弹层；长按同样唤醒菜单）
        IconButton(onClick = onLongPress, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Filled.Menu, contentDescription = "文件操作", modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun PathInputDialog(title: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true, placeholder = { Text("如 docs/backup.md") }) },
        confirmButton = {
            TextButton(onClick = { if (value.isNotBlank()) onConfirm(value.trim()) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ActionRow(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
    )
}

/**
 * 全屏文件查看器：文本可编辑保存（真实文件）、图片全屏、Office 三件套（docx/xlsx/pptx）预览。
 * 替代原底部弹层（只读）——用户要求"全屏的预览和编辑"。
 */
@Composable
private fun FullscreenFileViewer(
    entry: FileBackend.Entry,
    backend: FileBackend,
    onAttach: () -> Unit,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    var content by remember { mutableStateOf<String?>(null) }
    var editText by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    var imageBytes by remember { mutableStateOf<ByteArray?>(null) }
    var truncated by remember { mutableStateOf(false) }
    var binaryHint by remember { mutableStateOf<String?>(null) }
    var officeResult by remember { mutableStateOf<com.mlx.app.core.common.OoxmlPreview.Result.Text?>(null) }
    var sheetIndex by remember { mutableStateOf(0) }
    var saveHint by remember { mutableStateOf<String?>(null) }

    val ext = entry.name.substringAfterLast('.', "").lowercase()
    val isImage = ext in setOf("png", "jpg", "jpeg", "gif", "webp")
    val officeType = com.mlx.app.core.common.OoxmlPreview.typeOf(entry.name)
    val canEdit = !isImage && officeType == null // 文本类可编辑

    LaunchedEffect(entry.relPath) {
        when {
            isImage -> {
                imageBytes = backend.readBytes(entry.relPath, 8 * 1024 * 1024)
                if (imageBytes == null) binaryHint = "图片读取失败或超过 8MB"
            }
            officeType != null -> {
                val bytes = backend.readBytes(entry.relPath, 20 * 1024 * 1024)
                officeResult = if (bytes != null) com.mlx.app.core.common.OoxmlPreview.parse(bytes, officeType) else null
                if (officeResult == null) binaryHint = "Office 文档解析失败（可能已损坏或超过 20MB）"
            }
            else -> {
                val out = backend.readText(entry.relPath, 1_000_000)
                if (out != null) {
                    content = out.text
                    editText = out.text
                    truncated = out.truncated
                } else {
                    binaryHint = "二进制文件无法预览"
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // 顶部栏：返回 / 文件名 / 附加 / 编辑切换
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                }
                Text(
                    entry.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                OutlinedButton(onClick = onAttach) { Text("附加到对话") }
                if (canEdit && content != null && !truncated) {
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton(onClick = { editing = !editing }) {
                        Text(if (editing) "预览" else "编辑")
                    }
                }
            }
            Text(
                if (truncated) "超过 1MB 已截断（仅预览）" else formatSize(entry.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            when {
                // ---- 图片：全屏查看 ----
                imageBytes != null -> {
                    val bitmap = runCatching {
                        android.graphics.BitmapFactory.decodeByteArray(imageBytes!!, 0, imageBytes!!.size)
                    }.getOrNull()
                    if (bitmap != null) {
                        Box(
                            Modifier.fillMaxSize().background(Color.Black),
                            contentAlignment = Alignment.Center,
                        ) {
                            androidx.compose.foundation.Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = entry.name,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                            )
                        }
                    } else {
                        Text("图片解码失败", modifier = Modifier.padding(16.dp))
                    }
                }
                // ---- Office 三件套 ----
                officeResult != null -> {
                    val result = officeResult!!
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        Text(result.title, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        if (result.table.isNotEmpty()) {
                            // xlsx：sheet 切换 chips + 表格文本
                            if (result.sheets.size > 1) {
                                Row(
                                    Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    result.sheets.forEachIndexed { idx, name ->
                                        AssistChip(
                                            onClick = { sheetIndex = idx },
                                            label = { Text(name) },
                                        )
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                            result.table.forEach { row ->
                                Text(
                                    row.joinToString("  |  "),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(vertical = 1.dp),
                                )
                            }
                            if (result.truncated) {
                                Text(
                                    "…（仅显示前 ${result.table.size} 行）",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            result.paragraphs.forEach { p ->
                                Text(p, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 3.dp))
                            }
                        }
                    }
                }
                // ---- 文本：编辑 / 预览 ----
                content != null -> {
                    if (editing) {
                        Column(Modifier.fillMaxSize()) {
                            OutlinedTextField(
                                value = editText,
                                onValueChange = { editText = it },
                                modifier = Modifier.fillMaxSize().weight(1f).padding(horizontal = 12.dp),
                                textStyle = androidx.compose.ui.text.TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                ),
                            )
                            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.End) {
                                Button(onClick = {
                                    val ok = backend.writeText(entry.relPath, editText)
                                    saveHint = if (ok) "✓ 已保存到 ${entry.relPath}" else "✗ 保存失败"
                                    if (ok) {
                                        editing = false
                                        content = editText
                                        onSaved()
                                    }
                                }) { Text("保存") }
                            }
                            saveHint?.let {
                                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 16.dp))
                            }
                        }
                    } else {
                        com.mlx.app.ui.chat.MarkdownText(
                            content!!,
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp),
                        )
                    }
                }
                // ---- 加载/错误 ----
                else -> {
                    binaryHint?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
                    }
                    if (content == null && imageBytes == null && binaryHint == null && officeResult == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }
}


private fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1fMB".format(bytes / 1e6)
    bytes >= 1000 -> "%.1fKB".format(bytes / 1e3)
    else -> "$bytes B"
}
