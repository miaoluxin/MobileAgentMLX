package com.mlx.app.data.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WorkspaceRepoTest {

    private fun repo(dir: File) = WorkspaceRepo(
        File(dir, "projects"),
        ProjectRegistry(File(dir, "project-registry.json")),
    )

    @Test
    fun `meta persists name across list reload`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "wr_${System.nanoTime()}")
        val r = repo(dir)
        val p = r.create("我的博客")
        r.rename(p.id, "我的博客改")
        // 从注册表文件重建（模拟重启）：名称保留（注册表是唯一真相源）
        val reloaded = repo(dir).list().first { it.id == p.id }
        assertEquals("我的博客改", reloaded.name)
        dir.deleteRecursively()
    }

    @Test
    fun `empty project has no source`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "wr_${System.nanoTime()}")
        val r = repo(dir)
        val p = r.create("空工程")
        assertTrue(p.sourceUri.isBlank())
        assertTrue(p.sourceDir.isBlank())
        assertTrue(p.legacy)
        dir.deleteRecursively()
    }

    @Test
    fun `syncToSource skips without source uri or context`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "wr_${System.nanoTime()}")
        val r = repo(dir)
        val p = r.create("本地")
        val synced = kotlinx.coroutines.runBlocking { r.syncToSource(p, listOf("a.txt")) }
        assertEquals(0, synced)
        dir.deleteRecursively()
    }

    @Test
    fun `delete removes mirror only`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "wr_${System.nanoTime()}")
        val r = repo(dir)
        val p = r.create("工程")
        r.delete(p.id)
        assertNull(r.find(p.id))
        dir.deleteRecursively()
    }

    @Test
    fun `real project binds same path idempotently`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "wr_${System.nanoTime()}")
        val real = File(dir, "realdir").apply { mkdirs() }
        val r = repo(dir)
        val p1 = r.create("目录A", real.absolutePath)
        assertTrue(!p1.legacy)
        assertEquals(real.absolutePath, p1.path)
        // 同一路径重复绑定 → 同一工程（幂等）
        val p2 = r.create("目录A改", real.absolutePath)
        assertEquals(p1.id, p2.id)
        // 重启后注册表还原工程
        val reloaded = repo(dir).find(p1.id)
        assertEquals(real.absolutePath, reloaded?.path)
        dir.deleteRecursively()
    }

    @Test
    fun `real project delete keeps disk dir`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "wr_${System.nanoTime()}")
        val real = File(dir, "realdir").apply { mkdirs() }
        val r = repo(dir)
        val p = r.create("目录A", real.absolutePath)
        r.delete(p.id)
        assertNull(r.find(p.id))
        assertTrue(real.exists()) // 真实目录不删
        dir.deleteRecursively()
    }
}
