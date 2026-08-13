package com.mlx.app.data.store

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SessionLeaseTest {

    /** JVM 测试注入假 PID 与存活判定（生产环境用 android.os.Process.myPid() + /proc 判活） */
    private fun lease(dir: File, pid: Int = 4242, alive: (Int) -> Boolean = { true }) =
        SessionLease(dir, { pid }, alive)

    @Test
    fun `different instances exclude each other`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "lease_${System.nanoTime()}")
        val lease = lease(dir)
        assertTrue(lease.acquire("s1", "instA"))
        assertFalse(lease.acquire("s1", "instB"))
        lease.release("s1", "instA")
        assertTrue(lease.acquire("s1", "instB"))
        dir.deleteRecursively()
    }

    @Test
    fun `same instance does not self-exclude`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "lease_${System.nanoTime()}")
        val lease = lease(dir)
        assertTrue(lease.acquire("s1", "instA"))
        // 同实例多 VM 并存：不互斥
        assertTrue(lease.acquire("s1", "instA"))
        dir.deleteRecursively()
    }

    @Test
    fun `different sessions acquire independently`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "lease_${System.nanoTime()}")
        val lease = lease(dir)
        assertTrue(lease.acquire("s1", "instA"))
        assertTrue(lease.acquire("s2", "instA"))
        dir.deleteRecursively()
    }

    @Test
    fun `expired lock is taken over`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "lease_${System.nanoTime()}")
        val lease = lease(dir)
        assertTrue(lease.acquire("s1", "instA"))
        // 模拟过期：改写锁文件时间为 10 分钟前（旧两行锁格式 → 时间戳规则接管）
        File(dir, "s1.lock").writeText("instA\n${System.currentTimeMillis() - 10 * 60_000L}")
        assertTrue(lease.acquire("s1", "instB"))
        dir.deleteRecursively()
    }

    @Test
    fun `dead holder pid is taken over immediately`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "lease_${System.nanoTime()}")
        // 注入存活判定 = 永远判死（模拟持有者进程崩溃）
        val lease = lease(dir, alive = { false })
        assertTrue(lease.acquire("s1", "instA"))
        // 时间戳仍然新鲜，但持有者已死 → 立即接管（修复"重启即被锁"）
        assertTrue(lease.acquire("s1", "instB"))
        dir.deleteRecursively()
    }

    @Test
    fun `live holder pid still excludes others`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "lease_${System.nanoTime()}")
        // 注入存活判定 = 永远判活（模拟持有者进程仍在运行，如分屏双开）
        val lease = lease(dir, alive = { true })
        assertTrue(lease.acquire("s1", "instA"))
        // 持有者进程存活 → 三行锁互斥保持（分屏双开语义）
        assertFalse(lease.acquire("s1", "instB"))
        assertTrue(lease.isHeldElsewhere("s1", "instB")) // 确实被另一实例持有
        dir.deleteRecursively()
    }
}
