package com.mlx.app.core.agent

import com.mlx.app.core.checkpoint.CheckpointStore
import com.mlx.app.core.common.MiniJson
import com.mlx.app.core.context.ContextManager
import com.mlx.app.core.cost.CostAccount
import com.mlx.app.core.mcp.McpClient
import com.mlx.app.core.mcp.McpRegistry
import com.mlx.app.core.memory.FactMemory
import com.mlx.app.core.memory.InstructionFiles
import com.mlx.app.core.memory.SkillStore
import com.mlx.app.core.tools.ToolSpec
import com.mlx.app.core.llm.ChatDelta
import com.mlx.app.core.llm.DeepSeekClient
import com.mlx.app.core.llm.StreamEvent
import com.mlx.app.core.llm.ToolCallDelta
import com.mlx.app.core.llm.Usage
import com.mlx.app.core.policy.Decision
import com.mlx.app.core.policy.PolicyEngine
import com.mlx.app.core.repair.RepairPipeline
import com.mlx.app.core.tools.ToolContext
import com.mlx.app.core.tools.ToolRegistry
import com.mlx.app.core.tools.ToolResult
import com.mlx.app.data.saf.SafRepo
import com.mlx.app.data.store.MessageRecord
import com.mlx.app.data.store.Session
import com.mlx.app.data.store.SessionStore
import com.mlx.app.data.store.ToolCallRecord
import com.mlx.app.data.store.ToolStatus
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

enum class ApprovalDecision { ALLOW_ONCE, ALLOW_ALWAYS, DENY }

/** 引擎事件流（UI 层据此驱动状态） */
/** choice/ask 决策问题（P2-7：header 分组、多选、推荐前置；单题兼容 question+options） */
data class ChoiceQuestion(
    val header: String = "",
    val question: String,
    val options: List<String>,
    val multiSelect: Boolean = false,
    val recommendedFirst: Boolean = false,
)

sealed interface AgentEvent {
    // 二十二批（审计）：回合级事件补 sessionId —— 多窗口下 A 会话的回合结束/错误/流式事件
    // 不得污染 B 窗口（此前 TurnAborted 会把 B 的会话整体替换成 A 的）；VM 统一按此过滤
    data class AssistantDelta(val text: String, val sessionId: String = "") : AgentEvent
    data class ReasoningDelta(val text: String, val sessionId: String = "") : AgentEvent
    data class ToolStatusChanged(
        val callId: String,
        val name: String,
        val status: ToolStatus,
        val text: String,
        val argsJson: String = "",  // 工具参数（UI 按命令去重/合并重试）
        /** 批次意图描述（对齐 Claude Code"正在做什么"；空=UI 回退工具名映射） */
        val intent: String = "",
        /** 会话归属（审查修复：多窗口隔离 —— VM 按此过滤，防跨会话事件泄漏） */
        val sessionId: String = "",
    ) : AgentEvent
    /** 工具实时输出行（任务页/执行状态面板明细数据源；sessionId 供分屏多窗口过滤） */
    data class ToolOutput(val callId: String, val line: String, val sessionId: String = "") : AgentEvent
    /** 子代理实时增量（单事件双字段：思考链/正文各自可空；sessionId 供分屏多窗口过滤） */
    data class SubAgentStream(
        val callId: String,
        val content: String? = null,
        val reasoning: String? = null,
        val sessionId: String = "",
    ) : AgentEvent
    data class ApprovalRequired(
        val callId: String,
        val name: String,
        val argsJson: String,
        val path: String?,
        val sessionId: String = "",
    ) : AgentEvent
    /** choice 工具：需要用户从选项中决策（对应 PC choice 门控） */
    data class UserChoiceRequired(
        val callId: String,
        val questions: List<ChoiceQuestion>,
        val sessionId: String = "",
    ) : AgentEvent
    data class CostUpdated(
        val hitTokens: Long,
        val missTokens: Long,
        val completionTokens: Long,
        val costUsd: Double,
        val hitRate: Double,
    ) : AgentEvent
    data class CacheMissWarning(val hitRate: Double) : AgentEvent
    data class ModelUpgraded(val modelId: String) : AgentEvent
    data class ContextUsage(val ratio: Double) : AgentEvent
    /** 工具调用修复诊断：截断/搜刮修复或丢弃时提示（不再静默） */
    data class ToolRepair(val message: String) : AgentEvent
    /** 会话任务清单变更（todo_add/todo_complete 成功后发射；UI 据此响应式刷新） */
    data class TodoUpdated(val sessionId: String) : AgentEvent
    data class Error(val message: String, val sessionId: String = "") : AgentEvent
    data class TurnFinished(val sessionId: String = "") : AgentEvent
    /** 二十批：携带最终内存会话 —— UI 免二次读盘即复位（落盘随后台完成，teardownGate 保证后续回合读到的盘上状态完整） */
    data class TurnAborted(val session: Session) : AgentEvent
    /** 用户点名要求而技能清单中不存在的技能（架构级 12：明确告知并询问，不静默回退） */
    data class SkillMissing(val name: String) : AgentEvent
    /** 计划模式方案已提交（架构级 13：UI 弹审批层；respondPlanReview 后继续） */
    data class PlanReady(val planText: String, val sessionId: String = "") : AgentEvent
    /** 预算 80% 预警告（审查修复：接近阈值先提示，避免突然被预算停掉） */
    data class BudgetWarning(val totalUsd: Double, val budgetUsd: Double) : AgentEvent
}

/**
 * Agent 引擎（对应 PC 版会话/harness 循环）：
 * 缓存优先三区上下文 → 流式请求 → 修复管线 → 审批门控 → 工具执行 → 循环至完成。
 * 全程只通过 [events] 与外界通信；审批经 [respondApproval] 注入。
 */
