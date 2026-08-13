package com.mlx.app.core.cost

import com.mlx.app.core.llm.Usage

/**
 * 成本记账（对齐官网价格口径：人民币 CNY / 每百万 token）：
 * - 价格表 = 官网 https://api-docs.deepseek.com/zh-cn/quick_start/pricing 官方价（与 PC 源码 deepSeekV4PricesCNY 一致）
 * - 输入/输出/缓存命中三构成分解，写入每回合成本记录（单位：元）
 */
data class ModelPrice(
    val inputPerM: Double,      // CNY / 1M 非缓存输入 token
    val outputPerM: Double,     // CNY / 1M 输出 token
    val cachedInputPerM: Double, // CNY / 1M 缓存命中 token
)

object Pricing {
    /**
     * 默认价格表 = 官网 CNY 官方价（每百万 token；与 PC internal/config/pricing.go
     * deepSeekV4PricesCNY 完全一致）：
     * flash：输入 ¥1 / 输出 ¥2 / 缓存命中 ¥0.02；pro：输入 ¥3 / 输出 ¥6 / 缓存命中 ¥0.025
     * 旧别名 deepseek-chat/reasoner 保留为美元兼容项（已停用，仅历史会话记账用）。
     */
    val DEFAULT = mapOf(
        "deepseek-v4-flash" to ModelPrice(inputPerM = 1.0, outputPerM = 2.0, cachedInputPerM = 0.02),
        "deepseek-v4-pro" to ModelPrice(inputPerM = 3.0, outputPerM = 6.0, cachedInputPerM = 0.025),
        "deepseek-chat" to ModelPrice(0.27, 1.10, 0.07),      // USD 兼容
        "deepseek-reasoner" to ModelPrice(0.55, 2.19, 0.14),  // USD 兼容
    )

    fun priceFor(model: String): ModelPrice =
        DEFAULT[model] ?: ModelPrice(1.0, 2.0, 0.02)
}

data class CostRecord(
    val sessionId: String,
    val turn: Int,
    val model: String,
    val hitTokens: Long,
    val missTokens: Long,
    val completionTokens: Long,
    /** 成本（人民币元，官网 CNY 计价；字段名保留兼容历史序列化） */
    val costUsd: Double,
    val at: Long,
)

class CostAccount {
    fun record(sessionId: String, turn: Int, model: String, usage: Usage, at: Long): CostRecord {
        val p = Pricing.priceFor(model)
        val cost = usage.cacheHitTokens / 1e6 * p.cachedInputPerM +
            usage.cacheMissTokens / 1e6 * p.inputPerM +
            usage.completionTokens / 1e6 * p.outputPerM
        return CostRecord(
            sessionId = sessionId,
            turn = turn,
            model = model,
            hitTokens = usage.cacheHitTokens,
            missTokens = usage.cacheMissTokens,
            completionTokens = usage.completionTokens,
            costUsd = cost,
            at = at,
        )
    }
}
