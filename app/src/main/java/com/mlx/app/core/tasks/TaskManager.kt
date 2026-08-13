package com.mlx.app.core.tasks

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.mlx.app.MainActivity
import com.mlx.app.R
import com.mlx.app.MlxApp
import com.mlx.app.core.common.MiniJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * 后台任务（对应 PC /jobs /kill /logs 的移动端实现）：
 * - 任务模型 JSON 持久化（TaskStore）
 * - 长任务跑前台服务（TaskService）+ 常驻进度通知；App 被杀后由服务独立续跑
 * - 日志环形缓冲（最近 500 行）
 * MVP 内置任务类型：项目全量索引（扫描文件树）。
 */
object TaskManager {

    const val CHANNEL_ID = "mlx_tasks"
    const val ACTION_START = "com.mlx.app.task.START"
    const val ACTION_STOP = "com.mlx.app.task.STOP"
    const val EXTRA_TASK_ID = "task_id"
    const val EXTRA_TASK_TYPE = "task_type"

    enum class Status { RUNNING, SUCCESS, FAILED, KILLED }

    data class Task(
        val id: String,
        val name: String,
        val type: String,
        var command: String = "",          // bash 任务的实际命令（P13 bash_output）
        var projectId: String = "",        // 归属工程（P4 按工程层级展示）
        var projectName: String = "",      // 归属工程名（创建时快照，任务页展示不依赖注册表解析）
        var sessionId: String = "",        // 归属会话（任务页按 工程→会话 树状展示）
        var status: Status = Status.RUNNING,
        var progress: Float = 0f,
        var log: MutableList<String> = mutableListOf(),
        val createdAt: Long = System.currentTimeMillis(),
        var finishedAt: Long = 0L,
    ) {
        fun logTail(max: Int = 500): String = log.takeLast(max).joinToString("\n")
        fun appendLog(line: String) {
            log += line
            if (log.size > 500) log = log.takeLast(500).toMutableList()
        }
    }

    class TaskStore(private val dir: File) {
        init {
            dir.mkdirs() // 目录不存在时直接创建（测试/首启用临时目录场景）
        }