class AgentEngine(
    private val client: DeepSeekClient,
    private val registry: ToolRegistry,
    private val contextManager: ContextManager,
    private val sessionStore: SessionStore,
    private val costAccount: CostAccount,
    private val policy: PolicyEngine,
    private val config: EngineConfig,
    private val saf: SafRepo,
    private val checkpointStore: CheckpointStore,
    private val factMemory: FactMemory,
    private val skillStore: SkillStore,
    private val skillEngine: com.mlx.app.core.skills.SkillEngine,
    private val mcpRegistry: McpRegistry,
    private val workspaceRepo: com.mlx.app.data.store.WorkspaceRepo,
    private val processRegistry: com.mlx.app.core.tools.ProcessRegistry,
) {

    private val eventFlow = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<AgentEvent> = eventFlow

    /** 执行中回合快照（六批：后台服务保活/通知实时意图的数据源；单写者 runTurn 协程） */
    private val _activeTurn = MutableStateFlow(ActiveTurnStatus())
    val activeTurn: StateFlow<ActiveTurnStatus> = _activeTurn

    private val approvals = mutableMapOf<String, CompletableDeferred<ApprovalDecision>>()
    // 二次审查 P2-7：choice 升级 ask —— 每题返回选中索引集合（空集合 = 该题未选；整体空列表 = 取消）
    private val choices = mutableMapOf<String, CompletableDeferred<List<List<Int>>>>()
    /**
     * 计划模式状态机（架构级 13：规划 → 审批 → 执行）。
     * **Per-session 隔离（审查修复）**：按会话实例化 —— 会话 A 的计划审批不会被会话 B 回合破坏
     * （原单例 PlanGate 跨会话污染：B 的 startPlanning/endTurn/submitPlan 覆盖 A 的状态）。
     */
    private val planGates = java.util.concurrent.ConcurrentHashMap<String, PlanGate>()
    fun planGateFor(sessionId: String): PlanGate = planGates.getOrPut(sessionId) { PlanGate(sessionId) }
    /** 二十二批（审计）：会话删除时回收 PlanGate（此前 per-session 表永不清理） */
    fun removePlanGate(sessionId: String) {
        planGates.remove(sessionId)
    }
    private var seq = 0
    /**
     * 二十批：中止回合收尾落盘完成门闩。
     * 中止路径先发 TurnAborted（UI 即复位）再落盘 —— 若用户立刻发新消息，runTurn 读盘
     * 可能抢在落盘完成前读到旧快照，随后覆盖保存导致被中止回合数据丢失。
     * 仅 Main 线程访问（引擎协程都在 viewModelScope(Main) 上运行；runTurn 开头 await、catch 内 set/complete），无需加锁。
     */
    private var teardownGate: CompletableDeferred<Unit>? = null

    fun respondApproval(callId: String, decision: ApprovalDecision) {
        approvals.remove(callId)?.complete(decision)
    }

    /**
     * 用户对 choice 工具做出选择（P2-7：每题一个选中索引集合；空列表 = 取消）。
     * 单选题集合含 1 个索引；多选题含多个。
     */
    fun respondChoice(callId: String, selections: List<List<Int>>) {
        choices.remove(callId)?.complete(selections)
    }

    /**
     * 中止当前回合的即时动作：立即杀掉全部挂起进程。
     * 协程取消打断不了阻塞 I/O（readLine/阻塞 socket），必须主动杀进程 ——
     * 进程死亡 → 管道 EOF → 取消传播 → 引擎 CancellationException handler 收尾（destroyAll 兜底幂等）。
     * 由 ChatViewModel.abortAll 调用（先 cancel 后 destroy，防工具"正常完成"走 TurnFinished）。
     */
    fun abortCurrentTurn() {
        processRegistry.destroyAll()
    }

    private fun genId(): String {
        seq++
        return "c${System.nanoTime()}_$seq"
    }

    /**
     * @param userMessageId 乐观更新消息 id（ChatViewModel 已把用户消息上屏并持久化；
     *       非空时引擎用同一 id 落库，防止磁盘重载后消息重复）
     */
    suspend fun runTurn(sessionId: String, userText: String, userMessageId: String? = null) {
        // 二十批：等上一回合中止收尾落盘完成再读盘（防读到旧快照 + 覆盖保存丢数据；正常完成路径无门闩）
        teardownGate?.await()
        val session = sessionStore.load(sessionId) ?: run {
            eventFlow.emit(AgentEvent.Error("会话不存在", sessionId))
            return
        }
        // 优化 4a：技能选择器点名 —— 解析 @skill:name（UI 长按 + 选中回填的标记），
        // 剥离标记（落盘消息不含标记，剧本注入走下方统一技能注入通道）；技能不存在时发 SkillMissing
        val skillRef = SKILL_REF_REGEX.find(userText)?.groupValues?.get(1)
        val namedSkill = skillRef?.let { skillEngine.findBy(it) }
        val userTextForStorage = if (skillRef != null && namedSkill != null) {
            userText.replace(SKILL_REF_REGEX, "").trim()
        } else userText
        // Bug 2：@文件引用展开 —— 读取 @路径 文件内容内联进 user 消息（AI 必定拿到内容，无需全量扫描）。
        // 阻塞读走 IO 线程；原始 userText 仍用于标题/回合卡（不膨胀）；失败路径保留 @token 不阻塞发送。
        // 审查修正：纯 @skill:xxx 输入（剥离后为空）→ 不落空用户消息、不写空标题（技能注入照常走下方通道）
        if (userTextForStorage.isNotBlank()) {
            val storedUserText = withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.mlx.app.core.tools.FileAttachments.expand(userTextForStorage, currentBackend())
            }
            session.messages += MessageRecord(userMessageId ?: genId(), "user", storedUserText)
            // 标题用剥离 @skill 标记后的文本（防空输入框只选技能时标题变成 "@skill:review"）
            if (session.title == "新会话") session.title = userTextForStorage.take(20)
        } else if (userMessageId != null) {
            // 空内容时仍移除乐观上屏的占位消息（防止 UI 显示空白气泡）
            session.messages.removeAll { it.id == userMessageId }
        }

        // 回合号 = 非目标/非记忆回顾/非技能注入/非计划反馈的用户消息序号（检查点维度）
        // 二十二批（审计）：计划审批反馈消息（[计划已批准]/[计划被驳回]/[计划被拒绝]）此前
        // 污染回合计数 —— 首回合批准后第二回合 turnNumber 虚增，检查点序号与真实回合错位
        fun isInjected(m: MessageRecord) =
            m.content.startsWith(GOAL_PREFIX) || m.content.startsWith("[记忆回顾]") ||
                m.content.startsWith(SKILL_INJECT_PREFIX) ||
                PLAN_FEEDBACK_PREFIXES.any { m.content.startsWith(it) }
        val turnNumber = session.messages.count { it.role == "user" && !isInjected(it) }

        val modifiedPaths = mutableListOf<String>()
        val deletedPaths = mutableListOf<String>()
        var editFailures = 0
        var upgraded = false
        var steps = 0
        // 十二批修正：错误 break 路径（步数/预算/Key 缺失）标记 FAILED —— 原实现恒 SUCCESS 模糊复盘统计
        var exitStatus = com.mlx.app.data.store.TurnStatus.SUCCESS
        // 熔断状态：相同命令连续失败计数（防"失败刷屏"）
        var failStreak = 0
        var lastFailKey: String? = null
        var circuitOpen = false
        // 预算 80% 预警告已提示标记（每回合一次）
        var budgetWarned = false
        // 执行轨迹步骤树（架构级 11）：回合开始即入树，随回合中 save 落盘
        val turnTracker = TurnTracker()
        val turnStartCosts = session.costs.size
        // 计划模式状态机（架构级 13，per-session gate；try 外声明 —— 回合结束 endTurn 复用同一实例）
        val planGate = planGateFor(session.id)
        // 二十二批（审计 M5）：回合级 try 上移 —— 保存/技能提示/MCP 刷新等悬挂点此前在
        // try 之外：此间取消直接穿透（无 TurnAborted/无 gate/无清理），用户消息已落盘但回合
        // 永不执行，UI 只能等 15s 兜底。声明全部提前（主 catch 需要），try 覆盖至回合收尾。
        try {
            // 立即落盘：abort/进程被杀后 reloadSession 不丢乐观消息
            // （IO 线程：同步文件写不得阻塞 Main —— 会话 JSON 大时是 ANR 静默杀进程的根因）
            withContext(kotlinx.coroutines.Dispatchers.IO) { sessionStore.save(session) }

        // 背景事实记忆：BM25 召回（top-4/2400 字符，低权威注入）+ "请记住"指令
        // 二十二批（审计）：文件读写统一 IO 线程（此前同步阻塞 Main，对齐 session 落盘纪律）
        val recalled = withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (userText.contains("请记住")) {
                val content = userText.substringAfter("请记住", "").trim().removePrefix(":").trim()
                if (content.isNotBlank()) factMemory.add("user", content)
            }
            factMemory.recall(userText, topK = 4, maxChars = 2400)
        }
        if (recalled.isNotEmpty()) {
            // 二十二批（审计）：先清后注入 —— 每回合只保留最新召回，防跨回合无限累积
            //（此前随会话落盘且永不回收，多回合后上下文被重复旧记忆持续膨胀；对齐技能注入先例）
            session.messages.removeAll { it.role == "user" && it.content.startsWith("[记忆回顾]") }
            session.messages += MessageRecord(
                genId(), "user",
                // 二次审查修复：记忆陈旧性声明（对齐 PC memory.Compose —— 防旧事实误导模型）
                "[记忆回顾] 以下信息来自历史会话，可能已过时，仅作背景参考——使用时请现实验证：\n" +
                    recalled.joinToString("\n") { "- ${it.content}" },
            )
        }

        // 技能强制注入（统一通道）：
        // ① 优化 4a 用户点名技能（@skill:name，UI 技能选择器选中）—— 确定性保证，不依赖模型自觉 run_skill
        // ② 十七批 require 级命中 —— autoUse=require 且触发词命中 → 引擎直接带剧本
        // 先清后注入（对齐 GOAL_PREFIX 十二批修复先例）：注入消息随会话落盘，不清会跨回合累积重复。
        // 截断防上下文爆炸：单技能 ≤4KB、合计 ≤8KB（码点安全截断，对齐 skillIndexBlock 做法）。
        session.messages.removeAll { it.role == "user" && it.content.startsWith(SKILL_INJECT_PREFIX) }
        val requireSkills = skillEngine.requireMatches(userText)
        val injectSkills = (listOfNotNull(namedSkill) + requireSkills).distinctBy { it.name }
        if (injectSkills.isNotEmpty()) {
            // 十八批修复（审计）：计划模式下注入附加只读提示 —— 剧本可能含写入步骤，直接"按其执行"
            // 会与 planGate 写拦截冲突（模型反复尝试被拒）。注入在前置说明中澄清当前模式。
            val modeNote = if (config.planMode()) "注意：当前处于计划模式（只读），先按剧本输出方案，勿执行写入操作。" else ""
            val reason = when {
                namedSkill != null && requireSkills.isNotEmpty() -> "用户点名指定技能，且以下技能命中当前任务"
                namedSkill != null -> "用户点名指定技能"
                else -> "以下技能命中当前任务"
            }
            val payload = injectSkills.joinToString("\n\n") { s ->
                // 二十二批（审计）：description 也截断（此前无上限拼接），并落实合计 8KB 上限
                val cappedDesc = s.description.let { it.substring(0, it.offsetByCodePoints(0, 500).coerceAtMost(it.length)) }
                val capped = s.content.let { it.substring(0, it.offsetByCodePoints(0, 4000).coerceAtMost(it.length)) }
                "【技能 ${s.name}】$cappedDesc\n$capped"
            }.let { full ->
                // 合计 ≤8KB（码点安全）：超限按完整技能块边界截断（块之间用 \n\n 分隔）
                val budget = 8192
                if (full.offsetByCodePoints(0, budget).coerceAtMost(full.length) >= full.length) full
                else full.split("\n\n").fold(mutableListOf<String>()) { acc, block ->
                    val nextLen = acc.sumOf { it.length } + acc.size * 2 + block.length
                    if (nextLen <= budget) acc.add(block)
                    acc
                }.joinToString("\n\n") + "\n…（技能注入超 8KB，已截断）"
            }
            session.messages += MessageRecord(
                genId(), "user",
                SKILL_INJECT_PREFIX + "$reason，按其剧本执行。$modeNote\n" + payload,
            )
        }

        // 目标模式：跨回合注入长期目标（对应 /goal）
        // 十二批修正（审计高危）：原实现只检查"是否存在"——中途 update_goal 变更目标后
        // 旧目标消息残留，模型继续遵循旧目标；且清除目标后旧消息也不移除。改为先清后注入。
        val goal = config.goal()
        session.messages.removeAll { it.role == "user" && it.content.startsWith(GOAL_PREFIX) }
        if (!goal.isNullOrBlank()) {
            session.messages.add(0, MessageRecord(genId(), "user", "$GOAL_PREFIX$goal"))
        }

        // 指令文件（AGENTS.md/REASONIX.md/CLAUDE.md + @import）+ 技能索引 → 静态进 prefix
        val instructionText = listOfNotNull(
            InstructionFiles.load(saf),
            skillEngine.skillIndexBlock(),
        ).joinToString("\n").ifBlank { null }

        // 技能工具注册（幂等；与 MCP 同步刷新）
        skillEngine.refreshActiveTools()

        // 技能匹配环节（架构级 12）：用户点名要求而清单中没有 → 明确告知并询问，不静默回退
        if (userText.trim().startsWith("/")) {
            // 斜杠命令交给命令层，不误报技能缺失
        } else {
            skillEngine.missingSkillHint(userText)?.let { missing ->
                eventFlow.emit(AgentEvent.SkillMissing(missing))
            }
        }
        // 优化 4a：@skill: 点名但技能不存在 → 复用 SkillMissing 通道提示（UI snackbar）
        if (skillRef != null && namedSkill == null) {
            eventFlow.emit(AgentEvent.SkillMissing(skillRef))
        }

        // MCP 桥接：刷新启用服务器的远程工具（工具集变化 → prefix 自动重建）
        refreshMcpTools()

        session.turns += turnTracker.startTurn(turnNumber, userTextForStorage, System.currentTimeMillis())
        // 执行中快照（六批）：回合开始即置 THINKING（服务据此拉活/更新通知）
        _activeTurn.value = ActiveTurnStatus(
            sessionId = session.id, userText = userTextForStorage,
            phase = ActivePhase.THINKING, startedAt = System.currentTimeMillis(),
        )
            while (true) {
                if (!coroutineContext.isActive) break
                if (++steps > MAX_STEPS) {
                    eventFlow.emit(AgentEvent.Error("达到最大执行步数（$MAX_STEPS），请拆分子任务", session.id))
                    exitStatus = com.mlx.app.data.store.TurnStatus.FAILED
                    break
                }

                // 预算检查（对应 --budget）+ 80% 预警告（审查修复：成本控制关键，接近阈值先提示）
                val budget = config.budgetUsd()
                if (budget > 0) {
                    val total = session.totalCostUsd()
                    if (total >= budget) {
                        eventFlow.emit(AgentEvent.Error("预算已超限（${"%.4f".format(total)} USD ≥ 上限 $budget），回合已停止", session.id))
                        exitStatus = com.mlx.app.data.store.TurnStatus.FAILED
                        break
                    }
                    if (total >= budget * 0.8 && !budgetWarned) {
                        budgetWarned = true // 每回合只提示一次
                        eventFlow.emit(AgentEvent.BudgetWarning(total, budget))
                    }
                }

                // 压缩检查：soft 阈值提示（默认 50%，对应 PC soft_compact_ratio）/ compact 阈值强制（默认 80%，可配置）
                // 十二批修正：ratio 估算必须与 buildMessages 用同一完整参数（避免首轮"短 prefix"低估）
                val compactRatio = config.compactRatio()
                val softRatio = (compactRatio - 0.3).coerceAtLeast(0.2)
                val planModeSuffix = if (config.planMode()) SystemPrompts.PLAN_MODE_SUFFIX else null
                val outputStyleSuffix = SystemPrompts.OUTPUT_STYLE_SUFFIX[config.outputStyle()]
                // 审查修正：@文件引用段独立 suffix（不并入 BASE，规则可独立演进不触发全局缓存失效）
                val attachmentSuffix = SystemPrompts.FILE_ATTACHMENT_SUFFIX
                // 二十一批：委派提示词标准与子代理契约合并传参（合并后仍是静态字节，改动只影响 suffix 自身段，
                // 不触及 BASE+tools+指令文件的前导命中；保持 ratio 估算与 buildMessages 同一完整参数）
                val subagentSuffix = SystemPrompts.SUBAGENT_CONTRACT_SUFFIX + "\n\n" + SystemPrompts.DELEGATION_PROMPT_SUFFIX
                val ratio = contextManager.ratioUsed(session, registry.all(), instructionText, planModeSuffix, outputStyleSuffix, subagentSuffix, attachmentSuffix)
                when {
                    ratio >= compactRatio -> {
                        contextManager.compact(session, registry.all(), compactRatio)
                        eventFlow.emit(AgentEvent.ContextUsage(contextManager.ratioUsed(session, registry.all(), instructionText, planModeSuffix, outputStyleSuffix, subagentSuffix, attachmentSuffix)))
                    }
                    ratio >= softRatio -> eventFlow.emit(AgentEvent.ContextUsage(ratio))
                }

                val apiKey = config.apiKey()
                if (apiKey.isNullOrBlank()) {
                    eventFlow.emit(AgentEvent.Error("未配置 API Key，请在设置中填写", session.id))
                    exitStatus = com.mlx.app.data.store.TurnStatus.FAILED
                    break
                }
                val modelId = config.activeModelId()
                // 计划模式状态机（架构级 13）：回合开始进入规划阶段（幂等；per-session gate）
                if (config.planMode()) planGate.startPlanning()
                val messages = contextManager.buildMessages(
                    session,
                    registry.all(),
                    instructionFileText = instructionText,
                    planModeSuffix = planModeSuffix,
                    outputStyleSuffix = outputStyleSuffix,
                    subagentSuffix = subagentSuffix,
                    fileAttachmentSuffix = attachmentSuffix,
                )
                session.model = modelId

                val acc = StreamAccumulator()
                var streamError: String? = null
                // A1：结构化 tools 参数传给模型（对齐 PC；含 MCP 桥接工具）
                client.streamChat(
                    apiKey, config.baseUrl(), modelId, messages,
                    reasoningMode = config.reasoningMode(),
                    tools = registry.all(),
                ).collect { ev ->
                    when (ev) {
                        is StreamEvent.Delta -> {
                            acc.apply(ev.delta)
                            ev.delta.reasoning?.let { eventFlow.emit(AgentEvent.ReasoningDelta(it, session.id)) }
                            ev.delta.content?.let { content ->
                                eventFlow.emit(AgentEvent.AssistantDelta(content, session.id))
                                // 快照实时更新：流式阶段标记（通知"正在生成…"）
                                _activeTurn.value = _activeTurn.value.copy(phase = ActivePhase.STREAMING)
                            }
                        }
                        is StreamEvent.UsageEvent -> acc.usage = ev.usage
                        is StreamEvent.Error -> streamError = ev.message
                        StreamEvent.Done -> Unit
                    }
                }
                if (streamError != null) {
                    eventFlow.emit(AgentEvent.Error(streamError!!, session.id))
                    break
                }

                acc.usage?.let { usage ->
                    val cr = costAccount.record(session.id, steps, modelId, usage, System.currentTimeMillis())
                    session.costs += cr
                    val hitRate = usage.cacheHitRate()
                    eventFlow.emit(
                        AgentEvent.CostUpdated(
                            usage.cacheHitTokens, usage.cacheMissTokens, usage.completionTokens,
                            cr.costUsd, hitRate,
                        )
                    )
                    if (usage.cacheMissTokens > 0 && hitRate < 0.3) {
                        eventFlow.emit(AgentEvent.CacheMissWarning(hitRate))
                    }
                }

                // 修复管线：组装 + Scavenge + Truncation + Storm（带诊断统计）
                val repaired = RepairPipeline.recoverDetailed(acc.assembledCalls(), acc.reasoningText())
                val calls = repaired.calls
                if (repaired.dropped > 0) {
                    eventFlow.emit(
                        AgentEvent.ToolRepair(
                            "⚠ 检测到 ${repaired.dropped} 个不完整工具调用（无法修复，已丢弃）—— 模型输出可能被截断"
                        )
                    )
                } else if (repaired.repaired > 0 || repaired.scavenged > 0) {
                    eventFlow.emit(
                        AgentEvent.ToolRepair(
                            "✓ 已修复工具调用：截断闭合 ${repaired.repaired} 个，从思考文本找回 ${repaired.scavenged} 个"
                        )
                    )
                }

                if (calls.isNotEmpty()) {
                    // 批次意图（六批，对齐 Claude Code）：模型在调用前已输出的正文 → 首个工具携带，其余空串（UI 回退工具名映射）
                    val batchIntent = intentText(acc.contentText())
                    session.messages += MessageRecord(
                        id = genId(),
                        role = "assistant",
                        content = acc.contentText(),
                        reasoning = acc.reasoningText(),
                        toolCalls = calls.mapIndexed { idx, c ->
                            ToolCallRecord(c.id, c.name, c.argsJson, ToolStatus.RUNNING, intent = if (idx == 0) batchIntent else "")
                        },
                    ).let { m ->
                        // 防御：reasoning 存储截断（PC defaultReasoningByteLimit=128KB；本地存 32KB 防会话文件膨胀）
                        if (m.reasoning.length > MAX_REASONING_STORED) m.copy(reasoning = m.reasoning.take(MAX_REASONING_STORED) + "\n…[思考已截断]…") else m
                    }

                    // ---- 六批：局部辅助（串行/并行路径共用，闭包捕获回合状态；声明须在使用之前）----
                    suspend fun beginToolUI(call: RepairPipeline.AssembledCall, callIntent: String) {
                        markTool(session, call.id, ToolStatus.RUNNING, "")
                        eventFlow.emit(AgentEvent.ToolStatusChanged(call.id, call.name, ToolStatus.RUNNING, "", call.argsJson, intent = callIntent, sessionId = session.id))
                        _activeTurn.value = _activeTurn.value.copy(phase = ActivePhase.TOOL_RUNNING, intent = callIntent, toolName = call.name)
                        turnTracker.beginTool(call.id, call.name, call.argsJson, System.currentTimeMillis(), intent = callIntent)
                    }

                    suspend fun buildContext(callId: String): ToolContext = ToolContext(
                        sessionId = session.id,
                        workspaceRoot = config.workspaceRoot(),
                        projectId = session.projectId,
                        // 工程名快照（任务登记用；注册表缺失时任务页仍能显示名字）
                        workspaceName = runCatching { workspaceRepo.find(session.projectId)?.name ?: "" }.getOrDefault(""),
                        callId = callId,
                        onOutput = { line -> eventFlow.tryEmit(AgentEvent.ToolOutput(callId, line, session.id)) },
                        // 子代理流式增量 → UI（tryEmit + 256 缓冲：高频丢中间帧可接受，UI 每 100ms 合并）
                        onSubAgentDelta = { c, r -> eventFlow.tryEmit(AgentEvent.SubAgentStream(callId, c, r, session.id)) },
                    )

                    suspend fun doExecute(call: RepairPipeline.AssembledCall, argsMap: Map<String, Any?>, ctx: ToolContext): ToolResult = try {
                        // B2：工具执行超时护栏（PC shell 默认 120s；九批：subagent/planner 豁免 300s —— 子代理单次 API 60-300s）
                        withTimeout(timeoutFor(call.name)) {
                            withContext(Dispatchers.IO) {
                                registry.get(call.name)?.execute(argsMap, saf, ctx) ?: ToolResult(false, "未知工具: ${call.name}")
                            }
                        }
                    } catch (e: TimeoutCancellationException) {
                        // 超时：协程取消后进程仍在跑 → 杀进程树，防僵尸
                        // 十二批修正：只杀本工具进程（callId 注册 key）—— destroyAll 会误杀并行组
                        // 中其他工具（shell/python/bash）的进程，导致模型收到不完整结果
                        processRegistry.destroy(call.id)
                        ToolResult(false, "工具执行超时（>${timeoutFor(call.name) / 1000}s），已中止")
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // 用户停止：取消信号必须上抛（此前被 catch(Exception) 吞掉 →
                        // 工具"正常完成"走 TurnFinished 而非 TurnAborted，snackbar 错报"回合完成"）
                        // 精确杀本工具进程（key=callId，不误伤并行组；外圈 catch 的 destroyAll 兜底其余）
                        processRegistry.destroy(call.id)
                        throw e
                    } catch (e: Exception) {
                        ToolResult(false, "工具异常: ${e.message}")
                    }

                    /**
                     * 结果回填（B1 收缩/步骤树/产物/熔断）；返回 true = 熔断开路（调用方补 DENIED + 中止）。
                     * @param preResult 并行路径传入 batchRunner 已执行的结果（不传则本函数自行执行 —— 防并行组重复执行）
                     */
                    suspend fun applyResult(call: RepairPipeline.AssembledCall, argsMap: Map<String, Any?>, ctx: ToolContext, callIntent: String, preResult: ToolResult? = null): Boolean {
                        val result = preResult ?: doExecute(call, argsMap, ctx)
                        // B1：工具结果输出收缩（对齐 PC maxToolOutputBytes=32KB，head+tail 截断）
                        val shrunk = truncateToolOutput(result.text)

                        if (result.ok && result.fileChanged) {
                            modifiedPaths += extractPaths(call.name, argsMap) // 十二批：multi_edit 全路径
                            extractDeletedPath(call.name, argsMap)?.let { deletedPaths += it }
                        }
                        val status = if (result.ok) ToolStatus.SUCCESS else ToolStatus.FAILED
                        if (!result.ok && ToolRegistry.isWriteTool(call.name)) {
                            editFailures++
                            if (editFailures >= 3 && !upgraded) {
                                val proId = config.proModelId()
                                if (proId != modelId) {
                                    config.upgradeToPro()
                                    upgraded = true
                                    eventFlow.emit(AgentEvent.ModelUpgraded(proId))
                                }
                            }
                        }
                        markTool(session, call.id, status, shrunk)
                        recordDiff(session, call.id, result.diffText)
                        // 步骤树收尾：状态/耗时/结果/产物引用（fileChanged 路径供复盘定位）
                        turnTracker.finishTool(
                            call.id, status, shrunk, System.currentTimeMillis(),
                            outputRefs = if (result.ok && result.fileChanged) {
                                extractPath(call.name, argsMap)?.let { listOf(it) } ?: emptyList()
                            } else emptyList(),
                            diffText = result.diffText,
                        )
                        eventFlow.emit(AgentEvent.ToolStatusChanged(call.id, call.name, status, shrunk, call.argsJson, intent = callIntent, sessionId = session.id))
                        // 任务清单变更 → 通知 UI 立即刷新（todo_* 写文件不发事件曾导致"0 待办迟迟不更新"）
                        if (result.ok && (call.name == "todo_add" || call.name == "todo_complete")) {
                            eventFlow.tryEmit(AgentEvent.TodoUpdated(session.id))
                        }
                        session.messages += MessageRecord(genId(), "tool", shrunk, toolCallId = call.id)
                        // 熔断：相同命令连续失败 ≥3 次 → 停止重试（源头止血，防"失败无限刷屏"）
                        if (!result.ok) {
                            val failKey = call.name + call.argsJson
                            failStreak = if (lastFailKey == failKey) failStreak + 1 else 1
                            lastFailKey = failKey
                            if (failStreak >= 3) {
                                eventFlow.emit(
                                    AgentEvent.Error(
                                        "工具「${call.name}」相同参数连续失败 3 次，已停止重试（防重复执行）。" +
                                            "请检查环境/网络后重新尝试，或换一种方式完成该操作。"
                                    )
                                )
                                return true
                            }
                        } else {
                            failStreak = 0
                            lastFailKey = null
                        }
                        return false
                    }

                    /** 熔断补 DENIED：fromIdx 起未处理工具（含已 begin 未回填的 —— beginTool 防重自动跳过） */
                    fun denyRest(fromIdx: Int) {
                        calls.drop(fromIdx).forEach { rest ->
                            markTool(session, rest.id, ToolStatus.DENIED, "熔断中止：未执行")
                            // 二十二批（审计）：补发状态事件 —— 此前只改会话内存不发事件，
                            // 已 beginToolUI 的并行组工具在 UI 上保持 RUNNING 转圈直到回合结束才纠正
                            eventFlow.tryEmit(AgentEvent.ToolStatusChanged(rest.id, rest.name, ToolStatus.DENIED, "熔断中止：未执行", rest.argsJson, intent = "", sessionId = session.id))
                            turnTracker.beginTool(rest.id, rest.name, rest.argsJson, System.currentTimeMillis())
                            turnTracker.finishTool(rest.id, ToolStatus.DENIED, "熔断中止：未执行", System.currentTimeMillis())
                        }
                    }

                    // 并发执行器（六批提速：只读工具并行；上限 MAX_PARALLEL）
                    val batchRunner = ToolBatchRunner(maxParallel = MAX_PARALLEL) { call, ctx ->
                        doExecute(call, MiniJson.toMap(MiniJson.parse(call.argsJson)), ctx)
                    }

                    var i = 0
                    while (i < calls.size) {
                        val call = calls[i]
                        val callIdx = i
                        val argsMap = MiniJson.toMap(MiniJson.parse(call.argsJson))
                        val path = extractPath(call.name, argsMap)
                        // 批次意图仅首个工具携带（多工具共用一句；其余 UI 回退工具名映射）
                        val callIntent = if (callIdx == 0) batchIntent else ""

                        // choice 工具（二次审查 P2-7：升级 PC ask 能力）：不走 allow/deny，直接弹用户决策
                        if (call.name == "choice") {
                            // 多题模式（questions）或单题兼容（question+options）
                            val questions = (argsMap["questions"] as? List<*>)
                                ?.mapNotNull { it as? Map<String, Any?> }
                                ?.map { q ->
                                    ChoiceQuestion(
                                        header = (q["header"] as? String) ?: "",
                                        question = (q["question"] as? String)?.ifBlank { "请选择" } ?: "请选择",
                                        options = (q["options"] as? List<*>)
                                            ?.mapNotNull { it as? String }?.filter { it.isNotBlank() } ?: emptyList(),
                                        multiSelect = (q["multiSelect"] as? Boolean) ?: false,
                                        recommendedFirst = (q["recommendedFirst"] as? Boolean) ?: false,
                                    )
                                }
                                ?.filter { it.options.isNotEmpty() }
                                ?.take(4)
                            val effective = if (questions.isNullOrEmpty()) {
                                val q = (argsMap["question"] as? String)?.ifBlank { "请选择" } ?: "请选择"
                                val opts = (argsMap["options"] as? List<*>)
                                    ?.mapNotNull { it as? String }?.filter { it.isNotBlank() } ?: emptyList()
                                if (opts.isEmpty()) emptyList() else listOf(ChoiceQuestion("", q, opts))
                            } else questions
                            // 二十二批（审计）：选项 2-4 个校验（schema 描述声明 2-4，此前引擎不校验）
                            val badCount = effective.firstOrNull { it.options.size !in 2..4 }
                            if (effective.isEmpty() || badCount != null) {
                                val reason = if (badCount != null) "choice 选项需 2-4 个（当前 ${badCount.options.size} 个）" else "choice 选项为空"
                                markTool(session, call.id, ToolStatus.FAILED, reason)
                                eventFlow.emit(AgentEvent.ToolStatusChanged(call.id, call.name, ToolStatus.FAILED, reason, call.argsJson, intent = callIntent, sessionId = session.id))
                                session.messages += MessageRecord(genId(), "tool", reason, toolCallId = call.id)
                                i++
                                continue
                            }
                            markTool(session, call.id, ToolStatus.APPROVAL_REQUIRED, "")
                            eventFlow.emit(AgentEvent.UserChoiceRequired(call.id, effective, session.id))
                            // 快照：等待用户选择
                            _activeTurn.value = _activeTurn.value.copy(phase = ActivePhase.WAITING_USER, intent = callIntent)
                            val selections = awaitChoice(call.id)
                            if (selections.isEmpty() || selections.any { it.isEmpty() }) {
                                markTool(session, call.id, ToolStatus.DENIED, "用户取消")
                                eventFlow.emit(AgentEvent.ToolStatusChanged(call.id, call.name, ToolStatus.DENIED, "用户取消", call.argsJson, intent = callIntent, sessionId = session.id))
                                session.messages += MessageRecord(genId(), "tool", "用户取消了选择", toolCallId = call.id)
                            } else {
                                val chosenText = selections.mapIndexed { qi, idxs ->
                                    idxs.joinToString("、") { effective[qi].options[it] }
                                }.joinToString("；")
                                markTool(session, call.id, ToolStatus.SUCCESS, "用户选择: $chosenText")
                                eventFlow.emit(AgentEvent.ToolStatusChanged(call.id, call.name, ToolStatus.SUCCESS, "用户选择: $chosenText", call.argsJson, intent = callIntent, sessionId = session.id))
                                session.messages += MessageRecord(genId(), "tool", "用户选择: $chosenText", toolCallId = call.id)
                            }
                            i++
                            continue
                        }

                        // 计划模式方案提交（submit_plan，架构级 13）：暂停等待用户审批
                        if (call.name == "submit_plan") {
                            val planText = (argsMap["plan"] as? String)?.trim() ?: ""
                            if (planText.isBlank()) {
                                markTool(session, call.id, ToolStatus.FAILED, "方案不能为空")
                                eventFlow.emit(AgentEvent.ToolStatusChanged(call.id, call.name, ToolStatus.FAILED, "方案不能为空", call.argsJson, intent = callIntent, sessionId = session.id))
                                session.messages += MessageRecord(genId(), "tool", "方案不能为空", toolCallId = call.id)
                                i++
                                continue
                            }
                            // phase 前置检查（审查修复）：非 PLANNING 阶段（未开启计划模式/已批准执行中）
                            // → 记录 FAILED 跳过，不挂起 —— 修复"模型误调 submit_plan → 无限等待审批"
                            val deferred = planGate.submitPlan(planText)
                            if (deferred == null) {
                                val reason = "submit_plan 无效：当前不在计划模式规划阶段"
                                markTool(session, call.id, ToolStatus.FAILED, reason)
                                eventFlow.emit(AgentEvent.ToolStatusChanged(call.id, call.name, ToolStatus.FAILED, reason, call.argsJson, intent = callIntent, sessionId = session.id))
                                session.messages += MessageRecord(genId(), "tool", reason, toolCallId = call.id)
                                turnTracker.beginTool(call.id, call.name, call.argsJson, System.currentTimeMillis())
                                turnTracker.finishTool(call.id, ToolStatus.FAILED, reason, System.currentTimeMillis())
                                i++
                                continue
                            }
                            markTool(session, call.id, ToolStatus.SUCCESS, "方案已提交，等待审批")
                            eventFlow.emit(AgentEvent.ToolStatusChanged(call.id, call.name, ToolStatus.SUCCESS, "方案已提交，等待审批", call.argsJson, intent = callIntent, sessionId = session.id))
                            session.messages += MessageRecord(genId(), "tool", "方案已提交，等待用户审批", toolCallId = call.id)
                            turnTracker.beginTool(call.id, call.name, call.argsJson, System.currentTimeMillis())
                            turnTracker.finishTool(call.id, ToolStatus.SUCCESS, "方案已提交，等待审批", System.currentTimeMillis())
                            eventFlow.emit(AgentEvent.PlanReady(planText, session.id))
                            // 快照：等待计划审批
                            _activeTurn.value = _activeTurn.value.copy(phase = ActivePhase.WAITING_USER, intent = callIntent)
                            // 超时兜底（对齐 awaitApproval/awaitChoice）：计划审批也无限挂起 =
                            // 引擎侧唯一无超时挂起点，用户不点按钮回合永不结束
                            val planDecision = try {
                                withTimeout(APPROVAL_TIMEOUT_MS) { deferred.await() }
                            } catch (e: TimeoutCancellationException) {
                                eventFlow.emit(AgentEvent.ToolRepair("⏱ 计划审批等待超时（60s），已按拒绝处理"))
                                PlanReviewDecision.Reject
                            }
                            when (planDecision) {
                                is PlanReviewDecision.Approve -> {
                                    // 审查修复：批准注入 plan 原文（对齐 PC Executor 机制 —— 模型不依赖记忆已提交的方案）
                                    session.messages += MessageRecord(genId(), "user", "[计划已批准] 继续执行以下方案：\n$planText")
                                    eventFlow.emit(AgentEvent.ToolRepair("✅ 计划已批准，继续执行方案"))
                                }
                                is PlanReviewDecision.Revise -> {
                                    val comment = planDecision.comment.ifBlank { "请根据反馈修改方案后重新提交" }
                                    session.messages += MessageRecord(genId(), "user", "[计划被驳回] $comment，请修改后重新提交")
                                    eventFlow.emit(AgentEvent.ToolRepair("↩ 计划被驳回：$comment，请修改后重新提交"))
                                }
                                PlanReviewDecision.Reject -> {
                                    session.messages += MessageRecord(genId(), "user", "[计划被拒绝] 已取消执行")
                                    eventFlow.emit(AgentEvent.ToolRepair("✕ 计划被拒绝，回合已取消"))
                                    break
                                }
                            }
                            i++
                            continue
                        }

                        // 计划模式代码级拦截（P1-10 + 架构级 13）：PLANNING 阶段写工具直接拒绝，
                        // 不依赖模型遵守提示词；批准后（EXECUTING）自动放行；
                        // 拦截早于审批门控 → 计划模式下不会弹写工具审批（避免"计划模式还要逐个批"的矛盾体验）
                        // 二十二批：shell/python_exec/bash_output 也进拦截（只读命令白名单放行）
                        if (planGate.writeBlocked(call.name, argsMap)) {
                            val reason = "计划模式拒绝：只读模式下禁止写操作（请退出计划模式，或提交计划等待批准后执行）"
                            markTool(session, call.id, ToolStatus.DENIED, reason)
                            eventFlow.emit(AgentEvent.ToolStatusChanged(call.id, call.name, ToolStatus.DENIED, reason, call.argsJson, intent = callIntent, sessionId = session.id))
                            session.messages += MessageRecord(genId(), "tool", reason, toolCallId = call.id)
                            turnTracker.beginTool(call.id, call.name, call.argsJson, System.currentTimeMillis())
                            turnTracker.finishTool(call.id, ToolStatus.DENIED, reason, System.currentTimeMillis())
                            i++
                            continue
                        }

                        val decision = effectiveDecision(call.name, path)

                        // Yolo/Auto：最少询问，写操作直接放行（PC 审批模式语义）
                        val autoApprove = config.policyMode() == "auto" || config.policyMode() == "yolo"
                        val allowed = when {
                            // 二十二批（审计 CRITICAL）：deny 规则优先于 auto/yolo —— 用户显式规则最高优先，
                            // 此前 autoApprove 先行短路导致 deny 规则（默认 auto 模式）全部失效
                            decision == Decision.DENY -> false
                            autoApprove -> true
                            decision == Decision.ALLOW -> true
                            else -> {
                                markTool(session, call.id, ToolStatus.APPROVAL_REQUIRED, "")
                                eventFlow.emit(AgentEvent.ApprovalRequired(call.id, call.name, call.argsJson, path, session.id))
                                // 快照：等待审批
                                _activeTurn.value = _activeTurn.value.copy(phase = ActivePhase.WAITING_USER, intent = callIntent)
                                when (awaitApproval(call.id)) {
                                    ApprovalDecision.ALLOW_ALWAYS -> {
                                        // 二十二批（审计）：规则 key 与 decide 的 target 格式统一 ——
                                        // 无 path 工具存 `tool`（decide 查 `tool`），有 path 存 `tool:path`；
                                        // 此前存 `tool:*` 永不命中（Glob `^tool:[^/]*$` 不匹配无冒号 target），
                                        // "总是允许"对 shell/python 等无 path 工具形同虚设
                                        policy.addRule(if (path != null) "${call.name}:$path" else call.name, Decision.ALLOW)
                                        true
                                    }
                                    ApprovalDecision.ALLOW_ONCE -> true
                                    ApprovalDecision.DENY -> false
                                }
                            }
                        }

                        if (!allowed) {
                            val reason = if (decision == Decision.DENY) "策略拒绝（deny 规则命中）" else "用户拒绝"
                            markTool(session, call.id, ToolStatus.DENIED, reason)
                            eventFlow.emit(AgentEvent.ToolStatusChanged(call.id, call.name, ToolStatus.DENIED, reason, call.argsJson, intent = callIntent, sessionId = session.id))
                            session.messages += MessageRecord(genId(), "tool", reason, toolCallId = call.id)
                            turnTracker.beginTool(call.id, call.name, call.argsJson, System.currentTimeMillis())
                            turnTracker.finishTool(call.id, ToolStatus.DENIED, reason, System.currentTimeMillis())
                            i++
                            continue
                        }

                        // 六批：并行段判定（只读自动放行工具连续成组 → 并行；交互/写/拦截/需审批保持串行）
                        // 二十二批：blocked 判定带 args（计划模式 shell 白名单需命令内容，防并行路径绕过）
                        val span = parallelGroupSpan(calls, i) { idx ->
                            val c = calls[idx]
                            val cArgs = MiniJson.toMap(MiniJson.parse(c.argsJson))
                            val p = extractPath(c.name, cArgs)
                            isParallelEligible(autoApprove, effectiveDecision(c.name, p), c.name) { n, a -> planGate.writeBlocked(n, a) }
                        }

                        if (span.size >= 2) {
                            // ① 串行先发 RUNNING（步骤树/消息顺序稳定）
                            val jobs = span.map { idx ->
                                val c = calls[idx]
                                val cIntent = if (idx == 0) batchIntent else ""
                                beginToolUI(c, cIntent)
                                c to buildContext(c.id)
                            }
                            // ② 并发执行（结果按输入序收集；上限 MAX_PARALLEL 分块）
                            val results = batchRunner.run(jobs)
                            // ③ 父协程按原序回填（传 batchRunner 结果，绝不二次执行）
                            var openAt = -1
                            for (k in jobs.indices) {
                                val idx = span[k]
                                val c = calls[idx]
                                val cArgs = MiniJson.toMap(MiniJson.parse(c.argsJson))
                                if (applyResult(c, cArgs, jobs[k].second, if (idx == 0) batchIntent else "", results[k])) { openAt = k; break }
                            }
                            if (openAt >= 0) {
                                denyRest(span[openAt] + 1) // 组内已 begin 未回填的由 beginTool 防重跳过，finish 补 DENIED
                                circuitOpen = true
                                break
                            }
                            i += span.size
                            continue
                        }

                        // 单工具串行路径（交互/写/需审批工具）
                        beginToolUI(call, callIntent)
                        if (applyResult(call, argsMap, buildContext(call.id), callIntent)) {
                            denyRest(i + 1)
                            circuitOpen = true
                            break
                        }
                        i++
                    }
                    if (circuitOpen) {
                        // 二十二批（审计）：熔断中止的回合标 FAILED（此前恒 SUCCESS ——
                        // 复盘树状态与 snackbar"已停止重试"矛盾）
                        exitStatus = com.mlx.app.data.store.TurnStatus.FAILED
                        break
                    }
                    continue // 携带工具结果进入下一轮请求
                }

                if (acc.contentText().isNotBlank()) {
                    session.messages += MessageRecord(
                        id = genId(),
                        role = "assistant",
                        content = acc.contentText(),
                        reasoning = acc.reasoningText().take(MAX_REASONING_STORED),
                    )
                }
                turnTracker.addTextStep(acc.contentText(), System.currentTimeMillis())
                break
            }
            // 回合成功结束：步骤树状态/耗时/成本收尾（随下方 save 落盘）
            val turnCost = session.costs.drop(turnStartCosts).sumOf { it.costUsd }
            turnTracker.endTurn(exitStatus, System.currentTimeMillis(), turnCost)
            planGate.endTurn()
            // 六批：快照复位 IDLE（服务据此宽限后停服 + 完成通知；copy 保留 startedAt 供耗时统计）
            _activeTurn.value = _activeTurn.value.copy(phase = ActivePhase.IDLE, aborted = false, intent = "", toolName = "")
            session.updatedAt = System.currentTimeMillis()
            // 全部落盘统一 IO 线程（save/capture/syncAndBackup 内部均为阻塞磁盘 I/O ——
            // Main 线程同步执行是子代理大输出回合 ANR 静默杀进程的根因）
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                sessionStore.save(session)
                // 检查点：捕获本回合被修改文件的内容快照（Code 范围回退基础；通道与文件工具一致）
                checkpointStore.capture(session.id, turnNumber, modifiedPaths, checkpointBackend())
                // 目录即工作区：改动自动回写磁盘源目录（F2）+ 会话自动备份到工程磁盘目录（恢复机制）
                syncAndBackup(session, modifiedPaths, deletedPaths)
            }
            eventFlow.emit(AgentEvent.TurnFinished(session.id))
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 八批：清理悬挂的审批/选择等待（CompletableDeferred 随协程取消已抛异常，map 条目不再有用）
            approvals.clear()
            choices.clear()
            // 中止收尾：未完成工具标 FAILED（取消传播后不走 finishTool 回填，防历史卡"执行中"）+ 回合标 ABORTED
            turnTracker.abort(System.currentTimeMillis())
            markAbortedTools(session)
            planGate.endTurn()
            // 六批：快照复位 IDLE + 中止标记（服务据此出"已中止"完成通知；copy 保留 startedAt）
            _activeTurn.value = _activeTurn.value.copy(phase = ActivePhase.IDLE, aborted = true, intent = "", toolName = "")
            session.updatedAt = System.currentTimeMillis()
            // 二十批：先发 TurnAborted（UI 立即复位）再落盘，对齐 PC 停止语义 —— UI 不等待磁盘 I/O。
            // 关键：此 catch 在【已被取消的协程】里执行，任何可取消挂起点都会立即抛 CancellationException
            //（此前 withContext(IO)/emit 在此夭折 → TurnAborted 永不发出、会话永不落盘，
            //  UI 只能等 ChatViewModel 15s 兜底强复 —— "停止等十几秒"的真正根因），
            // 故整个收尾（emit + 落盘）必须用 NonCancellable 包裹。
            val gate = CompletableDeferred<Unit>()
            teardownGate = gate
            try {
                withContext(NonCancellable) {
                    eventFlow.emit(AgentEvent.TurnAborted(session))
                    // 用户中止：杀死仍挂着的进程（阻塞 IO 不会因协程取消而中断；killTree 含
                    // /proc 读取 + ProcessBuilder.kill + waitFor 阻塞 → IO 线程执行，防卡 Main）
                    withContext(kotlinx.coroutines.Dispatchers.IO) {
                        processRegistry.destroyAll()
                        sessionStore.save(session)
                        checkpointStore.capture(session.id, turnNumber, modifiedPaths, checkpointBackend())
                        syncAndBackup(session, modifiedPaths, deletedPaths)
                    }
                }
            } finally {
                teardownGate = null
                gate.complete(Unit)
            }
            throw e
        } catch (e: Exception) {
            // 十二批修正（审计 CRITICAL）：非取消异常兜底 —— 此前直接穿透导致
            // turnTracker/planGate/_activeTurn 状态泄露（PlanGate 死锁）、approvals/choices 泄漏、
            // 回合进度不落盘、UI 卡在 running=true。兜底统一收尾：标记 FAILED + 清理 + 落盘 + Error 事件。
            eventFlow.emit(AgentEvent.Error("引擎异常：${e.message ?: e.javaClass.simpleName}", session.id))
            runCatching {
                turnTracker.endTurn(com.mlx.app.data.store.TurnStatus.FAILED, System.currentTimeMillis(), 0.0)
                markAbortedTools(session) // 兜底：残留 RUNNING 工具标失败（防历史卡"执行中"）
                planGate.endTurn()
                _activeTurn.value = _activeTurn.value.copy(phase = ActivePhase.IDLE, aborted = false, intent = "", toolName = "")
                approvals.clear()
                choices.clear()
                session.updatedAt = System.currentTimeMillis()
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    sessionStore.save(session)
                    checkpointStore.capture(session.id, turnNumber, modifiedPaths, checkpointBackend())
                    syncAndBackup(session, modifiedPaths, deletedPaths)
                }
                eventFlow.emit(AgentEvent.TurnFinished(session.id))
            }
            // 不 rethrow：异常已由 Error 事件上屏（运行稳定优先；进程崩溃由 CrashLog 处理更重的场景）
        }
    }

    /**
     * 统一文件后端选择（与文件工具同源）：真实路径工程 → RealBackend（直接读写磁盘真实文件）；
     * legacy/SAF 工程 → SafBackend。@文件引用展开（FileAttachments）与检查点共用。
     */
    private suspend fun currentBackend(): com.mlx.app.core.tools.FileBackend {
        val root = config.workspaceRoot()
        return if (root != null) com.mlx.app.core.tools.RealBackend(root)
        else com.mlx.app.core.tools.SafBackend(saf)
    }

    /**
     * 检查点通道（与文件工具同源）：真实路径工程 → RealBackend（直接读写磁盘真实文件）；
     * legacy/SAF 工程 → SafBackend。修复：原实现固定走 SAF，真实路径工程下快照读空/回退失效。
     */
    private suspend fun checkpointBackend(): com.mlx.app.core.tools.FileBackend = currentBackend()

    /**
     * F2 + 恢复机制（目录即工作区 2.0）：
     * - 真实路径工程（legacy=false）：文件工具已直接操作真实目录，回写是 no-op → 仅备份会话到真实目录
     * - legacy 工程：镜像改动回写磁盘源目录 + 会话备份随回写落盘
     */
    private suspend fun syncAndBackup(session: Session, modifiedPaths: List<String>, deletedPaths: List<String> = emptyList()) {
        val projectId = session.projectId
        if (projectId.isBlank() || projectId == "saf") return
        val project = workspaceRepo.find(projectId) ?: return
        if (project.legacy && (modifiedPaths.isNotEmpty() || deletedPaths.isNotEmpty())) {
            workspaceRepo.syncToSource(project, modifiedPaths, deletedPaths)
        }
        // 会话备份：任何数据丢失场景（应用数据被清）下，磁盘目录保留完整会话
        val backupRel = workspaceRepo.backupSession(project, session.id, com.mlx.app.data.store.SessionStore.toJson(session))
        if (project.legacy) workspaceRepo.syncToSource(project, listOf(backupRel))
    }

    /**
     * MCP 桥接：刷新启用服务器的远程工具（工具集变化 → prefix 自动重建）。
     * 二十二批（审计）：
     * - listTools 失败时**保留旧工具**（此前"先删后建"，服务器临时不可达 → 模型看到的工具集静默缩小，无任何提示）
     * - 工具名做字符清洗（服务器名/工具名含空格、斜杠时会产生非法工具名）
     * - readOnlyHint 透传（readOnly 的 mcp 工具自动放行，见 effectiveDecision）
     */
    private suspend fun refreshMcpTools() {
        val enabled = mcpRegistry.list().filter { it.enabled }
        val fresh = mutableListOf<McpToolBridge>()
        val okServers = mutableSetOf<String>()
        for (srv in enabled) {
            McpClient.listTools(srv.url).onSuccess { infos ->
                okServers += srv.name
                infos.forEach { info ->
                    fresh += McpToolBridge(
                        srv.url, info.name, info.description,
                        mcpToolName(srv.name, info.name), info.inputSchema, info.readOnly,
                    )
                }
            } // 失败：静默跳过，旧工具由下方清理逻辑保留
        }
        // 仅移除"刷新成功服务器"的旧注册（失败服务器的旧工具保留）
        registry.all().filter { it.name.startsWith(MCP_TOOL_PREFIX) }
            .filter { t -> okServers.any { s -> t.name.startsWith(MCP_TOOL_PREFIX + sanitizeMcpName(s) + "_") } }
            .forEach { registry.remove(it.name) }
        fresh.forEach { registry.register(it) }
    }

    /** mcp 工具全名（前缀 + 服务器名 + 工具名，字符清洗防非法工具名） */
    private fun mcpToolName(srv: String, tool: String): String =
        MCP_TOOL_PREFIX + sanitizeMcpName(srv) + "_" + sanitizeMcpName(tool)

    private fun sanitizeMcpName(s: String): String = s.replace(Regex("[^A-Za-z0-9_\\-]"), "_")

    /** 二十二批：MCP 只读工具按 ALLOW 处理（服务器 readOnlyHint 声明）—— 其余委托 PolicyEngine */
    private fun effectiveDecision(name: String, path: String?): Decision {
        val d = policy.decide(name, path)
        if (d == Decision.ASK && (registry.get(name) as? McpToolBridge)?.readOnly == true) return Decision.ALLOW
        return d
    }

    /**
     * 等待审批（十批：60s 超时兜底 —— 修复"审批弹窗没点就无限挂起"的停住问题；
     * 超时按拒绝处理，引擎继续推进不卡死）。
     */
    private suspend fun awaitApproval(callId: String): ApprovalDecision {
        val deferred = CompletableDeferred<ApprovalDecision>()
        approvals[callId] = deferred
        val decision = try {
            withTimeout(APPROVAL_TIMEOUT_MS) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            approvals.remove(callId)
            eventFlow.emit(AgentEvent.ToolRepair("⏱ 审批等待超时（60s），已自动拒绝该操作"))
            ApprovalDecision.DENY
        }
        approvals.remove(callId)
        return decision
    }

    /** 等待 choice 选择（十批：60s 超时按取消处理） */
    private suspend fun awaitChoice(callId: String): List<List<Int>> {
        val deferred = CompletableDeferred<List<List<Int>>>()
        choices[callId] = deferred
        val selections = try {
            withTimeout(APPROVAL_TIMEOUT_MS) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            choices.remove(callId)
            eventFlow.emit(AgentEvent.ToolRepair("⏱ 选择等待超时（60s），已按取消处理"))
            emptyList()
        }
        choices.remove(callId)
        return selections
    }

    private fun markTool(session: Session, callId: String, status: ToolStatus, text: String) {
        val rec = session.messages.flatMap { it.toolCalls }.firstOrNull { it.id == callId } ?: return
        rec.status = status
        if (text.isNotBlank()) rec.resultText = text
    }

    private fun recordDiff(session: Session, callId: String, diff: String) {
        if (diff.isBlank()) return
        session.messages.flatMap { it.toolCalls }.firstOrNull { it.id == callId }?.let { rec ->
            if (rec.diffText.isBlank()) rec.diffText = diff
        }
    }

    private fun extractPath(toolName: String, args: Map<String, Any?>): String? = when (toolName) {
        "multi_edit" -> (args["edits"] as? List<*>)?.firstOrNull()?.let { e ->
            (e as? Map<String, Any?>)?.get("path") as? String
        }
        "move_file" -> (args["to"] as? String)?.takeIf { it.isNotBlank() } ?: (args["from"] as? String)?.takeIf { it.isNotBlank() }
        else -> (args["path"] as? String)?.takeIf { it.isNotBlank() }
    }

    /** 全部涉及路径（十二批修正：multi_edit 返回所有 edits 的 path —— 原实现只取第一个，
     *  检查点快照/磁盘回写漏掉其余文件，回退时无法恢复）；其余工具 = 单路径 */
    private fun extractPaths(toolName: String, args: Map<String, Any?>): List<String> = when (toolName) {
        "multi_edit" -> (args["edits"] as? List<*>)?.mapNotNull { e ->
            (e as? Map<String, Any?>)?.get("path") as? String
        } ?: emptyList()
        else -> extractPath(toolName, args)?.let { listOf(it) } ?: emptyList()
    }

    /** 移动/重命名：返回磁盘回写需要删除的旧路径（镜像已无该文件 → 源目录同步删除） */
    private fun extractDeletedPath(toolName: String, args: Map<String, Any?>): String? = when (toolName) {
        "move_file" -> (args["from"] as? String)?.takeIf { it.isNotBlank() }
        else -> null
    }

    companion object {
        // 八批：30 → 100（复杂任务步数上限；预算/熔断/无工具 break 已防失控）
        private const val MAX_STEPS = 100

        /**
         * 中止/异常收尾：把残留 RUNNING 工具标记为失败（防历史消息工具卡永久转圈）。
         * 取消传播（doExecute rethrow）后当前工具不再走 applyResult 回填，必须在此兜底。
         * APPROVAL_REQUIRED（审批挂起中）也标失败 —— 回合已中止，挂起的审批不再有机会决策，
         * 防历史残留"待审批"误导。DENIED/SUCCESS/FAILED 保留原状（有明确语义）。纯函数可单测。
         */
        fun markAbortedTools(session: Session) {
            for (m in session.messages) for (tc in m.toolCalls) {
                if (tc.status == ToolStatus.RUNNING) {
                    tc.status = ToolStatus.FAILED
                    tc.resultText = "已停止（用户停止）"
                } else if (tc.status == ToolStatus.APPROVAL_REQUIRED) {
                    tc.status = ToolStatus.FAILED
                    tc.resultText = "回合已中止，审批未完成"
                }
            }
        }
        const val GOAL_PREFIX = "[长期目标] "
        // 十七批：require 级技能强制注入前缀（命中触发词 → 引擎直接带剧本，不依赖模型自觉）
        const val SKILL_INJECT_PREFIX = "[技能注入] "
        // 二十二批（审计）：计划审批反馈注入前缀（引擎注入的 user 角色反馈消息）——
        // 与 GOAL/SKILL 注入同口径纳入 isInjected 排除（引擎/ChatScreen/rewindTo/backfillTurns 四处共用）
        val PLAN_FEEDBACK_PREFIXES = listOf("[计划已批准]", "[计划被驳回]", "[计划被拒绝]")
        // 优化 4a：技能选择器回填标记（UI 长按 + 选中后回填 "@skill:名称"；引擎解析后剥离）
        val SKILL_REF_REGEX = Regex("@skill:([A-Za-z0-9_\\-]+)")
        private const val MCP_TOOL_PREFIX = "mcp_"
        private const val MAX_PARALLEL = 4 // 六批：同批只读工具并行上限（SAF/电量友好）
        private const val TOOL_TIMEOUT_MS = 120_000L // PC shell 默认 120s
        private const val SUBAGENT_TIMEOUT_MS = 300_000L // 九批：子代理 API 单次 60-300s（DeepSeek 思考模式），豁免默认护栏
        private const val APPROVAL_TIMEOUT_MS = 60_000L // 十批：审批/选择等待超时（防无限挂起"停住"）

        /** 工具级超时映射（九批：subagent/planner 独立长超时，其余统一 120s） */
        fun timeoutFor(name: String): Long = when (name) {
            "subagent", "planner" -> SUBAGENT_TIMEOUT_MS
            else -> TOOL_TIMEOUT_MS
        }
        private const val MAX_TOOL_OUTPUT_BYTES = 32 * 1024 // PC maxToolOutputBytes
        private const val MAX_REASONING_STORED = 32 * 1024 // 本地存储截断（PC 端 128KB 运行时限制）

        /** B1：工具结果输出收缩（对齐 PC truncateToolOutput：head+tail，32KB ≈ 8K token） */
        fun truncateToolOutput(s: String): String {
            if (s.length <= MAX_TOOL_OUTPUT_BYTES) return s
            val head = MAX_TOOL_OUTPUT_BYTES * 3 / 4
            val tail = MAX_TOOL_OUTPUT_BYTES - head
            return s.take(head) + "\n…[中间省略 ${s.length - MAX_TOOL_OUTPUT_BYTES} 字符]…\n" + s.takeLast(tail)
        }
    }
}

