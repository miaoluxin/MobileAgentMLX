package com.mlx.app.core.agent

import com.mlx.app.core.policy.Decision
import com.mlx.app.core.repair.RepairPipeline
import com.mlx.app.core.tools.ToolContext
import com.mlx.app.core.tools.ToolResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** 六批：并行规划纯函数 + 并发执行器 */
class ParallelPlannerTest {

    private val noBlock: (String, Map<String, Any?>) -> Boolean = { _, _ -> false }

    @Test
    fun readOnlyToolsEligibleWhenAutoAllowed() {
        assertTrue(isParallelEligible(autoApprove = false, decision = Decision.ALLOW, name = "read_file", noBlock))
        assertTrue(isParallelEligible(autoApprove = true, decision = Decision.ASK, name = "read_file", noBlock))
    }

    @Test
    fun askAndDenyToolsNotEligible() {
        assertFalse(isParallelEligible(autoApprove = false, decision = Decision.ASK, name = "read_file", noBlock))
        assertFalse(isParallelEligible(autoApprove = false, decision = Decision.DENY, name = "read_file", noBlock))
    }

    @Test
    fun interactiveAndWriteToolsNeverEligible() {
        assertFalse(isParallelEligible(autoApprove = true, decision = Decision.ALLOW, name = "choice", noBlock))
        assertFalse(isParallelEligible(autoApprove = true, decision = Decision.ALLOW, name = "submit_plan", noBlock))
        assertFalse(isParallelEligible(autoApprove = true, decision = Decision.ALLOW, name = "write_file", noBlock))
        assertFalse(isParallelEligible(autoApprove = true, decision = Decision.ALLOW, name = "edit_file", noBlock))
    }

    @Test
    fun planGateBlockedToolsNotEligible() {
        assertFalse(isParallelEligible(autoApprove = true, decision = Decision.ALLOW, name = "read_file") { name, _ -> name == "read_file" })
    }

    @Test
    fun spanTakesMaximalConsecutiveRunAndNeedsTwo() {
        val calls = listOf("a", "b", "c", "d", "e")
        fun eligible(idx: Int) = idx != 2
        assertEquals(listOf(0, 1), parallelGroupSpan(calls, 0, ::eligible))
        assertEquals(listOf(3, 4), parallelGroupSpan(calls, 3, ::eligible))
        // 单元素段回落串行
        assertEquals(listOf(2), parallelGroupSpan(calls, 2, ::eligible))
        // 尾部单元素
        assertEquals(listOf(4), parallelGroupSpan(calls, 4) { it > 2 && it != 3 })
    }

    @Test
    fun concurrentRunCollectsInOrderAndIsReallyParallel() = runBlocking {
        val running = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val calls = (0 until 3).map { RepairPipeline.AssembledCall("c$it", "read_file", "{}") }
        val runner = ToolBatchRunner(maxParallel = 4) { call, _ ->
            val cur = running.incrementAndGet()
            maxConcurrent.updateAndGet { maxOf(it, cur) }
            delay(50)
            running.decrementAndGet()
            ToolResult(true, "ok-${call.id}")
        }
        val results = runner.run(calls.map { it to dummyCtx(it.id) })
        assertEquals(3, results.size)
        assertEquals(listOf("ok-c0", "ok-c1", "ok-c2"), results.map { it.text })
        assertTrue("并发度应 >1，实际 ${maxConcurrent.get()}", maxConcurrent.get() > 1)
    }

    @Test
    fun concurrencyCappedByMaxParallelChunks() = runBlocking {
        val running = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val calls = (0 until 5).map { RepairPipeline.AssembledCall("c$it", "read_file", "{}") }
        val runner = ToolBatchRunner(maxParallel = 2) { call, _ ->
            val cur = running.incrementAndGet()
            peak.updateAndGet { maxOf(it, cur) }
            delay(20)
            running.decrementAndGet()
            ToolResult(true, call.id)
        }
        val results = runner.run(calls.map { it to dummyCtx(it.id) })
        assertEquals(5, results.size)
        assertTrue("并发峰值应≤2，实际 ${peak.get()}", peak.get() <= 2)
    }

    @Test
    fun executorExceptionsPropagateAndEngineIsolates() = runBlocking {
        val calls = (0 until 3).map { RepairPipeline.AssembledCall("c$it", "read_file", "{}") }
        val runner = ToolBatchRunner(maxParallel = 3) { call, _ ->
            if (call.id == "c1") throw RuntimeException("boom")
            ToolResult(true, "ok-${call.id}")
        }
        // async 异常在 await 时抛出 → coroutineScope 传播；引擎 executeTool 不抛（异常转 ToolResult），此路径仅验证契约
        try {
            runner.run(calls.map { it to dummyCtx(it.id) })
            org.junit.Assert.fail("应抛出异常")
        } catch (e: RuntimeException) {
            assertEquals("boom", e.message)
        }
    }

    private fun dummyCtx(callId: String) = ToolContext(
        sessionId = "s", workspaceRoot = null, projectId = "",
        workspaceName = "", callId = callId, onOutput = {},
    )
}
