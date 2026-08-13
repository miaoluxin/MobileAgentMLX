package com.mlx.app.core.tools

import com.mlx.app.core.embed.EmbeddedEnv
import com.mlx.app.core.tasks.TaskManager
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive

/**
 * 统一执行通道（任务执行的唯一权威登记）：
 * - 任何 shell / python_exec / bash 类执行都经此登记为 Task（TaskStore），任务页与对话内工具卡同源
 * - 逐行读取输出（可取消）+ 进程树击杀支持（可终止）
 * - 日志按 10 行节流落盘，防 tasks.json 写放大
 */
object ShellTaskRunner {

    /**
     * 执行进程并返回工具结果。
     * @param taskStore 非空 → 登记 Task（任务页可见）；null → 仅执行（无登记场景）
     * @param onLine    每行输出回调（实时日志/状态面板数据源）
     * @param taskIdOverride 覆盖任务 id（十二批：引擎传 callId —— 超时精确 kill 单工具进程，
     *                       修复 destroyAll 误杀并行组其他工具进程；null = 内部生成）
     */
    suspend fun runProcess(
        embedded: EmbeddedEnv,
        command: List<String>,
        label: String,
        cwd: File? = null,
        taskStore: TaskManager.TaskStore? = null,
        taskName: String = label,
        projectId: String = "",
        projectName: String = "",
        sessionId: String = "",
        taskIdOverride: String? = null,
        registry: ProcessRegistry,
        onLine: (String) -> Unit = {},
    ): ToolResult {
        val taskId = if (taskStore != null) {
            val id = taskIdOverride ?: "t${System.nanoTime()}"
            taskStore.save(taskStore.list() + TaskManager.Task(
                id = id,
                name = taskName,
                type = "bash",
                command = command.joinToString(" "),
                projectId = projectId,
                projectName = projectName,
                sessionId = sessionId,
            ))
            id
        } else null

        val proc = try {
            val pb = embedded.processBuilder(command)
            pb.directory(cwd ?: embedded.home)
            pb.redirectErrorStream(true)
            pb.start()
        } catch (e: Exception) {
            // 进程启动失败（环境损坏/权限）：登记任务标记失败后返回
            if (taskId != null && taskStore != null) {
                taskStore.update(taskId) { t ->
                    t.status = TaskManager.Status.FAILED
                    t.finishedAt = System.currentTimeMillis()
                    t.appendLog("进程启动失败: ${e.message}")
                }
            }
            return ToolResult(false, "$label 启动失败: ${e.message}")
        }

        if (taskId != null) registry.register(taskId, proc)
        val out = StringBuilder()
        // 日志节流：每 10 行落盘一次（update 直接写含最新日志的列表，不再丢行）
        val pendingLines = mutableListOf<String>()
        fun flushPending() {
            if (pendingLines.isEmpty()) return
            taskStore?.update(taskId ?: return) { t -> pendingLines.forEach { t.appendLog(it) } }
            pendingLines.clear()
        }
        try {
            val reader = proc.inputStream.bufferedReader()
            while (true) {
                currentCoroutineContext().ensureActive() // 取消检查（abort/超时立即生效）
                val line = try {
                    reader.readLine()
                } catch (e: java.io.IOException) {
                    // 流被外部关闭（registry 杀进程/显式 close）：
                    // 协程已取消 → 转抛取消异常走 TurnAborted（而非"正常完成"）；
                    // 未取消（进程自身异常退出）→ 按进程结束处理
                    if (!currentCoroutineContext().isActive) {
                        throw kotlinx.coroutines.CancellationException("进程输出流已关闭（被终止）")
                    }
                    break
                } ?: break
                out.appendLine(line)
                onLine(line)
                if (taskId != null && taskStore != null) {
                    pendingLines += line
                    if (pendingLines.size >= 10) flushPending()
                }
            }
            val code = proc.waitFor()
            if (taskId != null && taskStore != null) {
                flushPending() // 不足 10 行的尾部日志一并落盘
                taskStore.update(taskId) { t ->
                    t.status = if (code == 0) TaskManager.Status.SUCCESS else TaskManager.Status.FAILED
                    t.finishedAt = System.currentTimeMillis()
                    t.appendLog("（退出码 $code）")
                }
            }
            val text = if (out.isBlank()) "（$label 无输出，退出码 $code）" else "（$label 退出码 $code）\n$out".trimEnd()
            return ToolResult(ok = code == 0, text = text)
        } finally {
            // 二十二批（审计）：取消路径先杀整树再 unregister —— 此前 finally 先 unregister、
            // 只 destroyForcibly 根进程，引擎 catch 的 registry.destroy(call.id) 因 map 已清拿不到
            // proc → killTree 不执行，bash 孙进程（git/python 等）残留僵尸
            //（顺序确定性：内层 finally 先于外层 catch 执行，树杀必然发生，引擎 destroy 幂等兜底）
            if (taskId != null && !currentCoroutineContext().isActive) {
                registry.destroy(taskId) // remove + killTree + destroyForcibly
            }
            if (taskId != null) registry.unregister(taskId)
            // 取消路径（协程被 cancel）落盘 KILLED
            if (taskId != null && taskStore != null && !currentCoroutineContext().isActive) {
                flushPending()
                taskStore.update(taskId) { t ->
                    if (t.status == TaskManager.Status.RUNNING) {
                        t.status = TaskManager.Status.KILLED
                        t.finishedAt = System.currentTimeMillis()
                        t.appendLog("已终止")
                    }
                }
            }
            // 取消路径兜底（未登记 taskId 的场景）：协程已取消但进程可能还挂着 → 强杀根进程
            if (!currentCoroutineContext().isActive) runCatching { proc.destroyForcibly() }
        }
    }
}
