package com.mlx.app.ui.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.mlx.app.ui.AppViewModel
import com.mlx.app.ui.theme.AccentTeal
import com.mlx.app.ui.theme.BrandBlue
import com.mlx.app.ui.theme.BrandBlueDeep
import com.mlx.app.ui.theme.MlxLogo
import kotlinx.coroutines.launch

/**
 * 首启极简流程（P14）：
 * 欢迎页 → 输入 API Key → 下一步后台验证（/models）→ 错误弹"API 不正确"
 * → 正确进入欢迎页 2（特性简览）→ 开始进入主页面。
 * 工程选择移出首启（主页空态引导）。
 */
@Composable
fun OnboardingScreen(vm: AppViewModel) {
    var step by rememberSaveable { mutableStateOf(0) } // 0 欢迎 / 1 Key / 2 欢迎2
    var apiKey by rememberSaveable { mutableStateOf("") }
    var keyVisible by rememberSaveable { mutableStateOf(false) }
    var verifying by remember { mutableStateOf(false) }
    var verifyError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                        MaterialTheme.colorScheme.background,
                    )
                )
            ),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 启动自动解压进度横幅（首次启动后台解压完整环境，进度实时可见，不再"以为卡住"）
            if (vm.envBusy) {
                Column(Modifier.fillMaxWidth()) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        "正在解压完整环境（首次启动自动进行，约 30 秒）…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(48.dp))
            // 签名级元素：品牌 Logo（渐变大图 + 光晕）
            MlxLogo(
                modifier = Modifier.size(120.dp),
                glow = true,
            )
            Spacer(Modifier.height(20.dp))
            Text("MLX", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
            Text(
                "DeepSeek 原生 AI 编程 Agent",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(36.dp))

            when (step) {
                0 -> StepWelcome(onNext = { step = 1 })
                1 -> StepKey(
                    apiKey = apiKey,
                    onApiKey = { apiKey = it },
                    keyVisible = keyVisible,
                    onToggleVisible = { keyVisible = !keyVisible },
                    verifying = verifying,
                    onVerify = {
                        verifying = true
                        verifyError = null
                        scope.launch {
                            vm.verifyApiKey(apiKey.trim()).fold(
                                onSuccess = {
                                    vm.completeSimpleOnboarding(apiKey.trim())
                                    step = 2
                                },
                                onFailure = { e ->
                                    val msg = e.message ?: "未知错误"
                                    verifyError = if (msg.contains("NetworkOnMainThread") || msg.contains("网络") || msg.contains("timeout") || msg.contains("Failed to connect")) {
                                        "网络连接失败，请检查网络后重试"
                                    } else {
                                        "API 不正确，请检查后重试"
                                    }
                                },
                            )
                            verifying = false
                        }
                    },
                )
                else -> StepReady(onStart = { /* 进入主页（configured 由 apiKey/project 状态驱动） */ })
            }
        }
    }

    verifyError?.let { err ->
        AlertDialog(
            onDismissRequest = { verifyError = null },
            title = { Text(if (err.contains("网络")) "网络错误" else "API 不正确") },
            text = { Text(err) },
            confirmButton = { TextButton(onClick = { verifyError = null }) { Text("好的") } },
        )
    }
}

/** 欢迎页：特性卡片 + staggered 亮相 */
@Composable
private fun StepWelcome(onNext: () -> Unit) {
    val features = listOf(
        "⚡ 缓存省钱" to "三区上下文，命中率 90%+",
        "🛠 全功能工具" to "shell/git/python 完整环境",
        "📁 本地工程" to "多会话管理，关键词定位",
        "🌐 联网检索" to "自动抓取与搜索",
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        features.forEachIndexed { i, (title, desc) ->
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(tween(300, delayMillis = i * 80)) +
                    slideInVertically(tween(300, delayMillis = i * 80), initialOffsetY = { it / 2 }),
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    ),
                ) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 13.dp)) {
                        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onNext,
            shape = MaterialTheme.shapes.large,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("下一步", style = MaterialTheme.typography.titleMedium) }
    }
}

/** Key 输入页 */
@Composable
private fun StepKey(
    apiKey: String,
    onApiKey: (String) -> Unit,
    keyVisible: Boolean,
    onToggleVisible: () -> Unit,
    verifying: Boolean,
    onVerify: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("输入 DeepSeek API Key", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(
            "其他配置均已按官方默认自动设置",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = apiKey,
            onValueChange = onApiKey,
            label = { Text("API Key") },
            singleLine = true,
            visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = onToggleVisible) {
                    Text(if (keyVisible) "🙈" else "👁")
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onVerify,
            enabled = apiKey.isNotBlank() && !verifying,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (verifying) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            else Text("下一步", style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** 就绪页 */
@Composable
private fun StepReady(onStart: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("一切就绪", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(20.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
        ) {
            Column(Modifier.padding(20.dp)) {
                listOf(
                    "内置完整 Linux 环境（bash/git/python3）",
                    "联网检索与网页抓取自动可用",
                    "本地工程下多会话管理",
                    "成本按官方价实时统计",
                ).forEach { line ->
                    Text("• $line", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onStart,
            shape = MaterialTheme.shapes.large,
            colors = ButtonDefaults.buttonColors(containerColor = BrandBlue),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("开始使用 →", style = MaterialTheme.typography.titleMedium) }
    }
}
