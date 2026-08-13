package com.mlx.app.core.embed

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 回归测试：真实 termux-root.tar 必须被 MinimalTar 完整解压。
 * 曾因 PAX 'x' 头数据区未跳 512 对齐 padding 导致解压中断（bash 在、git/python3 缺失）。
 */
class RealTarDiagnoseTest {

    @Test
    fun `real tar extracts fully`() {
        // gradle 测试工作目录 = app/ 模块根
        val tar = File("src/main/assets/termux-root.tar")
        assertTrue("tar 不存在: ${tar.absolutePath}", tar.exists())
        val dest = File(System.getProperty("java.io.tmpdir"), "diag_${System.nanoTime()}")
        var extracted = 0L
        MinimalTar.extract(tar.inputStream(), dest) { e, _ -> extracted = e }

        val prefix = "data/data/com.termux/files/usr"
        val checks = mapOf(
            "bash" to File(dest, "$prefix/bin/bash"),
            "git" to File(dest, "$prefix/bin/git"),
            "python3" to File(dest, "$prefix/bin/python3"),
            "apt" to File(dest, "$prefix/bin/apt"),
            "zip" to File(dest, "$prefix/bin/zip"),
            "libzstd" to File(dest, "$prefix/lib/libzstd.so.1"),
            "threading.py" to File(dest, "$prefix/lib/python3.14/threading.py"),
            "libicudata" to File(dest, "$prefix/lib/libicudata.so.78.3"),
        )
        val missing = checks.filterValues { !it.isFile }.keys
        val totalBytes = dest.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        println("解压字节=$extracted 实际占用=${totalBytes / 1024 / 1024}MB 缺失=$missing")
        assertTrue("缺失关键文件: $missing", missing.isEmpty())
        assertTrue("解压字节不足: $extracted", extracted > 300_000_000L)
        dest.deleteRecursively()
    }
}