/**
 * MCP schema 归一化（纯函数可单测）：服务器 inputSchema → 模型可见参数结构。
 * - 剔除模型无关键（$schema/$id/title/additionalProperties/definitions），保留 type/properties/required/enum/items/description 及嵌套
 * - 规模上限：序列化 ≤4KB 且 properties ≤40；超限回退 null（调用方用通用 arguments 壳，防超大 schema 撑爆 prefix）
 */
fun normalizeMcpSchema(s: Map<String, Any?>?): Map<String, Any?>? {
    if (s.isNullOrEmpty()) return null
    // LinkedHashMap：key 迭代序可预测 —— HashMap 序随哈希扰动变化会让 prefix 字节漂移（DeepSeek 缓存全量失效）
    val cleaned = java.util.LinkedHashMap<String, Any?>()
    for ((k, v) in s) {
        if (k in setOf("\$schema", "\$id", "title", "additionalProperties", "definitions")) continue
        cleaned[k] = when (v) {
            is Map<*, *> -> @Suppress("UNCHECKED_CAST") normalizeMcpSchema(v as Map<String, Any?>)
            is List<*> -> v.mapNotNull { it as? Map<String, Any?> }?.let { list ->
                if (list.isNotEmpty()) list.map { normalizeMcpSchema(it) ?: it } else v
            } ?: v
            else -> v
        }
    }
    val props = (cleaned["properties"] as? Map<*, *>)?.size ?: 0
    if (props > 40) return null
    if (MiniJson.stringify(cleaned).length > 4 * 1024) return null
    return cleaned
}

