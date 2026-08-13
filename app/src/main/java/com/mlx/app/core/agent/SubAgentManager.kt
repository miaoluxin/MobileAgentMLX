package com.mlx.app.core.agent

import com.mlx.app.core.llm.ApiMessage
import com.mlx.app.core.llm.DeepSeekClient
import com.mlx.app.core.llm.StreamEvent

/**
 * 子代理/规划者（对应 PC 版 subagents + planner）：
 * 独立 LLM 循环，无工具（只读分析），上下文独立不持久化。
 * - subagent：默认 flash（辅助调用硬编码 flash，与 PC 成本纪律一致）
 * - planner：pro 模型，系统提示为规划者角色
 * 深度限制：子代理循环内无工具注册表，天然不可再嵌套。
 */
class SubAgentManager(
    private val client: DeepSeekClient,
    private val config: EngineConfig,
) {

    /**
     * @param onDelta 流式增量回调（content/reasoning 双字段可空，可能在思考与正文阶段交错触发）。
     * 十一批：子代理过程可视化 —— 此前 reasoning 被丢弃、过程黑盒；回调由引擎接线经 ToolContext 发出
     */
    suspend fun runSubAgent(prompt: String, onDelta: (String?, String?) -> Unit = { _, _ -> }): String =
        runLoop(prompt, model = config.flashModelId(), system = SUBAGENT_SYSTEM, onDelta = onDelta)

    /** 技能声明的专用模型覆盖（P2-17：Skill.model 字段；如 deepseek-v4-pro） */
    suspend fun runSubAgentWithModel(prompt: String, model: String, onDelta: (String?, String?) -> Unit = { _, _ -> }): String =
        runLoop(prompt, model = model, system = SUBAGENT_SYSTEM, onDelta = onDelta)

    suspend fun runPlanner(question: String, onDelta: (String?, String?) -> Unit = { _, _ -> }): String =
        runLoop(question, model = config.proModelId(), system = PLANNER_SYSTEM, onDelta = onDelta)

    private suspend fun runLoop(prompt: String, model: String, system: String, onDelta: (String?, String?) -> Unit): String {
        val key = config.apiKey()
        if (key.isNullOrBlank()) return "错误：未配置 API Key"
        val sb = StringBuilder()
        var error: String? = null
        client.streamChat(
            key, config.baseUrl(), model,
            listOf(ApiMessage("system", system), ApiMessage("user", prompt)),
        ).collect { ev ->
            when (ev) {
                is StreamEvent.Delta -> {
                    onDelta(ev.delta.content, ev.delta.reasoning)
                    ev.delta.content?.let { sb.append(it) }
                }
                is StreamEvent.Error -> { error = ev.message }
                else -> Unit
            }
        }
        val out = sb.toString().trim()
        return if (error != null) "错误：$error${if (out.isNotBlank()) "\n（部分输出：${out.take(500)}）" else ""}"
        else out.ifBlank { "（子代理无输出）" }
    }

    companion object {
        // 二十一批：契约升级（对齐 Claude Code 子代理契约 + PC DefaultTaskSystemPrompt）——
        // 静态常量保持字节稳定（system 段前缀可命中；user 任务各异，其 miss 结构性不可避免）。
        // 委派消息结构（八要素）与父侧 DELEGATION_PROMPT_SUFFIX 严格对齐（二十二批：补【返回形态】）。
        const val SUBAGENT_SYSTEM = """你是 MLX 的子代理（subagent），被主 Agent 委派处理一个聚焦任务。
规则：
1. 只做被派发的任务，不扩展目标。user 消息按【任务】【上下文】【约束】【期望输出】【质量标准】【禁止事项】【成功定义】【返回形态】组织，照此结构理解任务。
2. 只做分析/回答，不执行任何工具；信息不足时明确说不确定，或给出精确问题让主 Agent 补充，禁止臆测。
3. 你的报告不直接展示给用户，主 Agent 会综合转述 → 结论式、结构化、可直接被引用：原始数据（文件路径/行号/数值/引文）优先，禁止表功式叙述。
4. 输出精炼，回答完即止，不追加无关内容。
5. 使用与用户消息相同的语言回答（主 Agent 无需翻译即可综合转述）。"""
        const val PLANNER_SYSTEM = """你是 MLX 的规划者（planner）。你的职责是对任务做只读研究与方案设计。
规则：
1. 只做被派发的任务，不扩展目标。user 消息按【任务】【上下文】【约束】【期望输出】【质量标准】【禁止事项】【成功定义】【返回形态】组织，照此结构理解任务。
2. 输出：问题拆解 → 关键决策点 → 推荐方案（含步骤与风险）。
3. 不执行任何工具；信息不足时明确说不确定，或给出精确问题让主 Agent 补充，禁止臆测。
4. 你的报告不直接展示给用户，主 Agent 会综合转述 → 结论式、结构化、可直接被引用：原始数据（文件路径/行号/数值/引文）优先，禁止表功式叙述。
5. 输出精炼，回答完即止，不追加无关内容。
6. 使用与用户消息相同的语言回答（主 Agent 无需翻译即可综合转述）。"""
    }
}
