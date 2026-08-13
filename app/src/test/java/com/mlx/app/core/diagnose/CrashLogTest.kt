package com.mlx.app.core.diagnose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A6：崩溃日志落盘（写盘成功 / 含堆栈 / 轮转保留最近 N 份 / 清空） */
class CrashLogTest {

    private fun dir() = File(System.getProperty("java.io.tmpdir"), "crash_${System.nanoTime()}").apply { mkdirs() }

    @Test
    fun writeCreatesFileWithStackTrace() {
        val d = dir()
        val t = RuntimeException("测试崩溃点")
        CrashLog.writeTo(d, Thread.currentThread(), t)
        val files = d.listFiles() ?: emptyArray()
        assertEquals(1, files.size)
        val text = files[0].readText()
        assertTrue(text.contains("测试崩溃点"))
        assertTrue(text.contains("线程:"))
        d.deleteRecursively()
    }

    @Test
    fun rotationKeepsNewestFive() {
        val d = dir()
        repeat(7) { i ->
            CrashLog.writeTo(d, Thread.currentThread(), RuntimeException("崩溃$i"))
            Thread.sleep(5) // 时间戳秒级粒度：错开保证排序稳定
        }
        val files = d.listFiles()!!.sortedBy { it.name }
        assertEquals(5, files.size)
        assertTrue(files.last().readText().contains("崩溃6"))
        d.deleteRecursively()
    }

    @Test
    fun writeNeverThrowsOnBadDir() {
        // 崩溃路径兜底：目录不可用时不得再次抛出（不存在的盘符 → mkdirs/writeText 失败被 runCatching 吞掉）
        CrashLog.writeTo(File("Z:\\\\nonexistent_root_${System.nanoTime()}", "x"), Thread.currentThread(), RuntimeException("x"))
    }
}
