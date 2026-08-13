package com.mlx.app.data.saf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class TreePathResolverTest {

    private val primary = File("/storage/emulated/0")

    @Test
    fun `primary volume resolves to real path`() {
        val path = TreePathResolver.resolveDocId("primary:Download/MLXproject", primary, emptyMap())
        assertEquals(File("/storage/emulated/0/Download/MLXproject").absolutePath, path)
    }

    @Test
    fun `primary root only resolves to storage root`() {
        val path = TreePathResolver.resolveDocId("primary:", primary, emptyMap())
        assertEquals(primary.absolutePath, path)
    }

    @Test
    fun `nested path is preserved`() {
        val path = TreePathResolver.resolveDocId("primary:Download/a/b/c", primary, emptyMap())
        assertEquals(File("/storage/emulated/0/Download/a/b/c").absolutePath, path)
    }

    @Test
    fun `secondary volume matches by uuid with dashes`() {
        // SD 卡卷：docId 里的 UUID 无连字符，卷 UUID 带连字符 → 归一化后匹配
        val sd = File("/storage/XXXX-YYYY")
        val roots = mapOf("XXXX-YYYY" to sd)
        val path = TreePathResolver.resolveDocId("XXXXYYYY:Projects", primary, roots)
        assertEquals(File("/storage/XXXX-YYYY/Projects").absolutePath, path)
    }

    @Test
    fun `unknown volume returns null`() {
        val path = TreePathResolver.resolveDocId("unknownvol:path", primary, emptyMap())
        assertNull(path)
    }

    @Test
    fun `unresolvable docId returns null`() {
        val path = TreePathResolver.resolveDocId(":", primary, emptyMap())
        assertNull(path)
    }
}
