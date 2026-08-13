package com.mlx.app.core.context

import com.mlx.app.core.common.MiniJson
import com.mlx.app.core.llm.ApiMessage
import com.mlx.app.core.llm.ApiToolCall
import com.mlx.app.core.tools.ToolSpec
import com.mlx.app.data.store.MessageRecord
import com.mlx.app.data.store.Session
import java.security.MessageDigest

/**
 * 缓存优先三区上下文管理器（对应 PC 版核心设计）：
 * - IMMUTABLE_PREFIX：system + 工具规范 + 指令文件（会话期间字节稳定，缓存命中基础）
 * - APPEND_ONLY_LOG：会话消息日志（只追加，压缩时仅做摘要替换）
 * - VOLATILE_SCRATCH：思考/草稿（永不上送；流式过程中由引擎持有）
 *
 * 纪律：动态信息（日期、窗口状态等）一律不得进入 prefix —— 由测试保证字节稳定性。
 */
class ContextManager(
    private val baseSystemPrompt: String,
    // DeepSeek V4 上下文窗口为 1M token（压缩阈值 40%/80% 为相对比例）
    private val maxEstimatedTokens: Int = 1_000_000,
) {
    var prefixVersion = 0
        private set
    var currentPrefixSha256: String = ""
        private set

    private var builtPrefix: String? = null
    // 八批：缓存短路 —— 输入未变时直接返回已构建前缀（回合循环每轮迭代省 20-30KB 字符串重建）。
    // 工具集按名称列表比较（ToolRegistry.all() 每次返回新列表实例，引用比较会永不命中）
    private var lastSpecsKey: String? = null
    private var lastInstruction: String? = null
    private var lastPlanMode: String? = null
    private var lastOutputStyle: String? = null
    private var lastSubagentSuffix: String? = null
    private var lastFileAttachmentSuffix: String? = null

    /** 构建不可变前缀。输入必须是静态内容。 */
    fun buildPrefix(
        specs: List<ToolSpec>,
        instructionFileText: String? = null,
        planModeSuffix: String? = null,
        outputStyleSuffix: String? = null,
        subagentSuffix: String? = null,
        fileAttachmentSuffix: String? = null,
    ): String {
        // 八批：输入与上次完全相同 → 直接复用，跳过重建与 SHA
        // 十一批修正：键含 名字+描述+参数指纹 —— 仅名字比较会吞掉运行时变化
        // （MCP schema 每回合"先卸后注"，description/parameters 变了必须触发 prefix 重建）
        // 十二批修正：① 非 specs 部分先判（O(1)），相同才计算 specsKey（省去每轮 stringify 全部工具参数）；
        // ② specsKey 按名称排序 —— 与下方实际拼接的 sortedBy { it.name } 一致，
        //   否则 refreshMcpTools 插入顺序变化会假 miss（内容字节相同仍每回合重建 prefix）
        if (builtPrefix != null &&
            instructionFileText == lastInstruction &&
            planModeSuffix == lastPlanMode &&
            outputStyleSuffix == lastOutputStyle &&
            subagentSuffix == lastSubagentSuffix &&
            fileAttachmentSuffix == lastFileAttachmentSuffix
        ) {
            val specsKey = specs.sortedBy { it.name }.joinToString(";") {
                "${it.name}|${it.description}|${MiniJson.stringify(it.parameters)}"
            }
            if (specsKey == lastSpecsKey) return builtPrefix!!
            lastSpecsKey = specsKey
            lastInstruction = instructionFileText
            lastPlanMode = planModeSuffix
            lastOutputStyle = outputStyleSuffix
            lastSubagentSuffix = subagentSuffix
            lastFileAttachmentSuffix = fileAttachmentSuffix
        } else {
            lastSpecsKey = specs.sortedBy { it.name }.joinToString(";") {
                "${it.name}|${it.description}|${MiniJson.stringify(it.parameters)}"
            }
            lastInstruction = instructionFileText
            lastPlanMode = planModeSuffix
            lastOutputStyle = outputStyleSuffix
            lastSubagentSuffix = subagentSuffix
            lastFileAttachmentSuffix = fileAttachmentSuffix
        }
        val sb = StringBuilder(baseSystemPrompt)
        sb.append("\n\n# 可用工具（参数为 JSON Schema）\n")
        for (t in specs.sortedBy { it.name }) {
            sb.append("## ").append(t.name).append('\n')
            sb.append(t.description).append('\n')
            sb.append("参数: ").append(MiniJson.stringify(t.parameters)).append('\n')
        }
        if (!instructionFileText.isNullOrBlank()) {
            // 二十二批（审计）：头部措辞修正 —— 技能索引也挂此头，原"优先级高于系统提示"声明
            // 被技能索引误用（索引条目无高于 BASE 语义），改为中性补充规则声明
            sb.append("\n# 项目指令文件与技能清单（补充规则）\n")
            sb.append(instructionFileText).append('\n')
        }
        if (!planModeSuffix.isNullOrBlank()) {
            sb.append('\n').append(planModeSuffix).append('\n')
        }
        if (!outputStyleSuffix.isNullOrBlank()) {
            sb.append('\n').append(outputStyleSuffix).append('\n')
        }
        if (!subagentSuffix.isNullOrBlank()) {
            sb.append('\n').append(subagentSuffix).append('\n')
        }
        if (!fileAttachmentSuffix.isNullOrBlank()) {
            sb.append('\n').append(fileAttachmentSuffix).append('\n')
        }
        val content = sb.toString()
        val sha = sha256(content)
        if (sha != currentPrefixSha256) {
            currentPrefixSha256 = sha
            prefixVersion++
        }
        builtPrefix = content
        return content
    }

    /** 组装发给 API 的消息列表：system(prefix) + 会话日志 */
    fun buildMessages(
        session: Session,
        specs: List<ToolSpec>,
        instructionFileText: String? = null,
        planModeSuffix: String? = null,
        outputStyleSuffix: String? = null,
        subagentSuffix: String? = null,
        fileAttachmentSuffix: String? = null,
    ): List<ApiMessage> {
        val system = buildPrefix(specs, instructionFileText, planModeSuffix, outputStyleSuffix, subagentSuffix, fileAttachmentSuffix)
        val out = mutableListOf(ApiMessage(role = "system", content = system))
        for (m in session.messages) {
            out += toApiMessage(m)
        }
        return out
    }

    private fun toApiMessage(m: MessageRecord): ApiMessage = when (m.role) {
        "user" -> ApiMessage("user", m.content.ifBlank { null })
        "tool" -> ApiMessage("tool", m.content.ifBlank { null }, toolCallId = m.toolCallId)
        else -> ApiMessage(
            role = "assistant",
            content = m.content.ifBlank { null },
            toolCalls = m.toolCalls.map {
                ApiToolCall(it.id, it.name, it.argsJson.ifBlank { "{}" })
            }.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * 估算当前上下文 token（字符/4 近似），用于压缩阈值触发。
     * 十二批修正：首轮构建必须带与 buildMessages 相同的完整参数 ——
     * 否则短 prefix 会被缓存为 builtPrefix 且 prefixVersion 虚增（版本 1 从未真正发给 API）。
     */
    fun estimatedTokens(
        session: Session,
        specs: List<ToolSpec>,
        instructionFileText: String? = null,
        planModeSuffix: String? = null,
        outputStyleSuffix: String? = null,
        subagentSuffix: String? = null,
        fileAttachmentSuffix: String? = null,
    ): Long {
        // 二十二批（审计）：参数为 null 时回退最近一次 buildPrefix 的完整参数 ——
        // 手动压缩（compactNow/compactManual）此前以默认 null 构建"短 prefix"并缓存
        //（prefixVersion 虚增、ratio 系统性低估），且依赖"引擎已先构建"的隐式调用顺序；
        // 回退后任何入口都用与 buildMessages 相同的完整参数
        val prefixLen = (builtPrefix
            ?: buildPrefix(
                specs,
                instructionFileText ?: lastInstruction,
                planModeSuffix ?: lastPlanMode,
                outputStyleSuffix ?: lastOutputStyle,
                subagentSuffix ?: lastSubagentSuffix,
                fileAttachmentSuffix ?: lastFileAttachmentSuffix,
            )).length
        return prefixLen / 4L + session.estimatedTokens()
    }

    fun ratioUsed(
        session: Session,
        specs: List<ToolSpec>,
        instructionFileText: String? = null,
        planModeSuffix: String? = null,
        outputStyleSuffix: String? = null,
        subagentSuffix: String? = null,
        fileAttachmentSuffix: String? = null,
    ): Double =
        estimatedTokens(session, specs, instructionFileText, planModeSuffix, outputStyleSuffix, subagentSuffix, fileAttachmentSuffix)
            .toDouble() / maxEstimatedTokens

    /** 自动压缩：将最旧的长工具结果替换为摘要（保持 prefix 不动，缓存键稳定） */
    fun compact(session: Session, specs: List<ToolSpec>, compactRatio: Double = 0.8) {
        var removed = 0
        // 1) 最旧的长工具结果 → 摘要
        val toolMessages = session.messages.filter { it.role == "tool" && it.content.length > 500 }
        for (m in toolMessages.take(4)) {
            m.content = "[已压缩] " + m.content.take(120) + " …（原 ${m.content.length} 字符）"
            removed++
        }
        // 2) 仍超限则丢弃最旧的非 user 消息（user 首条保留标题语义）
        // 十八批修复（审计）：原谓词 `createdAt == first().createdAt` 对首个元素恒真 → indexOfFirst 恒 0
        // → while 永不执行（Phase 2 死代码）。改为按角色查找：首条 user 天然不被选中（它 role=user）。
        while (ratioUsed(session, specs) > compactRatio && session.messages.size > 6) {
            val n = dropOldestNonUser(session)
            if (n == 0) break
            removed += n
        }
    }

    /**
     * 丢弃最旧非 user 消息（首条 user 保留标题语义）。
     * 二十二批（审计 CRITICAL）：若被删的是 assistant 消息（携带 tool_calls），其后的 tool 消息
     * 会变孤儿 —— OpenAI 兼容 API 拒绝"无对应 assistant tool_calls 的 tool 消息"（400），
     * 恰好发生在上下文最接近阈值、最需要压缩的时刻。故连带删除引用其 toolCallId 的 tool 消息。
     * 返回实际删除的消息数。
     */
    private fun dropOldestNonUser(session: Session): Int {
        val idx = session.messages.indexOfFirst { it.role != "user" }
        if (idx <= 0) return 0
        val removed = session.messages.removeAt(idx)
        var count = 1
        if (removed.role == "assistant" && removed.toolCalls.isNotEmpty()) {
            val orphanIds = removed.toolCalls.map { it.id }.toSet()
            val before = session.messages.size
            session.messages.removeAll { it.role == "tool" && it.toolCallId in orphanIds }
            count += before - session.messages.size
        }
        return count
    }

    /**
     * 手动压缩（对齐 PC CompactNow(force=true) + partitionFold 语义）：
     * 无条件执行一次 —— 折叠全部 assistant/tool 消息为摘要；
     * 用户消息原文永不折叠（用户陈述的事实不被摘要化）。
     * 返回折叠的消息数（0 = 无可压缩内容）。
     */
    fun compactManual(session: Session, specs: List<ToolSpec>, compactRatio: Double = 0.8): Int {
        var folded = 0
        for (m in session.messages) {
            if (m.role == "assistant" || m.role == "tool") {
                if (m.content.length > 200 || m.toolCalls.isNotEmpty()) {
                    val keep = if (m.role == "assistant") 150 else 80
                    m.content = "[已压缩] " + m.content.take(keep) + if (m.content.length > keep) " …" else ""
                    m.toolCalls.forEach { tc -> if (tc.resultText.length > 120) tc.resultText = tc.resultText.take(120) + " …" }
                    folded++
                }
            }
        }
        // 仍超阈值则丢弃最旧非 user 消息（user 首条保留标题语义）
        // 十八批修复（审计）：同 compact() —— 原谓词恒命中首元素导致循环永不执行（死代码）
        // 二十二批：连带清理孤儿 tool 消息（同 compact，防 API 400）
        var guard = 0
        while (ratioUsed(session, specs) > compactRatio && session.messages.size > 6 && guard++ < 20) {
            val n = dropOldestNonUser(session)
            if (n == 0) break
            folded += n
        }
        return folded
    }

    companion object {
        fun sha256(text: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
