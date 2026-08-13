package com.mlx.app.core.tools

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 进程注册表：登记所有由工具/任务启动的外部进程，提供进程树击杀（终止卡住的命令）。
 *
 * - 执行路径（shell/python/bash 任务）启动进程时 register(key, proc)，结束/取消时 unregister
 * - 引擎超时/abort 调 destroyAll()；任务页终止调 destroy(taskId)
 * - Android 无 ProcessHandle：用 procfs（/proc/<pid>/task/<pid>/children）遍历进程树，
 *   从叶子到根依次 kill -9（多轮防新 fork 竞态），兜底 destroyForcibly
 */
class ProcessRegistry {

    private val map = ConcurrentHashMap<String, Process>()

    fun register(key: String, p: Process) {
        map[key] = p
    }

    fun unregister(key: String) {
        map.remove(key)
    }

    fun destroy(key: String): Boolean {
        val p = map.remove(key) ?: return false
        killTree(p)
        return true
    }

    fun destroyAll() {
        val keys = map.keys.toList()
        for (k in keys) destroy(k)
    }

    fun size(): Int = map.size

    private fun killTree(p: Process) {
        val pid = pidOf(p)
        val kids = mutableListOf<Int>()
        var frontier = listOf(pid)
        // 多轮收集：子进程可能边杀边 fork 新进程
        repeat(5) {
            val next = frontier.flatMap { readChildren(it) }.filter { it !in kids }
            if (next.isEmpty()) return@repeat
            kids += next
            frontier = next
        }
        // 叶子先杀，最后杀根（防中间进程重新 spawn）
        kids.asReversed().forEach { pid ->
            runCatching {
                ProcessBuilder("/system/bin/kill", "-9", pid.toString()).start().waitFor()
            }
        }
        runCatching { p.destroyForcibly() }
    }

    private fun readChildren(pid: Int): List<Int> = runCatching {
        File("/proc/$pid/task/$pid/children").readText().trim()
            .split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }
    }.getOrDefault(emptyList())

    /** Android Process 无公开 pid()（Java 8 API）：反射读内部 pid 字段 */
    private fun pidOf(p: Process): Int = runCatching {
        val f = p.javaClass.getDeclaredField("pid")
        f.isAccessible = true
        f.getInt(p)
    }.getOrDefault(-1)
}
