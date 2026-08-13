package com.mlx.app.data.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SessionStoreTest {

    private fun store(dir: File) = SessionStore(File(dir, "sessions"))

    @Test
    fun `turns round trip through serialization`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "ss_${System.nanoTime()}")
        val s = store(dir)
        val sess = s.create("flash", "轨迹", projectId = "p1")
        sess.turns += TurnRecord(
            id = "t_1", turnNumber = 1, userText = "生成汇报", startedAt = 100L, finishedAt = 500L,
            status = TurnStatus.SUCCESS, costUsd = 0.1234,
            steps = mutableListOf(
                StepRecord("c1", StepKind.TOOL, "read_file", ToolStatus.SUCCESS, 200L, 300L, 100L, "{\"path\":\"a.md\"}", "内容", "", listOf("a.md")),
            ),
        )
        s.save(sess)
        val loaded = s.load(sess.id)!!
        assertEquals(1, loaded.turns.size)
        val t = loaded.turns[0]
        assertEquals(TurnStatus.SUCCESS, t.status)
        assertEquals(0.1234, t.costUsd, 1e-9)
        assertEquals(1, t.steps.size)
        assertEquals("read_file", t.steps[0].name)
        assertEquals(listOf("a.md"), t.steps[0].outputRefs)
        assertEquals(100L, t.steps[0].durationMs)
        dir.deleteRecursively()
    }

    @Test
    fun `old json without turns key loads and backfills from messages`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "ss_${System.nanoTime()}")
        val s = store(dir)
        // 手工写旧格式（无 turns key）——向后兼容验证 + 一次性回填
        val oldJson = """{"id":"old1","title":"旧会话","createdAt":1,"updatedAt":2,"model":"flash","projectId":"p1","projectName":"","messages":[
            {"id":"m1","role":"user","content":"第一轮","reasoning":"","toolCalls":[],"toolCallId":"","createdAt":10},
            {"id":"a1","role":"assistant","content":"回答一","reasoning":"","toolCalls":[{"id":"c1","name":"read_file","argsJson":"{}","status":"SUCCESS","resultText":"r","diffText":"","retryCount":0}],"toolCallId":"","createdAt":20},
            {"id":"m2","role":"user","content":"第二轮","reasoning":"","toolCalls":[],"toolCallId":"","createdAt":30}
        ],"costs":[]}"""
        File(dir, "sessions/old1.json").apply { parentFile?.mkdirs() }.writeText(oldJson)
        val loaded = s.load("old1")!!
        // 旧 JSON 无 turns → 不崩 + 回填 2 个回合
        assertEquals(2, loaded.turns.size)
        assertEquals(1, loaded.turns[0].turnNumber)
        assertEquals("第一轮", loaded.turns[0].userText)
        // 第一回合含工具步骤
        assertEquals(1, loaded.turns[0].steps.count { it.name == "read_file" })
        assertEquals(TurnStatus.SUCCESS, loaded.turns[0].status)
        // 幂等：回填后再次 fromJson 不重复
        val again = SessionStore.fromJson(SessionStore.toJson(loaded))
        assertEquals(2, again.turns.size)
        dir.deleteRecursively()
    }

    @Test
    fun `intent round trips through toolCalls steps and children`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "ss_${System.nanoTime()}")
        val s = store(dir)
        val sess = s.create("flash", "意图", projectId = "p1")
        sess.messages += MessageRecord(
            id = "a1", role = "assistant", content = "正在读取配置",
            toolCalls = listOf(
                ToolCallRecord("c1", "read_file", "{}", ToolStatus.SUCCESS, resultText = "r", intent = "正在读取配置"),
            ),
        )
        sess.turns += TurnRecord(
            id = "t_1", turnNumber = 1, userText = "分析", startedAt = 1L,
            steps = mutableListOf(
                StepRecord(
                    id = "c1", kind = StepKind.TOOL, name = "read_file", status = ToolStatus.SUCCESS,
                    argsJson = "{}", intent = "正在读取配置",
                    children = mutableListOf(
                        StepRecord("sub1", StepKind.TOOL, "grep", ToolStatus.SUCCESS, intent = "搜索引用"),
                    ),
                ),
            ),
        )
        s.save(sess)
        val loaded = s.load(sess.id)!!
        assertEquals("正在读取配置", loaded.messages[0].toolCalls[0].intent)
        assertEquals("正在读取配置", loaded.turns[0].steps[0].intent)
        assertEquals("搜索引用", loaded.turns[0].steps[0].children[0].intent)
        dir.deleteRecursively()
    }

    @Test
    fun `old json without intent keys loads with empty fallback`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "ss_${System.nanoTime()}")
        val s = store(dir)
        // 手工写旧格式（无 intent 键）——向后兼容：不崩 + 空串兜底
        val oldJson = """{"id":"old2","title":"旧会话","createdAt":1,"updatedAt":2,"model":"flash","projectId":"p1","projectName":"","messages":[
            {"id":"a1","role":"assistant","content":"正文","reasoning":"","toolCalls":[{"id":"c1","name":"shell","argsJson":"{}","status":"SUCCESS","resultText":"ok","diffText":"","retryCount":0}],"toolCallId":"","createdAt":20}
        ],"costs":[],"turns":[{"id":"t1","turnNumber":1,"userText":"任务","startedAt":1,"finishedAt":2,"status":"SUCCESS","costUsd":0.0,"steps":[
            {"id":"c1","kind":"TOOL","name":"shell","status":"SUCCESS","startedAt":1,"finishedAt":2,"durationMs":1,"argsJson":"{}","resultText":"ok","diffText":"","outputRefs":[],"children":[]}
        ]}]}"""
        File(dir, "sessions/old2.json").apply { parentFile?.mkdirs() }.writeText(oldJson)
        val loaded = s.load("old2")!!
        assertEquals("", loaded.messages[0].toolCalls[0].intent)
        assertEquals("", loaded.turns[0].steps[0].intent)
        dir.deleteRecursively()
    }

    @Test
    fun `sessions belong to projects and filter by project`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "ss_${System.nanoTime()}")
        val s = store(dir)
        s.save(s.create("flash", "会话A", projectId = "p1"))
        s.save(s.create("flash", "会话B", projectId = "p1"))
        s.save(s.create("flash", "会话C", projectId = "p2"))
        assertEquals(2, s.list("p1").size)
        assertEquals(1, s.list("p2").size)
        assertEquals(3, s.list(null).size)
        dir.deleteRecursively()
    }

    @Test
    fun `search finds session by title and content`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "ss_${System.nanoTime()}")
        val s = store(dir)
        val sess = s.create("flash", "登录模块", projectId = "p1")
        sess.messages += MessageRecord("m1", "user", "请实现扫码登录")
        s.save(sess)
        assertEquals(1, s.search("p1", "扫码").size)
        assertEquals(1, s.search("p1", "登录模块").size)
        assertEquals(0, s.search("p1", "不存在的内容").size)
        dir.deleteRecursively()
    }

    @Test
    fun `fork copies session into new id keeping project`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "ss_${System.nanoTime()}")
        val s = store(dir)
        val sess = s.create("flash", "原始", projectId = "p1")
        sess.messages += MessageRecord("m1", "user", "你好")
        s.save(sess)
        val copy = s.fork(sess.id)!!
        assertEquals("p1", copy.projectId)
        assertEquals(1, copy.messages.size)
        assertTrue(copy.id != sess.id)
        dir.deleteRecursively()
    }

    @Test
    fun `branch keeps history before index`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "ss_${System.nanoTime()}")
        val s = store(dir)
        val sess = s.create("flash", "原始", projectId = "p1")
        sess.messages += MessageRecord("m1", "user", "第一轮")
        sess.messages += MessageRecord("a1", "assistant", "回答一")
        sess.messages += MessageRecord("m2", "user", "第二轮")
        s.save(sess)
        val branch = s.branch(sess.id, 2)!!
        assertEquals(2, branch.messages.size)
        dir.deleteRecursively()
    }
}
