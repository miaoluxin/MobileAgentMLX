package com.mlx.app.ui

import android.app.Application
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mlx.app.MlxApp
import com.mlx.app.core.policy.Decision
import com.mlx.app.core.provider.AutoConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 文件页手动操作 → 磁盘源目录回写指令（op：write / delete / mkdir / rename / move，rename 的 to=新文件名，move 的 to=目标相对路径） */
data class MirrorOp(val op: String, val path: String, val to: String = "")

/** 底部导航 Tab（对应文档 5.2 节导航框架） */
enum class Tab(val label: String, val icon: ImageVector) {
    Chat("对话", Icons.Filled.Send),
    Files("文件", Icons.Filled.List),
    Jobs("任务", Icons.Filled.PlayArrow),
    Stats("统计", Icons.Filled.Info),
    Settings("设置", Icons.Filled.Settings),
}

/** 应用级状态：配置、导航、会话列表 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    val container = (app as MlxApp).container

    // ---- 配置状态（来自 AppStore 流） ----
    var configured by mutableStateOf(false)
        private set
    var projectName by mutableStateOf("")
        private set
    var projectType by mutableStateOf("saf") // saf | real（完整环境工作区）
        private set
    var realProjects by mutableStateOf<List<com.mlx.app.data.store.WorkspaceRepo.Project>>(emptyList())
        private set
    /** 所有文件访问权限（MANAGE_EXTERNAL_STORAGE，目录即工作区 2.0） */
    val allFilesAccess get() = container.allFilesAccess.value
    var projectOpMsg by mutableStateOf<String?>(null)
        private set
    /** 文件页手动操作的磁盘同步结果（Snackbar 反馈；失败不再静默） */
    var fileOpMsg by mutableStateOf<String?>(null)
        private set
    var modelTier by mutableStateOf("flash")
        private set
    var flashModel by mutableStateOf(com.mlx.app.data.store.AppStore.DEFAULT_FLASH_MODEL)
        private set
    var proModel by mutableStateOf(com.mlx.app.data.store.AppStore.DEFAULT_PRO_MODEL)
        private set
    var policyMode by mutableStateOf("review")
        private set
    var planMode by mutableStateOf(false)
        private set
    var themeMode by mutableStateOf("system")
        private set
    var budgetUsd by mutableStateOf(0.0)
        private set

    // ---- 导航状态 ----
    var tab by mutableStateOf(Tab.Chat)
        private set
    /** 设置页直达区块（P2-19：/skills /memory /mcp 等命令定位到对应设置区块；消费后清空） */
    var settingsSection by mutableStateOf<String?>(null)
        private set
    var activeSessionId by mutableStateOf<String?>(null)
        private set
    var pendingAttach by mutableStateOf<String?>(null)
        private set
    /** 当前工程真实路径（必须在 init 之前声明 —— init 的 flow collect 可能同步触发 setter） */
    var currentRealPath by mutableStateOf<String?>(null)
        private set
    // ---- 完整环境（嵌入式 Linux，M6）----
    // 必须在 init 之前声明：init 末尾自动解压会同步调用 installEnv()（访问 envBusy），
    // 属性晚于 init 声明时委托对象为 null → getValue() NPE → 构造函数崩溃闪退
    var envInstalled by mutableStateOf(false)
        private set
    var envBusy by mutableStateOf(false)
        private set
    var envProgress by mutableStateOf(0L)
        private set
    var envCheckResult by mutableStateOf<String?>(null)
        private set
    /** 启动时"所有文件访问"授权引导（未授权且未提示过 → 弹窗一次） */
    var allFilesPromptNeeded by mutableStateOf(false)
        private set

    init {
        // 启动诊断：记录数据统计（Logcat: MLX-Diag），排查数据"丢失"类问题
        viewModelScope.launch {
            android.util.Log.i(
                "MLX-Diag",
                "启动数据统计: projects=${container.workspaceRepo.list().size}, " +
                    "sessions=${container.sessionStore.list().size}, " +
                    "apiKeySet=${container.appStore.hasApiKeyFlow.first()}",
            )
        }
        // 首启简化后仅需 API Key 即可进入（工程选择移到主页空态引导，不再要求 projectUri）
        viewModelScope.launch {
            container.appStore.hasApiKeyFlow.collect { configured = it }
        }
        viewModelScope.launch { container.appStore.projectNameFlow.collect { projectName = it ?: "" } }
        viewModelScope.launch {
            container.appStore.projectTypeFlow.collect { projectType = it }
            realProjects = container.workspaceRepo.list()
        }
        viewModelScope.launch {
            container.appStore.projectPathFlow.collect { currentRealPath = it }
        }
        // 旧镜像工程迁移（目录即工作区 1.0 → 2.0 注册表模式；幂等，可安全重复）
        viewModelScope.launch {
            kotlinx.coroutines.delay(150)
            val migrated = com.mlx.app.data.store.WorkspaceMigration.run(
                getApplication(), container.projectRegistry, container.workspaceRepo, container.sessionStore,
            )
            if (migrated > 0) {
                android.util.Log.i("MLX-Diag", "迁移 $migrated 个旧工程到注册表")
                refreshRealProjects()
                refreshSessions()
            }
        }
        // 恢复机制：应用数据被清后，从磁盘目录备份自动恢复会话（双源：工程镜像 + 系统授权目录）
        viewModelScope.launch {
            kotlinx.coroutines.delay(300)
            val restored = restoreSessionsFromBackups()
            // 无条件刷新工程与会话：授权目录可能自动注册工程（注册表丢失自愈），节点必须出现
            refreshRealProjects()
            if (restored > 0) {
                android.util.Log.i("MLX-Diag", "从磁盘备份恢复 $restored 个会话")
                refreshSessions()
            }
        }
        viewModelScope.launch { container.appStore.modelTierFlow.collect { modelTier = it } }
        viewModelScope.launch { container.appStore.flashModelFlow.collect { flashModel = it } }
        viewModelScope.launch { container.appStore.proModelFlow.collect { proModel = it } }
        viewModelScope.launch { container.appStore.policyModeFlow.collect { policyMode = it } }
        viewModelScope.launch { container.appStore.planModeFlow.collect { planMode = it } }
        viewModelScope.launch { container.appStore.themeFlow.collect { themeMode = it } }
        viewModelScope.launch { container.appStore.budgetUsdFlow.collect { budgetUsd = it } }
        viewModelScope.launch {
            container.appStore.rulesFlow.collect { container.policy.setRules(it) }
        }
        // 启动恢复已授权的项目树
        viewModelScope.launch {
            val uriStr = container.appStore.projectUriFlow.first() ?: return@launch
            val uri = Uri.parse(uriStr)
            if (container.safRepo.hasPersistedPermission(uri)) {
                container.safRepo.bindTree(uri, container.appStore.projectNameFlow.first())
            }
        }
        // 启动自动解压完整环境（本地 assets 解压，零网络；installEnv 内部防重入，
        // 失败不阻塞主流程 —— 可去 设置 > 完整环境 重试；进度/完成提示由 UI 层展示）
        if (!container.embeddedEnv.installed) installEnv()
        // 启动时"所有文件访问"授权引导（Android 11+ 未授权且未提示过 → 弹窗引导，授权一次永久生效）
        viewModelScope.launch {
            if (android.os.Build.VERSION.SDK_INT >= 30 &&
                !android.os.Environment.isExternalStorageManager() &&
                !container.appStore.allFilesPrompted()
            ) {
                allFilesPromptNeeded = true
            }
        }
    }

    /** 关闭"所有文件访问"授权引导（记录"已提示"，不再每次启动弹） */
    fun dismissAllFilesPrompt() {
        allFilesPromptNeeded = false
        viewModelScope.launch { container.appStore.markAllFilesPrompted() }
    }

    // ---- 导航 ----
    fun selectTab(t: Tab, section: String? = null) {
        if (t == Tab.Chat) {
            // 对话 Tab：已在会话内则保持（防止误退）；否则切到会话列表
            if (activeSessionId != null) return
            tab = Tab.Chat
            return
        }
        // 其它 Tab：从会话层切换时先退出会话，保证底部导航始终可跳转
        activeSessionId = null
        tab = t
        // 设置页直达区块（P2-19）
        if (t == Tab.Settings && section != null) settingsSection = section
    }

    /** 消费设置页直达区块（SettingsScreen 定位后调用） */
    fun consumeSettingsSection(): String? = settingsSection.also { settingsSection = null }

    fun openSession(id: String) {
        // 会话 ↔ 工程上下文绑定：打开会话时把其工程设为当前（Agent 文件操作目标一致）
        val s = container.sessionStore.load(id)
        val pid = s?.projectId
        if (!pid.isNullOrBlank() && pid != "saf") {
            val p = container.workspaceRepo.find(pid)
            if (p != null && p.path != currentRealPath) {
                viewModelScope.launch { container.appStore.setRealProject(p.path, p.name) }
            }
        }
        activeSessionId = id
        tab = Tab.Chat
    }

    fun closeSession() {
        activeSessionId = null
    }

    /** 当前工程 id（P3：工程下多对话；real 用工作区 id，saf 统一 "saf"） */
    val currentProjectId: String
        get() = when (projectType) {
            "real" -> realProjects.firstOrNull { it.path == currentRealPath }?.id
                ?: realProjects.firstOrNull()?.id ?: ""
            else -> "saf"
        }

    /** 新建会话并归属指定工程（树状结构：会话必须属于某工程） */
    fun newSession(projectId: String): String {
        // 绑定工程上下文（Agent 文件操作目标随会话走）
        var projectName = ""
        if (projectId != "saf") {
            val p = container.workspaceRepo.find(projectId)
            if (p != null) {
                projectName = p.name
                viewModelScope.launch { container.appStore.setRealProject(p.path, p.name) }
            }
        }
        val s = container.sessionStore.create(model = modelTier, projectId = projectId, projectName = projectName)
        container.sessionStore.save(s)
        activeSessionId = s.id
        tab = Tab.Chat
        return s.id
    }

    fun deleteSession(id: String) {
        container.sessionStore.delete(id)
        // 二十二批（审计）：清理引擎侧 planGate（此前 per-session PlanGate 永不回收，长期使用累积）
        container.engine.removePlanGate(id)
        if (activeSessionId == id) activeSessionId = null
    }

    /** 复制为副本（对应 PC --copy fork）并打开 */
    fun forkSession(id: String) {
        container.sessionStore.fork(id)?.let { openSession(it.id) }
    }

    /** 从指定消息之后分支（对应 /branch）并打开；P11：标题标注分支来源 */
    fun branchSession(id: String, afterMessageIndex: Int) {
        val src = container.sessionStore.load(id) ?: return
        val branch = container.sessionStore.branch(id, afterMessageIndex) ?: return
        branch.title = src.title + "（分支自「${src.title.take(12)}」）"
        container.sessionStore.save(branch)
        openSession(branch.id)
    }

    /** 会话列表刷新计数（删除/剪枝后触发列表重读） */
    var refreshTick by mutableStateOf(0)
        private set

    fun refreshSessions() {
        refreshTick++
    }

    /**
     * 恢复机制（多工程闭环）：从磁盘目录备份恢复会话，双源收集 ——
     * 源1：各工程镜像 .mlx-backup/（filesDir 未清时）；
     * 源2：**系统持久授权记录**（persistedUriPermissions 由系统管理，不随应用数据清除丢失）
     *      遍历所有用户授权过的磁盘目录读取 .mlx-backup/ —— 覆盖多工程不同文件夹场景。
     */
    private suspend fun restoreSessionsFromBackups(): Int {
        var restored = 0
        // 源1：工程镜像
        for (p in container.workspaceRepo.list()) {
            for (backupFile in container.workspaceRepo.backupSessionsOf(p)) {
                val id = backupFile.name.removeSuffix(".json")
                // 已删除会话黑名单：删除残留备份并跳过（防已删会话复活）
                if (container.appStore.isSessionDeleted(id)) {
                    backupFile.delete()
                    continue
                }
                if (!container.sessionStore.exists(id) && container.sessionStore.importFromBackup(backupFile)) {
                    restored++
                }
                // 重绑（已存在也执行，幂等）：旧版本备份会话归属到备份所在工程
                rebindSessionToProject(id, p.id, p.name)
            }
        }
        // 源2：所有系统持久授权的磁盘目录（多工程场景的可靠来源）
        val app = getApplication<android.app.Application>()
        val resolver = app.contentResolver
        val saf = com.mlx.app.data.saf.SafRepo(app)
        for (perm in resolver.persistedUriPermissions) {
            if (!perm.isReadPermission) continue
            if (!saf.bindTree(perm.uri, null)) continue
            // 备份所在目录 → 当前工程（重绑依据）
            // 授权目录 = 工程目录的天然证据：注册表丢失/异常时自动注册（幂等），工程节点不丢
            val ownerProject = com.mlx.app.data.saf.TreePathResolver.resolve(app, perm.uri)
                ?.let { rp ->
                    container.workspaceRepo.findByPath(rp)
                        ?: if (java.io.File(rp).isDirectory) {
                            container.workspaceRepo.create(java.io.File(rp).name, rp)
                        } else null
                }
            val entries = saf.listDir(".mlx-backup")
            for (e in entries.filter { it.name.endsWith(".json") }) {
                val id = e.name.removeSuffix(".json")
                // 已删除会话黑名单：删除残留备份并跳过（防已删会话复活）
                if (container.appStore.isSessionDeleted(id)) {
                    saf.delete(e.relPath)
                    continue
                }
                if (container.sessionStore.exists(id)) {
                    ownerProject?.let { rebindSessionToProject(id, it.id, it.name) }
                    continue
                }
                val text = saf.readText(e.relPath)?.text ?: continue
                val sessionsDir = java.io.File(container.sessionStore.directoryForDiag() ?: continue)
                val dest = java.io.File(sessionsDir, "$id.json")
                dest.writeText(text)
                restored++
                ownerProject?.let { rebindSessionToProject(id, it.id, it.name) }
            }
        }
        // 注册表兜底重建（极端场景：应用数据被清，注册表与工程目录一起丢失，仅剩磁盘备份恢复的会话）：
        // 按会话 projectId 补建占位条目 —— 会话树完整展现（"恢复·xxx" 节点），不依赖"新建工程才显现"
        if (restored > 0) {
            val known = container.workspaceRepo.registryEntries().map { it.id }.toSet()
            val sessions = container.sessionStore.list()
            val missing = sessions
                .map { it.projectId }
                .filter { it.isNotBlank() && it != "saf" && it !in known }
                .distinct()
            // 占位条目优先用会话冗余的工程名快照（注册表丢失场景名字也不丢）
            val nameByPid = sessions.associate { it.projectId to it.projectName }
            for (pid in missing) {
                container.projectRegistry.upsert(
                    com.mlx.app.data.store.ProjectRegistry.Entry(
                        id = pid,
                        name = nameByPid[pid]?.ifBlank { null } ?: "恢复·${pid.take(6)}",
                        rootPath = java.io.File(getApplication<android.app.Application>().filesDir, "projects/$pid").absolutePath,
                        legacy = true,
                    )
                )
            }
            if (missing.isNotEmpty()) {
                android.util.Log.i("MLX-Diag", "注册表兜底重建 ${missing.size} 个工程条目（会话归属不丢）")
            }
        }
        return restored
    }

    /**
     * 恢复会话后重绑到备份所在目录的工程（修复：旧版本备份会话 projectId 失效 → 重启后落"未分组"）。
     * 幂等：归属已正确则不写盘；多工程安全（只重绑到备份所在目录对应的工程）。
     */
    private fun rebindSessionToProject(id: String, projectId: String, projectName: String) {
        runCatching {
            container.sessionStore.load(id)?.let { s ->
                if (s.projectId != projectId || s.projectName != projectName) {
                    s.projectId = projectId
                    s.projectName = projectName
                    container.sessionStore.save(s)
                }
            }
        }
    }

    /** 备份全部会话到当前工程镜像 .mlx-backup/（随自动回写落盘到磁盘目录，不受应用清理影响） */
    fun backupSessions(onDone: (String) -> Unit) {
        viewModelScope.launch {
            val projectPath = currentRealPath
            if (projectPath == null) {
                onDone("请先创建/切换工程后再备份")
                return@launch
            }
            val sessions = container.sessionStore.list()
            if (sessions.isEmpty()) {
                onDone("暂无会话可备份")
                return@launch
            }
            val backupDir = java.io.File(projectPath, ".mlx-backup")
            backupDir.mkdirs()
            var count = 0
            for (s in sessions) {
                val f = java.io.File(backupDir, "${s.id}.json")
                f.writeText(com.mlx.app.data.store.SessionStore.toJson(s))
                count++
            }
            // 同步回写磁盘源目录（目录即工作区）
            val project = container.workspaceRepo.list().firstOrNull { it.path == projectPath }
            if (project != null) {
                container.workspaceRepo.syncToSource(project, listOf(".mlx-backup/${sessions.first().id}.json"))
            }
            onDone("✓ 已备份 $count 个会话到工程磁盘目录 .mlx-backup/")
        }
    }

    fun pruneSessions(days: Int) {
        if (days > 0) {
            container.sessionStore.prune(days)
            refreshSessions()
        }
    }

    /** /init：扫描项目生成 REASONIX.md（指令文件） */
    fun initProject(onDone: (String) -> Unit) {
        viewModelScope.launch {
            val saf = container.safRepo
            val tree = saf.listTree("", 2)
            val files = tree.filter { !it.isDir }.take(300)
            val dirs = tree.filter { it.isDir }
            val md = buildString {
                appendLine("# REASONIX.md")
                appendLine()
                appendLine("> 由 MLX Mobile /init 自动生成。本文件作为项目指令文件注入系统提示（静态缓存）。")
                appendLine()
                appendLine("## 项目结构摘要（深度 2 扫描：${dirs.size} 目录 / ${files.size} 文件，最多列出 300 个）")
                appendLine()
                for (d in dirs.take(60)) appendLine("📁 ${d.relPath}/")
                for (f in files.take(200)) appendLine("📄 ${f.relPath}")
                appendLine()
                appendLine("## 工作约定")
                appendLine("- 修改文件前先读取确认；优先精确替换（edit_file）")
                appendLine("- 遵循项目既有代码风格")
                appendLine("- 需要了解更多结构时使用 list_files/search_files")
            }
            val ok = saf.writeText("REASONIX.md", md)
            onDone(if (ok) "已生成 REASONIX.md（${files.size} 文件摘要）" else "写入失败：检查项目文件夹授权")
        }
    }

    fun setGoal(goal: String) = viewModelScope.launch { container.appStore.setGoal(goal) }
    fun setCompactRatio(ratio: Double) = viewModelScope.launch { container.appStore.setCompactRatio(ratio) }

    fun refreshEnvStatus() {
        envInstalled = container.embeddedEnv.installed
    }

    fun installEnv() {
        if (envBusy) return
        envBusy = true
        envProgress = 0
        container.embeddedEnv.extractFromAssets(
            onProgress = { envProgress = it },
            onDone = { ok, err ->
                envBusy = false
                envInstalled = container.embeddedEnv.installed
                envCheckResult = if (ok) container.embeddedEnv.selfCheck() else err
            },
        )
    }

    fun checkEnv() {
        envCheckResult = container.embeddedEnv.selfCheck()
    }

    fun deleteEnv() {
        container.embeddedEnv.deleteEnvironment()
        envInstalled = false
        envCheckResult = null
    }

    // ---- 完整环境项目（真实路径工作区，M6.2） ----
    fun refreshRealProjects() {
        realProjects = container.workspaceRepo.list()
    }

    /** 快速空工程：直接在共享存储建真实目录（MANAGE_EXTERNAL_STORAGE 下 File.mkdirs 可用） */
    fun createRealProject(name: String) {
        val safe = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "新工程" }
        val base = java.io.File(
            android.os.Environment.getExternalStorageDirectory(), "Download/MLXProjects"
        ).apply { mkdirs() }
        var target = java.io.File(base, safe)
        var i = 1
        while (target.exists()) { target = java.io.File(base, "${safe}_$i"); i++ }
        val created = target.mkdirs()
        if (!created) {
            projectOpMsg = "✗ 创建目录失败（请检查「所有文件访问权限」）"
            return
        }
        val p = container.workspaceRepo.create(name, target.absolutePath)
        viewModelScope.launch {
            container.appStore.setRealProject(p.path, p.name)
        }
        projectOpMsg = "✓ 工程已建立（${target.absolutePath}）"
        refreshRealProjects()
        refreshSessions()
    }

    /**
     * 目录即工作区 2.0（F3）：新建工程 = 项目名 + 选磁盘目录 →
     * **解析即绑定**（tree URI → 真实路径，零复制零镜像）→ 注册表登记 → 设为当前工程。
     * 非 ExternalStorageProvider 目录（网盘等）→ 降级 legacy SAF 镜像模式（功能不丢）。
     */
    fun createProjectFromDir(name: String, uri: Uri, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val dirName = uri.lastPathSegment?.substringAfterLast(':') ?: name
            // 真实路径解析（纯字符串/DocumentsContract 操作，不依赖 MANAGE_EXTERNAL_STORAGE）
            val realPath = com.mlx.app.data.saf.TreePathResolver.resolve(getApplication(), uri)
            if (realPath != null) {
                // 统一工作区 = SAF 真实目录：文件工具/shell/python 全部直接操作该目录
                val p = container.workspaceRepo.create(name, realPath) // 幂等：同目录重复绑定 → 同一工程
                // SAF 句柄持久化：未授予"所有文件访问"时文件工具降级 SAF 后端读取（修复：只见空文件夹）
                container.safRepo.bindTree(uri, dirName)
                container.appStore.setUnifiedProject(uri.toString(), p.path, p.name)
                // 未授予"所有文件访问" → 非阻塞提示设置页入口（SAF 直连已可正常读写，授权为可选增强）
                projectOpMsg = if (allFilesAccess) {
                    "✓ 工程已建立（${realPath}）"
                } else {
                    "✓ 工程已建立（${realPath}）。未授予“所有文件访问”，当前经 SAF 直连工作区（可正常读写）；" +
                        "如需更强文件操作可到 设置 > 文件访问权限 授权"
                }
                refreshRealProjects()
                refreshSessions()
                onDone(p.id)
                return@launch
            }
            // 降级 legacy：非本地存储 provider（网盘等）走旧镜像流程
            projectOpMsg = "正在建立工程（目录类型不支持真实路径，使用兼容模式）…"
            val p = container.workspaceRepo.create(name)
            val bound = container.workspaceRepo.bindSource(p, uri, dirName) { c, t ->
                projectOpMsg = "复制目录中 $c/$t 文件"
            }
            if (bound != null) {
                container.appStore.setRealProject(bound.path, bound.name)
                projectOpMsg = "✓ 工程已建立（兼容模式，${dirName}）"
                refreshRealProjects()
                refreshSessions()
                onDone(bound.id)
            } else {
                container.appStore.setRealProject(p.path, p.name)
                refreshRealProjects()
                refreshSessions()
                projectOpMsg = "✗ 目录复制失败，已保留空工程「${p.name}」，可重试"
                onDone(null)
            }
        }
    }

    /**
     * 文件页手动操作 → 立即同步回写磁盘源目录（目录即工作区闭环）。
     * op ∈ write(镜像内容写回) / delete(磁盘删除) / mkdir(磁盘建目录) /
     *        rename(原子重命名 renameDocument，to=新文件名) / move(原子移动 moveDocument，to=目标相对路径)。
     * - IO 线程执行；结果经 fileOpMsg 反馈（失败可见，不再静默丢同步）
     * - 重命名/移动不再用"写新+删旧"两步（非原子，磁盘会残留旧文件/旧名）
     * - GlobalScope：同步须跨 Activity 生命周期 —— 用户操作后立即退出应用也要落盘
     */
    fun syncMirrorOps(ops: List<MirrorOp>) {
        if (ops.isEmpty()) return
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            suspend fun report(msg: String) = withContext(Dispatchers.Main) { fileOpMsg = msg }
            val path = currentRealPath ?: return@launch
            val project = container.workspaceRepo.list().firstOrNull { it.path == path } ?: return@launch
            // 目录即工作区 2.0：真实路径工程已直接操作磁盘目录，无需回写（legacy 镜像才回写）
            if (!project.legacy) return@launch
            if (project.sourceUri.isBlank()) return@launch
            val app = getApplication<android.app.Application>()
            val saf = com.mlx.app.data.saf.SafRepo(app)
            if (!saf.bindTree(android.net.Uri.parse(project.sourceUri), project.sourceDir)) {
                report("⚠ 磁盘同步失败：磁盘目录授权不可用，请到设置页重新绑定工程目录")
                return@launch
            }
            val errors = mutableListOf<String>()
            for (op in ops) {
                when (op.op) {
                    "write" -> {
                        val src = java.io.File(project.root, op.path)
                        if (!src.isFile) { errors += "镜像无文件「${op.path}」"; continue }
                        if (!saf.writeText(op.path, src.readText(Charsets.UTF_8))) errors += "写入「${op.path}」失败"
                    }
                    "delete" -> if (!saf.delete(op.path)) errors += "删除「${op.path}」失败"
                    "mkdir" -> if (!saf.createDirectory(op.path)) errors += "创建目录「${op.path}」失败"
                    "rename" -> {
                        if (saf.rename(op.path, op.to)) {
                            // 旧名文档仍存在（此前"写新+删旧"失败残留的重复文件）→ 镜像已无旧名，按镜像清掉
                            if (saf.resolve(op.path) != null) saf.delete(op.path)
                        }
                        else errors += if (saf.resolve(targetRel(op.path, op.to)) != null) "重命名失败：磁盘已存在同名文件「${op.to}」" else "重命名「${op.path}」失败"
                    }
                    "move" -> {
                        if (!saf.move(op.path, op.to)) {
                            // 兜底：目标目录不存在等场景 → 建目录 + 写新 + 删旧
                            val mirror = java.io.File(project.root, op.to)
                            val parentDir = op.to.substringBeforeLast('/', "")
                            val createdDir = parentDir.isBlank() || saf.createDirectory(parentDir)
                            val okWrite = createdDir && mirror.isFile && saf.writeText(op.to, mirror.readText(Charsets.UTF_8))
                            val okDelete = saf.delete(op.path)
                            if (!(okWrite && okDelete)) errors += "移动「${op.path}」失败"
                        }
                    }
                }
            }
            report(if (errors.isEmpty()) "✓ 已同步到磁盘目录" else "⚠ 磁盘同步失败：${errors.first()}${if (errors.size > 1) "（共 ${errors.size} 处）" else ""}")
        }
    }

    /** 同目录重命名目标相对路径 */
    private fun targetRel(path: String, newName: String): String {
        val parent = path.substringBeforeLast('/', "")
        return if (parent.isEmpty()) newName else "$parent/$newName"
    }

    fun consumeFileOpMsg() { fileOpMsg = null }

    fun consumeProjectOpMsg() { projectOpMsg = null }

    /** 重命名工程（F4 长按菜单） */
    fun renameProject(id: String, name: String) {
        container.workspaceRepo.rename(id, name)
        refreshRealProjects()
        refreshSessions()
    }

    fun switchRealProject(id: String) {
        viewModelScope.launch {
            val p = container.workspaceRepo.find(id) ?: return@launch
            container.appStore.setRealProject(p.path, p.name)
            refreshSessions()
        }
    }

    /**
     * 删除工程（级联清理，安全边界见 SessionCascade 注释）：
     * 1) 先删该工程全部会话（逐 id 级联 → 备份/检查点/黑名单随之清理）
     * 2) 移除注册表条目（legacy 镜像目录整体删除）
     * 3) 真实路径工程只补删 App 生成的 .mlx-backup/ 元数据子目录，绝不碰用户磁盘工作文件
     */
    fun deleteRealProject(id: String) {
        container.sessionStore.list().filter { it.projectId == id }
            .forEach {
                container.sessionStore.delete(it.id)
                container.engine.removePlanGate(it.id) // 二十二批：级联清理 planGate
            }
        val p = container.workspaceRepo.find(id)
        container.workspaceRepo.delete(id)
        if (p != null && !p.legacy) {
            java.io.File(p.root, ".mlx-backup").deleteRecursively()
        }
        refreshRealProjects()
        refreshSessions()
    }

    // ---- 后台任务（前台服务） ----
    fun startIndexTask() {
        val task = com.mlx.app.core.tasks.TaskManager.Task(
            id = "t${System.currentTimeMillis()}",
            name = "项目全量索引",
            type = "index",
            projectId = currentProjectId,            // 任务页按工程归属展示
            projectName = projectName,               // 工程名快照（任务页展示不依赖注册表解析）
            sessionId = activeSessionId ?: "",        // 会话内触发则归属该会话
        )
        container.taskStore.save(container.taskStore.list() + task)
        val intent = android.content.Intent(getApplication(), com.mlx.app.core.tasks.TaskService::class.java).apply {
            action = com.mlx.app.core.tasks.TaskManager.ACTION_START
            putExtra(com.mlx.app.core.tasks.TaskManager.EXTRA_TASK_ID, task.id)
            putExtra(com.mlx.app.core.tasks.TaskManager.EXTRA_TASK_TYPE, task.type)
        }
        androidx.core.content.ContextCompat.startForegroundService(getApplication(), intent)
    }

    fun killTask(id: String) {
        // 真正杀进程树（shell/工具进程句柄都在注册表里，任务页终止不再只是标状态）
        container.processRegistry.destroy(id)
        val tasks = container.taskStore.list()
        val t = tasks.firstOrNull { it.id == id } ?: return
        if (t.status == com.mlx.app.core.tasks.TaskManager.Status.RUNNING) {
            t.status = com.mlx.app.core.tasks.TaskManager.Status.KILLED
            t.finishedAt = System.currentTimeMillis()
            t.appendLog("已终止")
            container.taskStore.save(tasks)
            getApplication<android.app.Application>().startService(
                android.content.Intent(getApplication(), com.mlx.app.core.tasks.TaskService::class.java)
                    .setAction(com.mlx.app.core.tasks.TaskManager.ACTION_STOP)
            )
        } else {
            container.taskStore.save(tasks.filterNot { it.id == id })
        }
        refreshSessions()
    }

    /** 清空任务日志（保留任务本体与状态） */
    fun clearTaskLog(id: String) {
        container.taskStore.update(id) { it.log.clear() }
    }

    /**
     * 查询账户余额（P2-20：成本条/统计页共用；DeepSeek /user/balance）。
     * 抽自 StatsScreen 原私有实现 —— 会话详情成本条回合后展示余额（充值决策依据）。
     */
    suspend fun loadBalanceText(): String {
        val key = container.appStore.apiKeyPlain()
        if (key.isNullOrBlank()) return "未配置 API Key"
        val baseUrl = try { container.appStore.baseUrl() } catch (e: Exception) { "https://api.deepseek.com/v1" }
        return container.llm.balance(key, baseUrl).fold(
            onSuccess = { b ->
                val info = b.infos.firstOrNull()
                if (info != null) {
                    "${info.currency} ${"%.2f".format(info.total)}（赠送 ${"%.2f".format(info.granted)} / 充值 ${"%.2f".format(info.toppedUp)}）"
                } else {
                    "查询成功，暂无余额信息"
                }
            },
            onFailure = { "余额获取失败" },
        )
    }

    /** 清理成本记录（projectId = null 清全部；否则清该工程全部会话的成本） */
    fun clearCosts(projectId: String?) {
        var cleared = 0
        for (s in container.sessionStore.list()) {
            if ((projectId == null || s.projectId == projectId) && s.costs.isNotEmpty()) {
                s.costs.clear()
                container.sessionStore.save(s)
                cleared++
            }
        }
        projectOpMsg = if (projectId == null) {
            "已清除全部成本记录（$cleared 个会话）"
        } else {
            "已清除该工程成本记录（$cleared 个会话）"
        }
        refreshSessions() // refreshTick++ → StatsScreen/会话列表重载
    }

    fun attachPath(p: String) {
        pendingAttach = p
        tab = Tab.Chat
    }

    fun consumeAttach(): String? = pendingAttach.also { pendingAttach = null }

    // ---- 配置操作 ----

    /** P14：后台验证 API Key（/models 探测，IO 线程）；返回错误详情以区分网络/鉴权失败 */
    suspend fun verifyApiKey(key: String): Result<Unit> {
        return container.llm.listModels(key, AutoConfig.DEFAULT_BASE_URL).fold(
            onSuccess = { Result.success(Unit) },
            onFailure = { Result.failure(it) },
        )
    }

    fun completeSimpleOnboarding(key: String) {
        viewModelScope.launch {
            container.appStore.setApiKey(key)
            container.appStore.setBaseUrl(AutoConfig.DEFAULT_BASE_URL)
            container.appStore.setFlashModel(AutoConfig.PREFERRED_FLASH_MODEL)
            container.appStore.setProModel(AutoConfig.PREFERRED_PRO_MODEL)
        }
    }

    /**
     * 一键自动配置（对应 PC 端"只填密钥就完事"）：
     * 用密钥探测 /models → 自动挑选 flash/pro 模型 → 保存官方预设 base_url 与模型。
     * 探测失败时回退内置预设（AutoConfig.PREFERRED_*）。
     */
    suspend fun autoConfigure(apiKey: String): String {
        val baseUrl = AutoConfig.DEFAULT_BASE_URL
        var result = ""
        val models = container.llm.listModels(apiKey, baseUrl)
        val (flash, pro) = models.fold(
            onSuccess = { ids ->
                result = "✓ 探测到 ${ids.size} 个模型"
                AutoConfig.pickModels(ids)
            },
            onFailure = {
                result = "⚠ 模型探测失败（${it.message ?: "网络错误"}），已使用内置预设"
                AutoConfig.PREFERRED_FLASH_MODEL to AutoConfig.PREFERRED_PRO_MODEL
            },
        )
        container.appStore.setApiKey(apiKey)
        container.appStore.setBaseUrl(baseUrl)
        container.appStore.setFlashModel(flash)
        container.appStore.setProModel(pro)
        return "$result\nFlash: $flash\nPro: $pro"
    }

    suspend fun testConnection(key: String, baseUrl: String, model: String) =
        container.llm.testConnection(key, baseUrl, model)

    fun completeOnboarding(
        apiKey: String,
        baseUrl: String,
        flashModel: String,
        proModel: String,
        treeUri: Uri,
    ) {
        viewModelScope.launch {
            container.appStore.setApiKey(apiKey)
            container.appStore.setBaseUrl(baseUrl)
            container.appStore.setFlashModel(flashModel)
            container.appStore.setProModel(proModel)
            if (container.safRepo.bindTree(treeUri, null)) {
                container.appStore.setProject(treeUri.toString(), container.safRepo.rootName)
            }
        }
    }

    fun rebindProject(treeUri: Uri) {
        viewModelScope.launch {
            if (container.safRepo.bindTree(treeUri, null)) {
                container.appStore.setProject(treeUri.toString(), container.safRepo.rootName)
            }
        }
    }

    fun setModelTier(tier: String) = viewModelScope.launch { container.appStore.setModelTier(tier) }
    fun setPolicyMode(mode: String) = viewModelScope.launch { container.appStore.setPolicyMode(mode) }
    fun setPlanMode(v: Boolean) = viewModelScope.launch { container.appStore.setPlanMode(v) }
    fun setReasoningMode(mode: String) = viewModelScope.launch { container.appStore.setReasoningMode(mode) }
    fun setOutputStyle(style: String) = viewModelScope.launch { container.appStore.setOutputStyle(style) }
    fun setSearchBackend(backend: String) = viewModelScope.launch { container.appStore.setSearchBackend(backend) }
    fun setTavilyKey(key: String) = viewModelScope.launch { container.appStore.setTavilyKey(key) }
    fun setTheme(mode: String) = viewModelScope.launch { container.appStore.setTheme(mode) }
    fun setBudget(v: Double) = viewModelScope.launch { container.appStore.setBudgetUsd(v) }
    fun setApiKeyPlain(key: String) = viewModelScope.launch { container.appStore.setApiKey(key) }
    fun setBaseUrlEx(v: String) = viewModelScope.launch { container.appStore.setBaseUrl(v) }
    fun setFlashModelEx(v: String) = viewModelScope.launch { container.appStore.setFlashModel(v) }
    fun setProModelEx(v: String) = viewModelScope.launch { container.appStore.setProModel(v) }
    fun savePolicyRules() = viewModelScope.launch { container.appStore.saveRules(container.policy.rules) }
    fun addPolicyRule(pattern: String, decision: Decision) {
        container.policy.addRule(pattern, decision)
        savePolicyRules()
    }
    fun removePolicyRule(pattern: String) {
        container.policy.removeRule(pattern)
        savePolicyRules()
    }
}
