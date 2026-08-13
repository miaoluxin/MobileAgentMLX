package com.mlx.app.core.repair

import com.mlx.app.core.common.MiniJson

/**
 * 工具调用修复管线（对应 PC 版针对 DeepSeek API 的四道修复）：
 * 1. Flatten  —— schema 扁平化（参数描述层做，见 ToolSpec 注释；此处对嵌套参数做点号展开）
 * 2. Scavenge —— 从 reasoning 文本中搜刮散落的 tool_call JSON
 * 3. Truncation —— 检测被 max_tokens 截断的半截 JSON 并尝试闭合
 * 4. Storm  —— 相邻重复工具调用去重
 * 全部为纯字符串/JSON 逻辑，与平台无关。
 */
object RepairPipeline {

    data class AssembledCall(val id: String, val name: String, val argsJson: String)

    /** 恢复统计（诊断可见性：不再静默丢弃） */
    data class RecoverStats(
        val calls: List<AssembledCall>,
        val dropped: Int = 0,     // 无法修复而被丢弃的调用数
        val repaired: Int = 0,    // 通过截断闭合/搜刮找回成功修复的调用数
        val scavenged: Int = 0,   // 从 reasoning 文本搜刮找回的调用数
    )

    /**
     * 综合恢复入口（详细版，带统计）：组装后的调用 + 思考文本（八批：移除未使用的 contentText 参数 —— 正文不参与修复）。
     * 返回修复后可用于执行的调用列表（argsJson 保证是合法 JSON）。
     */
    fun recoverDetailed(
        assembled: List<AssembledCall>,
        reasoningText: String,
    ): RecoverStats {
        val result = mutableListOf<AssembledCall>()
        var seq = 0
        var dropped = 0
        var repaired = 0

        // 2) Scavenge：补充 reasoning 中散落的调用
        val scavenged = scavengeToolCalls(reasoningText)
        val all = assembled.toMutableList() +
            scavenged.map { AssembledCall(it.id, it.name, it.argsJson) }

        for (call in all) {
            var name = call.name.trim()
            var args = call.argsJson.trim()
            var id = call.id.ifBlank { "call_${seq++}" }
            var wasBroken = false

            // 3) Truncation：闭合被截断的 JSON；无法修复则丢弃该调用
            var argsRecoverable = true
            if (args.isNotBlank() && MiniJson.parse(args) == null) {
                val recovered = truncationRecover(args)
                if (recovered == null) {
                    argsRecoverable = false
                } else {
                    args = recovered
                    wasBroken = true
                }
            }
            if (!argsRecoverable) {
                dropped++
                continue
            }

            // name 缺失时尝试从 arguments 中恢复
            if (name.isBlank()) {
                val m = (MiniJson.parse(args) as? Map<String, Any?>)
                name = (m?.get("name") as? String) ?: ""
                if (name.isNotBlank()) wasBroken = true
            }
            // 仍无效则丢弃该调用
            if (name.isBlank()) {
                dropped++
                continue
            }
            val finalArgs = args.ifBlank { "{}" }
            if (MiniJson.parse(finalArgs) == null) {
                dropped++
                continue
            }
            result.add(AssembledCall(id, name, finalArgs))
            if (wasBroken) repaired++
        }

        // 4) Storm：滑动窗口去重（相同 name+args 连续出现则丢弃）
        val deduped = dedupeStorm(result)
        return RecoverStats(
            calls = deduped,
            dropped = dropped,
            repaired = repaired,
            scavenged = scavenged.size,
        )
    }

    /**
     * 综合恢复入口（简版，保持兼容）：返回修复后可用于执行的调用列表。
     */
    fun recover(
        assembled: List<AssembledCall>,
        reasoningText: String,
    ): List<AssembledCall> = recoverDetailed(assembled, reasoningText).calls

    // 字符串形式：{"name":"xxx","arguments":"{...}"}（name 在前）
    private val argsPattern = Regex(
        "\"name\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"arguments\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\""
    )
    // 字符串形式：arguments 在前、name 在后（顺序颠倒）
    private val argsPatternReversed = Regex(
        "\"arguments\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*,\\s*\"name\"\\s*:\\s*\"([^\"]+)\""
    )
    // 对象形式：arguments 为嵌套 JSON 对象（V4 可能输出对象而非字符串）
    private val argsObjectPattern = Regex(
        "\"name\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"arguments\"\\s*:\\s*(\\{[^{}]*\\})"
    )
    private val argsObjectPatternReversed = Regex(
        "\"arguments\"\\s*:\\s*(\\{[^{}]*\\})\\s*,\\s*\"name\"\\s*:\\s*\"([^\"]+)\""
    )

    /** Scavenge：在 reasoning_content 中寻找被模型"遗忘"的工具调用 JSON（兼容字符串/对象、任意顺序） */
    fun scavengeToolCalls(reasoningText: String): List<AssembledCall> {
        if (reasoningText.isBlank()) return emptyList()
        val found = mutableListOf<AssembledCall>()
        fun addCall(name: String, argsRaw: String) {
            val args = truncationRecover(argsRaw) ?: argsRaw
            if (MiniJson.parse(args) == null) return
            found += AssembledCall("", name, args)
        }
        for (m in argsPattern.findAll(reasoningText)) addCall(m.groupValues[1], unescape(m.groupValues[2]))
        for (m in argsPatternReversed.findAll(reasoningText)) addCall(m.groupValues[2], unescape(m.groupValues[1]))
        for (m in argsObjectPattern.findAll(reasoningText)) addCall(m.groupValues[1], m.groupValues[2])
        for (m in argsObjectPatternReversed.findAll(reasoningText)) addCall(m.groupValues[2], m.groupValues[1])
        return found.distinctBy { it.name + it.argsJson }
    }

    /** Truncation：JSON 状态栈扫描 + 闭合 + 校验 */
    fun truncationRecover(raw: String): String? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return null
        val stack = ArrayDeque<Char>()
        var inString = false
        var escaped = false
        for (c in trimmed) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> stack.addLast(c)
                '}', ']' -> if (stack.isNotEmpty()) stack.removeLast()
            }
        }
        if (stack.isEmpty() && !inString) return trimmed
        val sb = StringBuilder(trimmed)
        if (inString) sb.append('"')
        while (stack.isNotEmpty()) {
            when (stack.removeLast()) {
                '{' -> sb.append('}')
                '[' -> sb.append(']')
            }
        }
        val candidate = sb.toString()
        return if (MiniJson.parse(candidate) != null) candidate else null
    }

    /** Storm：连续重复 (name, args) 去重（八批：key 用完整 argsJson 字符串，消除 hashCode 碰撞误删） */
    fun dedupeStorm(calls: List<AssembledCall>): List<AssembledCall> {
        val recent = ArrayDeque<String>()
        val out = mutableListOf<AssembledCall>()
        for (c in calls) {
            val key = c.name + ":" + c.argsJson
            if (recent.contains(key)) continue
            recent.addLast(key)
            if (recent.size > 4) recent.removeFirst()
            out.add(c)
        }
        return out
    }

    private fun unescape(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'u' -> {
                        if (i + 5 < s.length) {
                            sb.append(s.substring(i + 2, i + 6).toInt(16).toChar())
                            i += 5
                        }
                    }
                    else -> sb.append(n)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
