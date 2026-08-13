package com.mlx.app.ui.chat

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mlx.app.MlxApp
import com.mlx.app.core.agent.AgentEngine
import com.mlx.app.core.agent.AgentEvent
import com.mlx.app.core.agent.AgentKeepaliveService
import com.mlx.app.core.agent.ApprovalDecision
import com.mlx.app.core.agent.fmtSecs
import com.mlx.app.data.store.Session
import com.mlx.app.data.store.ToolStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import com.mlx.app.ui.UiFormats
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ApprovalItem(
    val callId: String,
    val name: String,
    val argsJson: String,
    val path: String?,
    val question: String? = null,      // choice 工具的问题（单题兼容）
    val options: List<String> = emptyList(), // choice 工具的选项（单题兼容）
    val questions: List<com.mlx.app.core.agent.ChoiceQuestion> = emptyList(), // P2-7：多题 ask 结构
)

data class CostDisplay(val hitRate: Double, val costUsd: Double, val tokens: Long)

/**
 * 上下文/成本条展示信息（纯函数可单测）。
 * 口径统一原则：主显示永远为「会话累计成本」（= totalCostUsd() 全量求和，与成本页同源）；
 * 最近一步成本仅执行中作附加信息（"本步 +¥x"），杜绝两页口径不一致（实测 13 倍差距根因）。
 */
data class ContextBarInfo(
    val primaryUsd: String, // 会话累计成本（主显示）
    val stepUsd: String?,   // 执行中最近一步成本（null = 不显示）
    val hitRate: Double?,   // 命中率（最近一步优先，无则会话均）
    val avgHitRate: Double?, // 会话均命中率
)

fun contextBarInfo(session: Session?, lastCost: CostDisplay?, running: Boolean): ContextBarInfo =
    ContextBarInfo(
        primaryUsd = UiFormats.usd(session?.totalCostUsd() ?: 0.0),
        stepUsd = if (running) lastCost?.let { "+${UiFormats.usd(it.costUsd)}" } else null,
        // 执行中优先最近一步命中率；非执行中恒用会话均（lastCost 回合结束已清空，兜底语义一致）
        hitRate = if (running) (lastCost?.hitRate ?: session?.cacheHitRate()) else session?.cacheHitRate(),
        avgHitRate = session?.cacheHitRate(),
    )

/** 排队消息（执行中发送的指令：可见、可单独取消） */
data class QueuedMessage(val id: String, val text: String)

/**
 * 子代理实时流状态（十一批：过程可视化 —— 思考链/正文双缓冲，尾部截断）。
 * 执行中仅实时展示；完成后结果走 ToolCallRecord.resultText 既有路径；回合结束清空不入 session。
 */
data class SubAgentStreamState(
    val content: String = "",
    val reasoning: String = "",
    val updatedAt: Long = 0L,
)

/** 计划审批（架构级 13：Agent 提交方案 → 用户批准/驳回/拒绝） */
data class PlanReview(val planText: String)

data class ChatUiState(
    val session: Session? = null,
    val streamingText: String = "",
    val streamingReasoning: String = "",
    val running: Boolean = false,
    val aborting: Boolean = false, // 已请求停止（按钮变灰"正在停止…"；TurnFinished/Aborted 复位）
    val lastCost: CostDisplay? = null,
    val contextRatio: Double = 0.0,
    val approvals: List<ApprovalItem> = emptyList(),
    val commandPaletteOpen: Boolean = false,
    val modelTier: String = "flash",
    val planMode: Boolean = false,
    val reasoningMode: String = "auto",
    val readOnly: Boolean = false, // 分屏另一窗口持有会话写锁时只读
    val toolCount: Int = 0,        // 当前注册工具数（诊断：确认工具定义已传给模型）
    val compactRatio: Double = 0.8, // 自动压缩阈值（对应 PC compact_ratio，可配置）
    val activeTurnTools: List<com.mlx.app.data.store.ToolCallRecord> = emptyList(), // P1 本回合任务区
    val queuedMessages: List<QueuedMessage> = emptyList(), // 排队中的用户指令
    val toolOutputs: Map<String, List<String>> = emptyMap(), // callId → 实时输出行（tail 200，状态面板明细）
    val subagentStreams: Map<String, SubAgentStreamState> = emptyMap(), // callId → 子代理实时流（十一批：过程可视化）
    val todos: List<com.mlx.app.core.tools.AuxTools.TodoStore.Todo> = emptyList(), // 会话任务清单（响应式，TodoUpdated 刷新）
    val planReady: PlanReview? = null, // 计划模式方案待审批（架构级 13）
    val balanceText: String? = null, // 账户余额（P2-20：回合后查询，成本条展示）
)

