package com.mlx.app

import android.app.Application
import android.content.Context
import com.mlx.app.core.agent.AgentEngine
import com.mlx.app.core.agent.SubAgentManager
import com.mlx.app.core.agent.SystemPrompts
import com.mlx.app.core.checkpoint.CheckpointStore
import com.mlx.app.core.context.ContextManager
import com.mlx.app.core.cost.CostAccount
import com.mlx.app.core.embed.EmbeddedEnv
import com.mlx.app.core.llm.DeepSeekClient
import com.mlx.app.core.mcp.McpRegistry
import com.mlx.app.core.memory.FactMemory
import com.mlx.app.core.memory.SkillStore
import com.mlx.app.core.policy.PolicyEngine
import com.mlx.app.core.tasks.TaskManager
import com.mlx.app.core.tasks.TaskService
import com.mlx.app.core.tools.AuxTools
import com.mlx.app.core.tools.ToolRegistry
import com.mlx.app.core.tools.WebSearch
import com.mlx.app.core.tools.WebSearchTool
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.mlx.app.data.saf.SafRepo
import com.mlx.app.data.store.AppStore
import com.mlx.app.data.store.SessionStore

/** 手动依赖容器（MVP 不引入 DI 框架；对应文档 3.3 节模块边界） */
class AppContainer(context: Context) {
    val appStore = AppStore(context)
    val safRepo = SafRepo(context)
    val sessionStore = SessionStore(java.io.File(context.filesDir, "sessions"))
    val policy = PolicyEngine()
    val todoStore = AuxTools.TodoStore(java.io.File(context.filesDir, "todos").apply { mkdirs() })
    val webSearchTool = WebSearchTool(
        backendProvider = {
            val name = try { appStore.searchBackendFlow.first() } catch (e: Exception) { "BING" }
            WebSearch.Backend.entries.firstOrNull { it.name == name } ?: WebSearch.Backend.BING
        },
        apiKeyProvider = {
            try { appStore.tavilyKeyFlow.first() } catch (e: Exception) { "" }
        },
    )
    val llm = DeepSeekClient()
    val embeddedEnv = EmbeddedEnv(context)
    val factMemory = FactMemory(java.io.File(context.filesDir, "memory").apply { mkdirs() })
    val subAgents = SubAgentManager(llm, appStore)
    /** 进程注册表：所有外部进程句柄（shell/python/bash），支持进程树击杀 */
    val processRegistry = com.mlx.app.core.tools.ProcessRegistry()
    val taskStore = TaskManager.TaskStore(java.io.File(context.filesDir, "tasks").apply { mkdirs() })
    val toolRegistry = ToolRegistry(safRepo).apply {
        registerAux(todoStore, webSearchTool, AuxTools.Shell(embeddedEnv, processRegistry, taskStore))
        register(AuxTools.PythonExecTool(embeddedEnv, processRegistry, taskStore))
        register(AuxTools.SubAgentTool(subAgents))
        register(AuxTools.PlannerTool(subAgents))
        // P13：PC 工具集补全
        register(com.mlx.app.core.tools.PcTools.GrepTool())
        register(com.mlx.app.core.tools.PcTools.GlobTool())
        register(com.mlx.app.core.tools.PcTools.RememberTool(factMemory))
        register(com.mlx.app.core.tools.PcTools.ForgetTool(factMemory))
        register(com.mlx.app.core.tools.PcTools.WebFetchTool())
        register(com.mlx.app.core.tools.PcTools.UpdateGoalTool { goal ->
            kotlinx.coroutines.GlobalScope.launch { appStore.setGoal(goal) }
        })
        register(com.mlx.app.core.tools.PcTools.DeleteRangeTool())
        register(com.mlx.app.core.agent.SubmitPlanTool()) // 计划模式方案提交（架构级 13）
        register(com.mlx.app.core.tools.PcTools.CodeIndexTool())
        register(com.mlx.app.core.tools.PcTools.CompleteStepTool())
        register(
            com.mlx.app.core.tools.PcTools.BashOutputTool { name, cmd ->
                val task = TaskManager.Task(
                    id = "t${System.nanoTime()}",
                    name = name,
                    type = "bash",
                    command = cmd,
                    projectId = runCatching {
                        // 注：引擎在 withContext(Dispatchers.IO) 内执行工具（AgentEngine），此处 runBlocking 不阻塞主线程，无死锁
                        kotlinx.coroutines.runBlocking { appStore.workspaceRoot() }
                            ?.let { workspaceRepo.findByPath(it.absolutePath)?.id } ?: ""
                    }.getOrDefault(""),
                )
                taskStore.save(taskStore.list() + task)
                android.content.Intent(context, TaskService::class.java).apply {
                    action = TaskManager.ACTION_START
                    putExtra(TaskManager.EXTRA_TASK_ID, task.id)
                    putExtra(TaskManager.EXTRA_TASK_TYPE, task.type)
                }.also { androidx.core.content.ContextCompat.startForegroundService(context, it) }
                task.id
            }
        )
        register(
            com.mlx.app.core.tools.PcTools.KillShellTool { id ->
                kotlinx.coroutines.GlobalScope.launch {
                    // 真正杀进程树（原实现只标 KILLED，进程继续跑）
                    processRegistry.destroy(id)
                    val tasks = taskStore.list()
                    val t = tasks.firstOrNull { it.id == id } ?: return@launch
                    if (t.status == TaskManager.Status.RUNNING) {
                        t.status = TaskManager.Status.KILLED
                        t.appendLog("已终止")
                        taskStore.save(tasks)
                        context.startService(
                            android.content.Intent(context, TaskService::class.java)
                                .setAction(TaskManager.ACTION_STOP)
                        )
                    } else {
                        taskStore.delete(id)
                    }
                }
            }
        )
        register(
            com.mlx.app.core.tools.PcTools.WaitJobTool { id ->
                val t = taskStore.list().firstOrNull { it.id == id }
                    ?: return@WaitJobTool null
                val status = when (t.status) {
                    TaskManager.Status.RUNNING -> "运行中"
                    TaskManager.Status.SUCCESS -> "成功"
                    TaskManager.Status.FAILED -> "失败"
                    TaskManager.Status.KILLED -> "已终止"
                }
                "任务 $id：$status\n" + t.logTail(100).ifBlank { "（暂无输出）" }
            }
        )
    }
    val contextManager = ContextManager(SystemPrompts.BASE)
    val costAccount = CostAccount()
    val checkpointStore = CheckpointStore(java.io.File(context.filesDir, "checkpoints").apply { mkdirs() })
    val skillStore = SkillStore(java.io.File(context.filesDir, "memory").apply { mkdirs() })
    /** 技能引擎（架构级 12：索引注入 + run_skill/read_skill/install_skill + 缺失询问 + URL 安装） */
    val skillEngine = com.mlx.app.core.skills.SkillEngine(skillStore, toolRegistry, subAgents)
    val mcpRegistry = McpRegistry(java.io.File(context.filesDir, "mcp").apply { mkdirs() })
    val projectRegistry = com.mlx.app.data.store.ProjectRegistry(java.io.File(context.filesDir, "project-registry.json"))
    companion object {
        fun hasAllFilesAccess(ctx: Context): Boolean =
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.os.Environment.isExternalStorageManager()
            } else {
                android.os.Build.VERSION.SDK_INT < 29 ||
                    ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }

