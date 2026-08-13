package com.mlx.app.ui.chat

import com.mlx.app.core.cost.CostRecord
import com.mlx.app.data.store.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContextBarInfoTest {

    private fun session(totalCost: Double): Session {
        val s = Session(
            id = "s1", title = "测试", createdAt = 0L, updatedAt = 0L, model = "deepseek-v4-flash",
        )
        s.costs += CostRecord("s1", 1, "deepseek-v4-flash", 1000, 1000, 100, totalCost, 0L)
        return s
    }

    @Test
    fun `primary is always session total regardless of lastCost`() {
        // 修复：口径统一 —— 主显示恒为会话累计（与成本页同源全量求和），
        // 差距 13 倍根因 = 回合结束后仍显示 lastCost（最后一轮单轮成本）
        val s = session(totalCost = 1.0174)
        val lastCost = CostDisplay(hitRate = 0.9, costUsd = 0.012, tokens = 5000)

        val afterTurn = contextBarInfo(s, lastCost, running = false)
        assertEquals("¥1.02", afterTurn.primaryUsd)
        assertNull(afterTurn.stepUsd) // 非执行中不显示"本步"

        val running = contextBarInfo(s, lastCost, running = true)
        assertEquals("¥1.02", running.primaryUsd)
        assertEquals("+¥0.01", running.stepUsd) // 执行中才附显最近一步（UiFormats 两位小数）
    }

    @Test
    fun `no session and no lastCost shows zero`() {
        val info = contextBarInfo(null, null, running = false)
        assertEquals("¥0.000", info.primaryUsd)
        assertNull(info.stepUsd)
    }

    @Test
    fun `hitRate prefers lastCost when running otherwise session average`() {
        val s = session(totalCost = 0.1)
        // 会话命中率 = 1000/(1000+1000) = 0.5
        val running = contextBarInfo(s, CostDisplay(hitRate = 0.9, costUsd = 0.01, tokens = 1), running = true)
        assertEquals(0.9, running.hitRate!!, 1e-9)
        val idle = contextBarInfo(s, CostDisplay(hitRate = 0.9, costUsd = 0.01, tokens = 1), running = false)
        assertEquals(0.5, idle.hitRate!!, 1e-9)
    }
}