/** 对话页 ViewModel：收集引擎事件 → 驱动 UI 状态；审批/中止/压缩入口 */
class ChatViewModel(app: Application, private val sessionId: String) : AndroidViewModel(app) {

    private val container = (app as MlxApp).container

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState

    // 二十二批（审计）：Snackbar 通道全部 tryEmit —— 16 缓冲 + showSnackbar 串行 4s 展示，
    // emit 会挂起 → handleEvent 挂起 → 引擎 eventFlow 反压（低命中率回合的警告刷屏可卡停整条引擎）；
    // Snackbar 丢失可接受（展示类通知，非状态）
    private val _snackbarEvents = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val snackbarEvents: SharedFlow<String> = _snackbarEvents

    private var runJob: Job? = null
    /** 二十二批：abort 序号（连续两次停止时，旧兜底定时器不得强复新一次的中止状态） */
    private var abortSeqCounter = 0
    /** 本回合开始时刻（六批：完成通知/复盘耗时统计） */
    private var turnStartedAt = 0L
    // 十批：流式 debounce —— 每 1-5 字符一个 delta，直接更新 StateFlow 会每 token 全 UI 重组（1000 token = 200-1000 次）
    // 改为增量累积 + 100ms 批量 flush（重组从每 token 降到每秒 ~10 次）
    private val streamPending = StringBuilder()
    private val reasoningPending = StringBuilder()
    // 十一批：子代理流按 callId 分区缓冲（多子代理并行 delta 交错，不得共用单缓冲）+ 统一 100ms flush
    private class SubAgentPending {
        val content = StringBuilder()
        val reasoning = StringBuilder()
    }
    private val subagentPending = mutableMapOf<String, SubAgentPending>()