        /** 跳系统授权页（API<30 走运行时权限） */
        fun requestAllFilesAccess(activity: android.app.Activity) {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                try {
                    activity.startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            android.net.Uri.parse("package:${activity.packageName}"),
                        )
                    )
                } catch (e: Exception) {
                    activity.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    )
                }
            } else {
                androidx.core.app.ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    ),
                    100,
                )
            }
        }
    }
    val workspaceRepo = com.mlx.app.data.store.WorkspaceRepo(java.io.File(context.filesDir, "projects"), projectRegistry, context)
    /** 会话删除级联清理（装配在全部 store 构造完成后 —— init 块，见下方） */
    val sessionCascade = com.mlx.app.data.store.SessionCascade(context, workspaceRepo, checkpointStore, appStore, safRepo)
    /** 进程级实例标识（P7：分屏多实例区分用） */
    val instanceId: String = "inst_" + java.util.UUID.randomUUID().toString().substring(0, 8)
    val sessionLease = com.mlx.app.data.store.SessionLease(java.io.File(context.filesDir, "leases"))
    val engine = AgentEngine(
        client = llm,
        registry = toolRegistry,
        contextManager = contextManager,
        sessionStore = sessionStore,
        costAccount = costAccount,
        policy = policy,
        config = appStore,
        saf = safRepo,
        checkpointStore = checkpointStore,
        factMemory = factMemory,
        skillStore = skillStore,
        skillEngine = skillEngine,
        mcpRegistry = mcpRegistry,
        workspaceRepo = workspaceRepo,
        processRegistry = processRegistry,
    )
    init {
        // 级联删除装配（全部 store 构造完成后；避免构造顺序循环依赖）
        sessionStore.onDeleted = { id -> sessionCascade.onSessionDeleted(id) }
        // 八批：审批规则持久化 —— 启动恢复"始终允许"等规则 + 运行时变更即时落盘（低频；
        // GlobalScope.launch 对齐 UpdateGoalTool 先例 —— 消除 Main 线程 runBlocking 阻塞）
        policy.onRulesChanged = { rules ->
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { appStore.saveRules(rules) }
        }
        kotlinx.coroutines.runBlocking { policy.setRules(appStore.loadRules()) }
    }

    /** 所有文件访问权限状态（MANAGE_EXTERNAL_STORAGE，目录即工作区 2.0：统一工作区=真实目录） */
    private val appContext: Context = context
    val allFilesAccess = androidx.compose.runtime.mutableStateOf(hasAllFilesAccess(appContext))

    fun refreshAllFilesAccess() {
        allFilesAccess.value = hasAllFilesAccess(appContext)
    }
}

class MlxApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // 崩溃日志最先安装：容器初始化阶段崩溃也能落盘（此前无任何 handler，崩溃=静默退出无日志）
        com.mlx.app.core.diagnose.CrashLog.install(this)
        container = AppContainer(this)
    }
}
