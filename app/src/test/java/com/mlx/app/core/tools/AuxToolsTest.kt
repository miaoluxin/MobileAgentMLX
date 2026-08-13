package com.mlx.app.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A1：Todo id 生成唯一性 + TodoStore 往返 + 历史重复 id 防御（LazyColumn key 崩溃修复） */
class AuxToolsTest {

    private fun store(tmp: File) = AuxTools.TodoStore(File(tmp, "todos").apply { mkdirs() })

    @Test
    fun `todo id unique within same millisecond`() {
        // 同毫秒 + 不同纳秒 → 必不相等（旧实现 t+时间戳 同毫秒碰撞 → TodoSheet LazyColumn key 重复崩溃）
        assertNotEquals(AuxTools.newTodoId(1_000L, 1L), AuxTools.newTodoId(1_000L, 2L))
        // 不同毫秒 → 必不相等
        assertNotEquals(AuxTools.newTodoId(1_000L, 1L), AuxTools.newTodoId(1_001L, 1L))
    }

    @Test
    fun `add list setDone roundtrip`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "todo_${System.nanoTime()}")
        dir.mkdirs()
        val s = store(dir)
        val a = s.add("s1", "解析 xlsx 数据")
        val b = s.add("s1", "修复金额精度")
        assertEquals(2, s.list("s1").size)
        assertEquals("t1_2", AuxTools.newTodoId(1L, 2L)) // 纯函数入参可预期
        s.setDone("s1", a.id, true)
        val list = s.list("s1")
        assertEquals(2, list.size)
        assertTrue(list.first { it.id == a.id }.done)
        assertTrue(!list.first { it.id == b.id }.done)
        // 跨会话隔离
        assertEquals(0, s.list("s2").size)
        dir.deleteRecursively()
    }

    @Test
    fun `duplicate ids from legacy file are deduped on read`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "todo_${System.nanoTime()}")
        dir.mkdirs()
        val s = store(dir)
        // 手工写入旧格式（同 id 两条：旧版本同毫秒碰撞产物；文件在 store 的 todos 子目录）
        val f = File(dir, "todos/todos_legacy.json")
        f.writeText("""[{"id":"t123","text":"A","done":false,"createdAt":1},{"id":"t123","text":"B","done":true,"createdAt":1},{"id":"t456","text":"C","done":false,"createdAt":2}]""")
        val list = s.list("legacy")
        assertEquals(2, list.size) // 重复 id 去重，保留后者
        assertEquals("B", list.first { it.id == "t123" }.text)
        dir.deleteRecursively()
    }
}
