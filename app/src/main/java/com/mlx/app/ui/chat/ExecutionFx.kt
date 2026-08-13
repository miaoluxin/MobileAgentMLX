package com.mlx.app.ui.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mlx.app.core.agent.fmtSecs
import com.mlx.app.data.store.ToolStatus
import kotlinx.coroutines.delay

/**
 * 执行状态动效基件（文档 2.8 补充需求：两处动效，统一视觉）：
 * - ① 底部常驻执行指示条（输入框上方、最新消息下方）
 * - ② to do list 当前项脉冲高亮
 * （原"等待执行结果"提示已并入底部指示条 —— 十五批去重整合）
 */

/** 三点波浪等待动画（. . . 依次亮起，模拟打字机呼吸感） */
@Composable
fun LoadingDots(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val transition = rememberInfiniteTransition(label = "loading_dots")
    // 三点依次亮起（相位错开 130ms/260ms），各自独立动画
    val a1 = transition.animateFloat(
        initialValue = 0.25f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(380), RepeatMode.Reverse, initialStartOffset = StartOffset(0)),
        label = "dot_0",
    )
    val a2 = transition.animateFloat(
        initialValue = 0.25f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(380), RepeatMode.Reverse, initialStartOffset = StartOffset(130)),
        label = "dot_130",
    )
    val a3 = transition.animateFloat(
        initialValue = 0.25f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(380), RepeatMode.Reverse, initialStartOffset = StartOffset(260)),
        label = "dot_260",
    )
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        listOf(a1, a2, a3).forEach { a ->
            Text(".", style = MaterialTheme.typography.titleMedium, color = color.copy(alpha = a.value))
        }
    }
}

/**
 * 工具名 → 人类可读动作文案（纯函数可单测；六批扩展：意图体系兜底 + 总览/工具链/步骤树统一复用）。
 * 意图描述缺失时 UI 各渲染点统一回退此映射，杜绝裸工具名。
 */
fun toolActionLabel(name: String): String = when {
    // read_skill 必须在 startsWith("read") 之前（后者前缀会吞掉它）
    name == "read_skill" -> "正在读取技能说明…"
    name == "list_files" || name.startsWith("read") -> "正在读取文件…"
    name == "write_file" -> "正在写入文件…"
    name.startsWith("edit") || name == "multi_edit" -> "正在编辑文件…"
    name == "bash_output" || name == "shell" -> "正在执行命令…"
    name == "python_exec" -> "正在运行 Python…"
    name == "web_search" || name == "web_fetch" -> "正在检索网络…"
    name == "grep" || name == "glob" -> "正在搜索文件…"
    name == "todo_add" || name == "todo_list" -> "正在整理任务清单…"
    name == "todo_complete" || name == "complete_step" -> "正在更新任务进度…"
    name == "submit_plan" || name == "planner" -> "正在规划方案…"
    name == "choice" || name == "ask" -> "正在等待你的选择…"
    name == "run_skill" -> "正在执行技能…"
    name == "install_skill" -> "正在安装技能…"
    name == "subagent" || name == "explore" || name == "research" -> "正在并行调研…"
    name == "review" -> "正在审查方案…"
    name == "wait_job" -> "正在等待后台任务…"
    name == "remember" -> "正在保存记忆…"
    name == "forget" -> "正在清理记忆…"
    name == "update_goal" -> "正在更新目标…"
    name == "code_index" -> "正在建立索引…"
    name == "move_file" -> "正在移动文件…"
    name == "delete_range" -> "正在删除内容…"
    else -> "正在执行 $name…"
}

/** 当前动作文案（纯函数可单测）：六批 intent 优先 —— 工具携带意图描述时直接展示（对齐 Claude Code） */
fun runningActionLabel(state: ChatUiState): String {
    val running = state.activeTurnTools.lastOrNull { it.status == ToolStatus.RUNNING }
        ?: return if (state.todos.isEmpty()) "正在思考…" else "正在执行…"
    return running.intent.ifBlank { toolActionLabel(running.name) }
}

/** 并行子代理计数（九批纯函数可单测）：当前 RUNNING 的 subagent/planner 数量 */
fun parallelSubagentCount(tools: List<com.mlx.app.data.store.ToolCallRecord>): Int =
    tools.count { it.status == ToolStatus.RUNNING && (it.name == "subagent" || it.name == "planner") }

/** 子代理流增量累积（纯函数可单测）：双字段追加 + 尾部上限（展示最新进度用尾；最终结果走 resultText） */
fun appendSubAgentDelta(
    state: SubAgentStreamState,
    content: String?,
    reasoning: String?,
    now: Long = System.currentTimeMillis(),
    capContent: Int = 8192,
    capReasoning: Int = 4096,
): SubAgentStreamState = SubAgentStreamState(
    content = if (content.isNullOrEmpty()) state.content else (state.content + content).takeLast(capContent),
    reasoning = if (reasoning.isNullOrEmpty()) state.reasoning else (state.reasoning + reasoning).takeLast(capReasoning),
    updatedAt = now,
)

/** 事件→状态映射（纯函数可单测）：子代理增量累积进 uiState.subagentStreams（VM 100ms flush 复用） */
fun applySubAgentDelta(
    uiState: ChatUiState,
    callId: String,
    content: String?,
    reasoning: String?,
    now: Long = System.currentTimeMillis(),
): ChatUiState {
    if (content == null && reasoning == null) return uiState
    val prev = uiState.subagentStreams[callId] ?: SubAgentStreamState()
    return uiState.copy(subagentStreams = uiState.subagentStreams + (callId to appendSubAgentDelta(prev, content, reasoning, now)))
}

