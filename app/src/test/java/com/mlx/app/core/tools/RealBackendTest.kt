package com.mlx.app.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RealBackendTest {

    private fun backend(): Pair<RealBackend, File> {
        val root = File(System.getProperty("java.io.tmpdir"), "rb_${System.nanoTime()}")
        root.mkdirs()
        return RealBackend(root) to root
    }

    @Test
    fun `write read edit roundtrip`() {
        val (b, root) = backend()
        assertTrue(b.writeText("docs/a.md", "# 标题\n内容"))
        assertEquals("# 标题\n内容", b.readText("docs/a.md", 1024)?.text)
        val out = b.editText("docs/a.md", "内容", "新内容")
        assertTrue(out != null && out.newContent.contains("新内容"))
        root.deleteRecursively()
    }

    @Test
    fun `rename copy move delete`() {
        val (b, root) = backend()
        b.writeText("a.txt", "hello")
        assertTrue(b.rename("a.txt", "b.txt"))
        assertTrue(b.copy("b.txt", "c.txt"))
        assertTrue(b.move("c.txt", "docs/c.txt"))
        assertEquals("hello", b.readText("docs/c.txt", 1024)?.text)
        assertTrue(b.delete("docs/c.txt"))
        assertFalse(b.readText("docs/c.txt", 1024) != null)
        root.deleteRecursively()
    }

    @Test
    fun `grep finds content with snippet`() {
        val (b, root) = backend()
        b.writeText("src/A.kt", "fun main() { println(\"hello mlx\") }")
        b.writeText("src/B.kt", "val x = 1")
        val hits = b.grep("mlx", "", 10)
        assertEquals(1, hits.size)
        assertTrue(hits[0].first.contains("A.kt"))
        assertTrue(hits[0].second.contains("hello mlx"))
        root.deleteRecursively()
    }

    @Test
    fun `glob matches patterns`() {
        val (b, root) = backend()
        b.writeText("src/A.kt", "")
        b.writeText("src/B.kt", "")
        b.writeText("README.md", "")
        val all = b.listTree("", 3).filter { !it.isDir }
        val kt = all.filter { com.mlx.app.core.policy.Glob.matches("**/*.kt", it.relPath) }
        assertEquals(2, kt.size)
        root.deleteRecursively()
    }
}
