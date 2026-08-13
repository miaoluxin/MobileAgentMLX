package com.mlx.app.core.diagnose

import android.app.Application
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局崩溃日志：未捕获异常落盘（崩溃取证 —— 此前无任何 handler，任何崩溃都是"静默退出"）。
 * - 崩溃线程直接同步写（崩溃时不能依赖协程/主线程）
 * - runCatching 兜底 + 单份大小上限：写盘自身不得再次抛出或卡死退出流程
 * - 写完链回系统默认 handler（Android 默认 handler 负责真正杀进程）：不改变退出语义、不拦截
 */
object CrashLog {

    private const val MAX_FILES = 5          // 轮转：保留最近 5 份
    private const val MAX_BYTES = 64 * 1024  // 单份上限（崩溃线程同步写，必须快）
    private var prev: Thread.UncaughtExceptionHandler? = null
    private var dir: File? = null
    private var appInfo: String = ""

    fun install(app: Application) {
        dir = File(app.filesDir, "crashlogs").apply { mkdirs() }
        // 版本信息一次取好（崩溃路径不查 PackageManager）
        appInfo = runCatching {
            val pkg = app.packageManager.getPackageInfo(app.packageName, 0)
            "${app.packageName} v${pkg.versionName}(${pkg.versionCode})"
        }.getOrDefault("")
        prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, t -> write(thread, t); prev?.uncaughtException(thread, t) }
    }

    internal fun write(thread: Thread, t: Throwable) {
        val dir = dir ?: return
        writeTo(dir, thread, t)
    }

    /** 供测试的纯写盘入口（JVM 单测无 Application 上下文） */
    internal fun writeTo(dir: File, thread: Thread, t: Throwable) {
        runCatching { // 崩溃路径：任何失败都不得再次抛出
            dir.mkdirs()
            // 毫秒级时间戳：崩溃-重启循环中同秒多次崩溃不得互相覆盖
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val f = File(dir, "crash_$stamp.log")
            f.writeText(buildString {
                appendLine("时间: $stamp")
                appendLine("线程: ${thread.name}")
                appendLine("机型: ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT}, ${Build.VERSION.RELEASE})")
                appendLine("包: $appInfo")
                appendLine()
                appendLine(t.stackTraceToString().take(MAX_BYTES))
            })
            // 轮转：保留最近 MAX_FILES 份，删最旧
            dir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(MAX_FILES)?.forEach { it.delete() }
        }
    }

    fun files(): List<File> = dir?.listFiles()?.sortedByDescending { it.lastModified() }?.toList() ?: emptyList()

    fun latestText(): String? = files().firstOrNull()?.takeIf { it.exists() }?.readText()

    fun clear() {
        files().forEach { it.delete() }
    }
}