/** MCP 远程工具桥（参数结构由服务器 tools/list 定义，见工具描述；readOnly 来自服务器 readOnlyHint） */
class McpToolBridge(
    private val serverUrl: String,
    private val remoteName: String,
    desc: String,
    private val fullName: String,
    private val inputSchema: Map<String, Any?>? = null,
    val readOnly: Boolean = false,
) : ToolSpec {
    override val name = fullName
    override val description = "（MCP 远程工具 $remoteName）$desc。参数结构由服务器 tools/list 定义。"
    override val parameters = normalizeMcpSchema(inputSchema) ?: mapOf(
        "type" to "object",
        "properties" to mapOf(
            "arguments" to mapOf("type" to "object", "description" to "远程工具参数对象"),
        ),
        "required" to listOf<String>(),
    )

    override suspend fun execute(args: Map<String, Any?>, saf: SafRepo, ctx: ToolContext): ToolResult {
        val inner = args["arguments"] as? Map<String, Any?> ?: args
        return McpClient.callTool(serverUrl, remoteName, inner).fold(
            onSuccess = { ToolResult(true, it) },
            onFailure = { ToolResult(false, "MCP 调用失败: ${it.message}") },
        )
    }
}

/** 流式增量累积器：正文 / 思考 / 按 index 装配工具调用 */
private class StreamAccumulator {
    var usage: Usage? = null
    private val reasoning = StringBuilder()
    private val content = StringBuilder()
    private val toolCalls = LinkedHashMap<Int, MutableToolCall>()

    fun apply(d: ChatDelta) {
        d.reasoning?.let { reasoning.append(it) }
        d.content?.let { content.append(it) }
        d.toolCalls?.forEach { t -> applyToolDelta(t) }
    }

    private fun applyToolDelta(t: ToolCallDelta) {
        val cur = toolCalls.getOrPut(t.index) { MutableToolCall() }
        t.id?.let { cur.id = it }
        t.name?.let { cur.name = it }
        t.argumentsFragment?.let { cur.args.append(it) }
    }

    fun reasoningText(): String = reasoning.toString()
    fun contentText(): String = content.toString()

    fun assembledCalls(): List<RepairPipeline.AssembledCall> =
        toolCalls.values.map { RepairPipeline.AssembledCall(it.id, it.name, it.args.toString()) }

    private class MutableToolCall {
        var id: String = ""
        var name: String = ""
        val args = StringBuilder()
    }
}
