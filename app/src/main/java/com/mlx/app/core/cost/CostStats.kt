package com.mlx.app.core.cost

/**
 * 成本统计聚合（纯函数，全部可单测；文档 2.6.2 改进设计的计算核心）。
 * 口径统一：三价公式 Cost = hitTokens×P_命中 + missTokens×P_未命中 + completionTokens×P_输出
 * （每个模型用各自的单价，见 Pricing.DEFAULT；与 CostAccount.record 完全同源，残差=0 可断言）。
 */

/** 模型分组展示名（Pro / Flash / 其他） */
fun modelGroup(model: String): String = when {
    model.contains("pro", ignoreCase = true) -> "Pro"
    model.contains("flash", ignoreCase = true) -> "Flash"
    else -> "其他"
}

/** 按模型分组的三档汇总（含三价复算成本与缓存节省；三档金额逐条按各模型单价精确累加） */
data class ModelBreakdown(
    val group: String,           // Pro / Flash / 其他
    val hitTokens: Long,
    val missTokens: Long,
    val completionTokens: Long,
    val cost: Double,            // 按各模型三价逐条复算求和
    val cachedSavings: Double,   // 缓存节省金额（Σ hit×(P_未命中−P_命中)）
    val hitCost: Double,         // 命中输入金额
    val missCost: Double,        // 未命中输入金额
    val outputCost: Double,      // 输出金额
)

fun aggregateByModel(costs: List<CostRecord>): List<ModelBreakdown> =
    costs.groupBy { modelGroup(it.model) }
        .map { (group, recs) ->
            ModelBreakdown(
                group = group,
                hitTokens = recs.sumOf { it.hitTokens },
                missTokens = recs.sumOf { it.missTokens },
                completionTokens = recs.sumOf { it.completionTokens },
                cost = recs.sumOf { recomputeCost(it.model, it.hitTokens, it.missTokens, it.completionTokens) },
                cachedSavings = recs.sumOf { cachedSaving(it) },
                hitCost = recs.sumOf { it.hitTokens / 1e6 * Pricing.priceFor(it.model).cachedInputPerM },
                missCost = recs.sumOf { it.missTokens / 1e6 * Pricing.priceFor(it.model).inputPerM },
                outputCost = recs.sumOf { it.completionTokens / 1e6 * Pricing.priceFor(it.model).outputPerM },
            )
        }
        .sortedByDescending { it.cost }

/** 单条三价复算（与 CostAccount.record 同公式） */
fun recomputeCost(model: String, hit: Long, miss: Long, completion: Long): Double {
    val p = Pricing.priceFor(model)
    return hit / 1e6 * p.cachedInputPerM + miss / 1e6 * p.inputPerM + completion / 1e6 * p.outputPerM
}

/** 单条缓存节省（命中 token 若按未命中价计费的差额） */
fun cachedSaving(c: CostRecord): Double {
    val p = Pricing.priceFor(c.model)
    return c.hitTokens / 1e6 * (p.inputPerM - p.cachedInputPerM)
}

fun cachedSavings(costs: List<CostRecord>): Double = costs.sumOf { cachedSaving(it) }

/** 命中率（hit / (hit + miss)；无输入返回 null） */
fun hitRateOf(hit: Long, miss: Long): Double? =
    if (hit + miss > 0) hit.toDouble() / (hit + miss) else null

// ---- 架构级 14：双树状图 / 折线图数据（纯函数） ----

/** 成本树节点（模型 → 日期 → 会话 → 轮次；叶子携带单轮三档明细文本） */
data class CostTreeNode(
    val id: String,
    val label: String,
    val value: Double,
    val detail: String? = null, // 叶子：命中/未命中/输出三档金额
    val children: List<CostTreeNode> = emptyList(),
)

/**
 * 双树状图（文档 2.6.2 第 2 条）：按模型分组各建一棵树，
 * 结构：模型根（合计）→ 日期（小计）→ 会话（小计）→ 轮次（单轮三档金额，复算校验）。
 */