        // 二十二批（审计）：list/save/update 同步化 —— 读改写竞态会丢任务登记
        //（auto 模式 shell 工具可并行，两个 IO 协程并发 list()+save 互相覆盖；对齐 TodoStore 十二批先例）
        @Synchronized
        fun list(): List<Task> {
            val f = File(dir, "tasks.json")
            if (!f.exists()) return emptyList()
            val list = MiniJson.parse(f.readText()) as? List<*> ?: return emptyList()
            return list.mapNotNull { raw ->
                val m = raw as? Map<String, Any?> ?: return@mapNotNull null
                Task(
                    id = (m["id"] as? String) ?: return@mapNotNull null,
                    name = (m["name"] as? String) ?: "",
                    type = (m["type"] as? String) ?: "",
                    command = (m["command"] as? String) ?: "",
                    projectId = (m["projectId"] as? String) ?: "",
                    projectName = (m["projectName"] as? String) ?: "",
                    sessionId = (m["sessionId"] as? String) ?: "", // 旧数据无此 key → 空串，向后兼容
                    status = runCatching { Status.valueOf((m["status"] as? String) ?: "RUNNING") }.getOrDefault(Status.RUNNING),
                    progress = ((m["progress"] as? Number)?.toFloat()) ?: 0f,
                    log = ((m["log"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()).toMutableList(),
                    createdAt = ((m["createdAt"] as? Number)?.toLong()) ?: 0L,
                    finishedAt = ((m["finishedAt"] as? Number)?.toLong()) ?: 0L,
                )
            }
        }

        @Synchronized
        fun save(tasks: List<Task>) {
            File(dir, "tasks.json").writeText(
                MiniJson.stringify(tasks.map { t ->
                    mapOf(
                        "id" to t.id, "name" to t.name, "type" to t.type, "command" to t.command, "projectId" to t.projectId,
                        "projectName" to t.projectName, "sessionId" to t.sessionId,
                        "status" to t.status.name,
                        "progress" to t.progress, "log" to t.log, "createdAt" to t.createdAt, "finishedAt" to t.finishedAt,
                    )
                })
            )
        }

        /** 删除任务（P10：任意状态可删） */
        fun delete(id: String) {
            save(list().filterNot { it.id == id })
        }

        /**
         * 就地修改任务并落盘。
         * 注意：必须传"含被修改对象"的同一列表（save(list()) 模式会重新读盘 → 修改全丢，
         * 曾导致任务页永远显示运行中、日志 0 行）。
         */
        @Synchronized
        fun update(id: String, transform: (Task) -> Unit) {
            val all = list()
            all.firstOrNull { it.id == id }?.let { t ->
                transform(t)
                save(all)
            }
        }

        /**
         * 完成/失败任务自动清理：保留最近 keep 条（P10；修复：原按插入序保留最旧 → 最新完成的被删）。
         * 默认 200：大幅放宽（修复：prune(10) 导致任务页"时有时无"——用户任务超 10 条即被删）
         */
        fun prune(keep: Int = 200) {
            val all = list()
            val finished = all.filter { it.status != Status.RUNNING }
            if (finished.size <= keep) return
            val keepIds = finished
                .sortedWith(compareByDescending { maxOf(it.finishedAt, it.createdAt) })
                .take(keep).map { it.id }.toSet()
            finished.filterNot { it.id in keepIds }.forEach { delete(it.id) }
        }

        /** 按年龄清理已完成任务（运行中永不删；进任务页时执行一次，防止旧任务无限堆积） */
        fun pruneByAge(days: Int) {
            val cutoff = System.currentTimeMillis() - days * 86_400_000L
            list().filter { it.status != Status.RUNNING && maxOf(it.finishedAt, it.createdAt) < cutoff }
                .forEach { delete(it.id) }
        }
    }
}

/** 前台服务：执行后台任务 + 常驻通知 */
class TaskService : Service() {

    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                TaskManager.CHANNEL_ID,
                "后台任务",
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            TaskManager.ACTION_START -> {
                val taskId = intent.getStringExtra(TaskManager.EXTRA_TASK_ID) ?: return START_NOT_STICKY
                val type = intent.getStringExtra(TaskManager.EXTRA_TASK_TYPE) ?: "index"
                startForeground(1, buildNotification("正在执行后台任务…", 0f))
                job?.cancel()
                job = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob()).launch {
                    runTask(taskId, type)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            TaskManager.ACTION_STOP -> {
                job?.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runTask(taskId: String, type: String) {
        val container = (application as MlxApp).container
        val store = container.taskStore
        if (store.list().none { it.id == taskId }) return
        try {
            when (type) {
                "index" -> {
                    store.update(taskId) { t -> t.appendLog("开始项目全量索引…") }
                    val saf = container.safRepo
                    val all = saf.listTree("", 4)
                    val files = all.filter { !it.isDir }
                    store.update(taskId) { t -> t.appendLog("共发现 ${files.size} 个文件（深度 4）") }
                    var i = 0
                    for (f in files) {
                        i++
                        if (i % 20 == 0) {
                            val progress = i.toFloat() / files.size.coerceAtLeast(1)
                            store.update(taskId) { t -> t.progress = progress }
                            updateNotification("项目全量索引", progress)
                        }
                        if (!kotlinx.coroutines.currentCoroutineContext().isActive) {
                            store.update(taskId) { t ->
                                t.status = TaskManager.Status.KILLED
                                t.appendLog("已终止（用户取消）")
                            }
                            return
                        }
                        // 触达每个文件（读指纹验证可读性，轻量）
                        saf.fingerprint(f.relPath)
                    }
                    store.update(taskId) { t ->
                        t.progress = 1f
                        t.status = TaskManager.Status.SUCCESS
                        t.finishedAt = System.currentTimeMillis()
                        t.appendLog("索引完成：${files.size} 个文件")
                    }
                    notifyDone("索引完成", "${files.size} 个文件已扫描")
                }
                "bash" -> {
                    // P13：bash_output 后台任务 —— 在完整环境中执行命令，日志回流。
                    // 进程注册到 ProcessRegistry：任务页"终止"真正杀进程树（原实现只标 KILLED）
                    val command = store.list().firstOrNull { it.id == taskId }?.command ?: ""
                    store.update(taskId) { t -> t.appendLog("执行: $command") }
                    val embedded = container.embeddedEnv
                    if (!embedded.installed) {
                        store.update(taskId) { t ->
                            t.status = TaskManager.Status.FAILED
                            t.appendLog("完整环境未安装（设置 > 完整环境）")
                        }
                        return
                    }
                    // 目录即工作区 2.0：cwd 绑定工程真实路径（注册表解析）；未归属回退 HOME
                    val root = container.workspaceRepo.find(store.list().firstOrNull { it.id == taskId }?.projectId ?: "")?.root
                    val pb = embedded.processBuilder(listOf(embedded.bashPath, "-lc", command))
                    pb.directory(root ?: embedded.home)
                    pb.redirectErrorStream(true)
                    val proc = pb.start()
                    container.processRegistry.register(taskId, proc)
                    try {
                        val reader = proc.inputStream.bufferedReader()
                        // 日志节流：每 10 行落盘一次（update 直接写含最新日志的列表，不再丢行）
                        val pendingLines = mutableListOf<String>()
                        fun flush() {
                            if (pendingLines.isEmpty()) return
                            store.update(taskId) { t -> pendingLines.forEach { t.appendLog(it) } }
                            pendingLines.clear()
                        }
                        while (true) {
                            if (!kotlinx.coroutines.currentCoroutineContext().isActive) {
                                container.processRegistry.destroy(taskId)
                                flush()
                                store.update(taskId) { t ->
                                    t.status = TaskManager.Status.KILLED
                                    t.appendLog("已终止")
                                }
                                return
                            }
                            val line = reader.readLine() ?: break
                            pendingLines += line
                            if (pendingLines.size >= 10) flush()
                        }
                        val code = proc.waitFor()
                        flush()
                        store.update(taskId) { t ->
                            t.status = if (code == 0) TaskManager.Status.SUCCESS else TaskManager.Status.FAILED
                            t.finishedAt = System.currentTimeMillis()
                            t.appendLog("（退出码 $code）")
                        }
                        notifyDone(if (code == 0) "任务完成" else "任务失败", "bash 任务")
                    } finally {
                        container.processRegistry.unregister(taskId)
                    }
                }
                else -> {
                    store.update(taskId) { t ->
                        t.status = TaskManager.Status.FAILED
                        t.appendLog("未知任务类型: $type")
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            store.update(taskId) { t ->
                t.status = TaskManager.Status.FAILED
                t.finishedAt = System.currentTimeMillis()
                t.appendLog("任务失败: ${e.message}")
            }
            notifyDone("任务失败", e.message ?: "未知错误")
        }
    }

    private fun buildNotification(text: String, progress: Float): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = android.app.Notification.Builder(this, TaskManager.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("MLX 后台任务")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
        if (progress > 0f) {
            builder.setProgress(100, (progress * 100).toInt(), false)
        }
        return builder.build()
    }

    private fun updateNotification(text: String, progress: Float) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(1, buildNotification(text, progress))
    }

    private fun notifyDone(title: String, text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val done = android.app.Notification.Builder(this, TaskManager.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        nm.notify(2, done)
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }
}
