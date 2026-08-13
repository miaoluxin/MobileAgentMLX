package com.mlx.app.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 决策弹窗的不可关闭覆盖层（替代 ModalBottomSheet）：
 * 点遮罩 / 返回键 / 下滑 均无法关闭 —— 必须显式决策。
 *
 * 背景：ModalBottomSheet 的空 onDismissRequest 无法阻止内部 hide()，
 * 弹窗视觉消失后其窗口仍 touch-modal 拦截全屏触摸 → 停止按钮点不到、引擎挂起 = 全屏假死。
 * Dialog 窗口级 = 天然 touch-modal（盖住含底部导航在内的所有界面），
 * 三路 dismiss 属性 + BackHandler + 手势消费全部禁用，物理上无法被外部关闭。
 * 关闭只可能来自：用户显式决策（状态清空 → 离开组合）或引擎侧超时兜底。
 */
@Composable
fun DecisionOverlay(content: @Composable ColumnScope.() -> Unit) {
    BackHandler(enabled = true) { /* 吞掉返回键：不可外部关闭 */ }
    Dialog(
        onDismissRequest = { /* 三路 dismiss 已禁用，防御性空实现 */ },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        // ime 放外层：键盘弹出时 scrim 与面板一起上移（计划审批意见输入框需要），
        // 面板内不再叠加 navigationBarsPadding 外的 ime（避免双重留白）
        Box(Modifier.fillMaxSize().imePadding()) {
            // scrim：消费全部手势（Dialog 窗口级已挡触摸，此为双保险防穿透）
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent().changes.forEach { it.consume() }
                            }
                        }
                    }
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
            )
            // 底部圆角面板（无 dragHandle、无拖拽手势 → 下滑无任何反应）
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding(),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shadowElevation = 8.dp,
            ) {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), content = content)
            }
        }
    }
}