fun buildModelDayTrees(costs: List<CostRecord>): List<CostTreeNode> =
    listOf("Pro", "Flash", "其他").mapNotNull { group ->
        val gCosts = costs.filter { modelGroup(it.model) == group }
        val total = gCosts.sumOf { it.costUsd }
        if (gCosts.isEmpty()) return@mapNotNull null
        val byDay = gCosts.groupBy { c -> dayLabel(c.at) }
        CostTreeNode(
            id = "model_root_$group",
            label = group,
            value = total,
            children = byDay.map { (day, dayCosts) ->
                CostTreeNode(
                    id = "day_${group}_$day",
                    label = day,
                    value = dayCosts.sumOf { it.costUsd },
                    children = dayCosts.groupBy { it.sessionId }.map { (sid, sCosts) ->
                        CostTreeNode(
                            id = "sess_${group}_${sid}_$day",
                            label = sessionLabel(sid),
                            value = sCosts.sumOf { it.costUsd },
                            children = sCosts.sortedBy { it.turn }.map { c ->
                                val hitCost = c.hitTokens / 1e6 * Pricing.priceFor(c.model).cachedInputPerM
                                val missCost = c.missTokens / 1e6 * Pricing.priceFor(c.model).inputPerM
                                val outCost = c.completionTokens / 1e6 * Pricing.priceFor(c.model).outputPerM
                                CostTreeNode(
                                    id = "turn_${c.sessionId}_${c.turn}",
                                    label = "第 ${c.turn} 轮",
                                    value = c.costUsd,
                                    // 审查修复：金额精度与 UiFormats.usd 对齐（≥0.01 两位小数，否则三位）
                                    detail = "命中 ${fmtTokens(c.hitTokens)}/${fmtUsd(hitCost)} · 未命中 ${fmtTokens(c.missTokens)}/${fmtUsd(missCost)} · 输出 ${fmtTokens(c.completionTokens)}/${fmtUsd(outCost)}",
                                )
                            },
                        )
                    },
                )
            },
        )
    }

/** 折线图数据点（命中率 / 未命中绝对量 / 每轮成本三视图共用） */
data class TurnPoint(
    val turn: Int,
    val model: String,
    val hitRate: Double?,
    val missTokens: Long,
    val costUsd: Double,
)

fun turnSeries(costs: List<CostRecord>): List<TurnPoint> =
    costs.sortedBy { it.turn }.map { c ->
        TurnPoint(
            turn = c.turn,
            model = c.model,
            hitRate = hitRateOf(c.hitTokens, c.missTokens),
            missTokens = c.missTokens,
            costUsd = c.costUsd,
        )
    }

/** 模型切换轮次（相邻两轮模型不同 → 该轮为切换点，用于折线图竖线标注） */
fun modelSwitchTurns(series: List<TurnPoint>): List<Int> {
    val switches = mutableListOf<Int>()
    series.zipWithNext().forEach { (a, b) ->
        if (a.model != b.model) switches += b.turn
    }
    return switches
}

/** 高 miss 轮次（单轮未命中 ≥ 5 万 token，折线图红点高亮） */
fun highMissTurns(series: List<TurnPoint>, threshold: Long = 50_000): List<Int> =
    series.filter { it.missTokens >= threshold }.map { it.turn }

// ---- 内部格式化（纯 JDK，无 Android 依赖，保证可单测） ----

private fun dayLabel(at: Long): String {
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = at
    return "%02d-%02d".format(cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH))
}

private fun sessionLabel(sessionId: String): String =
    if (sessionId.isBlank()) "（无归属会话）" else "会话 $sessionId"

private fun fmtTokens(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1e6)
    n >= 1000 -> "%.1fk".format(n / 1e3)
    else -> "$n"
}

/** 金额展示（与 UI 层 UiFormats.usd 同规则：≥0.01 两位小数，否则三位） */
private fun fmtUsd(cny: Double): String = if (cny >= 0.01) "¥%.2f".format(cny) else "¥%.3f".format(cny)
