package com.mlx.app.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mlx.app.MlxApp
import com.mlx.app.core.agent.ActivePhase
import com.mlx.app.ui.chat.ChatScaffold
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.mlx.app.ui.jobs.JobsScreen
import com.mlx.app.ui.onboarding.OnboardingScreen
import com.mlx.app.ui.sessions.SessionListScreen
import com.mlx.app.ui.settings.SettingsScreen
import com.mlx.app.ui.stats.StatsScreen
import com.mlx.app.ui.theme.MLXTheme
import com.mlx.app.ui.workspace.WorkspaceScreen

/**
 * 应用根：主题 → 配置检查（未配置进首启向导）→ 自适应导航框架。
 * 窗口尺寸类：<600dp 底部导航；≥600dp 左侧 NavigationRail（分屏宽格/横屏）。
 */
@Composable
fun AppRoot(vm: AppViewModel = viewModel()) {
    val dark = when (vm.themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    MLXTheme(darkTheme = dark) {
        // 返回键分层：内部页（会话/文件子目录等）返回上一层；根部页弹"确认退出"；首启向导直接退出
        val activity = LocalContext.current as? Activity
        val context = LocalContext.current
        var showExitConfirm by remember { mutableStateOf(false) }

        // ---- 六批：执行保活引导（首回合触发；通知权限 + 电池优化豁免双保险）----
        val engine = (context.applicationContext as MlxApp).container.engine
        var notifPrompted by remember { mutableStateOf(false) }
        val notifPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { /* 被拒不阻塞流程（FGS 照常运行，仅通知不可见） */ }
        LaunchedEffect(Unit) {
            if (Build.VERSION.SDK_INT >= 33 && !notifPrompted &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                // 等待首个回合开始（不阻塞 UI）
                engine.activeTurn.filter { it.phase != ActivePhase.IDLE }.first()
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    notifPrompted = true
                    notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
        var showBatteryDialog by remember { mutableStateOf(false) }
        val batteryStore = (context.applicationContext as MlxApp).container.appStore
        val coroutineScope = rememberCoroutineScope()
        LaunchedEffect(Unit) {
            val pm = context.getSystemService(Activity.POWER_SERVICE) as? PowerManager ?: return@LaunchedEffect
            if (!pm.isIgnoringBatteryOptimizations(context.packageName) && !batteryStore.batteryPromptShown()) {
                engine.activeTurn.filter { it.phase != ActivePhase.IDLE }.first()
                showBatteryDialog = true
            }
        }
        if (showBatteryDialog) {
            AlertDialog(
                onDismissRequest = {
                    showBatteryDialog = false
                    coroutineScope.launch { batteryStore.markBatteryPromptShown() }
                },
                title = { Text("允许后台长时间运行？") },
                text = {
                    Text(
                        "MLX 需要执行长时间任务（数十分钟到数小时）。" +
                            "允许忽略电池优化后，锁屏/后台时任务不会被系统暂停。\n\n" +
                            "说明：此项仅影响电池调度，不会增加任何权限。"
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showBatteryDialog = false
                        coroutineScope.launch { batteryStore.markBatteryPromptShown() }
                        // 特殊权限：部分机型直接拒绝/无响应 → 回退到电池优化设置页
                        runCatching {
                            val intent = android.content.Intent(
                                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                android.net.Uri.parse("package:${context.packageName}")
                            )
                            activity?.startActivity(intent)
                        }.onFailure {
                            runCatching {
                                activity?.startActivity(
                                    android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                )
                            }
                        }
                    }) { Text("去设置") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showBatteryDialog = false
                        coroutineScope.launch { batteryStore.markBatteryPromptShown() }
                    }) { Text("暂不") }
                },
            )
        }
        if (!vm.configured) {
            BackHandler { activity?.finish() }
        } else {
            BackHandler(enabled = vm.activeSessionId != null) { vm.closeSession() }   // 会话内 → 回列表
            BackHandler(enabled = vm.activeSessionId == null) { showExitConfirm = true } // 根部 → 弹确认
        }
        if (showExitConfirm) {
            AlertDialog(
                onDismissRequest = { showExitConfirm = false },
                title = { Text("退出 MLX？") },
                text = { Text("确认退出后，后台任务将继续运行（前台服务常驻通知）。") },
                confirmButton = {
                    TextButton(onClick = { activity?.finish() }) { Text("退出", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { showExitConfirm = false }) { Text("取消") }
                },
            )
        }
        // 启动"所有文件访问"授权引导（Android 11+ 未授权且未提示过；授权一次永久生效，暂不则不阻塞）
        if (vm.allFilesPromptNeeded) {
            AlertDialog(
                onDismissRequest = { vm.dismissAllFilesPrompt() },
                title = { Text("授予文件访问权限？") },
                text = {
                    Text(
                        "授予“所有文件访问”权限后，文件工具可直接读写你选择的文件夹及其全部子文件夹（含后续新增文件），授权一次永久生效。\n\n" +
                            "暂不授权也可正常使用：选目录时已自动授权，文件经 SAF 直连读写。"
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        vm.dismissAllFilesPrompt()
                        runCatching {
                            val intent = android.content.Intent(
                                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                android.net.Uri.parse("package:${activity?.packageName}")
                            )
                            activity?.startActivity(intent)
                        }
                    }) { Text("去授权") }
                },
                dismissButton = {
                    TextButton(onClick = { vm.dismissAllFilesPrompt() }) { Text("暂不") }
                },
            )
        }
        val config = LocalConfiguration.current
        val wide = config.screenWidthDp >= 600
        if (!vm.configured) {
            OnboardingScreen(vm)
        } else {
            MainScaffold(vm, wide)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MainScaffold(vm: AppViewModel, wide: Boolean) {
    val snackbarHostState = remember { SnackbarHostState() }
    // 自动解压完成提示（envBusy true→false 且已安装）
    var wasEnvBusy by remember { mutableStateOf(vm.envBusy) }
    LaunchedEffect(vm.envBusy) {
        if (wasEnvBusy && !vm.envBusy && vm.envInstalled) {
            snackbarHostState.showSnackbar("✓ 完整环境解压完成")
        }
        wasEnvBusy = vm.envBusy
    }
    // P1：键盘弹出时隐藏底部导航，输入框贴键盘（收起时恢复）
    val imeVisible = WindowInsets.isImeVisible
    // 会话页（activeSessionId 非空）不渲染导航栏 —— 对话占满全屏，返回时恢复（用户要求）
    val inSession = vm.activeSessionId != null
    val bottomBar: @Composable (() -> Unit)? = if (imeVisible || inSession) null else {
        {
            // 悬浮圆角矩形导航栏（自定义）：选中指示器为圆角矩形并在按钮间滑动；
            // 图标层与指示器层分离，保证共轴居中
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    // 抬高悬浮：导航条 inset（手势条机型）+ 12dp 底距（三键机型）
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .shadow(6.dp, RoundedCornerShape(26.dp))
                    .clip(RoundedCornerShape(26.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(26.dp))
                    .height(62.dp),
            ) {
                val itemW = maxWidth / Tab.entries.size
                val indicatorW = itemW - 24.dp
                // 滑动指示器：目标 x = 选中项中心 - 指示器半宽
                val indicatorOffset by animateDpAsState(
                    targetValue = itemW * vm.tab.ordinal + (itemW - indicatorW) / 2,
                    animationSpec = tween(durationMillis = 240),
                    label = "navIndicator",
                )
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = indicatorOffset)
                        .width(indicatorW)
                        .height(42.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(14.dp)),
                )
                // 图标 + 文字标签层（文档 2.11：底部导航无文字 → 每个页签显示标签）
                // 对齐：每格 weight(1f) 宽恒等于 itemW，图标中心与指示器中心精确重合
                Row(
                    Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (t in Tab.entries) {
                        val selected = vm.tab == t
                        // 瞬时切换选中色（去 240ms 灰色过渡动效）；无 ripple（去灰色点按动效）
                        val tint = if (selected) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { vm.selectTab(t) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(t.icon, contentDescription = t.label, modifier = Modifier.size(20.dp), tint = tint)
                                Text(
                                    t.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = tint,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    val rail: @Composable (() -> Unit)? = if (imeVisible || inSession) null else {
        {
            NavigationRail {
                for (t in Tab.entries) {
                    NavigationRailItem(
                        selected = vm.tab == t,
                        onClick = { vm.selectTab(t) },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                    )
                }
            }
        }
    }

    val content: @Composable () -> Unit = {
        val sessionId = vm.activeSessionId
        if (sessionId != null) {
            ChatScaffold(
                appVm = vm,
                sessionId = sessionId,
                snackbarHostState = snackbarHostState,
            )
        } else {
            when (vm.tab) {
                Tab.Chat -> SessionListScreen(vm)
                Tab.Files -> WorkspaceScreen(vm)
                Tab.Jobs -> JobsScreen(vm)
                Tab.Stats -> StatsScreen(vm)
                Tab.Settings -> SettingsScreen(vm)
            }
        }
    }

    if (wide) {
        Row(Modifier.fillMaxSize()) {
            rail?.invoke()
            Box(Modifier.weight(1f).fillMaxSize()) {
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                ) { padding ->
                    Box(Modifier.fillMaxSize()) { content() }
                }
            }
        }
    } else {
        Scaffold(
            bottomBar = bottomBar ?: {},
            snackbarHost = { SnackbarHost(snackbarHostState) },
            // P1：系统栏 inset 统一由各页面自理（避免会话页双重 padding 下移）
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) { content() }
        }
    }
}

