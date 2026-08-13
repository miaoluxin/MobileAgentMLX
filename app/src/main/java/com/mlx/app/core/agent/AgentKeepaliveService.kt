package com.mlx.app.core.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import com.mlx.app.MainActivity
import com.mlx.app.R
import com.mlx.app.MlxApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Agent 执行保活服务（六批：解决"切后台就停"）。
 * 仅做保活锚点，不承载引擎协程（引擎仍在 ViewModel scope —— 审批/choice 交互依赖 UI 弹层）：
 * - 前台服务提升进程优先级（FGS 属 Doze 豁免清单 → 后台网络/CPU 不受限）
 * - PARTIAL_WAKE_LOCK 防 CPU 休眠（acquire 2h 兜底防泄漏，超长回合自动释放）
 * - 通知实时展示"正在执行：<意图>"（消费引擎 activeTurn StateFlow 快照）
 * - 回合结束（IDLE）：宽限 2s（队列续回合到达则取消停止）→ 发完成通知 → 停服
 */
class AgentKeepaliveService : Service() {

    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Agent 执行", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFY_RUNNING, buildNotification("正在执行任务…"))
                acquireWakeLock()
                job?.cancel()
                job = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob()).launch {
                    val engine = (application as MlxApp).container.engine
                    engine.activeTurn.collect { s ->
                        if (s.phase == ActivePhase.IDLE) {
                            val done = s
                            // 宽限：队列续回合在新回合开始（THINKING）时取消停止，避免通知闪烁
                            delay(GRACE_MS)
                            if (engine.activeTurn.value.phase == ActivePhase.IDLE) {
                                notifyDone(done)
                                stopForeground(STOP_FOREGROUND_REMOVE)
                                stopSelf()
                            }
                        } else {
                            notifyProgress(notificationText(s))
                        }
                    }
                }
            }
            ACTION_STOP -> {
                job?.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mlx:agent")
            .apply { acquire(WAKELOCK_TIMEOUT_MS) } // 2h 兜底自动释放（防泄漏；FGS 仍在）
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return android.app.Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("MLX 正在执行")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    private fun notifyProgress(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFY_RUNNING, buildNotification(text))
    }

    private fun notifyDone(done: ActiveTurnStatus) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val secs = ((System.currentTimeMillis() - done.startedAt) / 1000).coerceAtLeast(0L)
        val title = if (done.aborted) "MLX 已停止" else "MLX 执行完成"
        val text = if (done.aborted) "任务已手动停止"
        else if (secs > 0) "任务已完成 · 耗时${fmtSecs(secs)}"
        else "任务已完成"
        val finished = android.app.Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFY_DONE, finished)
    }

    override fun onDestroy() {
        job?.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.mlx.app.action.AGENT_START"
        const val ACTION_STOP = "com.mlx.app.action.AGENT_STOP"
        private const val CHANNEL_ID = "mlx_agent"
        // 通知 id 与 TaskService（1/2）错开 —— 同一 NotificationManager 下 id 全局唯一，避免互相覆盖
        private const val NOTIFY_RUNNING = 11
        private const val NOTIFY_DONE = 12
        private const val GRACE_MS = 2_000L
        private const val WAKELOCK_TIMEOUT_MS = 2 * 60 * 60 * 1000L
    }
}