/**
 * 状态行动作（九批纯函数可单测）：并行子任务 ≥2 时显示 "● N 个并行子任务"（对齐 Claude CLI "3 background agents launched"），
 * 否则回退现有动作文案（intent 优先）。单个子代理仍显示其动作（"正在并行调研…"）。
 */
fun statusBarAction(state: ChatUiState): String {
    val n = parallelSubagentCount(state.activeTurnTools)
    if (n >= 2) return "● $n 个并行子任务"
    return runningActionLabel(state)
}

/**
 * ① 底部常驻任务区（七批，对齐 Claude Code CLI：执行时会话最下方固定渲染）：
 * - 有待办：当前任务（◼ 粗体，复用 CurrentTodoRow 脉冲高亮）+ 状态行（耗时 · ↓tokens · 动作）
 *   + 未完成待办列表（◻，最多 4 条超出折叠）+ 完成项折叠行（… +N completed）+ 排队行
 * - 无待办：退化为动作条（spinner + 动作文案 + 波浪点 + 计时，即原 ExecutionIndicatorBar）
 * - 点击整块打开 TodoSheet 弹层（查看/添加/勾选）；回合结束自动消失
 */
@Composable
fun AgentStatusBar(
    state: ChatUiState,
    onOpenTodo: () -> Unit,
    onCancelQueued: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var elapsed by remember { mutableStateOf(0L) }
    LaunchedEffect(state.running) {
        val start = System.currentTimeMillis()
        while (state.running) {
            elapsed = System.currentTimeMillis() - start
            delay(1000)
        }
    }
    val undone = state.todos.filter { !it.done }
    val current = undone.firstOrNull()
    val done = doneCount(state.todos)
    val (visible, overflow) = visibleTodos(state.todos)
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth(),
    ) {
        if (undone.isEmpty() && done == 0) {
            // 无待办：退化动作条（原 ExecutionIndicatorBar 行为）
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(statusBarAction(state), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(4.dp))
                LoadingDots(color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text(
                    "${elapsed / 1000}s",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Column(
                Modifier
                    .clickable { onOpenTodo() }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .heightIn(max = 140.dp),
            ) {
                // Row1：当前任务（◼ 前缀粗体 + 脉冲；无未完成时显示当前动作）
                CurrentTodoRow(text = current?.text ?: statusBarAction(state))
                // Row2：状态行（耗时 · ↓tokens · 动作；对齐 Claude "5m 55s · ↓ 51.8k tokens · thinking"）
                Text(
                    statusBarStateLine(elapsed / 1000, statusBarAction(state)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 2.dp),
                )
                // 未完成待办列表（◻；当前项已在 Row1 高亮，这里从第二项起）
                visible.filter { it.id != current?.id }.forEach { todo ->
                    Row(
                        Modifier.padding(start = 16.dp, top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("◻", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            todo.text,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(start = 6.dp),
                        )
                    }
                }
                // 未完成超出折叠
                if (overflow > 0) {
                    Text(
                        "… +$overflow 更多",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 2.dp),
                    )
                }
                // 完成项折叠行（对齐 Claude "… +N completed"）
                if (done > 0) {
                    Text(
                        "… +$done completed",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 2.dp),
                    )
                }
                // 排队指令（移入任务区：可见可逐条取消；最多展开 2 条超出折叠）
                if (state.queuedMessages.isNotEmpty()) {
                    Text(
                        "⏳ ${state.queuedMessages.size} 条排队指令",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 2.dp),
                    )
                    state.queuedMessages.take(2).forEach { q ->
                        Row(
                            Modifier.padding(start = 16.dp, top = 1.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                q.text,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "取消",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.clickable { onCancelQueued(q.id) }.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    if (state.queuedMessages.size > 2) {
                        Text(
                            "… +${state.queuedMessages.size - 2} 更多",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, top = 1.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 状态行文案（七批纯函数可单测）：耗时 · 动作（对齐 Claude 状态行；tokens 统一由顶部 ContextBar 展示） */
fun statusBarStateLine(elapsedSecs: Long, action: String): String {
    val parts = mutableListOf(fmtSecs(elapsedSecs))
    parts += action
    return parts.joinToString(" · ")
}

/** 未完成待办取前 max 条 + 超出折叠数（七批纯函数可单测；完成项另行折叠计数） */
fun visibleTodos(todos: List<com.mlx.app.core.tools.AuxTools.TodoStore.Todo>, max: Int = 4): Pair<List<com.mlx.app.core.tools.AuxTools.TodoStore.Todo>, Int> {
    val undone = todos.filter { !it.done }
    return if (undone.size > max) undone.take(max) to (undone.size - max) else undone to 0
}

/** 已完成待办数（七批纯函数可单测；折叠行 … +N completed） */
fun doneCount(todos: List<com.mlx.app.core.tools.AuxTools.TodoStore.Todo>): Int = todos.count { it.done }

/**
 * ② to do list 当前项脉冲高亮（首条未完成项：alpha 呼吸 + 左侧主色竖条）：
 * 让"现在正在做哪一步"一目了然。
 */
@Composable
fun CurrentTodoRow(
    text: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    val transition = rememberInfiniteTransition(label = "current_todo")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "current_todo_alpha",
    )
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            color = accent,
            modifier = Modifier.size(width = 3.dp, height = 14.dp),
            shape = MaterialTheme.shapes.extraSmall,
        ) {}
        Text(
            " ▶ $text",
            style = MaterialTheme.typography.labelSmall,
            color = accent.copy(alpha = alpha),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
