package com.mlx.app.core.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 二十二批（审计 CRITICAL）：路径穿越防护 —— resolveSafe 规范化 + 前缀校验。
 * 此前 read_file("../x") 可读写工作区外（BASE 承诺"禁止访问工作区之外"无代码落实）。
 */
class FileBackendTest {

    private fun root(): File {
        val d = File(System.getProperty("java.io.tmpdir"), "fb_${System.nanoTime()}")
        d.mkdirs()
        File(d, "sub").mkdirs()
        File(d, "a.txt").writeText("hello")
        File(d, "sub/b.txt").writeText("world")
        return d
    }

    @Test
    fun `normal relative paths work`() {
        val b = RealBackend(root())
        assertNotNull(b.readText("a.txt", 100))
        assertNotNull(b.readText("sub/b.txt", 100))
        assertTrue(b.writeText("sub/c.txt", "x"))
        assertNotNull(b.readText("sub/c.txt", 100))
    }

    @Test
    fun `parent traversal rejected`() {
        val b = RealBackend(root())
        assertNull(b.readText("../secret.txt", 100))
        assertNull(b.readText("../../etc/passwd", 100))
        assertNull(b.readBytes("../secret.bin", 100))
        assertFalse(b.writeText("../evil.txt", "x"))
        assertFalse(b.delete("../whatever"))
        assertFalse(b.createFile("../../root.txt"))
        assertFalse(b.move("a.txt", "../moved.txt"))
        assertFalse(b.copy("a.txt", "../copied.txt"))
        assertTrue(b.listTree("../", 2).isEmpty())
        assertTrue(b.listDir("..").isEmpty())
        assertTrue(b.search("x", "../../", 10).isEmpty())
        assertTrue(b.grep("hello", "../", 10).isEmpty())
    }

    @Test
    fun `symlink escaping workspace rejected`() {
        val d = root()
        val outside = File(System.getProperty("java.io.tmpdir"), "fb_out_${System.nanoTime()}")
        outside.writeText("secret")
        val link = File(d, "link")
        // 符号链接在部分环境（Windows 开发者模式未开）不可用 → 跳过（真机 Android/Linux 可验证）
        val created = runCatching {
            java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
            true
        }.getOrDefault(false)
        if (!created) return
        try {
            val b = RealBackend(d)
            assertNull(b.readText("link", 100)) // 链接指向工作区外 → 拒绝
            assertNull(b.readText("link/secret.txt", 100))
        } finally {
            link.delete()
            outside.delete()
            d.deleteRecursively()
        }
    }
}
