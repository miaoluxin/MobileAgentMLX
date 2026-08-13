package com.mlx.app.core.cost

import com.mlx.app.core.llm.Usage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CostAccountTest {

    @Test
    fun `cached tokens billed at official cny cache price`() {
        val account = CostAccount()
        val usage = Usage(
            promptTokens = 1_000_000,
            completionTokens = 0,
            cacheHitTokens = 1_000_000,
            cacheMissTokens = 0,
        )
        val rec = account.record("s1", 1, "deepseek-v4-flash", usage, 0L)
        // 1M 缓存命中 × ¥0.02/M（官网 flash 缓存价）= ¥0.02
        assertEquals(0.02, rec.costUsd, 1e-9)
    }

    @Test
    fun `missed tokens billed at official cny input rate`() {
        val account = CostAccount()
        val usage = Usage(
            promptTokens = 1_000_000,
            completionTokens = 0,
            cacheHitTokens = 0,
            cacheMissTokens = 1_000_000,
        )
        val rec = account.record("s1", 1, "deepseek-v4-flash", usage, 0L)
        // 1M 非缓存输入 × ¥1/M = ¥1
        assertEquals(1.0, rec.costUsd, 1e-9)
    }

    @Test
    fun `mixed usage combines with cny prices`() {
        val account = CostAccount()
        val usage = Usage(
            promptTokens = 2_000_000,
            completionTokens = 100_000,
            cacheHitTokens = 1_500_000,
            cacheMissTokens = 500_000,
        )
        val rec = account.record("s1", 1, "deepseek-v4-flash", usage, 0L)
        val expected = 1_500_000 / 1e6 * 0.02 + 500_000 / 1e6 * 1.0 + 100_000 / 1e6 * 2.0
        assertEquals(expected, rec.costUsd, 1e-9)
    }

    @Test
    fun `unknown model falls back to flash cny price`() {
        val account = CostAccount()
        val usage = Usage(1_000_000, 0, 0, 1_000_000)
        val rec = account.record("s1", 1, "custom-model", usage, 0L)
        assertEquals(1.0, rec.costUsd, 1e-9)
    }

    @Test
    fun `v4 flash full price matches official cny`() {
        val account = CostAccount()
        val usage = Usage(1_000_000, 1_000_000, 0, 1_000_000)
        val rec = account.record("s1", 1, "deepseek-v4-flash", usage, 0L)
        assertEquals(1.0 + 2.0, rec.costUsd, 1e-9)
    }

    @Test
    fun `v4 pro price matches official cny`() {
        val account = CostAccount()
        val usage = Usage(1_000_000, 1_000_000, 0, 1_000_000)
        val rec = account.record("s1", 1, "deepseek-v4-pro", usage, 0L)
        assertEquals(3.0 + 6.0, rec.costUsd, 1e-9)
    }

    @Test
    fun `reasoner model has different price`() {
        val account = CostAccount()
        val usage = Usage(1_000_000, 1_000_000, 0, 1_000_000)
        val rec = account.record("s1", 1, "deepseek-reasoner", usage, 0L)
        assertTrue(rec.costUsd > 1.0)
    }

    @Test
    fun `cache hit rate computed from usage`() {
        val usage = Usage(
            promptTokens = 10_000,
            completionTokens = 0,
            cacheHitTokens = 9_000,
            cacheMissTokens = 1_000,
        )
        assertEquals(0.9, usage.cacheHitRate(), 1e-9)
    }
}
