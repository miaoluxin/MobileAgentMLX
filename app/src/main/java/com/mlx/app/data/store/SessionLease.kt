package com.mlx.app.data.store

import java.io.File

/**
 * 会话租赁锁（对应 PC 版 session lease 语义）：
 * 分屏双实例时同一会话仅允许一个窗口写操作，另一窗口只读。
 * 锁文件独占创建（原子）；含时间戳，超 5 分钟自动接管（防异常退出死锁）。
 *
 * 锁文件格式（三行）：`instanceId` / `timestamp` / `holderPid`
 * - holderPid：持有者进程 PID。进程被强杀/崩溃（onCleared 不执行）时锁会残留，
 *   重启后凭 PID 判死（/proc/<pid> 不存在）**立即接管**，不再等 5 分钟（修复"重启即被锁"）。
 * - 旧两行锁（holderPid 缺失）：走原时间戳规则，行为兼容。
 */
class SessionLease(
    private val dir: File,
    private val pidProvider: () -> Int = { android.os.Process.myPid() },
    private val pidAlive: (Int) -> Boolean = { File("/proc/$it").exists() }, // 测试注入用
) {

    companion object {
        private const val LEASE_MS = 5 * 60_000L
    }

    /**
     * 获取会话写许可（P7：引入 instanceId —— 同实例内多会话 VM 并存不互斥，
     * 仅不同进程实例（分屏双开）才互斥）。
     */
    fun acquire(sessionId: String, instanceId: String): Boolean {
        val f = lockFile(sessionId)
        if (f.exists()) {
            val lines = f.readText().lines()
            val holder = lines.getOrNull(0) ?: ""
            val ts = lines.getOrNull(1)?.toLongOrNull() ?: 0L
            if (holder == instanceId) {
                // 同实例：刷新时间戳并视为持有（多 VM 并存）
                f.writeText("$instanceId\n${System.currentTimeMillis()}\n${pidProvider()}")
                return true
            }
            val holderPid = lines.getOrNull(2)?.toIntOrNull()
            if (holderPid != null && !pidAlive(holderPid)) {
                // 核心修复：持有者进程已死（强杀/崩溃）→ 孤儿锁立即接管，不等 5 分钟
                f.delete()
            } else if (System.currentTimeMillis() - ts < LEASE_MS) {
                return false
            } else {
                f.delete() // 过期锁接管
            }
        }
        return try {
            val ok = f.createNewFile()
            if (ok) f.writeText("$instanceId\n${System.currentTimeMillis()}\n${pidProvider()}")
            ok
        } catch (e: Exception) {
            false
        }
    }

    fun release(sessionId: String, instanceId: String) {
        val f = lockFile(sessionId)
        if (f.exists() && f.readText().lines().getOrNull(0) == instanceId) {
            f.delete()
        }
    }

    fun isHeldElsewhere(sessionId: String, instanceId: String): Boolean {
        val f = lockFile(sessionId)
        if (!f.exists()) return false
        val lines = f.readText().lines()
        val holder = lines.getOrNull(0) ?: ""
        if (holder == instanceId) return false
        // 持有者进程已死 → 不视为被占用（只读横幅不误报）
        val holderPid = lines.getOrNull(2)?.toIntOrNull()
        if (holderPid != null && !pidAlive(holderPid)) return false
        val ts = lines.getOrNull(1)?.toLongOrNull() ?: 0L
        return System.currentTimeMillis() - ts < LEASE_MS
    }

    /** Android 无 ProcessHandle：同 UID 进程间 /proc/<pid> 可见，文件存在即存活 */

    private fun lockFile(sessionId: String) = File(dir, "$sessionId.lock").apply { parentFile?.mkdirs() }
}
