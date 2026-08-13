package com.mlx.app.core.cost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CostStatsTest {

    private fun rec(turn: Int, model: String, hit: Long, miss: Long, completion: Long, at: Long = 1000L, sessionId: String = "s1") =
        CostRecord(sessionId = sessionId, turn = turn, model = model, hitTokens = hit, missTokens = miss, completionTokens = completion, costUsd = recomputeCost(model, hit, miss, completion), at = at)

    @Test
    fun `modelGroup classifies pro flash and other`() {
        assertEquals("Pro", modelGroup("deepseek-v4-pro"))
        assertEquals("Flash", modelGroup("deepseek-v4-flash"))
        assertEquals("其他", modelGroup("deepseek-reasoner"))
    }

    @Test
    fun `aggregateByModel groups mixed session and recompute matches recorded cost`() {
        // 文档实测场景：pro 前 18 轮 + flash 后 24 轮混用，残差=0
        val pro = rec(1, "deepseek-v4-pro", hit = 3_000_000, miss = 150_000, completion = 10_000)
        val flash = rec(19, "deepseek-v4-flash", hit = 3_500_000, miss = 250_000, completion = 8_000)
        val breakdowns = aggregateByModel(listOf(pro, flash))
        assertEquals(2, breakdowns.size)
        val proB = breakdowns.first { it.group == "Pro" }
        val flashB = breakdowns.first { it.group == "Flash" }
        // 三价复算与 CostRecord.costUsd 残差 0（引擎计价同公式）
        assertEquals(pro.costUsd, proB.cost, 1e-9)
        assertEquals(flash.costUsd, flashB.cost, 1e-9)
        // 三档分列金额累加 = 总成本
        assertEquals(proB.hitCost + proB.missCost + proB.outputCost, proB.cost, 1e-9)
        assertEquals(flashB.hitCost + flashB.missCost + flashB.outputCost, flashB.cost, 1e-9)
        // 排序：成本高的在前
        assertTrue(breakdowns[0].cost >= breakdowns[1].cost)
    }

    @Test
    fun `cachedSavings quantifies hit tokens at miss price differential`() {
        // flash：命中 1M × (1.0 - 0.02)/M = ¥0.98；pro：命中 2M × (3.0 - 0.025)/M = ¥5.95
        val flash = rec(1, "deepseek-v4-flash", hit = 1_000_000, miss = 0, completion = 0)
        val pro = rec(2, "deepseek-v4-pro", hit = 2_000_000, miss = 0, completion = 0)
        assertEquals(0.98, cachedSavings(listOf(flash)), 1e-6)
        assertEquals(5.95, cachedSavings(listOf(pro)), 1e-6)
    }

    @Test
    fun `hitRateOf null when no input`() {
        assertNull(hitRateOf(0, 0))
        assertEquals(0.8, hitRateOf(8000, 2000)!!, 1e-9)
    }

    @Test
    fun `buildModelDayTrees nests model to day to session to turn with detail`() {
        val t1 = rec(1, "deepseek-v4-pro", hit = 1000, miss = 2000, completion = 500, at = 1_000L)
        val t2 = rec(2, "deepseek-v4-pro", hit = 500, miss = 1000, completion = 300, at = 86_400_000L) // 次日
        val trees = buildModelDayTrees(listOf(t1, t2))
        val proTree = trees.first { it.label == "Pro" }
        assertEquals(2, proTree.children.size) // 两天
        val firstDay = proTree.children[0]
        val sessionNode = firstDay.children.first { it.label == "会话 s1" }
        val leaf = sessionNode.children.first()
        assertNotNull(leaf.detail)
        assertTrue(leaf.detail!!.contains("命中"))
        assertTrue(leaf.detail!!.contains("未命中"))
        assertTrue(leaf.detail!!.contains("输出"))
        // 叶子金额与记录一致
        assertEquals(t1.costUsd, leaf.value, 1e-9)
    }

    @Test
    fun `turnSeries and modelSwitchTurns detect pro to flash switch`() {
        // 文档实测：第 19 轮 pro → flash 切换
        val costs = (1..20).map { i ->
            rec(i, if (i <= 18) "deepseek-v4-pro" else "deepseek-v4-flash", hit = 1000, miss = 500, completion = 100)
        }
        val series = turnSeries(costs)
        assertEquals(20, series.size)
        val switches = modelSwitchTurns(series)
        assertEquals(listOf(19), switches)
        // 高 miss 轮次（≥50k）
        val high = turnSeries(listOf(rec(15, "deepseek-v4-pro", hit = 0, miss = 76_004, completion = 100)))
        assertEquals(listOf(15), highMissTurns(high))
    }
}
