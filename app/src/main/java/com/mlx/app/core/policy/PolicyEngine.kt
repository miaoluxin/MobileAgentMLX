package com.mlx.app.core.policy

/**
 * 权限策略引擎（对应 PC 版 allow/ask/deny 三级模型）。
 * 规则格式：工具名:路径glob，例如 edit_file:/app/src/...
 * 决策优先级：deny > allow > 默认（只读工具 ALLOW，其余 ASK）。
 */
enum class Decision { ALLOW, ASK, DENY }

data class PolicyRule(val pattern: String, val decision: Decision, val global: Boolean = true)

object Glob {
    /** glob 匹配：* 不跨斜杠；** 跨斜杠；? 匹配单字符（不跨斜杠） */
    fun matches(pattern: String, target: String): Boolean {
        return toRegex(pattern).matches(target)
    }

    private fun toRegex(pattern: String): Regex {
        val sb = StringBuilder("^")
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when (c) {
                '*' -> {
                    val isDouble = i + 1 < pattern.length && pattern[i + 1] == '*'
                    if (isDouble) {
                        if (i + 2 < pattern.length && pattern[i + 2] == '/') {
                            // 双星加斜杠：匹配任意层级（含零层）
                            sb.append("(?:.*/)?")
                            i += 3
                            continue
                        }
                        sb.append(".*")
                        i += 2
                        continue
                    }
                    sb.append("[^/]*")
                    i++
                }
                '?' -> {
                    sb.append("[^/]")
                    i++
                }
                '\\' -> {
                    if (i + 1 < pattern.length) {
                        sb.append(Regex.escape(pattern[i + 1].toString()))
                        i += 2
                    } else {
                        i++
                    }
                }
                else -> {
                    sb.append(Regex.escape(c.toString()))
                    i++
                }
            }
        }
        sb.append("$")
        return Regex(sb.toString())
    }
}

class PolicyEngine {

    private val _rules = mutableListOf<PolicyRule>()
    val rules: List<PolicyRule> get() = _rules.toList()

    /** 只读工具默认放行（P12/P13：联网与搜索类工具自动调用，PC 体感无审批）；其余默认询问 */
    val readOnlyTools = setOf(
        "read_file", "list_files", "search_files", "grep", "glob", "web_fetch", "web_search",
        "code_index", "complete_step",
        "submit_plan", // 计划模式方案提交（架构级 13）：自动放行，走 PlanGate 审批流而非权限审批
        "explore", "research", "review", // 技能顶级工具（P2-8）：只读分析，自动放行
        // 六批：todo_* 纯记账无副作用 → 默认放行（修复"任务骨架每次弹审批"与提示词"先建 todo"自相矛盾）
        "todo_add", "todo_list", "todo_complete",
        // 九批：subagent/planner 只读无文件副作用 → 自动放行 + 入并行组（"自动并行子任务"钥匙：
        // 模型同轮调多个 subagent 时引擎 ToolBatchRunner 自动并发执行）
        "subagent", "planner",
        // 二十二批（审计）：技能工具纯读取 → 自动放行（read_skill 读剧本、run_skill inline 返回
        // 剧本/subagent 只读分析、wait_job 状态查询；与 explore/research/review 同类待遇，
        // 此前 review 模式每次弹审批，与 BASE"匹配到技能后用 run_skill 加载"引导矛盾）
        "read_skill", "run_skill", "wait_job",
    )

    /**
     * 规则变更回调（八批：审批"始终允许"持久化 —— 由 MlxApp 装配写回 AppStore）。
     * 低频操作（用户点审批时），装配方可选 runBlocking(IO) 同步落盘。
     */
    var onRulesChanged: ((List<PolicyRule>) -> Unit)? = null

    fun addRule(pattern: String, decision: Decision) {
        _rules.removeAll { it.pattern == pattern }
        _rules += PolicyRule(pattern, decision)
        onRulesChanged?.invoke(_rules.toList())
    }

    fun setRules(newRules: List<PolicyRule>) {
        _rules.clear()
        _rules += newRules
    }

    fun removeRule(pattern: String) {
        _rules.removeAll { it.pattern == pattern }
        onRulesChanged?.invoke(_rules.toList())
    }

    fun decide(tool: String, path: String? = null): Decision {
        val target = if (path != null) "$tool:$path" else tool
        var allowHit = false
        for (r in _rules) {
            if (Glob.matches(r.pattern, target)) {
                when (r.decision) {
                    Decision.DENY -> return Decision.DENY
                    Decision.ALLOW -> allowHit = true
                    Decision.ASK -> Unit // 继续扫描，deny 优先级更高
                }
            }
        }
        if (allowHit) return Decision.ALLOW
        return if (tool in readOnlyTools) Decision.ALLOW else Decision.ASK
    }
}
