package com.mlx.app.core.tasks

import com.mlx.app.core.common.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TaskStoreTest {

    private fun store(dir: File) = TaskManager.TaskStore(dir)

    private fun task(id: String, createdAt: Long, status: TaskManager.Status = TaskManager.Status.SUCCESS, finishedAt: Long = createdAt) =
        TaskManager.Task(
            id = id,
            name = "任务 $id",
            type = "bash",
            projectId = "p1",
            sessionId = "s1",
            status = status,
            createdAt = createdAt,
            finishedAt = finishedAt,
        )

    @Test
    fun `old tasks json without sessionId deserializes with empty session`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "ts_${System.nanoTime()}")
        dir.mkdirs()
        // 手工写旧格式（无 sessionId key）——向后兼容验证
        File(dir, "tasks.json").writeText(
            MiniJson.stringify(listOf(mapOf(
                "id" to "t1", "name" to "旧任务", "type" to "index",
                "command" to "", "projectId" to "",
                "status" to "SUCCESS", "progress" to 1.0,
                "log" to listOf("done"), "createdAt" to 1000L, "finishedAt" to 2000L,
            )))
        )
        val tasks = store(dir).list()
        assertEquals(1, tasks.size)
        assertEquals("t1", tasks[0].id)
        assertEquals("", tasks[0].sessionId) // 旧数据 → 空串，不崩
        dir.deleteRecursively()
    }

    @Test
    fun `sessionId survives save and load round trip`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "ts_${System.nanoTime()}")
        val s = store(dir)
        s.save(listOf(task("t1", 1000L), task("t2", 2000L).copy(sessionId = "s2")))
        val loaded = s.list().associateBy { it.id }
        assertEquals("s1", loaded["t1"]?.sessionId)
        assertEquals("s2", loaded["t2"]?.sessionId)
        dir.deleteRecursively()
    }

    @Test
    fun `update persists in-place modification including log lines`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "ts_${System.nanoTime()}")
        val s = store(dir)
        s.save(listOf(task("t1", 1000L, status = TaskManager.Status.RUNNING)))
        // 回归：原 "save(list())" 模式重新读盘导致修改全丢（任务永远 RUNNING、日志 0 行）
        s.update("t1") { t ->
            t.status = TaskManager.Status.SUCCESS
            t.appendLog("第一行")
            t.appendLog("第二行")
            t.finishedAt = 9999L
        }
        val t = s.list().first()
        assertEquals(TaskManager.Status.SUCCESS, t.status)
        assertEquals(listOf("第一行", "第二行"), t.log)
        assertEquals(9999L, t.finishedAt)
        dir.deleteRecursively()
    }

    @Test
    fun `pruneByAge removes only aged finished tasks and keeps running forever`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "ts_${System.nanoTime()}")
        val s = store(dir)
        val now = System.currentTimeMillis()
        val aged = task("old1", createdAt = now - 31 * 86_400_000L, finishedAt = now - 31 * 86_400_000L)
        val recent = task("new1", createdAt = now - 1000L, finishedAt = now - 500L)
        val running = task("run1", createdAt = now - 31 * 86_400_000L, status = TaskManager.Status.RUNNING)
        s.save(listOf(aged, recent, running))
        s.pruneByAge(30)
        val remaining = s.list().associateBy { it.id }
        // 超龄已完成被删；近期完成保留；运行中永不删（即使超龄）
        assertTrue(!remaining.containsKey("old1"))
        assertTrue(remaining.containsKey("new1"))
        assertTrue(remaining.containsKey("run1"))
        dir.deleteRecursively()
    }

    @Test
    fun `prune defaults to keep 200 (task page disappearing fix)`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "ts_${System.nanoTime()}")
        val s = store(dir)
        // 150 条已完成：原 prune(10) 会删掉 140 条 → 任务"时有时无"；默认 200 全部保留
        val many = (0L until 150L).map { task("f$it", createdAt = 1000L + it, finishedAt = 1000L + it) }
        s.save(many)
        s.prune()
        assertEquals(150, s.list().size)
        dir.deleteRecursively()
    }

    @Test
    fun `prune keeps newest finished tasks and never removes running`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "ts_${System.nanoTime()}")
        val s = store(dir)
        val all = (0L until 12L).map { task("f$it", createdAt = 1000L + it * 10, finishedAt = 1000L + it * 10) } +
            task("running", createdAt = 99999L, status = TaskManager.Status.RUNNING)
        s.save(all)
        s.prune(keep = 10)
        val remaining = s.list().associateBy { it.id }
        assertEquals(11, remaining.size)                 // 10 最新 finished + 1 RUNNING
        assertTrue("running", remaining.containsKey("running"))
        // 最旧 2 条 finished 被删（f0/f1），最新的 10 条保留（f2..f11）
        assertTrue(!remaining.containsKey("f0"))
        assertTrue(!remaining.containsKey("f1"))
        assertTrue(remaining.containsKey("f11"))
        assertTrue(remaining.containsKey("f2"))
        dir.deleteRecursively()
    }
}
