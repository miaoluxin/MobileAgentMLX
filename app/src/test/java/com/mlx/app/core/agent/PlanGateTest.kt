package com.mlx.app.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class PlanGateTest {

    @Test
    fun `full lifecycle idle to planning to review to executing`() {
        val gate = PlanGate("s1")
        assertEquals(PlanPhase.IDLE, gate.phase)
        gate.startPlanning()
        assertEquals(PlanPhase.PLANNING, gate.phase)
        val deferred = gate.submitPlan("方案：读取 README 后生成 REASONIX.md")
        assertTrue(deferred != null)
        assertEquals(PlanPhase.PENDING_REVIEW, gate.phase)
        assertEquals("方案：读取 README 后生成 REASONIX.md", gate.pendingPlanText)
        // 批准 → EXECUTING，deferred 完成
        runBlocking {
            gate.respond(PlanReviewDecision.Approve("x"))
            assertEquals(PlanReviewDecision.Approve("x"), deferred!!.await())
        }
        assertEquals(PlanPhase.EXECUTING, gate.phase)
        gate.endTurn()
        assertEquals(PlanPhase.IDLE, gate.phase)
    }

    @Test
    fun `submitPlan rejected outside planning phase no hang`() {
        // 审查修复：非 PLANNING 阶段（未开启计划模式/已批准执行中）→ null，引擎记录 FAILED 不挂起
        val gate = PlanGate("s1")
        assertNull(gate.submitPlan("plan")) // IDLE
        gate.startPlanning()
        gate.submitPlan("plan")
        gate.respond(PlanReviewDecision.Approve("plan"))
        assertNull(gate.submitPlan("another plan")) // EXECUTING
    }

    @Test
    fun `planning phase allows read-only shell and blocks writes`() {
        // 二十二批（审计）：计划模式 shell/python_exec 白名单 —— 搜索类命令放行，写类拒绝
        val gate = PlanGate("s1")
        gate.startPlanning()
        assertFalse(gate.writeBlocked("shell", mapOf("command" to "grep -r foo .")))
        assertFalse(gate.writeBlocked("shell", mapOf("command" to "cat a.txt | head -5")))
        assertFalse(gate.writeBlocked("shell", mapOf("command" to "git status")))
        assertFalse(gate.writeBlocked("shell", mapOf("command" to "find . -name '*.kt'")))
        assertTrue(gate.writeBlocked("shell", mapOf("command" to "rm -rf .")))
        assertTrue(gate.writeBlocked("shell", mapOf("command" to "cat a > b")))
        assertTrue(gate.writeBlocked("shell", mapOf("command" to "echo hi >> log")))
        assertTrue(gate.writeBlocked("shell", mapOf("command" to "sed -i s/a/b/ f")))
        assertTrue(gate.writeBlocked("shell", mapOf("command" to "git push")))
        assertTrue(gate.writeBlocked("shell", mapOf())) // 无命令
        assertTrue(gate.writeBlocked("python_exec", mapOf("code" to "print(1)"))) // 无法静态判断只读
        assertFalse(gate.writeBlocked("read_file")) // 非 shell 不受影响
    }

    @Test
    fun `isReadOnlyShell pure function`() {
        assertTrue(PlanGate.isReadOnlyShell("grep foo"))
        assertTrue(PlanGate.isReadOnlyShell("find . -name '*.kt'"))
        assertTrue(PlanGate.isReadOnlyShell("git log --oneline"))
        assertTrue(PlanGate.isReadOnlyShell("grep a f | head -5"))
        assertTrue(PlanGate.isReadOnlyShell("ls -la"))
        assertTrue(PlanGate.isReadOnlyShell("sed s/a/b/ f")) // 无 -i 允许（只读替换输出）
        assertFalse(PlanGate.isReadOnlyShell("echo hi > f"))
        assertFalse(PlanGate.isReadOnlyShell("rm -rf ."))
        assertFalse(PlanGate.isReadOnlyShell("sed -i 's/a/b/' f"))
        assertFalse(PlanGate.isReadOnlyShell("git push"))
        assertFalse(PlanGate.isReadOnlyShell("python3 x.py"))
        assertFalse(PlanGate.isReadOnlyShell(""))
    }

    @Test
    fun `revise returns to planning and clears pending text`() {
        val gate = PlanGate("s1")
        gate.startPlanning()
        val deferred = gate.submitPlan("plan")!!
        gate.respond(PlanReviewDecision.Revise("补充测试步骤"))
        assertTrue(deferred.isCompleted)
        assertEquals(PlanPhase.PLANNING, gate.phase)
        assertEquals("", gate.pendingPlanText)
    }

    @Test
    fun `reject completes deferred and stays idle after endTurn`() {
        val gate = PlanGate("s1")
        gate.startPlanning()
        val deferred = gate.submitPlan("plan")!!
        gate.respond(PlanReviewDecision.Reject)
        assertEquals(PlanPhase.IDLE, gate.phase)
        assertTrue(deferred.isCompleted)
    }

    @Test
    fun `endTurn completes pending deferred with reject engine abort safety`() {
        val gate = PlanGate("s1")
        gate.startPlanning()
        val deferred = gate.submitPlan("plan")!!
        gate.endTurn() // 回合被取消/中断 → 挂起的审批自动完成（防引擎死锁）
        assertTrue(deferred.isCompleted)
        assertEquals(PlanPhase.IDLE, gate.phase)
        // 审查修复（停止竞态）：endTurn 后 UI 迟到点击 → 忽略（防 phase 泄漏 EXECUTING 卡死计划模式）
        gate.respond(PlanReviewDecision.Approve("x"))
        assertEquals(PlanPhase.IDLE, gate.phase)
        // 下一回合可正常进入规划
        gate.startPlanning()
        assertEquals(PlanPhase.PLANNING, gate.phase)
    }

    @Test
    fun `writeBlocked true only in planning phase for write tools`() {
        val gate = PlanGate("s1")
        // IDLE：不拦截
        assertFalse(gate.writeBlocked("write_file"))
        gate.startPlanning()
        // PLANNING：写工具拦截、读工具放行
        assertTrue(gate.writeBlocked("write_file"))
        assertTrue(gate.writeBlocked("edit_file"))
        assertTrue(gate.writeBlocked("delete_range"))
        assertFalse(gate.writeBlocked("read_file"))
        assertFalse(gate.writeBlocked("list_files"))
        // EXECUTING：写工具放行（批准后）
        gate.submitPlan("plan")
        gate.respond(PlanReviewDecision.Approve("plan"))
        assertFalse(gate.writeBlocked("write_file"))
    }

    @Test
    fun `startPlanning idempotent when already planning`() {
        val gate = PlanGate("s1")
        gate.startPlanning()
        gate.startPlanning() // 重复调用不重置（回合内一次）
        assertEquals(PlanPhase.PLANNING, gate.phase)
        gate.submitPlan("p")
        gate.startPlanning() // PENDING_REVIEW 阶段不被打断
        assertEquals(PlanPhase.PENDING_REVIEW, gate.phase)
    }

    @Test
    fun `per session gates are isolated cross session pollution fix`() {
        // 审查修复：会话 A 的计划审批不被会话 B 回合破坏（PlanGate per-session 隔离）
        val gateA = PlanGate("s_a")
        val gateB = PlanGate("s_b")
        gateA.startPlanning()
        val deferredA = gateA.submitPlan("A 的方案")!!
        // B 会话：IDLE 独立状态，不受 A 影响
        assertEquals(PlanPhase.IDLE, gateB.phase)
        gateB.startPlanning()
        assertEquals(PlanPhase.PLANNING, gateB.phase)
        // B 回合结束只影响 B
        gateB.endTurn()
        assertEquals(PlanPhase.IDLE, gateB.phase)
        assertEquals(PlanPhase.PENDING_REVIEW, gateA.phase)
        assertFalse(deferredA.isCompleted) // A 的审批仍挂起等待
        // A 继续完成审批
        gateA.respond(PlanReviewDecision.Approve("A"))
        assertTrue(deferredA.isCompleted)
        assertEquals(PlanPhase.EXECUTING, gateA.phase)
    }
}
