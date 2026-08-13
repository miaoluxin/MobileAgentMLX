package com.mlx.app.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mlx.app.core.agent.AgentEngine
import com.mlx.app.core.commands.CommandDef
import com.mlx.app.core.commands.CommandRegistry
import com.mlx.app.data.store.MessageRecord
import com.mlx.app.data.store.ToolCallRecord
import com.mlx.app.data.store.ToolStatus
import com.mlx.app.ui.AppViewModel
import com.mlx.app.ui.Tab
import com.mlx.app.ui.UiFormats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 对话主界面（对应文档 5.3 节 + 线框图 W1）：
 * 挖孔安全区 → 顶部应用栏（模型/模式芯片、计划锁）→ 成本缓存条 → 消息流（工具卡片）
 * → 快捷操作栏 → 输入区 → 底部导航（由外层 Scaffold 提供）。
 */
@Composable
fun ChatScaffold(
    appVm: AppViewModel,
    sessionId: String,
    snackbarHostState: SnackbarHostState,
) {
    val chatVm: ChatViewModel = viewModel(key = sessionId, factory = ChatViewModel.factory(sessionId))
    val state by chatVm.uiState.collectAsState()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var input by rememberSaveable(sessionId) { mutableStateOf("") }
    // 九批：粘贴长文折叠（阈值 500 字符/15 行）—— 折叠时全文存 pastedFullText，输入框显示摘要卡片
    // 用 remember 而非 rememberSaveable：大文本进 Bundle 有 TransactionTooLarge 风险，转屏丢失可接受
    var pastedFullText by remember { mutableStateOf<String?>(null) }
    var pastedSummary by remember { mutableStateOf<String?>(null) }
    var previousInput by remember { mutableStateOf("") }
    var showPastePreview by remember { mutableStateOf(false) }
    var showAbortDialog by remember { mutableStateOf(false) }
    var showBudgetDialog by remember { mutableStateOf(false) }
    var showTodoSheet by remember { mutableStateOf(false) }
    var showGoalDialog by remember { mutableStateOf(false) }
    var goalInput by remember { mutableStateOf("") }
    var longPressedMessage by remember { mutableStateOf<com.mlx.app.data.store.MessageRecord?>(null) }
    var budgetInput by remember { mutableStateOf("") }
    var showExpandedInput by rememberSaveable(sessionId + "expand") { mutableStateOf(false) }
    // 目标/计划模式首次使用引导（AppStore 标志，只弹一次）
    var showGoalIntro by remember { mutableStateOf(false) }
    var showPlanIntro by remember { mutableStateOf(false) }
    // 优化 4a：技能选择器（长按 + 按钮唤出）
    var showSkillPicker by remember { mutableStateOf(false) }
    // 输入框焦点（审查修复：命令回填后请求焦点，光标落输入框可直接回车执行）
    val inputFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }

    // 文件工作台"附加到对话"回填输入框
    LaunchedEffect(appVm.pendingAttach) {
        appVm.pendingAttach?.let { p ->
            input = (input.trim() + " @$p").trim()
            appVm.consumeAttach()
        }
    }
    // 会话被删除后自动返回列表
    LaunchedEffect(state.session) {
        if (state.session == null && !state.running) appVm.closeSession()
    }
    LaunchedEffect(Unit) {
        chatVm.snackbarEvents.collect { snackbarHostState.showSnackbar(it) }
    }

    // 九批：粘贴长文折叠检测 —— input 增量超阈值（500 字符/15 行）判定为粘贴，折叠为摘要卡片
    LaunchedEffect(input) {
        val info = pasteCollapseInfo(previousInput, input)
        if (info != null) {
            pastedFullText = input
            pastedSummary = info
            previousInput = ""
            input = "" // 折叠后输入框清空（全文在 pastedFullText，后续打字不会重复触发）
        } else {
            previousInput = input
        }
    }

    // 手势：流式中右滑 = Esc 中止（对应 PC TUI Esc）
    val scope = rememberCoroutineScope()
    // 二次审查修复：面板执行后回填的命令标记 —— 用户未编辑直接回车时跳过重复执行
    // （原实现：面板点击已执行一次 + 回填 + 回车 → 二次执行，/plan.toggle 切回原状态、/goal 弹两次）
    var lastExecutedCommand by remember { mutableStateOf<String?>(null) }

    // 审查修复：斜杠命令本地分发（对齐 PC CLI —— 回填/手动输入的斜杠命令回车时执行而非发给模型；
    // 不匹配已知命令的斜杠原文照常发送，模型可解释）
    fun sendInput() {
        // 九批：折叠态发送粘贴全文（pastedFullText），否则发送输入框文本
        val textToSend = pastedFullText ?: input
        val trimmed = textToSend.trimStart()
        if (trimmed.startsWith("/") && !trimmed.startsWith("//")) {
            val def = CommandRegistry.all.firstOrNull { it.name == trimmed.trim() }
            if (def != null) {
                // 面板刚执行过的同一命令（用户未编辑）→ 只清空不重复执行
                if (def.name == lastExecutedCommand) {
                    lastExecutedCommand = null
                    input = ""
                    return
                }
                lastExecutedCommand = null
                executeCommand(
                    def, chatVm, appVm, snackbarHostState, scope,
                    onTodoRequest = { showTodoSheet = true },
                    onGoalRequest = { showGoalDialog = true },
                )
                input = ""
                return
            }
        }
        chatVm.send(textToSend)
        input = ""
        pastedFullText = null
        pastedSummary = null
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .pointerInput(state.running) {
                if (!state.running) return@pointerInput
                var acc = 0f
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        acc += amount
                        if (acc > 260f) {
                            acc = 0f
                            scope.launch { showAbortDialog = true }
                        }
                    }
                )
            }
    ) {
        if (state.readOnly) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "🔒 该会话被另一窗口占用（只读）—— 分屏协同中",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        ChatTopBar(
            appVm = appVm,
            state = state,
            onBack = { appVm.closeSession() },
            // 首次使用弹引导（只弹一次），之后直接打开目标设置
            onGoal = {
                scope.launch {
                    if (!appVm.container.appStore.goalIntroShown()) {
                        appVm.container.appStore.markGoalIntroShown()
                        showGoalIntro = true
                    } else {
                        showGoalDialog = true
                    }
                }
            },
            // 计划模式开关：首次使用弹引导，之后切换 + Snackbar 反馈
            onPlanToggle = {
                scope.launch {
                    if (!appVm.container.appStore.planIntroShown()) {
                        appVm.container.appStore.markPlanIntroShown()
                        showPlanIntro = true
                    } else {
                        val newPlan = !state.planMode
                        appVm.setPlanMode(newPlan)
                        snackbarHostState.showSnackbar(
                            if (newPlan) "🔒 计划模式已开启：只读分析，写工具将被拦截"
                            else "🔓 计划模式已关闭"
                        )
                    }
                }
            },
        )
        ContextBar(state = state, onCompact = { chatVm.compactNow() })
        MessageList(
            state = state,
            onLongPress = { longPressedMessage = it },
        )
        // ① 底部常驻任务区（七批：对齐 Claude CLI —— 当前任务/待办/状态行/排队；点击打开 TodoSheet）
        if (state.running) {
            AgentStatusBar(
                state = state,
                onOpenTodo = { showTodoSheet = true },
                onCancelQueued = { chatVm.cancelQueued(it) },
            )
        }
        ModeBar(
            appVm = appVm,
            state = state,
        )
        InputRow(
            value = input,
            onValueChange = { input = it },
            enabled = !state.readOnly, // 输入始终可用：执行中发送进入排队
            running = state.running,
            aborting = state.aborting,
            queuedCount = state.queuedMessages.size,
            focusRequester = inputFocusRequester,
            pasteSummary = pastedSummary,
            onPastePreview = { showPastePreview = true },
            onPasteClear = {
                pastedFullText = null
                pastedSummary = null
            },
            onSend = { sendInput() },
            onAbortAll = { chatVm.abortAll() },
            onExpand = { showExpandedInput = true },
            onAttach = { appVm.selectTab(Tab.Files) },
            onInputSlash = { chatVm.toggleCommandPalette() },
            onPickSkill = { showSkillPicker = true },
        )
    }

    // 九批：粘贴预览（滚动查看全文；发送全文 / 展开编辑回填输入框 / 删除）
    if (showPastePreview && pastedFullText != null) {
        PastePreviewDialog(
            text = pastedFullText!!,
            onSend = {
                showPastePreview = false
                sendInput() // sendInput 内部用 pastedFullText 发送全文并清折叠
            },
            onEdit = {
                showPastePreview = false
                // 回填前先同步 previousInput，避免 LaunchedEffect 再次判定为粘贴折叠
                previousInput = pastedFullText!!
                input = pastedFullText!!
                pastedFullText = null
                pastedSummary = null
            },
            onDelete = {
                showPastePreview = false
                pastedFullText = null
                pastedSummary = null
            },
            onDismiss = { showPastePreview = false },
        )
    }

    // C2：最大化输入（全屏编辑区，右下角发送；与外层共享 input 状态）
    if (showExpandedInput) {
        ExpandedInputDialog(
            value = input,
            onValueChange = { input = it },
            enabled = !state.readOnly,
            running = state.running,
            aborting = state.aborting,
            queuedCount = state.queuedMessages.size,
            onSend = { sendInput() },
            onAbortAll = { chatVm.abortAll() },
            onCollapse = { showExpandedInput = false },
        )
    }

    if (showAbortDialog) {
        AlertDialog(
            onDismissRequest = { showAbortDialog = false },
            title = { Text("中止当前回合？") },
            text = { Text("Agent 正在执行中。中止后已完成的修改会保留。") },
            confirmButton = {
                TextButton(onClick = {
                    showAbortDialog = false
                    chatVm.abortAll()
                }) { Text("中止") }
            },
            dismissButton = {
                TextButton(onClick = { showAbortDialog = false }) { Text("继续") }
            },
        )
    }

    if (showBudgetDialog) {
        AlertDialog(
            onDismissRequest = { showBudgetDialog = false },
            title = { Text("设置预算上限（元）") },
            text = {
                OutlinedTextField(
                    value = budgetInput,
                    onValueChange = { budgetInput = it },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    placeholder = { Text("0 = 不限") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    appVm.setBudget(budgetInput.toDoubleOrNull() ?: 0.0)
                    showBudgetDialog = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showBudgetDialog = false }) { Text("取消") }
            },
        )
    }

    if (state.approvals.isNotEmpty()) {
        ApprovalSheet(
            approvals = state.approvals,
            onDecision = { callId, decision -> chatVm.respondApproval(callId, decision) },
            onChoice = { callId, selections -> chatVm.respondChoice(callId, selections) },
        )
    }

    // 计划审批层（架构级 13：规划 → 审批 → 执行 两阶段）
    state.planReady?.let { review ->
        PlanReviewSheet(
            planText = review.planText,
            onApprove = {
                chatVm.respondPlanReview(
                    com.mlx.app.core.agent.PlanReviewDecision.Approve(review.planText)
                )
            },
            onRevise = { comment ->
                chatVm.respondPlanReview(
                    com.mlx.app.core.agent.PlanReviewDecision.Revise(comment)
                )
            },
            onReject = {
                chatVm.respondPlanReview(com.mlx.app.core.agent.PlanReviewDecision.Reject)
            },
        )
    }

    if (state.commandPaletteOpen) {
        CommandPalette(
            commands = CommandRegistry.all,
            onDismiss = { chatVm.toggleCommandPalette() },
            onExecute = { def ->
                val backfill = executeCommand(
                    def, chatVm, appVm, snackbarHostState, scope,
                    onTodoRequest = { showTodoSheet = true },
                    onGoalRequest = { showGoalDialog = true },
                )
                // 设置类命令：命令文本回填输入框（可见反馈，光标落末尾，可编辑后回车重发）
                if (backfill != null) {
                    input = backfill
                    // 二次审查修复：记录已执行命令 —— 未编辑直接回车时 sendInput 跳过重复执行
                    lastExecutedCommand = def.name
                    // 审查修复：请求焦点（光标落输入框，回车即本地执行 —— sendInput 斜杠分发）
                    scope.launch { inputFocusRequester.requestFocus() }
                }
                chatVm.toggleCommandPalette()
            },
            onBudgetRequest = { showBudgetDialog = true },
            onTodoRequest = { showTodoSheet = true },
        )
    }

    // 优化 4a：技能选择器（长按 + 唤出）—— 选中回填 @skill:名称，引擎解析后注入剧本
    if (showSkillPicker) {
        SkillPickerSheet(
            skills = remember(showSkillPicker, appVm.container.skillStore) { appVm.container.skillStore.list() },
            onDismiss = { showSkillPicker = false },
            onPick = { skill ->
                input = (input.trim() + " @skill:" + skill.name).trim()
                showSkillPicker = false
                scope.launch { inputFocusRequester.requestFocus() }
            },
        )
    }

    if (showTodoSheet) {
        TodoSheet(
            chatVm = chatVm,
            onDismiss = { showTodoSheet = false },
        )
    }

    // 消息长按操作：复制 / 从这里分支 / 回退到此处
    longPressedMessage?.let { msg ->
        val idx = state.session?.messages?.indexOfFirst { it.id == msg.id } ?: -1
        MessageActionsSheet(
            message = msg,
            messageIndex = idx,
            onDismiss = { longPressedMessage = null },
            onCopy = {
                val text = msg.content.ifBlank { msg.toolCalls.joinToString { it.name } }
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
                longPressedMessage = null
                scope.launch { snackbarHostState.showSnackbar("已复制") }
            },
            onBranch = {
                longPressedMessage = null
                if (idx >= 0) appVm.branchSession(sessionId, idx + 1)
            },
            onRewind = {
                longPressedMessage = null
                if (idx >= 0) chatVm.rewindTo(idx) { msg2 ->
                    scope.launch { snackbarHostState.showSnackbar(msg2) }
                }
            },
        )
    }

    // 目标模式（/goal）：设置/清除长期目标
    if (showGoalDialog) {
        val currentGoal by appVm.container.appStore.goalFlow.collectAsState(initial = "")
        AlertDialog(
            onDismissRequest = { showGoalDialog = false },
            title = { Text("目标模式（/goal）") },
            text = {
                Column {
                    OutlinedTextField(
                        value = goalInput,
                        onValueChange = { goalInput = it },
                        placeholder = { Text(currentGoal.ifBlank { "输入长期目标，Agent 将跨回合持续遵循…" }) },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (currentGoal.isNotBlank()) {
                        Text(
                            "当前目标：$currentGoal",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    // 帮助文案（首次使用引导的一部分）：用途说明
                    Text(
                        "目标模式让 Agent 跨回合持续遵循一条长期目标（如「保持项目结构整洁」）。" +
                            "设置后每一轮都会注入该目标；可随时修改或清除。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    appVm.setGoal(goalInput)
                    goalInput = ""
                    showGoalDialog = false
                }) { Text("设置") }
            },
            dismissButton = {
                TextButton(onClick = {
                    appVm.setGoal("")
                    goalInput = ""
                    showGoalDialog = false
                }) { Text("清除") }
            },
        )
    }

    // 目标模式首次引导（只弹一次；"我知道了"后进入设置对话框）
    if (showGoalIntro) {
        AlertDialog(
            onDismissRequest = { showGoalIntro = false },
            title = { Text("目标模式") },
            text = {
                Text(
                    "让 Agent 跨回合持续遵循一条长期目标（如「保持项目结构整洁」「汇报用麦肯锡风格」）。\n\n" +
                        "· 设置：输入目标文本 → 点「设置」\n" +
                        "· 生效：此后每一轮都会注入该目标，Agent 持续遵循\n" +
                        "· 修改/退出：再次点击顶部 🚩 图标 → 修改或「清除」",
                )
            },
            confirmButton = {
                TextButton(onClick = { showGoalIntro = false; showGoalDialog = true }) {
                    Text("我知道了，去设置")
                }
            },
        )
    }

    // 计划模式首次引导（只弹一次；对应 PC Plan Mode 两阶段）
    if (showPlanIntro) {
        AlertDialog(
            onDismissRequest = { showPlanIntro = false },
            title = { Text("计划模式（只读分析）") },
            text = {
                Text(
                    "计划模式下 Agent 只读分析、输出执行方案，写工具会被引擎直接拦截（计划模式拒绝）。\n\n" +
                        "· 开启：点击顶部的锁图标（变为蓝色）\n" +
                        "· 退出：再次点击锁图标\n" +
                        "· 适用：先看方案再动手的场景（审阅/方案评估）",
                )
            },
            confirmButton = {
                TextButton(onClick = { showPlanIntro = false }) { Text("我知道了") }
            },
        )
    }
}

/**
 * 指令面板命令执行分发（对应文档 5.4 节映射表）。
 * @return 需要回填输入框的命令文本（immediate 导航/表单类返回 null —— 页面切换本身有可见反馈；
 *         设置类返回 def.name —— 命令文本留存在输入框作为"已生效"痕迹，可编辑后回车重发）
 */
private fun executeCommand(
    def: CommandDef,
    chatVm: ChatViewModel,
    appVm: AppViewModel,
    snackbarHostState: SnackbarHostState,
    scope: CoroutineScope,
    onTodoRequest: () -> Unit,
    onGoalRequest: () -> Unit,
): String? {
    fun toast(msg: String) {
        scope.launch { snackbarHostState.showSnackbar(msg) }
    }
    if (def.immediate) {
        when (def.id) {
            "new" -> {
                appVm.selectTab(Tab.Chat) // 新建会话走会话页树状选择（工程归属）
                toast("已返回会话列表，点击 + 新建会话")
            }
            "sessions" -> {
                appVm.closeSession()
                toast("已返回会话列表")
            }
            "stats" -> {
                appVm.closeSession()
                appVm.selectTab(Tab.Stats)
                toast("已打开成本统计")
            }
            "permissions", "sandbox", "memory", "skills", "mcp" -> {
                // P2-19：命令直达对应设置区块（自动滚动定位）
                val section = when (def.id) {
                    "memory" -> "memory"
                    "skills" -> "skills"
                    "mcp" -> "mcp"
                    "permissions", "sandbox" -> "permissions"
                    else -> null
                }
                appVm.selectTab(Tab.Settings, section)
                toast("已打开设置（${def.zhName}）")
            }
            "theme" -> {
                appVm.selectTab(Tab.Settings)
                toast("已打开设置（外观 > 主题切换）")
            }
            "attach" -> {
                appVm.selectTab(Tab.Files)
                toast("已打开文件页，请选择要附加的文件")
            }
            "search" -> {
                appVm.selectTab(Tab.Files)
                toast("已打开文件页，顶部可搜索项目文件")
            }
            "todo" -> onTodoRequest()
            "websearch" -> {
                appVm.closeSession()
                appVm.selectTab(Tab.Settings)
                toast("联网搜索为 Agent 自动能力，可在设置中切换后端")
            }
            "output-style" -> {
                appVm.selectTab(Tab.Settings)
                toast("已打开设置（外观 > 输出风格）")
            }
            "jobs" -> {
                appVm.closeSession()
                appVm.selectTab(Tab.Jobs)
                toast("已打开后台任务")
            }
            "language" -> {
                appVm.selectTab(Tab.Settings)
                toast("已打开设置（界面语言以中文为主）")
            }
            "branch" -> toast("长按对话中任意消息 → 从这里分支")
            "rewind" -> toast("长按用户消息 → 回退到此处")
            "budget" -> toast("请在 设置 > 预算 中配置（或点击输入区旁的 + 再试）")
            else -> toast("已执行：${def.zhName}")
        }
        return null
    }
    // 设置类：执行 + 命令文本回填输入框（可见反馈）
    when (def.id) {
        "model.flash" -> {
            appVm.setModelTier("flash")
            toast("✓ 已切换 Flash 模型")
        }
        "model.pro" -> {
            appVm.setModelTier("pro")
            toast("✓ 已切换 Pro 模型")
        }
        "mode.review" -> {
            appVm.setPolicyMode("review")
            toast("✓ 审批模式：写操作全部询问")
        }
        "mode.auto" -> {
            appVm.setPolicyMode("auto")
            toast("✓ 审批模式：大部分操作自动放行")
        }
        "mode.yolo" -> {
            appVm.setPolicyMode("yolo")
            toast("✓ 审批模式：最少询问，全部记录")
        }
        "plan.toggle" -> {
            val newPlan = !appVm.planMode
            appVm.setPlanMode(newPlan)
            toast(if (newPlan) "🔒 计划模式已开启：只读分析，写工具将被拦截" else "🔓 计划模式已关闭")
        }
        "goal" -> onGoalRequest()
        "init" -> appVm.initProject { msg -> toast(msg) }
        "compact" -> chatVm.compactNow()
        else -> toast(if (def.available) "已执行：${def.zhName}" else "「${def.zhName}」规划中，敬请期待")
    }
    return def.name
}

// ---------- 顶部应用栏（挖孔安全区上方留白；中央不放交互元素） ----------

@Composable
private fun ChatTopBar(
    appVm: AppViewModel,
    state: ChatUiState,
    onBack: () -> Unit,
    onGoal: () -> Unit,
    onPlanToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .displayCutoutPadding()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "返回会话列表")
        }
        Text(
            state.session?.title ?: "会话",
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // 目标模式（/goal）：Material 定位 pin 图标替代 emoji（修复"红圈"突兀观感）
        IconButton(onClick = onGoal) {
            Icon(
                Icons.Filled.Place,
                contentDescription = "目标模式（长期目标）",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 计划锁（对应 /plan；切换反馈与首次引导由上层处理）
        IconButton(onClick = onPlanToggle) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = "计划模式",
                tint = if (state.planMode) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun modeLabel(mode: String): String = when (mode) {
    "auto" -> "Auto"
    "yolo" -> "Yolo"
    else -> "Review"
}

private fun reasoningLabel(mode: String): String = when (mode) {
    "off" -> "关闭"
    "max" -> "深度"
    else -> "标准"
}

/** 模式条（输入框上方；仿 PC 底部状态区布局）：模型 / 思考模式 / 审批模式 */
@Composable
private fun ModeBar(appVm: AppViewModel, state: ChatUiState) {
    var modelMenu by remember { mutableStateOf(false) }
    var thinkingMenu by remember { mutableStateOf(false) }
    var modeMenu by remember { mutableStateOf(false) }
    // 十五批：菜单弹窗不抢窗口焦点（focusable=false）→ 输入框不失焦、软键盘保持展开不跳动；
    // 非 focusable 弹窗不拦截返回键 → 用 BackHandler 兜底关闭
    BackHandler(enabled = modelMenu || thinkingMenu || modeMenu) {
        modelMenu = false
        thinkingMenu = false
        modeMenu = false
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TextButton(onClick = { modelMenu = true }, modifier = Modifier.height(32.dp)) {
            Text(
                "⚙ ${if (state.modelTier == "pro") "Pro" else "Flash"} ▾",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        DropdownMenu(
            expanded = modelMenu,
            onDismissRequest = { modelMenu = false },
            properties = PopupProperties(focusable = false),
        ) {
            DropdownMenuItem(
                text = { Text("Flash：${appVm.flashModel}") },
                onClick = { appVm.setModelTier("flash"); modelMenu = false },
            )
            DropdownMenuItem(
                text = { Text("Pro：${appVm.proModel}") },
                onClick = { appVm.setModelTier("pro"); modelMenu = false },
            )
        }
        TextButton(onClick = { thinkingMenu = true }, modifier = Modifier.height(32.dp)) {
            Text(
                "💭 思考:${reasoningLabel(state.reasoningMode)} ▾",
                style = MaterialTheme.typography.labelMedium,
            )
        }
        DropdownMenu(
            expanded = thinkingMenu,
            onDismissRequest = { thinkingMenu = false },
            properties = PopupProperties(focusable = false),
        ) {
            DropdownMenuItem(
                text = { Text("💭 标准（默认）") },
                onClick = { appVm.setReasoningMode("auto"); thinkingMenu = false },
            )
            DropdownMenuItem(
                text = { Text("💭 关闭（更快更省）") },
                onClick = { appVm.setReasoningMode("off"); thinkingMenu = false },
            )
            DropdownMenuItem(
                text = { Text("💭 深度 max（更细致）") },
                onClick = { appVm.setReasoningMode("max"); thinkingMenu = false },
            )
        }
        TextButton(onClick = { modeMenu = true }, modifier = Modifier.height(32.dp)) {
            Text(
                "🛡 ${modeLabel(appVm.policyMode)} ▾",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(
            expanded = modeMenu,
            onDismissRequest = { modeMenu = false },
            properties = PopupProperties(focusable = false),
        ) {
            for (m in listOf("review", "auto", "yolo")) {
                DropdownMenuItem(
                    text = { Text(if (m == "review") "Review · 写操作全部询问" else if (m == "auto") "Auto · 大部分放行" else "Yolo · 最少询问") },
                    onClick = { appVm.setPolicyMode(m); modeMenu = false },
                )
            }
        }
    }
}

// ---------- 上下文/成本条（对应 PC 状态面板；两行信息） ----------

@Composable
private fun ContextBar(state: ChatUiState, onCompact: () -> Unit) {
    val ratio = state.contextRatio.coerceIn(0.0, 1.0)
    // 口径统一：主显示恒为会话累计（与成本页同源全量求和）；最近一步仅执行中附显
    val info = contextBarInfo(state.session, state.lastCost, state.running)
    val hitRate = info.hitRate
    val avgHitRate = info.avgHitRate
    val estTokens = state.session?.estimatedTokens() ?: 0L
    // 会话累计 tokens（缓存输入 + 非缓存输入 + 输出）
    val sessionTokens = (state.session?.totalHitTokens() ?: 0L) +
        (state.session?.totalMissTokens() ?: 0L) +
        (state.session?.totalCompletionTokens() ?: 0L)
    val lastTokens = state.lastCost?.tokens ?: 0L
    // 轮数：用户消息数（排除 [长期目标]/[记忆回顾]/[技能注入]/计划审批反馈 注入 —— 与引擎 isInjected 同口径）
    val rounds = state.session?.messages?.count {
        it.role == "user" &&
            !it.content.startsWith("[长期目标] ") &&
            !it.content.startsWith("[记忆回顾]") &&
            !it.content.startsWith("[技能注入] ") &&
            !AgentEngine.PLAN_FEEDBACK_PREFIXES.any { p -> it.content.startsWith(p) } // 二十二批：计划反馈排除
    } ?: 0
    val compactRatio = state.compactRatio
    val softRatio = (compactRatio - 0.3).coerceAtLeast(0.2)
    val barColor = when {
        ratio >= compactRatio -> MaterialTheme.colorScheme.error
        ratio >= softRatio -> Color(0xFFF59E0B) // 八批：与 Theme.WarnAmber 统一
        else -> MaterialTheme.colorScheme.primary
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "命中 ${UiFormats.percent(hitRate)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "· 均 ${UiFormats.percent(avgHitRate)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                info.primaryUsd,
                style = MaterialTheme.typography.labelSmall,
            )
            info.stepUsd?.let { step ->
                Text(
                    "本步 $step",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "${UiFormats.tokens(estTokens)}/1M",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            LinearProgressIndicator(
                progress = { ratio.toFloat() },
                modifier = Modifier.weight(1f).height(4.dp),
                color = barColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            TextButton(onClick = onCompact) {
                Text("压缩", style = MaterialTheme.typography.labelSmall)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "本次 ${UiFormats.tokens(lastTokens)} tok · 会话 ${UiFormats.tokens(sessionTokens)} tok · 第 $rounds 轮 · 用量 ${UiFormats.percent(ratio)} · 阈值 ${UiFormats.percent(softRatio)}/${UiFormats.percent(compactRatio)} · 工具 ${state.toolCount} 个" +
                    (if (state.approvals.isNotEmpty()) " · ⏳ 等待审批 ${state.approvals.size}" else "") +
                    (if (state.balanceText != null) " · 余额 ${state.balanceText}" else ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

// ---------- 消息流 ----------

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.MessageList(
    state: ChatUiState,
    onLongPress: (com.mlx.app.data.store.MessageRecord) -> Unit,
) {
    val listState = rememberLazyListState()
    val count = state.session?.messages?.size ?: 0
    // 二十二批：接近底部判定 —— 用户主动上滑查看历史（最后可见 item 距末尾 ≥2）时不抢滚动
    val nearBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= info.totalItemsCount - 2
        }
    }
    // 回合执行中：消息末尾有流式区（+1 偏移，七批：总览卡已并入底部任务区）；否则最新消息即末尾
    // 十批：key 只保留 count（剥离 streamingText.length —— 每 token 触发 300ms 滚动动画是卡顿主因之一）
    // 二十二批：滚到底部语义（scrollToItem 顶部对齐会把高于视口的流式/末条 item 钉在思维链头部，
    // offset=Int.MAX_VALUE 钳制到列表底部 = 最新文字/答案正文可见）
    LaunchedEffect(count) {
        if (count > 0 && nearBottom) {
            listState.animateScrollToItem(if (state.running) count else count - 1, Int.MAX_VALUE)
        }
    }
    // 十批：流式正文自动跟随（无动画直跳 + 200ms 节流 —— 不打断查看、不占主线程动画系统）
    // 二十二批：nearBottom 判定（用户上滑时不再被拉回）+ 滚到底部语义
    LaunchedEffect(state.streamingText.length) {
        if (state.running && count > 0 && nearBottom) {
            kotlinx.coroutines.delay(200)
            listState.scrollToItem(count, Int.MAX_VALUE) // streaming item 索引 = 消息数；offset 钳制=滚到底
        }
    }
    // 二十二批：推理流式同样跟随（此前只监听正文长度 —— 纯推理阶段零滚动，最新推理不可见；
    // 此时 streaming item 底部 = 最后展开段详情框底部 = 最新推理）
    LaunchedEffect(state.streamingReasoning.length) {
        if (state.running && count > 0 && nearBottom) {
            kotlinx.coroutines.delay(200)
            listState.scrollToItem(count, Int.MAX_VALUE)
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        state.session?.messages?.let { msgs ->
            // 回合轨迹卡（架构级 11）：用户消息后挂对应回合的步骤树（持久化复盘）；旧会话无 turns 回退平铺
            val turnsByNumber = state.session?.turns?.associateBy { it.turnNumber } ?: emptyMap()
            var userTurn = 0
            itemsIndexed(msgs) { _, m ->
                MessageRow(
                    m,
                    onLongPress = { onLongPress(m) },
                    toolOutputs = state.toolOutputs,
                    subagentStreams = state.subagentStreams,
                )
                if (m.role == "user" &&
                    !m.content.startsWith("[长期目标] ") &&
                    !m.content.startsWith("[记忆回顾]") &&
                    !m.content.startsWith("[技能注入] ") &&
                    !AgentEngine.PLAN_FEEDBACK_PREFIXES.any { p -> m.content.startsWith(p) } // 二十二批：计划反馈排除
                ) {
                    userTurn++
                    turnsByNumber[userTurn]?.let { turn ->
                        TurnTreeCard(turn)
                    }
                }
            }
        }
        if (state.running) {
            // 七批：执行中状态已由底部 AgentStatusBar 承担（当前任务/待办/状态行/排队），
            // 消息流仅保留流式正文（对齐 Claude CLI：历史 + 流式回复 + 底部任务区）
            item(key = "streaming") { StreamingBlock(state) }
        }
    }
}


@Composable
private fun StreamingBlock(state: ChatUiState) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        // 十四批：思维链树（推理流进树节点、当前段实时流式 —— 升级替换十批的"💭 思考中"+尾部 600 字纯文本）
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp)) {
                if (state.streamingReasoning.isNotBlank()) {
                    ThinkingTreeCard(
                        segments = remember(state.streamingReasoning) {
                            parseReasoningSegments(state.streamingReasoning, live = true)
                        },
                        live = true,
                        modifier = Modifier.padding(bottom = if (state.streamingText.isNotBlank()) 6.dp else 0.dp),
                    )
                }
                if (state.streamingText.isNotBlank()) {
                    Text(
                        state.streamingText + "▍",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                // 十五批：删除"等待执行结果"提示 —— 底部 AgentStatusBar 的波浪点+动作行已承担该语义（去重）
            }
        }
    }
}

@Composable
private fun MessageRow(
    m: MessageRecord,
    onLongPress: () -> Unit,
    toolOutputs: Map<String, List<String>> = emptyMap(), // 十批：工具实时输出（shell 日志）
    subagentStreams: Map<String, SubAgentStreamState> = emptyMap(), // 十一批：子代理实时流（过程可视化）
) {
    // 长按消息 → 操作菜单（复制/分支/回退）
    val longPressModifier = Modifier.pointerInput(m.id) {
        detectTapGestures(onLongPress = { onLongPress() })
    }
    when (m.role) {
        "user" -> {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .then(longPressModifier),
                horizontalArrangement = Arrangement.End,
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(0.86f),
                    shadowElevation = 1.dp,
                ) {
                    MarkdownText(
                        m.content,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
        "assistant" -> {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .then(longPressModifier),
            ) {
                // 十四批：思维链树（替换 ReasoningBlock 纯文本折叠 —— 按结构分段、段详情可展开）
                if (m.reasoning.isNotBlank()) {
                    ThinkingTreeCard(
                        segments = remember(m.reasoning) { parseReasoningSegments(m.reasoning, live = false) },
                        live = false,
                    )
                }
                if (m.content.isNotBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = MaterialTheme.shapes.medium,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        MarkdownText(m.content, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                    }
                }
                // 七批：工具调用整体收敛为折叠汇总条（用户只要结果 —— 细节点击展开，不复用平铺）
                // 十批：执行中（任一工具 RUNNING）强制展开 —— 让用户实时看到工具执行状态（对齐 Claude CLI 工具行实时滚动）
                if (m.toolCalls.isNotEmpty()) {
                    var toolsExpanded by remember(m.id) { mutableStateOf(false) }
                    val anyRunning = m.toolCalls.any { it.status == com.mlx.app.data.store.ToolStatus.RUNNING }
                    LaunchedEffect(anyRunning) {
                        if (anyRunning) toolsExpanded = true // 执行中强制展开；完成后保持用户状态
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { toolsExpanded = !toolsExpanded }
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "⚙ 工具调用（${m.toolCalls.size}）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            if (toolsExpanded) "▾" else "▸",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (toolsExpanded) {
                        m.toolCalls.forEach { tc ->
                            ToolCard(tc, outputs = toolOutputs[tc.id] ?: emptyList(), stream = subagentStreams[tc.id])
                        }
                    }
                }
            }
        }
        else -> Unit // 七批：tool 结果行不再平铺（结果文本在工具卡展开区已有，平铺是噪音）
    }
}

@Composable
private fun ToolCard(
    tc: ToolCallRecord,
    outputs: List<String> = emptyList(),
    stream: SubAgentStreamState? = null, // 十一批：子代理实时流（过程可视化；仅 subagent/planner 携带）
) {
    val color = when (tc.status) {
        ToolStatus.RUNNING -> MaterialTheme.colorScheme.primary
        ToolStatus.SUCCESS -> Color(0xFF34A853)
        ToolStatus.FAILED -> MaterialTheme.colorScheme.error
        ToolStatus.APPROVAL_REQUIRED -> Color(0xFFF59E0B) // 八批：与 Theme.WarnAmber/步骤树一致
        ToolStatus.DENIED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    // 十批：执行中（RUNNING）详情默认展开 —— 实时可见参数与输出；完成后恢复折叠
    var expanded by remember { mutableStateOf(tc.status == ToolStatus.RUNNING) }
    LaunchedEffect(tc.status == ToolStatus.RUNNING) {
        if (tc.status == ToolStatus.RUNNING) expanded = true
    }
    // 十五批：子代理详情弹窗（原入口在 TodoSheet"当前工具/子代理"块，随去重整合移入消息流工具卡）
    var showSubAgentDetail by remember(tc.id) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (tc.status == ToolStatus.RUNNING) {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                } else {
                    Box(
                        Modifier
                            .size(10.dp)
                            .background(color, CircleShape)
                    )
                }
                Spacer(Modifier.width(8.dp))
                // 六批：意图优先主文案（对齐 Claude Code"正在做什么"），工具名降为小字后缀
                Text(
                    tc.intent.ifBlank { toolActionLabel(tc.name) },
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    tc.name,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp),
                    maxLines = 1,
                )
                Text(tc.status.label, style = MaterialTheme.typography.labelSmall, color = color)
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "收起" else "详情", style = MaterialTheme.typography.labelSmall)
                }
            }
            if (expanded) {
                // 十一批：子代理实时流（执行中展示思考链尾部 + 正文；完成后 resultText 兜底）
                if (tc.status == ToolStatus.RUNNING && stream != null &&
                    (stream.reasoning.isNotBlank() || stream.content.isNotBlank())
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        if (stream.reasoning.isNotBlank()) {
                            Text(
                                "💭 " + stream.reasoning.takeLast(600),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (stream.content.isNotBlank()) {
                            Text(
                                stream.content + "▍",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                // 十五批：子代理/规划者"查看执行全程"入口（打开详情弹窗看完整过程）
                if (tc.name == "subagent" || tc.name == "planner") {
                    Text(
                        "查看执行全程 ▸",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .clickable { showSubAgentDetail = true },
                    )
                }
                // 十批：实时输出行（shell/bash 等工具执行中的 stdout/stderr —— 此前数据存了但无 UI 消费）
                if (outputs.isNotEmpty()) {
                    Text(
                        outputs.takeLast(40).joinToString("\n"),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 120.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
                Text(
                    "参数: ${tc.argsJson.take(400)}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (tc.diffText.isNotBlank()) {
                    DiffText(tc.diffText)
                }
                if (tc.resultText.isNotBlank()) {
                    Text(
                        tc.resultText,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()),
                    )
                }
            }
        }
    }
    // 十五批：子代理详情弹窗（数据 = 工具卡本身 + 流，直接挂载于卡片内）
    if (showSubAgentDetail) {
        SubAgentDetailSheet(
            rec = tc,
            stream = stream,
            onDismiss = { showSubAgentDetail = false },
        )
    }
}

@Composable
private fun DiffText(diff: String) {
    Column(
        Modifier
            .padding(vertical = 4.dp)
            .heightIn(max = 200.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            "— Diff —",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (line in diff.lines().take(80)) {
            val c = when {
                line.startsWith("- ") -> MaterialTheme.colorScheme.error
                line.startsWith("+ ") -> Color(0xFF34A853)
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = c)
        }
    }
}

// ---------- 输入区 ----------

/**
 * 九批：粘贴折叠检测（纯函数可单测）—— input 增量超阈值判定为粘贴，返回折叠摘要。
 * 正常打字/删除的增量远小于阈值；一次 onValueChange 变化超阈值只可能是粘贴。
 * @return "📋 已粘贴文字 +N行（M 字符）"（N=粘贴后总行数，M=全文长度，中文逐字计数）；未命中返回 null
 */
fun pasteCollapseInfo(prev: String, cur: String, charThreshold: Int = 500, lineThreshold: Int = 15): String? {
    val deltaChars = cur.length - prev.length
    val deltaLines = cur.count { it == '\n' } - prev.count { it == '\n' }
    if (deltaChars <= 0 && deltaLines <= 0) return null // 删除/清空等非粘贴
    if (deltaChars <= charThreshold && deltaLines <= lineThreshold) return null
    val totalLines = cur.count { it == '\n' } + 1
    return "📋 已粘贴文字 +${totalLines}行（${cur.length} 字符）"
}

/** 九批：粘贴预览对话框（滚动查看全文 + 发送全文 / 展开编辑 / 删除） */
@Composable
private fun PastePreviewDialog(
    text: String,
    onSend: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 480.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("粘贴预览（${text.length} 字符）", style = MaterialTheme.typography.titleMedium)
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = onEdit) { Text("展开编辑") }
                    Button(onClick = onSend) { Text("发送全文") }
                }
            }
        }
    }
}

/** 消息长按操作弹层：复制 / 从这里分支 / 回退到此处 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageActionsSheet(
    message: MessageRecord,
    messageIndex: Int,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onBranch: () -> Unit,
    onRewind: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                message.content.take(40).ifBlank { message.role },
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 8.dp),
                maxLines = 2,
            )
            OutlinedButton(onClick = onCopy, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text("复制内容", modifier = Modifier.weight(1f))
            }
            OutlinedButton(onClick = onBranch, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text("从这里分支（新会话，从此处继续）", modifier = Modifier.weight(1f))
            }
            if (message.role == "user" && messageIndex >= 0) {
                OutlinedButton(
                    onClick = onRewind,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                ) {
                    Text("回退到此处（恢复文件 + 截断对话）", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/**
 * 输入行：[/] 指令面板 · [+] 附加文件 · 输入框 · 发送/终止。
 * - 执行中/排队时发送按钮右侧显示红色"终止"按钮（停止全部任务）
 * - 输入始终可用：执行中发送进入排队（不再静默丢弃）
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun InputRow(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    running: Boolean,
    aborting: Boolean,
    queuedCount: Int,
    focusRequester: androidx.compose.ui.focus.FocusRequester,
    pasteSummary: String? = null, // 九批：粘贴折叠摘要（非空时输入框显示折叠卡片）
    onPastePreview: () -> Unit = {},
    onPasteClear: () -> Unit = {},
    onSend: () -> Unit,
    onAbortAll: () -> Unit,
    onExpand: () -> Unit,
    onAttach: () -> Unit,
    onInputSlash: () -> Unit,
    onPickSkill: () -> Unit,
) {
    // 输入 / 开头 → 自动唤出指令面板
    LaunchedEffect(value) {
        if (value.startsWith("/") && value.length == 1) {
            onInputSlash()
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // [+] 附加文件：短按打开文件工作台选择回填；长按唤出技能选择器（优化 4a）
        // 注：指令面板入口已移除（移动端非 CLI，所有功能有开关入口）；
        // 仍可在输入框输入 / 开头自动唤出（见上方 LaunchedEffect(value)）；
        // 用 Box+combinedClickable 而非 IconButton（内层 clickable 会与长按手势竞争）
        Box(
            modifier = Modifier
                .size(36.dp)
                .combinedClickable(
                    onClick = onAttach,
                    onLongClick = onPickSkill,
                    onLongClickLabel = "附加技能",
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = "附加文件（长按附加技能）",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 九批：粘贴折叠态 —— 输入框替换为摘要卡片（📋 已粘贴文字 +N行（M 字符）+ ▸ 预览 + ✕ 删除）
        if (pasteSummary != null) {
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    pasteSummary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "▸ 预览",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable(onClick = onPastePreview)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
                Text(
                    "✕",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clickable(onClick = onPasteClear)
                        .padding(start = 4.dp, top = 2.dp, bottom = 2.dp),
                )
            }
        } else {
        // 输入框：多行（1-5 行自适应，回车=换行，发送仅靠按钮）；
        // 紧凑高度（单行 40dp ≈ 24sp 行高 + 对称 8dp padding，文字垂直居中）；⬍ 最大化在框内右缘。
        // 注：M3 1.3 OutlinedTextField 无 contentPadding 参数 → 自绘边框 + BasicTextField 精确控制高度
        val inputInteraction = remember { MutableInteractionSource() }
        val inputFocused by inputInteraction.collectIsFocusedAsState()
        val inputBorderColor = if (inputFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        Box(
            // 高度由 BasicTextField 内容决定（heightIn 在其上）；此处仅画边框背景
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .border(1.dp, inputBorderColor, RoundedCornerShape(12.dp)),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                interactionSource = inputInteraction,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 24.sp,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                maxLines = 5,
                // 注意：不能用 fillMaxSize（会撑满父最大约束）；heightIn 必须在此
                // （单行 40dp ≈ 24sp 行高 + 对称 8dp padding，文字垂直居中）
                modifier = Modifier
                    .fillMaxWidth()
                    // 审查修复：命令回填后请求焦点（光标落输入框）
                    .focusRequester(focusRequester)
                    .heightIn(min = 40.dp, max = 140.dp)
                    .padding(start = 12.dp, end = 40.dp, top = 8.dp, bottom = 8.dp),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth()) {
                        if (value.isEmpty()) {
                            Text(
                                "输入任务…",
                                // 行高与输入文字对齐（24sp），否则 placeholder 在 40dp 内容区内偏上
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 24.sp,
                                ),
                            )
                        }
                        inner()
                    }
                },
            )
            // 最大化输入（⬍）—— 输入框内右缘，不占输入框外宽度
            IconButton(onClick = onExpand, modifier = Modifier.align(Alignment.CenterEnd).size(32.dp)) {
                Text("⬍", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        } // 九批：粘贴折叠态分支结束
        Spacer(Modifier.width(6.dp))
        // C1：终止按钮（执行中或排队时可见）—— 发送按钮左侧的红色方框键；
        // 已请求停止 → spinner 替代（即时反馈"正在停止"，防重复点击）
        if (running || queuedCount > 0) {
            if (aborting) {
                // 十九批：转圈 + "正在停止…"文字（对齐 ExpandedInputDialog 文案 —— 原只有裸转圈，用户不知道在停止）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.height(36.dp).padding(horizontal = 2.dp),
                ) {
                    CircularProgressIndicator(
                        Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "正在停止…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                IconButton(
                    onClick = onAbortAll,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "终止所有任务",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        // 发送：蓝色圆形向上箭头（disabled 半透明）
        val sendColor = MaterialTheme.colorScheme.primary
        IconButton(
            onClick = onSend,
            enabled = enabled && value.isNotBlank(),
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(sendColor)
                .alpha(if (enabled && value.isNotBlank()) 1f else 0.4f),
        ) {
            Icon(
                Icons.Filled.KeyboardArrowUp,
                contentDescription = "发送",
                tint = Color.White,
            )
        }
    }
}

/**
 * C2：最大化输入对话框（⬍ 全屏）—— 大编辑区（等宽字体、不限行数、垂直滚动），
 * 底部发送/终止按钮，顶部收起。与外层输入框共享同一 value 状态（输入实时同步）。
 */
@Composable
private fun ExpandedInputDialog(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    running: Boolean,
    aborting: Boolean,
    queuedCount: Int,
    onSend: () -> Unit,
    onAbortAll: () -> Unit,
    onCollapse: () -> Unit,
) {
    Dialog(
        onDismissRequest = onCollapse,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
        ) {
            // 顶部栏：收起 ⬍ + 标题
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onCollapse) {
                    Text("⬎", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                }
                Text(
                    "输入任务",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (queuedCount > 0) {
                    Text(
                        "⏳ ${queuedCount} 条排队",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            // 大编辑区（多行、等宽字体、垂直滚动）
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = { Text("输入任务…（回车换行，点发送发送）") },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 15.sp,
                ),
            )
            // 底部操作行：终止（执行中/排队时）+ 发送
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (running || queuedCount > 0) {
                    if (aborting) {
                        // 已请求停止：spinner + 文案（即时反馈）
                        CircularProgressIndicator(
                            Modifier.size(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("正在停止…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        TextButton(onClick = onAbortAll) {
                            Text("■ 终止", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Button(onClick = onSend, enabled = enabled && value.isNotBlank()) {
                    Icon(Icons.Filled.Send, contentDescription = "发送")
                    Spacer(Modifier.width(4.dp))
                    Text("发送")
                }
            }
        }
    }
}
