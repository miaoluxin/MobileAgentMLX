package com.mlx.app.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.mlx.app.core.policy.Decision
import com.mlx.app.core.skills.installSkillFromUrl
import com.mlx.app.data.store.AppStore
import com.mlx.app.ui.AppViewModel
import com.mlx.app.ui.UiFormats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置中心（对应文档 5.7 节）：Provider/API Key、模型、项目、权限策略、预算、外观。
 */
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val hasKey by vm.container.appStore.hasApiKeyFlow.collectAsState(initial = false)
    val scope = rememberCoroutineScope()
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    var showKeyDialog by remember { mutableStateOf(false) }
    var showRuleDialog by remember { mutableStateOf(false) }
    var showBudgetDialog by remember { mutableStateOf(false) }
    var showSkillDialog by remember { mutableStateOf(false) }
    var showMcpDialog by remember { mutableStateOf(false) }
    var showDoc by remember { mutableStateOf<String?>(null) }
    var showCrashLog by remember { mutableStateOf(false) }
    // 设置页命令直达定位（P2-19）：section → LazyColumn item index（0 = PageHeader；每区块 = SectionTitle + Card 两 item）
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(vm.settingsSection) {
        vm.settingsSection?.let { sec ->
            val idx = SECTION_ITEM_INDEX[sec]
            if (idx != null) {
                listState.scrollToItem(idx)
                vm.consumeSettingsSection()
            } else {
                vm.consumeSettingsSection()
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
    ) {
        // 页签标题（文档 2.11：页头主标题）
        item {
            com.mlx.app.ui.components.PageHeader(
                title = "设置",
                subtitle = "模型、权限、技能、完整环境与文件访问",
            )
            Spacer(Modifier.height(4.dp))
        }
        item { SectionTitle("Provider 与模型") }
        item {
            SettingCard {
                SettingRow("API Key", if (hasKey) "已配置（Keystore 加密）" else "未配置", onEdit = { showKeyDialog = true })
                SettingRow("Flash 模型", vm.flashModel) { showKeyDialog = true }
                SettingRow("Pro 模型", vm.proModel) { showKeyDialog = true }
                SettingRow("当前档位", if (vm.modelTier == "pro") "Pro" else "Flash") {
                    vm.setModelTier(if (vm.modelTier == "pro") "flash" else "pro")
                }
                Spacer(Modifier.height(6.dp))
                val rm by vm.container.appStore.reasoningModeFlow.collectAsState(initial = "auto")
                Text("思考模式（DeepSeek V4）", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                    // 八批：标签文案如实 —— auto=标准思考（成本中等），off=快速（不思考，最省），max=深度思考（最慢最贵）
                    for ((key, label) in listOf("off" to "快速", "auto" to "标准", "max" to "深度")) {
                        FilterChip(
                            selected = rm == key,
                            onClick = { vm.setReasoningMode(key) },
                            label = { Text(label) },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                // 计划模式（与思考模式同级别；对话页顶栏 🔒 同步开关）
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("计划模式（只读审计，禁止写操作）", modifier = Modifier.weight(1f))
                    Switch(checked = vm.planMode, onCheckedChange = { vm.setPlanMode(it) })
                }
            }
        }

        item { SectionTitle("当前工程") }
        item {
            val currentProject = vm.realProjects.firstOrNull { it.path == vm.currentRealPath }
            SettingCard {
                SettingRow("工程名", vm.projectName.ifBlank { "未选择" }, onEdit = null)
                if (currentProject != null) {
                    SettingRow("磁盘目录", currentProject.sourceDir.ifBlank { "应用内目录" }, onEdit = null)
                }
                SettingRow("文件操作", if (vm.projectType == "real") "完整环境（shell/git/python 可用）" else "手机磁盘目录（基础文件工具）", onEdit = null)
                // 目录即工作区 2.0：所有文件访问权限入口（授权后文件工具/shell 直接操作磁盘真实目录）
                SettingRow(
                    "所有文件访问权限",
                    if (vm.allFilesAccess) "已授予（直接操作磁盘目录）" else "未授予（文件工具降级）",
                ) {
                    if (activity != null) {
                        com.mlx.app.AppContainer.requestAllFilesAccess(activity)
                    }
                }
                Text(
                    "工程在会话页管理：新建工程需选择手机磁盘目录，Agent 的文件改动会自动写回该目录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                vm.projectOpMsg?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("指令文件（AGENTS.md / REASONIX.md / CLAUDE.md）", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "工程目录下的指令文件会自动注入系统提示（支持 @import，最多 5 层）；生成后新回合生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { scope.launch { vm.initProject { } } },
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                ) { Text("生成 REASONIX.md（/init）", maxLines = 1) }
            }
        }

        item { SectionTitle("会话管理") }
        item {
            SettingCard {
                var backupMsg by remember { mutableStateOf<String?>(null) }
                OutlinedButton(
                    onClick = { vm.backupSessions { backupMsg = it } },
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                ) { Text("备份会话到工程目录（.mlx-backup/）", maxLines = 1) }
                backupMsg?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        item {
            SettingCard {
                var pruneDays by rememberSaveable { mutableStateOf("30") }
                Text("自动剪枝（prune-sessions）", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = pruneDays,
                        onValueChange = { pruneDays = it.filter { ch -> ch.isDigit() }.take(3) },
                        label = { Text("保留天数") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { vm.pruneSessions(pruneDays.toIntOrNull() ?: 0) }) {
                        Text("清理旧会话")
                    }
                }
            }
        }

        item { SectionTitle("权限与沙箱") }
        item {
            SettingCard {
                Text("审批模式", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                    for (m in listOf("auto", "review", "yolo")) {
                        FilterChip(
                            selected = vm.policyMode == m,
                            onClick = { vm.setPolicyMode(m) },
                            label = { Text(if (m == "review") "Review" else if (m == "auto") "Auto" else "Yolo") },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                // 十批：默认 auto（工具调用默认放行）；说明文案让用户理解差异
                Text(
                    "Auto：工具调用默认放行（推荐日常使用）\nReview：敏感操作逐个确认（60 秒无响应自动拒绝）\nYolo：跳过全部确认",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text("策略规则（deny > allow > ask）", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                vm.container.policy.rules.forEach { r ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${r.pattern} → ${r.decision.name}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { vm.removePolicyRule(r.pattern) }) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                OutlinedButton(onClick = { showRuleDialog = true }) { Text("＋ 新增规则") }
                Spacer(Modifier.height(8.dp))
                Text(
                    "沙箱说明：SAF 授权即天然沙箱 —— 文件工具仅可访问已授权的项目树；写入受策略控制。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item { SectionTitle("网络搜索") }
        item {
            SettingCard {
                val backend by vm.container.appStore.searchBackendFlow.collectAsState(initial = "BING")
                val tavilyKey by vm.container.appStore.tavilyKeyFlow.collectAsState(initial = "")
                Text("搜索后端（web_search 工具）", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                    for (b in listOf("BING", "TAVILY")) {
                        FilterChip(
                            selected = backend == b,
                            onClick = { vm.setSearchBackend(b) },
                            label = { Text(b) },
                        )
                    }
                }
                if (backend == "TAVILY") {
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = tavilyKey,
                        onValueChange = { vm.setTavilyKey(it) },
                        label = { Text("Tavily API Key") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Bing 无需 Key；Tavily 需在 tavily.com 申请 Key。（百度后端待接入百度智能云 API，暂不提供入口）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item { SectionTitle("上下文压缩（对应 PC compact_ratio）") }
        item {
            val cr by vm.container.appStore.compactRatioFlow.collectAsState(initial = 0.8)
            SettingCard {
                Text("自动压缩阈值：达到该用量时自动压缩旧消息（软阈值提前 ${UiFormats.percent((cr - 0.3).coerceAtLeast(0.2))} 提示）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                    for (pct in listOf(0.5, 0.6, 0.7, 0.8, 0.9)) {
                        FilterChip(
                            selected = cr == pct,
                            onClick = { vm.setCompactRatio(pct) },
                            label = { Text("${(pct * 100).toInt()}%") },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "对话页「压缩」按钮 = 手动压缩（对齐 PC /compact）：无条件折叠旧消息区，保留你的原文。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item { SectionTitle("预算") }
        item {
            SettingCard {
                SettingRow("会话预算上限", if (vm.budgetUsd > 0) "¥${vm.budgetUsd}" else "不限") { showBudgetDialog = true }
            }
        }

        item { SectionTitle("外观") }
        item {
            SettingCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                    for (t in listOf("system", "light", "dark")) {
                        FilterChip(
                            selected = vm.themeMode == t,
                            onClick = { vm.setTheme(t) },
                            label = { Text(if (t == "system") "跟随系统" else if (t == "light") "浅色" else "深色") },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                val style by vm.container.appStore.outputStyleFlow.collectAsState(initial = "standard")
                Text("输出风格（/output-style）", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                    for ((key, label) in listOf(
                        "standard" to "标准", "concise" to "简洁", "detailed" to "详细",
                        "json" to "结构化", "explanatory" to "解释性", "learning" to "协作",
                    )) {
                        FilterChip(
                            selected = style == key,
                            onClick = { vm.setOutputStyle(key) },
                            label = { Text(label) },
                        )
                    }
                }
            }
        }

        item { SectionTitle("记忆（facts · BM25 自动召回）") }
        item {
            var facts by remember { mutableStateOf(vm.container.factMemory.list()) }
            var previewQuery by remember { mutableStateOf("") }
            var previewResult by remember { mutableStateOf<List<Pair<String, Double>>?>(null) }
            SettingCard {
                facts.forEach { f ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "[${f.type}] ${f.content.take(40)}",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                            )
                            Text(
                                "创建 ${UiFormats.time(f.createdAt)} · 召回 ${if (f.lastRecalledAt > 0) UiFormats.time(f.lastRecalledAt) else "未召回"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = {
                            vm.container.factMemory.delete(f.id)
                            facts = vm.container.factMemory.list()
                        }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    }
                }
                if (facts.isEmpty()) {
                    Text(
                        "暂无记忆。Agent 可用 remember 工具自主保存（对话中也会出现审批卡片）；说「请记住…」同样有效。最多召回 4 条/2400 字符。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                // F3：召回预览（验证 BM25 召回质量）
                Text("召回预览（输入关键词看下一回合会召回哪些）", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = previewQuery,
                        onValueChange = { previewQuery = it },
                        placeholder = { Text("输入关键词…") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        previewResult = vm.container.factMemory.recall(previewQuery, topK = 4, maxChars = 2400)
                            .map { it.content.take(30) to com.mlx.app.core.memory.BM25.score(previewQuery, it.content, vm.container.factMemory.list().size, 1) }
                    }) { Text("预览") }
                }
                previewResult?.forEach { (content, score) ->
                    Text(
                        "• $content（得分 ${"%.2f".format(score)}）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        item { SectionTitle("技能") }
        item {
            var skillTick by remember { mutableStateOf(0) }
            val skills = remember(skillTick) { vm.container.skillStore.list() }
            var showSkillUrlDialog by remember { mutableStateOf(false) }
            var previewSkill by remember { mutableStateOf<com.mlx.app.core.skills.Skill?>(null) }
            SettingCard {
                skills.forEach { s ->
                    val isBuiltin = s.scope == "builtin"
                    val enabled = vm.container.skillStore.isEnabled(s.name)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "📜 ${s.name}${if (s.runAs == "subagent") " 🧬" else ""}" +
                                "${if (s.autoUse == "require") " [必须]" else if (s.autoUse == "prefer") " [优先]" else if (s.autoUse == "off") " [手动]" else ""}" +
                                "${if (s.readOnly) " [只读]" else ""}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                        if (isBuiltin) {
                            Text(
                                "内置",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            TextButton(onClick = { previewSkill = s }) { Text("预览") }
                            androidx.compose.material3.Switch(
                                checked = enabled,
                                onCheckedChange = {
                                    vm.container.skillStore.setEnabled(s.name, it)
                                    skillTick++
                                },
                                modifier = Modifier.scale(0.7f),
                            )
                            TextButton(onClick = {
                                vm.container.skillStore.delete(s.name)
                                skillTick++
                            }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
                Text(
                    "技能 = 剧本（方法论+规范），Agent 匹配到即按其执行；内置技能离线可用不可删，" +
                        "用户技能可新建或从链接安装（SKILL.md 格式，PC 版技能可互通）。在线技能市场规划中。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showSkillDialog = true }) { Text("＋ 新建技能") }
                    OutlinedButton(onClick = { showSkillUrlDialog = true }) { Text("🔗 从链接安装") }
                }
                if (showSkillUrlDialog) {
                    SkillUrlInstallDialog(
                        skillStore = vm.container.skillStore,
                        onDone = {
                            showSkillUrlDialog = false
                            skillTick++
                        },
                    )
                }
                // 技能内容预览（P2-18：对齐 PC /skills show）
                previewSkill?.let { skill ->
                    SkillPreviewDialog(skill = skill, onDismiss = { previewSkill = null })
                }
            }
        }

        item { SectionTitle("MCP 插件") }
        item {
            val servers by remember { mutableStateOf(vm.container.mcpRegistry.list()) }
            SettingCard {
                servers.forEach { srv ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${if (srv.enabled) "🟢" else "⚪"} ${srv.name}（${srv.url.take(30)}）",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                        TextButton(onClick = {
                            vm.container.mcpRegistry.save(srv.copy(enabled = !srv.enabled))
                        }) { Text(if (srv.enabled) "停用" else "启用") }
                        TextButton(onClick = {
                            vm.container.mcpRegistry.delete(srv.name)
                        }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    }
                }
                Text("MCP over HTTP（JSON-RPC）；stdio 进程桥受 Android 平台限制。远程工具自动桥接为 mcp_* 工具。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { showMcpDialog = true }) { Text("＋ 添加 MCP 服务器") }
            }
        }

        item { SectionTitle("完整环境（嵌入式 Linux）") }
        item {
            vm.refreshEnvStatus()
            SettingCard {
                if (!vm.envInstalled) {
                    Text("内置完整 Linux 环境（Termux 发行）：bash/git/python3/apt 全功能，与 PC 同级能力。未解压（APK 内已携带，本地解压无需网络）。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    if (vm.envBusy) {
                        // 启动自动解压进行中（无总字节数可算比例 → 不确定进度条 + 已解压字节数）
                        Text("解压中… ${UiFormats.tokens(vm.envProgress)} 字节", style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp))
                    } else {
                        Button(onClick = { vm.installEnv() }) { Text("解压完整环境") }
                    }
                } else {
                    Text("已安装（占用 ${UiFormats.tokens(vm.container.embeddedEnv.sizeBytes)}）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.checkEnv() }, modifier = Modifier.weight(1f)) { Text("自检") }
                        OutlinedButton(onClick = { vm.deleteEnv() }, modifier = Modifier.weight(1f)) { Text("卸载环境") }
                    }
                }
                vm.envCheckResult?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
                Spacer(Modifier.height(6.dp))
                Text("Agent 的 shell 工具在此环境中真实执行；任意工具可 pkg install（如 nodejs/clang）。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        item { SectionTitle("关于") }
        item {
            SettingCard {
                Text("MLX Mobile v0.1.0-mvp", style = MaterialTheme.typography.bodySmall)
                Text("MLX（内置完整 Linux 环境）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                // P6：内置文档查看（打包时自动刷新）
                SettingRow("产品介绍", "内置文档", onEdit = { showDoc = "product.md" })
                SettingRow("开发文档", "内置文档", onEdit = { showDoc = "dev.md" })
                // 崩溃日志（CrashLog 落盘；崩溃取证：此前无任何日志导致"静默退出"无法定位）
                SettingRow(
                    "崩溃日志",
                    if (com.mlx.app.core.diagnose.CrashLog.files().isEmpty()) "无" else "最近 ${com.mlx.app.core.diagnose.CrashLog.files().size} 条",
                    onEdit = { showCrashLog = true },
                )
            }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }

    if (showKeyDialog) {
        KeyConfigDialog(vm, onDismiss = { showKeyDialog = false })
    }
    if (showBudgetDialog) {
        var input by remember { mutableStateOf(if (vm.budgetUsd > 0) vm.budgetUsd.toString() else "") }
        AlertDialog(
            onDismissRequest = { showBudgetDialog = false },
            title = { Text("预算上限（元）") },
            text = {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("0 = 不限") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.setBudget(input.toDoubleOrNull() ?: 0.0)
                    showBudgetDialog = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showBudgetDialog = false }) { Text("取消") } },
        )
    }
    if (showRuleDialog) {
        RuleDialog(vm, onDismiss = { showRuleDialog = false })
    }
    if (showSkillDialog) {
        SkillDialog(vm, onDismiss = { showSkillDialog = false })
    }
    if (showMcpDialog) {
        McpDialog(vm, onDismiss = { showMcpDialog = false })
    }
    showDoc?.let { docName ->
        DocViewerDialog(docName, onDismiss = { showDoc = null })
    }
    if (showCrashLog) {
        CrashLogDialog(onDismiss = { showCrashLog = false })
    }
}

/** 设置区块 → LazyColumn item index（P2-19 命令直达定位；0 = PageHeader，每区块两 item） */
private val SECTION_ITEM_INDEX = mapOf(
    "provider" to 1, "project" to 3, "sessions" to 5, "permissions" to 8, "search" to 10,
    "compact" to 12, "budget" to 14, "appearance" to 16, "memory" to 18, "skills" to 20,
    "mcp" to 22, "env" to 24, "about" to 26,
)

/** 技能内容预览（P2-18：对齐 PC /skills show —— 查看完整剧本与元信息） */
@Composable
private fun SkillPreviewDialog(
    skill: com.mlx.app.core.skills.Skill,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("技能：${skill.name}") },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                Text("用途：${skill.description}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "分类 ${skill.category} · v${skill.version} · ${skill.runAs} · 自动使用 ${skill.autoUse}" +
                        (if (skill.readOnly) " · 只读" else "") +
                        (if (skill.model.isNotBlank()) " · 模型 ${skill.model}" else ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                if (skill.triggers.isNotEmpty()) {
                    Text("触发词：${skill.triggers.joinToString("、")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (skill.requires.isNotEmpty()) {
                    Text("依赖：${skill.requires.joinToString("、")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                Text(skill.content, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/** 从链接安装技能（架构级 12："给个链接自动装好"；下载 SKILL.md → 解析 → 保存为用户技能） */
@Composable
private fun SkillUrlInstallDialog(
    skillStore: com.mlx.app.core.memory.SkillStore,
    onDone: () -> Unit,
) {
    var url by remember { mutableStateOf("") }
    var installing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!installing) onDone() },
        title = { Text("从链接安装技能") },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; message = null },
                    placeholder = { Text("SKILL.md 链接（GitHub raw / 任意 .md URL）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "下载 → 解析 frontmatter（name/description 必需）→ 保存为用户技能。PC 版 MLX 导出的 SKILL.md 可直接安装。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                message?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (it.startsWith("✓")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    installing = true
                    message = null
                    scope.launch {
                        val result = installSkillFromUrl(url.trim(), skillStore) { u ->
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    val http = okhttp3.OkHttpClient.Builder()
                                        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                                        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                                        .build()
                                    http.newCall(okhttp3.Request.Builder().url(u).get().build()).execute().use { resp ->
                                        if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
                                        resp.body?.string() ?: throw java.io.IOException("空响应")
                                    }
                                }
                            }
                        }
                        installing = false
                        result.fold(
                            onSuccess = {
                                message = "✓ 技能「${it.name}」已安装（${it.category} v${it.version}），下回合生效"
                                url = ""
                            },
                            onFailure = { message = "✗ ${it.message ?: "安装失败"}" },
                        )
                    }
                },
                enabled = url.trim().isNotBlank() && !installing,
            ) { Text(if (installing) "安装中…" else "安装") }
        },
        dismissButton = { TextButton(onClick = { if (!installing) onDone() }) { Text("关闭") } },
    )
}
