package com.mlx.app.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mlx.app.core.cost.aggregateByModel
import com.mlx.app.core.cost.buildModelDayTrees
import com.mlx.app.core.cost.cachedSavings
import com.mlx.app.core.cost.highMissTurns
import com.mlx.app.core.cost.modelSwitchTurns
import com.mlx.app.core.cost.turnSeries
import com.mlx.app.data.store.Session
import com.mlx.app.ui.AppViewModel
import com.mlx.app.ui.UiFormats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

private const val DAY_MS = 86_400_000L

/**
 * 成本统计面板（对应文档 5.10 节）：
 * 余额展示（/user/balance）· 时间范围（今日/7日/30日/自定义）· ¥ 单位 · 横向堆叠 token 图。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun StatsScreen(vm: AppViewModel) {
    // 明细折叠状态（持久化：切 tab/重启恢复；默认全部展开）
    val expandedSaver = remember {
        Saver<Set<String>, ArrayList<String>>(
            save = { ArrayList(it) },
            restore = { it.toSet() },
        )
    }
    var expandedProjects by rememberSaveable(stateSaver = expandedSaver) { mutableStateOf(emptySet()) }
    var userToggledProjects by rememberSaveable { mutableStateOf(false) }
    var showClearAllDialog by remember { mutableStateOf(false) }
    var sessions by remember { mutableStateOf<List<Session>>(emptyList()) }
    var range by remember { mutableStateOf("7d") }
    var customStart by remember { mutableStateOf<Long?>(null) }
    var customEnd by remember { mutableStateOf<Long?>(null) }
    var pickTarget by remember { mutableStateOf("start") } // start | end
    var showDatePicker by remember { mutableStateOf(false) }
    var balanceText by remember { mutableStateOf("加载中…") }
    val datePickerState = rememberDatePickerState()
    val scope = rememberCoroutineScope()
    val dateFmt = remember { SimpleDateFormat("MM-dd", Locale.getDefault()) }

    // 清理反馈（先消费再展示，防重复弹）
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(vm.projectOpMsg) {
        vm.projectOpMsg?.let { msg ->
            vm.consumeProjectOpMsg()
            snackbarHostState.showSnackbar(msg, withDismissAction = true)
        }
    }

    LaunchedEffect(vm.refreshTick) {
        sessions = vm.container.sessionStore.list()
        scope.launch { balanceText = vm.loadBalanceText() }
        // 默认全部展开（用户主动折叠后不再干预）
        if (!userToggledProjects && expandedProjects.isEmpty()) {
            expandedProjects = vm.realProjects.map { it.id }.toSet()
        }
    }

    val now = System.currentTimeMillis()
    val (rangeStart, rangeEnd) = when (range) {
        "today" -> {
            val cal = java.util.Calendar.getInstance()
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
            cal.timeInMillis to now
        }
        "30d" -> (now - 30 * DAY_MS) to now
        "custom" -> (customStart ?: (now - 7 * DAY_MS)) to (customEnd ?: now)
        else -> (now - 7 * DAY_MS) to now
    }
    val rangeCosts = sessions.flatMap { it.costs }.filter { it.at in rangeStart..rangeEnd }
    val rangeTotal = rangeCosts.sumOf { it.costUsd }
    val totalCost = sessions.sumOf { it.totalCostUsd() }
    val totalHit = sessions.sumOf { it.totalHitTokens() }
    val totalMiss = sessions.sumOf { it.totalMissTokens() }
    val overallHitRate = if (totalHit + totalMiss > 0) totalHit.toDouble() / (totalHit + totalMiss) else null
    val budget = vm.budgetUsd
    val days = ((rangeEnd - rangeStart) / DAY_MS).toInt().coerceIn(1, 31)
    val daily = (0 until days).map { offset ->
        val start = rangeStart + offset * DAY_MS
        val end = start + DAY_MS
        rangeCosts.filter { it.at in start until end }.sumOf { it.costUsd }
    }
    val maxDaily = daily.maxOrNull()?.coerceAtLeast(0.0001) ?: 0.0001

    Box(Modifier.fillMaxSize()) {
    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
    ) {
        // 页签标题（文档 2.11：页头主标题 + 内容从第二行起排）
        item {
            com.mlx.app.ui.components.PageHeader(
                title = "统计",
                subtitle = "成本、缓存命中与模型维度分析",
            )
            Spacer(Modifier.height(4.dp))
        }
        // 余额卡
        item {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("账户余额", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(balanceText, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = {
                        scope.launch { balanceText = vm.loadBalanceText() }
                    }) { Text("↻") }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("成本概览", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Row {
                        StatCell("区间成本", UiFormats.usd(rangeTotal))
                        Spacer(Modifier.width(16.dp))
                        StatCell("累计", UiFormats.usd(totalCost))
                        Spacer(Modifier.width(16.dp))
                        StatCell("缓存命中", UiFormats.percent(overallHitRate))
                    }
                    if (budget > 0) {
                        Spacer(Modifier.height(12.dp))
                        val ratio = (totalCost / budget).coerceIn(0.0, 1.0)
                        Text(
                            "预算 ${UiFormats.usd(totalCost)} / ${UiFormats.usd(budget)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (ratio >= 1f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LinearProgressIndicator(
                            progress = { ratio.toFloat() },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            color = if (ratio >= 1f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                    }
                    // 时间范围选择
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for ((key, label) in listOf("today" to "今日", "7d" to "近 7 日", "30d" to "近 30 日", "custom" to "自定义")) {
                            FilterChip(
                                selected = range == key,
                                onClick = { range = key },
                                label = { Text(label) },
                            )
                        }
                    }
                    if (range == "custom") {
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { pickTarget = "start"; showDatePicker = true }) {
                                Text("起：${customStart?.let { dateFmt.format(Date(it)) } ?: "选择"}")
                            }
                            Text("—", style = MaterialTheme.typography.labelMedium)
                            TextButton(onClick = { pickTarget = "end"; showDatePicker = true }) {
                                Text("止：${customEnd?.let { dateFmt.format(Date(it)) } ?: "选择"}")
                            }
                        }
                    }
                    // 近 N 日成本柱状图（¥，官网 CNY 计价）
                    Spacer(Modifier.height(8.dp))
                    Text("近 $days 日成本（¥）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    val barColor = MaterialTheme.colorScheme.primary
                    Canvas(Modifier.fillMaxWidth().height(90.dp)) {
                        val w = size.width
                        val h = size.height
                        val barW = w / (days + 0.4f)
                        daily.forEachIndexed { i, cost ->
                            val barH = (cost / maxDaily * (h - 8f)).toFloat().coerceAtLeast(2f)
                            val x = i * (w / days) + (w / days - barW) / 2
                            drawRoundRect(
                                color = barColor.copy(alpha = 0.75f),
                                topLeft = Offset(x, h - barH),
                                size = Size(barW, barH),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f),
                            )
                        }
                    }
                    // token 构成：横向堆叠柱状图
                    Spacer(Modifier.height(10.dp))
                    TokenStackBar(hit = totalHit, miss = totalMiss, out = sessions.sumOf { it.totalCompletionTokens() })
                    // 模型分区（文档 2.6.2：按模型分组 + 三档可展开；数据来自 costs 每轮 model，准确反映混用）
                    val rangeBreakdowns = aggregateByModel(rangeCosts)
                    if (rangeBreakdowns.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("模型分区（区间）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        var expandedModel by remember { mutableStateOf<String?>(null) }
                        rangeBreakdowns.forEach { b ->
                            val expanded = expandedModel == b.group
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { expandedModel = if (expanded) null else b.group }
                                    .padding(vertical = 3.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        if (expanded) "▾" else "▸",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        "${b.group} ${UiFormats.usd(b.cost)}（${UiFormats.percent(b.cost / rangeTotal.coerceAtLeast(0.0001))}）",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                                    )
                                }
                                if (expanded) {
                                    // 三档分列（命中/未命中/输出 token + 金额；单价逐条按模型复算；
                                    // 审查修复：金额精度统一走 UiFormats.usd）
                                    Text(
                                        "命中 ${UiFormats.tokens(b.hitTokens)}（${UiFormats.usd(b.hitCost)}）",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 14.dp),
                                    )
                                    Text(
                                        "未命中 ${UiFormats.tokens(b.missTokens)}（${UiFormats.usd(b.missCost)}）",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 14.dp),
                                    )
                                    Text(
                                        "输出 ${UiFormats.tokens(b.completionTokens)}（${UiFormats.usd(b.outputCost)}）",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 14.dp),
                                    )
                                }
                            }
                        }
                        val totalSavings = cachedSavings(rangeCosts)
                        if (totalSavings > 0.0001) {
                            Text(
                                "缓存节省 ¥%.2f（若无缓存按未命中计价的差额）".format(totalSavings),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF34A853),
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }
        }
        // ---- 会话明细：工程 → 会话 树状（可折叠；未分组兜底，与会话页同模式） ----
        // 显式按最新交互时间倒序（组内最新在前）
        val rangeSessions = sessions
            .filter { s -> s.costs.any { it.at in rangeStart..rangeEnd } }
            .sortedByDescending { it.updatedAt }
        val projects = vm.realProjects
        val knownIds = projects.map { it.id }.toSet()
        val byProject = rangeSessions.groupBy { it.projectId }
        val orphan = rangeSessions.filter { it.projectId.isEmpty() || it.projectId == "saf" || it.projectId !in knownIds }
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "会话明细（区间内 ${rangeSessions.size}）",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                // 顶层：一键清除所有成本（影响全部会话 → 确认对话框）
                TextButton(onClick = { showClearAllDialog = true }) {
                    Text("清除全部", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        // 工程节点 → 会话
        for (p in projects) {
            val pSessions = byProject[p.id].orEmpty()
            if (pSessions.isEmpty()) continue
            val pCost = pSessions.sumOf { s -> s.costs.filter { it.at in rangeStart..rangeEnd }.sumOf { it.costUsd } }
            val expanded = p.id in expandedProjects
            item(key = "p_${p.id}") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = {
                                userToggledProjects = true
                                expandedProjects = if (expanded) expandedProjects - p.id else expandedProjects + p.id
                            },
                        )
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (expanded) "▾" else "▸", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        // 工程名：会话快照优先，注册表兜底
                        "📁 ${pSessions.firstOrNull { it.projectName.isNotBlank() }?.projectName ?: p.name}（${pSessions.size} · ${UiFormats.usd(pCost)}）",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        modifier = Modifier.weight(1f).padding(start = 6.dp),
                        maxLines = 1,
                    )
                    // 工程级：一键清理该工程全部成本记录（独立点击区，不与折叠冲突）
                    TextButton(onClick = { vm.clearCosts(p.id) }) {
                        Text("清理", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    }
                }
                androidx.compose.material3.HorizontalDivider(Modifier.padding(horizontal = 6.dp))
            }
            if (expanded) {
                items(pSessions, key = { "ps_${it.id}" }) { s -> SessionCostCard(s) }
            }
        }
        // 未分组节点（pid 空/saf/不在注册表）
        if (orphan.isNotEmpty()) {
            val orphanExpanded = "orphan" in expandedProjects
            item(key = "p_orphan") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = {
                                userToggledProjects = true
                                expandedProjects = if (orphanExpanded) expandedProjects - "orphan" else expandedProjects + "orphan"
                            },
                        )
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (orphanExpanded) "▾" else "▸", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "📁 未分组（${orphan.size}）",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        modifier = Modifier.weight(1f).padding(start = 6.dp),
                        maxLines = 1,
                    )
                }
                androidx.compose.material3.HorizontalDivider(Modifier.padding(horizontal = 6.dp))
            }
            if (orphanExpanded) {
                items(orphan, key = { "po_${it.id}" }) { s -> SessionCostCard(s) }
            }
        }
        if (rangeSessions.isEmpty()) {
            item {
                Text(
                    "区间内无会话成本",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        // 双树状图（架构级 14 / 文档 2.6.2 第 2 条：区间内按模型分组 模型→日期→会话→轮次）
        if (rangeCosts.isNotEmpty()) {
            item {
                Text(
                    "成本树状图（区间 · 按模型分组）",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                )
            }
            item {
                CostTree(buildModelDayTrees(rangeCosts))
            }
            // TOP 5 会话成本排行（2.6.2 第 6 条：引导拆分长会话）
            item {
                val top5 = rangeSessions.sortedByDescending { s ->
                    s.costs.filter { it.at in rangeStart..rangeEnd }.sumOf { it.costUsd }
                }.take(5)
                if (top5.isNotEmpty()) {
                    Text(
                        "TOP 5 会话（按成本降序）",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                    )
                    top5.forEachIndexed { i, s ->
                        val c = s.costs.filter { it.at in rangeStart..rangeEnd }.sumOf { it.costUsd }
                        Text(
                            "${i + 1}. ${s.title.take(24)} — ${UiFormats.usd(c)}（${s.costs.count { it.at in rangeStart..rangeEnd }} 轮）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        // 统计口径说明 + 价格表折叠区（文档 2.6.2 第 6 条：公示全部计算规则，两页同源可解释）
        item {
            var showPricing by remember { mutableStateOf(false) }
            Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { showPricing = !showPricing }
                        .padding(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "统计口径与价格表",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        Text(if (showPricing) "▾" else "▸", style = MaterialTheme.typography.titleSmall)
                    }
                    if (showPricing) {
                        Spacer(Modifier.height(8.dp))
                        Text("【计费公式】成本 = 命中输入×P₁ + 未命中输入×P₂ + 输出×P₃（每百万 token，CNY）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("· 输入 token 分「命中/未命中」两档，价格差 50~120 倍（命中率高 ≠ 成本低，看未命中绝对量）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("· 工具调用的参数与返回内容计入输入 token（长任务上下文膨胀的主因）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("· 同一会话多模型混用时按模型分别计价再合计（本页已按模型分区展示）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("· 会话详情页成本 = 本页口径（会话累计全量求和，两页同源）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Text("【价格表（官方 CNY / 每百万 token）】", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        com.mlx.app.core.cost.Pricing.DEFAULT.forEach { (model, p) ->
                            Text(
                                "$model — 输入 ¥${p.inputPerM} / 输出 ¥${p.outputPerM} / 缓存命中 ¥${p.cachedInputPerM}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(vertical = 1.dp),
                            )
                        }
                    }
                }
            }
        }
    }
    SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }

    // 清除全部成本：影响所有会话，需确认
    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = { Text("清除全部成本记录？") },
            text = { Text("将清空所有会话的成本记录（余额与 Token 统计归零），此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    showClearAllDialog = false
                    vm.clearCosts(null)
                }) { Text("清除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showClearAllDialog = false }) { Text("取消") } },
        )
    }

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        if (pickTarget == "start") customStart = millis else customEnd = millis
                    }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } },
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

/** 会话成本卡（树内二级节点，缩进展示；模型分区分行 —— 修复 s.model 单标签误导混用会话；可展开折线图） */
@Composable
private fun SessionCostCard(s: Session) {
    var showTrend by remember { mutableStateOf(false) }
    var chartMode by remember { mutableStateOf(TurnChartMode.HIT_RATE) }
    val series = turnSeries(s.costs)
    val switchTurns = modelSwitchTurns(series)
    val highMiss = highMissTurns(series)
    Card(Modifier.fillMaxWidth().padding(start = 14.dp, end = 2.dp, top = 3.dp, bottom = 3.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row {
                Text(s.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1)
                Text(UiFormats.usd(s.totalCostUsd()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                "${UiFormats.time(s.updatedAt)} · 命中 ${UiFormats.percent(s.cacheHitRate())} · 输入 ${UiFormats.tokens(s.totalHitTokens() + s.totalMissTokens())} / 输出 ${UiFormats.tokens(s.totalCompletionTokens())}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 模型分区：每个模型一行（金额 + 三档 token），混用会话不再只显示"最后使用的模型"
            val breakdowns = aggregateByModel(s.costs)
            if (breakdowns.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                breakdowns.forEach { b ->
                    Text(
                        "${b.group} ${UiFormats.usd(b.cost)}（命中 ${UiFormats.tokens(b.hitTokens)} · 未命中 ${UiFormats.tokens(b.missTokens)} · 输出 ${UiFormats.tokens(b.completionTokens)}）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val savings = cachedSavings(s.costs)
                if (savings > 0.0001) {
                    Text(
                        "缓存节省 ¥%.2f".format(savings),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF34A853),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            // 折线图入口（架构级 14 / 2.6.2 第 4 条：命中率双线 + 成本 + 切换点 + 高 miss 高亮）
            if (s.costs.size >= 2) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { showTrend = !showTrend }
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (showTrend) "▾ 收起趋势图" else "▸ 缓存命中率趋势（${s.costs.size} 轮）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (showTrend) {
                    androidx.compose.foundation.layout.Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                    ) {
                        TurnChartMode.entries.forEach { m ->
                            FilterChip(
                                selected = chartMode == m,
                                onClick = { chartMode = m },
                                label = { Text(m.label, style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                    TurnLineChart(series, switchTurns, highMiss, chartMode, modifier = Modifier.padding(top = 4.dp))
                    if (switchTurns.isNotEmpty()) {
                        Text(
                            "模型切换点：第 ${switchTurns.joinToString("、")} 轮",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
}

/** token 构成：横向堆叠柱状图 + 图例（k 单位） */
@Composable
private fun TokenStackBar(hit: Long, miss: Long, out: Long) {
    val total = (hit + miss + out).coerceAtLeast(1L)
    val green = Color(0xFF34A853)
    val primary = MaterialTheme.colorScheme.primary
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    Text("Token 构成（共 ${UiFormats.tokens(hit + miss + out)}）", style = MaterialTheme.typography.labelSmall, color = variant)
    Spacer(Modifier.height(4.dp))
    Canvas(Modifier.fillMaxWidth().height(18.dp)) {
        val w = size.width
        val h = size.height
        fun seg(frac: Double, color: Color, startX: Float): Float {
            val sw = (w * frac).toFloat()
            drawRect(color = color, topLeft = Offset(startX, 0f), size = Size(sw, h))
            return startX + sw
        }
        var x = 0f
        x = seg(hit.toDouble() / total, green, x)
        x = seg(miss.toDouble() / total, primary, x)
        seg(out.toDouble() / total, variant, x)
    }
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Legend(green, "缓存输入", hit)
        Legend(primary, "非缓存输入", miss)
        Legend(variant, "输出", out)
    }
}

@Composable
private fun Legend(color: Color, label: String, tokens: Long) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.width(10.dp).height(10.dp)) {
            drawCircle(color)
        }
        Spacer(Modifier.width(4.dp))
        Text("$label ${UiFormats.tokens(tokens)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