    init {
        // 会话租赁锁：分屏双实例时同一会话仅一个窗口可写（同实例多 VM 不互斥）
        val leaseHeld = container.sessionLease.acquire(sessionId, container.instanceId)
        _uiState.update {
            it.copy(
                session = container.sessionStore.load(sessionId),
                readOnly = !leaseHeld,
                todos = container.todoStore.list(sessionId), // 初始即显示存量任务清单
            )
        }
        viewModelScope.launch {
            // 二十二批（审计）：事件处理异常防护 —— 任一分支未预期异常此前会杀死 collector
            // 协程 → 事件流永久静默（running 卡死、队列不消费，唯一恢复路径是 15s 兜底）；
            // 现在记日志后继续，单事件异常不拖垮整条事件链
            container.engine.events.collect { ev ->
                runCatching { handleEvent(ev) }
                    .onFailure { e ->
                        android.util.Log.w("ChatVM", "handleEvent 异常: ${e.message}", e)
                    }
            }
        }
        // 十批：流式批量 flush（100ms；pending 非空即 flush，回合外无 delta 所以恒空转）
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(100)
                if (streamPending.isNotEmpty() || reasoningPending.isNotEmpty() || subagentPending.isNotEmpty()) {
                    val t = streamPending.toString().also { streamPending.clear() }
                    val r = reasoningPending.toString().also { reasoningPending.clear() }
                    _uiState.update {
                        it.copy(
                            streamingText = it.streamingText + t,
                            streamingReasoning = it.streamingReasoning + r,
                        )
                    }
                    // 十一批：子代理流按 callId 合并进 map（每 callId 独立缓冲 → 一次 update，4 并行不放大重组）
                    // 十二批修正：flush 时应用 8KB/4KB 尾部截断 —— 此前只截显示不截 state，长输出全量驻留内存
                    if (subagentPending.isNotEmpty()) {
                        val now = System.currentTimeMillis()
                        val updated = subagentPending.mapValues { (_, p) ->
                            SubAgentStreamState(
                                content = p.content.toString().takeLast(8192),
                                reasoning = p.reasoning.toString().takeLast(4096),
                                updatedAt = now,
                            )
                        }
                        subagentPending.clear()
                        _uiState.update { it.copy(subagentStreams = it.subagentStreams + updated) }
                    }
                }
            }
        }
        viewModelScope.launch {
            container.appStore.modelTierFlow.collect { tier ->
                _uiState.update { it.copy(modelTier = tier) }
            }
        }
        viewModelScope.launch {
            container.appStore.planModeFlow.collect { plan ->
                _uiState.update { it.copy(planMode = plan) }
            }
        }
        viewModelScope.launch {
            container.appStore.reasoningModeFlow.collect { mode ->
                _uiState.update { it.copy(reasoningMode = mode) }
            }
        }
        _uiState.update { it.copy(toolCount = container.toolRegistry.all().size) }
        viewModelScope.launch {
            container.appStore.compactRatioFlow.collect { r ->
                _uiState.update { it.copy(compactRatio = r) }
            }
        }
    }

    override fun onCleared() {
        container.sessionLease.release(sessionId, container.instanceId)
        super.onCleared()
    }

    /**
     * 发送消息：乐观更新（立即上屏，不等回合结束）+ 执行中排队（不再静默丢弃）。
     * 新指令可见地进入队列，回合结束自动串行消费。
     */
    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (_uiState.value.readOnly) {
            _snackbarEvents.tryEmit("该会话被另一窗口占用（只读）。请在该窗口关闭会话后再操作。")
            return
        }
        if (runJob?.isActive == true) {
            // 执行中：进入排队（可见、可取消），不再无声吞掉
            val q = QueuedMessage("q_${System.nanoTime()}", trimmed)
            _uiState.update {
                it.copy(queuedMessages = it.queuedMessages + q)
            }
            _snackbarEvents.tryEmit("⏳ 正在执行当前任务，你的消息已排队（${_uiState.value.queuedMessages.size} 条，可点状态条查看/取消）")
            return
        }
        startTurn(trimmed)
    }

    private fun startTurn(text: String) {
        val msgId = "m_${System.nanoTime()}"
        turnStartedAt = System.currentTimeMillis()
        keepaliveStart()
        // 乐观更新：用户消息立即进消息列表（引擎用同一 id 落库，磁盘重载不重复）
        _uiState.update { s ->
            s.copy(
                session = s.session?.also { sess ->
                    if (sess.messages.none { it.id == msgId }) {
                        sess.messages += com.mlx.app.data.store.MessageRecord(msgId, "user", text)
                    }
                },
                running = true,
                aborting = false, // 审查修复：新回合防御性复位（防 Error 后残留"正在停止"态）
                streamingText = "",
                streamingReasoning = "",
                lastCost = null,
                contextRatio = 0.0,
                activeTurnTools = emptyList(), // P1：新回合清空任务区
                toolOutputs = emptyMap(),
                subagentStreams = emptyMap(), // 十一批：新回合清空子代理实时流
            )
        }
        runJob = viewModelScope.launch {
            try {
                container.engine.runTurn(sessionId, text, msgId)
            } catch (e: CancellationException) {
                // TurnAborted 事件已由引擎发出
                // 审查修正：队列消费移到 handleEvent 回合结束分支 ——
                // finally 立即消费会与 TurnAborted→reloadSession 竞态（新回合 running 被覆盖为 false）
            }
        }
    }

    /**
     * 六批：执行期间启动前台服务保活（进程提权 + 通知实时意图 + 唤醒锁防 CPU 休眠）。
     * 仅首回合/续回合启动（runJob 空闲时）；队列续回合时服务仍在运行（IDLE 宽限内新回合到达即取消停止）。
     * Android 12+ 后台禁止启动 FGS —— 失败静默（服务已在运行则无碍；极端窗口由宽限覆盖）。
     */
    private fun keepaliveStart() {
        if (runJob?.isActive == true) return
        runCatching {
            val ctx = getApplication<Application>()
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, AgentKeepaliveService::class.java).setAction(AgentKeepaliveService.ACTION_START),
            )
        }
    }

    /** 回合结束后消费队列下一条 */
    private fun dequeueNext() {
        val next = _uiState.value.queuedMessages.firstOrNull() ?: return
        _uiState.update { it.copy(queuedMessages = it.queuedMessages - next) }
        startTurn(next.text)
    }

    /** 取消一条排队消息 */
    fun cancelQueued(id: String) {
        _uiState.update { it.copy(queuedMessages = it.queuedMessages.filterNot { q -> q.id == id }) }
    }

    /**
     * 终止所有任务：清空排队 + 取消当前回合（引擎 CancellationException → 进程树击杀）。
     * 立即置 aborting（按钮变灰"正在停止…"），回合终止事件到达后复位 ——
     * 修复"点击停止无反应"体感（中断链路本身完整，缺的是即时 UI 反馈）。
     */
    fun abortAll() {
        val active = runJob?.isActive == true
        _uiState.update {
            // 二十二批（审计 L15）：仅排队无执行时不清空队列即可复位，不置假"正在停止…"转圈
            //（停止按钮在 running || queuedCount>0 时可见，此前空闲仅排队时按停止 → 假转圈 15s）
            it.copy(queuedMessages = emptyList(), aborting = active, running = active)
        }
        _snackbarEvents.tryEmit(if (active) "正在停止…" else "已清空排队消息")
        if (!active) return
        // 二十二批（审计 L14）：abort 序号 —— 连续两次停止时旧兜底定时器不得强复新一次的中止状态
        val abortSeq = ++abortSeqCounter
        // ① 先 cancel：工具不得"正常完成"（反了会走 TurnFinished 错报"回合完成"）
        runJob?.cancel()
        // ② 再 destroy：协程取消打断不了阻塞 I/O（readLine/socket 读），主动杀进程 →
        //    进程死亡 → 管道 EOF → 取消传播 → 引擎 TurnAborted 收尾
        //    二十批：killTree 含 /proc 读取 + ProcessBuilder.kill + waitFor 阻塞 → IO 线程，
        //    防"正在停止…"动画卡主线程（cancel 已同步在前，"先 cancel 后 destroy"纪律不变）
        viewModelScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                container.engine.abortCurrentTurn()
            }
        }
        // 十九批：停止超时兜底 —— TurnAborted 事件异常未达时（引擎卡死等），15s 后强制恢复控制，
        // 防"正在停止…"转圈永不消失；同时补刀杀进程（引擎 handler 未执行时底层命令也在跑）
        viewModelScope.launch {
            kotlinx.coroutines.delay(15_000L)
            if (_uiState.value.aborting && abortSeq == abortSeqCounter) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    container.engine.abortCurrentTurn()
                }
                _uiState.update {
                    it.copy(
                        running = false,
                        aborting = false,
                        // 审查修正：兜底复位对齐 reloadSession —— TurnAborted 永不达时也清残留工具卡/输出
                        activeTurnTools = emptyList(),
                        toolOutputs = emptyMap(),
                        subagentStreams = emptyMap(),
                    )
                }
                _snackbarEvents.tryEmit("⚠ 停止超时，已强制恢复控制（未完成的修改保留）")
            }
        }
    }

    fun respondApproval(callId: String, decision: ApprovalDecision) {
        container.engine.respondApproval(callId, decision)
        _uiState.update {
            it.copy(approvals = it.approvals.filterNot { a -> a.callId == callId })
        }
    }

    /** choice 工具决策（P2-7 多题）：每题一个选中索引集合；空列表 = 取消 */
    fun respondChoice(callId: String, selections: List<List<Int>>) {
        container.engine.respondChoice(callId, selections)
        _uiState.update {
            it.copy(approvals = it.approvals.filterNot { a -> a.callId == callId })
        }
    }

    /** 计划审批决策（架构级 13）：批准 → 引擎解锁写工具继续；驳回带意见 → 重新规划；拒绝 → 取消回合 */
    fun respondPlanReview(decision: com.mlx.app.core.agent.PlanReviewDecision) {
        // 审查修复：per-session gate（跨会话隔离）
        container.engine.planGateFor(sessionId).respond(decision)
        _uiState.update { it.copy(planReady = null) }
    }

    fun toggleCommandPalette() {
        _uiState.update { it.copy(commandPaletteOpen = !it.commandPaletteOpen) }
    }

    /**
     * 手动压缩（对齐 PC /compact force 语义）：无条件折叠旧消息区，
     * 返回效果反馈（折叠条数 + token 前后对比），并刷新进度条。
     */
    fun compactNow() {
        viewModelScope.launch {
            val s = container.sessionStore.load(sessionId) ?: return@launch
            val compactRatio = container.appStore.compactRatioFlow.first()
            val before = s.estimatedTokens()
            val folded = container.contextManager.compactManual(s, container.toolRegistry.all(), compactRatio)
            container.sessionStore.save(s)
            val after = s.estimatedTokens()
            val ratio = container.contextManager.ratioUsed(s, container.toolRegistry.all())
            _uiState.update { it.copy(session = s, contextRatio = ratio) }
            _snackbarEvents.tryEmit(
                if (folded > 0) {
                    "✓ 已压缩 $folded 条消息：${UiFormats.tokens(before)} → ${UiFormats.tokens(after)} tok"
                } else {
                    "上下文未超阈值，暂无可压缩内容（当前 ${UiFormats.percent(ratio)}）"
                }
            )
        }
    }

    /** 回退到指定用户消息之后（Code + Conversation 范围；对应 PC rewind） */
    fun rewindTo(userMessageIndex: Int, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val s = container.sessionStore.load(sessionId) ?: return@launch
            // 十八批修复（审计 CRITICAL）：回合计数必须与引擎 isInjected 同口径 ——
            // 漏排 [记忆回顾]/[技能注入] 会让检查点按错 turn 恢复（文件回退错位/不完整）
            // 二十二批：计划审批反馈消息同口径排除
            val turn = s.messages.take(userMessageIndex + 1)
                .count {
                    it.role == "user" && !it.content.startsWith("[长期目标] ") &&
                        !it.content.startsWith("[记忆回顾]") &&
                        !it.content.startsWith(AgentEngine.SKILL_INJECT_PREFIX) &&
                        !AgentEngine.PLAN_FEEDBACK_PREFIXES.any { p -> it.content.startsWith(p) }
                }
            // 检查点通道与文件工具同源：真实路径工程 → RealBackend（直接写回真实磁盘文件）
            val backend = container.appStore.workspaceRoot()
                ?.let { com.mlx.app.core.tools.RealBackend(it) }
                ?: com.mlx.app.core.tools.SafBackend(container.safRepo)
            val result = container.checkpointStore.rewind(sessionId, turn, backend)
            result.fold(
                onSuccess = { restored ->
                    val keep = s.messages.take(userMessageIndex + 1)
                    s.messages.clear()
                    s.messages.addAll(keep)
                    container.sessionStore.save(s)
                    _uiState.update { it.copy(session = s) }
                    onDone("已回退到该回合（恢复 ${restored.size} 个文件，对话截断至此处）")
                },
                onFailure = { e -> onDone("回退失败：${e.message}") },
            )
        }
    }

    // ---- 任务清单（与 todo_* 工具共享存储；UI 状态由 TodoUpdated 事件/本地操作同步刷新） ----
    // （todoStore 同步读盘+写盘：放 IO 线程，避免勾选/添加时卡 Main）
    fun todoAdd(text: String) {
        viewModelScope.launch {
            val todos = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                container.todoStore.add(sessionId, text)
                container.todoStore.list(sessionId)
            }
            _uiState.update { it.copy(todos = todos) }
        }
    }

    fun todoToggle(id: String, done: Boolean) {
        viewModelScope.launch {
            val todos = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                container.todoStore.setDone(sessionId, id, done)
                container.todoStore.list(sessionId)
            }
            _uiState.update { it.copy(todos = todos) }
        }
    }

    private suspend fun handleEvent(ev: AgentEvent) {
        // 二十二批（审计）：回合级事件统一 sessionId 过滤 —— 引擎事件流全局共享，多窗口下
        // A 会话的回合结束/错误/流式/审批事件不得污染 B 窗口（此前 TurnAborted 会把 B 的
        // 会话整体替换成 A 的，TurnFinished 会让 B 错报"回合完成"并误消费队列）
        when (ev) {
            is AgentEvent.ToolStatusChanged -> if (ev.sessionId.isNotEmpty() && ev.sessionId != sessionId) return
            is AgentEvent.ToolOutput -> if (ev.sessionId.isNotEmpty() && ev.sessionId != sessionId) return
            is AgentEvent.SubAgentStream -> if (ev.sessionId.isNotEmpty() && ev.sessionId != sessionId) return
            is AgentEvent.TodoUpdated -> if (ev.sessionId != sessionId) return
            is AgentEvent.AssistantDelta -> if (ev.sessionId != sessionId) return
            is AgentEvent.ReasoningDelta -> if (ev.sessionId != sessionId) return
            is AgentEvent.ApprovalRequired -> if (ev.sessionId != sessionId) return
            is AgentEvent.UserChoiceRequired -> if (ev.sessionId != sessionId) return
            is AgentEvent.PlanReady -> if (ev.sessionId != sessionId) return
            is AgentEvent.TurnFinished -> if (ev.sessionId != sessionId) return
            is AgentEvent.TurnAborted -> if (ev.session.id != sessionId) return
            is AgentEvent.Error -> if (ev.sessionId != sessionId) return
            else -> Unit
        }
        when (ev) {
            is AgentEvent.AssistantDelta ->
                streamPending.append(ev.text) // 十批：debounce —— 由 flush 循环批量更新 UI

            is AgentEvent.ReasoningDelta ->
                reasoningPending.append(ev.text) // 十批：debounce（思考链 60-300s 高频流同样节流）

            is AgentEvent.ToolStatusChanged -> {
                // 六批：跨会话事件隔离（引擎单例全局事件流 —— 分屏另一会话的工具事件不得漏进本窗口）
                if (ev.sessionId.isNotEmpty() && ev.sessionId != sessionId) return
                val s = _uiState.value.session ?: return
                val rec = s.messages.flatMap { it.toolCalls }.firstOrNull { it.id == ev.callId }
                if (rec != null) {
                    rec.status = ev.status
                    if (ev.text.isNotBlank()) rec.resultText = ev.text
                    if (ev.intent.isNotBlank()) rec.intent = ev.intent
                }
                // P1：同步维护本回合固定任务区（工具实时状态）
                // 防刷屏：相同命令（name+argsJson）的重复失败复用同一行，累加重试次数
                val turnTools = _uiState.value.activeTurnTools.toMutableList()
                val existing = turnTools.firstOrNull { it.id == ev.callId }
                    ?: turnTools.firstOrNull {
                        it.name == ev.name && it.argsJson == ev.argsJson && it.status == com.mlx.app.data.store.ToolStatus.FAILED
                    }
                if (existing != null) {
                    if (existing.id != ev.callId) existing.retryCount++
                    existing.id = ev.callId
                    existing.status = ev.status
                    if (ev.text.isNotBlank()) existing.resultText = ev.text
                    if (ev.intent.isNotBlank()) existing.intent = ev.intent
                } else {
                    turnTools += com.mlx.app.data.store.ToolCallRecord(
                        id = ev.callId, name = ev.name, argsJson = ev.argsJson,
                        status = ev.status, resultText = ev.text, intent = ev.intent,
                    )
                }
                // 审批壳防残留：引擎侧超时自动 DENY（60s）/ choice 超时取消后 emit DENIED ——
                // 此时必须清审批条目（此前只有 respondApproval/respondChoice 会清），
                // 否则审批弹窗在回合继续期间残留挡屏（视觉消失 + 触摸拦截 = 全屏假死）
                _uiState.update {
                    it.copy(
                        session = s,
                        activeTurnTools = turnTools,
                        approvals = if (ev.status == com.mlx.app.data.store.ToolStatus.DENIED) {
                            it.approvals.filterNot { a -> a.callId == ev.callId }
                        } else {
                            it.approvals
                        },
                    )
                }
            }

            is AgentEvent.ToolOutput -> {
                // 实时输出行：执行状态面板明细（tail 200 防内存膨胀；十二批：sessionId 过滤防分屏串流）
                if (ev.sessionId.isNotEmpty() && ev.sessionId != sessionId) return
                _uiState.update {
                    val cur = it.toolOutputs[ev.callId] ?: emptyList()
                    val next = (cur + ev.line).takeLast(200)
                    it.copy(toolOutputs = it.toolOutputs + (ev.callId to next))
                }
            }

            is AgentEvent.SubAgentStream -> {
                // 十一批：子代理实时增量 → 按 callId 分区缓冲（100ms flush 合并进 subagentStreams）
                // 十二批：sessionId 过滤 —— 分屏另一窗口的子代理流不得进本窗口缓冲（内存/状态污染）
                if (ev.sessionId.isNotEmpty() && ev.sessionId != sessionId) return
                val p = subagentPending.getOrPut(ev.callId) { SubAgentPending() }
                ev.content?.let { p.content.append(it) }
                ev.reasoning?.let { p.reasoning.append(it) }
            }

            is AgentEvent.ApprovalRequired -> _uiState.update {
                it.copy(
                    approvals = it.approvals +
                        ApprovalItem(ev.callId, ev.name, ev.argsJson, ev.path)
                )
            }

            is AgentEvent.UserChoiceRequired -> _uiState.update {
                it.copy(
                    approvals = it.approvals +
                        ApprovalItem(
                            callId = ev.callId,
                            name = "choice",
                            argsJson = ev.questions.joinToString("；") { q -> "${q.question}(${q.options.joinToString("/")})" },
                            path = null,
                            question = ev.questions.firstOrNull()?.question,
                            options = ev.questions.firstOrNull()?.options ?: emptyList(),
                            questions = ev.questions,
                        )
                )
            }

            is AgentEvent.CostUpdated -> _uiState.update {
                it.copy(
                    lastCost = CostDisplay(
                        hitRate = ev.hitRate,
                        costUsd = ev.costUsd,
                        tokens = ev.hitTokens + ev.missTokens + ev.completionTokens,
                    ),
                )
            }

            is AgentEvent.CacheMissWarning ->
                _snackbarEvents.tryEmit("⚠ 缓存命中率偏低（%.0f%%），上下文前缀可能已变化".format(ev.hitRate * 100))

            is AgentEvent.ModelUpgraded ->
                _snackbarEvents.tryEmit("⚡ 连续编辑失败，已自动升级 Pro 模型")

            is AgentEvent.ContextUsage ->
                _uiState.update { it.copy(contextRatio = ev.ratio) }

            is AgentEvent.ToolRepair ->
                _snackbarEvents.tryEmit(ev.message)

            is AgentEvent.SkillMissing ->
                // 架构级 12：点名要求而清单没有 → 明确告知并询问（不静默回退）
                _snackbarEvents.tryEmit(
                    "⚠ 未找到技能「${ev.name}」。可用通用方式完成（我会直接处理），" +
                        "或在 设置 > 技能 中从链接安装/新建该技能。"
                )

            is AgentEvent.PlanReady ->
                // 架构级 13：方案待审批 → 弹审批层
                _uiState.update { it.copy(planReady = PlanReview(ev.planText)) }

            is AgentEvent.BudgetWarning ->
                _snackbarEvents.tryEmit(
                    "⚠ 预算已使用 80%（${UiFormats.usd(ev.totalUsd)} / ${UiFormats.usd(ev.budgetUsd)}），接近上限"
                )

            is AgentEvent.Error -> {
                _snackbarEvents.tryEmit(ev.message)
                // 审查修复：Error 同步复位 aborting/planReady（防"正在停止"残留与新回合卡态）
                _uiState.update { it.copy(running = false, aborting = false, planReady = null) }
            }

            is AgentEvent.TodoUpdated -> {
                // todo_add/todo_complete 成功后刷新任务清单（sessionId 过滤，分屏另一窗口不误刷；
                // list 同步读盘 → IO 线程，防引擎高频 todo 操作时卡 Main）
                if (ev.sessionId == sessionId) {
                    val todos = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        container.todoStore.list(sessionId)
                    }
                    _uiState.update { it.copy(todos = todos) }
                }
            }

            is AgentEvent.TurnFinished -> {
                reloadSession()
                refreshBalance()
                // 审查修正：回合结束事件处理完（reloadSession 复位 running）再消费队列 ——
                // 防 dequeueNext→startTurn 的 running=true 被 reloadSession 的 running=false 覆盖（新回合静默运行）
                dequeueNext()
                // 六批：完成反馈（服务侧另有"执行完成"通知；App 内 snackbar 即时可见）
                val secs = ((System.currentTimeMillis() - turnStartedAt) / 1000).coerceAtLeast(0L)
                _snackbarEvents.tryEmit("✓ 回合完成 · 耗时${fmtSecs(secs)}")
            }
            is AgentEvent.TurnAborted -> {
                // 二十批：事件直接携带引擎最终内存会话 —— 免二次读盘（读盘可能抢在后台落盘完成前
                // 读到旧快照，且多一层磁盘 I/O 拖慢复位；后续回合由引擎 teardownGate 保证读到完整状态）
                resetAfterTurn(ev.session)
                refreshBalance()
                // 同上：回合结束事件处理完再消费队列（停止后排队消息在 UI 复位后开始新回合）
                dequeueNext()
                // 十九批：文案与"正在停止…"呼应（原"已中止"表述含糊）
                _snackbarEvents.tryEmit("已停止")
            }
        }
    }

    /** 回合后刷新账户余额（P2-20：成本条展示；余额查询低频，失败静默保留旧值） */
    private fun refreshBalance() {
        viewModelScope.launch {
            runCatching {
                val key = container.appStore.apiKeyPlain() ?: return@runCatching null
                val baseUrl = try { container.appStore.baseUrl() } catch (e: Exception) { "https://api.deepseek.com/v1" }
                container.llm.balance(key, baseUrl).getOrNull()?.infos?.firstOrNull()?.let { info ->
                    "${info.currency} ${"%.2f".format(info.total)}"
                }
            }.getOrNull()?.let { text ->
                _uiState.update { it.copy(balanceText = text) }
            }
        }
    }

    /** 回合结束重载（IO 线程读盘：会话 JSON 大时同步 load 会卡 Main 触发 ANR） */
    private suspend fun reloadSession() {
        val s = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            container.sessionStore.load(sessionId)
        }
        resetAfterTurn(s)
    }

    /** 回合（含中止）结束统一复位（二十批：中止路径事件直接带内存会话，复用同一复位逻辑） */
    private fun resetAfterTurn(s: Session?) {
        _uiState.update {
            it.copy(
                session = s,
                running = false,
                aborting = false, // 回合（含中止）结束：复位"正在停止"状态
                planReady = null, // 审查修复：中止/结束清除待审批（防审批层卡屏）
                approvals = emptyList(), // 回合结束不可能再有等待审批 —— 清空防僵尸审批壳挡屏
                streamingText = "",
                streamingReasoning = "",
                lastCost = null, // 回合结束清除"最近一步"附显（主显示恒为会话累计，与成本页同口径）
                activeTurnTools = emptyList(), // P1：回合结束任务区并入历史
                toolOutputs = emptyMap(),
                subagentStreams = emptyMap(), // 十一批：回合结束清空子代理实时流（完成后结果走 resultText 持久化）
            )
        }
    }

    companion object {
        fun factory(sessionId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ChatViewModel(
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!,
                    sessionId,
                )
            }
        }
    }
}
