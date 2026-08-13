package com.mlx.app.core.embed

import android.content.Context
import java.io.File

/**
 * 嵌入式完整 Linux 环境（Termux 发行形态，构建期打进 APK assets/termux-root.tar）。
 * - 首启从 assets 本地解压到私有目录（零网络下载）
 * - 提供 bash/git/python 路径与环境变量模板（shell 工具真实执行用）
 * - 自检：bash --version / git --version / python3 --version
 * - apt 完整可用 → 全仓库 2906 包 pkg install 按需
 *
 * W^X 执行限制（Android 10+，targetSdk≥29 禁止 execve app-data 文件）：
 * - 首跳：Kotlin 侧直接经 /system/bin/linker64 加载二进制（内核只见系统 linker）
 * - 子进程：LD_PRELOAD 注入 termux-exec（Apache-2.0，Termux 官方生产库，assets 内置）
 *   拦截 execve 系 → 改写为 linker64 加载，并处理 shebang 脚本
 * - targetSdk 34：Android 15/16 的 app-data dlopen 限制仅对 targetSdk≥35 生效
 */
class EmbeddedEnv(private val context: Context) {

    val root: File get() = File(context.filesDir, "termux")
    // tar 内结构为 data/data/com.termux/files/...（Termux 发行布局，PREFIX 指向此）
    val usr: File get() = File(root, "data/data/com.termux/files/usr")
    val home: File get() = File(root, "data/data/com.termux/files/home")

    val bashPath: String get() = File(usr, "bin/bash").absolutePath
    val gitPath: String get() = File(usr, "bin/git").absolutePath
    val pythonPath: String get() = File(usr, "bin/python3").absolutePath
    val aptPath: String get() = File(usr, "bin/apt").absolutePath

    /** termux-exec 预加载库（解压后注入 usr/lib；LD_PRELOAD 指向它接管子进程 exec） */
    val preloadLibPath: String get() = File(usr, "lib/libtermux-exec-ld-preload.so").absolutePath

    /**
     * 环境完整性校验（升级版判定）：只看 bash 会被"解压中断的半成品"骗过
     * （bash 在 tar 靠前先解出 → 误判已安装 → 永不重试，git/python3 等缺失）。
     * 关键文件全部存在才算已安装。
     */
    val installed: Boolean
        get() = criticalMissing().isEmpty()

    /** 缺失关键文件清单（空 = 环境完整可用；非空 = 半成品/损坏，需重新解压） */
    fun criticalMissing(): List<String> {
        val missing = mutableListOf<String>()
        if (!File(usr, "bin/bash").isFile) missing += "usr/bin/bash"
        if (!File(usr, "bin/git").isFile) missing += "usr/bin/git"
        if (!File(usr, "bin/python3").isFile) missing += "usr/bin/python3"
        if (!File(usr, "bin/apt").isFile) missing += "usr/bin/apt"
        if (!File(usr, "bin/zip").isFile) missing += "usr/bin/zip"
        if (!File(usr, "lib").isDirectory) missing += "usr/lib"
        return missing
    }

    val sizeBytes: Long
        get() = if (root.exists()) root.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L

    /** 环境变量模板（shell/python/git 进程注入用）；含 termux-exec 所需配置 */
    fun envVars(extra: Map<String, String> = emptyMap()): Map<String, String> {
        val base = mapOf(
            "PREFIX" to usr.absolutePath,
            "HOME" to home.absolutePath,
            "TMPDIR" to File(usr, "tmp").absolutePath,
            "LD_LIBRARY_PATH" to File(usr, "lib").absolutePath,
            "PATH" to File(usr, "bin").absolutePath + ":/system/bin:/system/xbin",
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
            // ---- termux-exec：子进程 execve 拦截 → /system/bin/linker64 加载 ----
            "LD_PRELOAD" to preloadLibPath,
            "TERMUX__PREFIX" to usr.absolutePath,
            "TERMUX__ROOTFS" to root.absolutePath,
            "TERMUX_APP__DATA_DIR" to root.absolutePath,
            "TERMUX_APP__LEGACY_DATA_DIR" to root.absolutePath,
            "TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE" to "1", // enable：需要时走 linker
            // 2026-08-09 修复：python prefix 显式对齐安装路径（治"prefix 与安装路径不匹配"缺标准库）
            "PYTHONHOME" to usr.absolutePath,
            "PYTHONUNBUFFERED" to "1", // python 输出实时回流（任务页/状态面板逐行可见）
        )
        return base + extra
    }

    /**
     * 进程启动（W^X 首跳）：API≥29 时先经 /system/bin/linker64 加载目标二进制
     * （应用数据目录不可 execve；linker 直接加载 ELF 不触发内核拒绝）。
     * 所有 app 侧 ProcessBuilder 启动环境内二进制必须走此入口。
     */
    fun processBuilder(cmd: List<String>): ProcessBuilder {
        val real = if (android.os.Build.VERSION.SDK_INT >= 29 && cmd.isNotEmpty()) {
            listOf("/system/bin/linker64", cmd[0]) + cmd.drop(1)
        } else cmd
        return ProcessBuilder(real).apply { environment().putAll(envVars()) }
    }

    /** 幂等注入 termux-exec 预加载库（API≥29 用 linker 变体，否则 direct 变体；老版本环境升级时补注入） */
    private fun injectPreloadLibIfMissing() {
        try {
            val libPath = java.io.File(preloadLibPath)
            if (libPath.exists()) return
            File(usr, "lib").mkdirs()
            val preloadAsset = if (android.os.Build.VERSION.SDK_INT >= 29) "termux-exec-linker.so" else "termux-exec-direct.so"
            context.assets.open(preloadAsset).use { ins ->
                libPath.writeBytes(ins.readBytes())
            }
            libPath.setExecutable(true, false)
        } catch (e: Exception) { /* 注入失败不阻塞（自检会暴露） */ }
    }

    /**
     * 从 assets 解压完整环境（本地文件操作，无网络）。
     * onProgress(已解压字节) 在后台线程回调；onDone(成功, 错误信息) 收尾。
     */
    fun extractFromAssets(onProgress: (Long) -> Unit, onDone: (Boolean, String?) -> Unit) {
        Thread {
            try {
                if (installed) {
                    injectPreloadLibIfMissing()
                    onDone(true, null)
                    return@Thread
                }
                // 半成品清理：环境不完整（上次解压中断）→ 先删干净再重新解压，防新旧混合
                if (root.exists() && criticalMissing().isNotEmpty()) {
                    root.deleteRecursively()
                }
                root.mkdirs()
                val tarName = "termux-root.tar"
                // 优先 openFd 获取总长度（asset 已标记 noCompress）；失败回退流式无总长
                var total = 0L
                try {
                    context.assets.openFd(tarName).use { fd -> total = fd.length }
                } catch (e: Exception) {
                    total = 0L
                }
                context.assets.open(tarName).use { input ->
                    MinimalTar.extract(input, root) { extracted, _ ->
                        if (total > 0) onProgress(extracted)
                    }
                }
                // 注入 termux-exec 预加载库（W^X 子进程接管；API≥29 用 linker 变体，否则 direct 变体）
                injectPreloadLibIfMissing()
                // 写 .bashrc 兜底（PREFIX 指向解压后的真实路径）
                home.mkdirs()
                val bashrc = File(home, ".bashrc")
                if (!bashrc.exists()) {
                    bashrc.writeText(
                        "export PREFIX=${usr.absolutePath}\n" +
                            "export HOME=${home.absolutePath}\n" +
                            "export TMPDIR=${File(usr, "tmp").absolutePath}\n" +
                            "export LD_LIBRARY_PATH=${File(usr, "lib").absolutePath}\n" +
                            "export LD_PRELOAD=${preloadLibPath}\n" +
                            "export PATH=${File(usr, "bin").absolutePath}:\$PATH\n"
                    )
                }
                // 完整性校验（防解压中断产生的半成品被误判为已安装）
                val missing = criticalMissing()
                if (missing.isEmpty()) {
                    onDone(true, null)
                } else {
                    // 清理半成品（含 .bashrc 等残留），允许重试
                    root.deleteRecursively()
                    onDone(
                        false,
                        "解压不完整（缺失 ${missing.first()} 等 ${missing.size} 项，可能因内存/后台被杀中断）。已清理，请重新解压。",
                    )
                }
            } catch (e: Exception) {
                onDone(false, "解压失败: ${e.message}")
            }
        }.start()
    }

    /** 自检：返回各工具版本（失败项标注 ✗ 与完整错误码） */
    fun selfCheck(): String {
        fun ver(cmd: String, vararg args: String): String = try {
            val p = processBuilder(listOf(cmd) + args)
                .redirectErrorStream(true)
                .start()
            val out = p.inputStream.bufferedReader().readText().trim().lines().firstOrNull() ?: ""
            if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) "（超时）" else out
        } catch (e: Exception) {
            // 完整 errno（error=13 exec 拒绝 / error=8 ELF 格式），不截断
            "✗ ${e.message}${e.cause?.let { "（${it.message}）" } ?: ""}"
        }
        return buildString {
            append("bash: ").append(ver(bashPath, "--version").take(60)).append('\n')
            append("git: ").append(ver(gitPath, "--version")).append('\n')
            append("python3: ").append(ver(pythonPath, "--version")).append('\n')
            append("apt: ").append(ver(aptPath, "--version").take(60)).append('\n')
            // 2026-08-09 打包修复校验：标准库 / zip / libzstd（threading.py 缺失即在此暴露）
            append("python stdlib: ")
                .append(ver(pythonPath, "-c", "import threading,sqlite3,ssl,zlib,lzma;print('stdlib OK')")).append('\n')
            append("zip: ").append(ver(java.io.File(usr, "bin/zip").absolutePath, "-v").take(40)).append('\n')
            append("unzip: ").append(ver(java.io.File(usr, "bin/unzip").absolutePath, "-v").take(40)).append('\n')
            append("libzstd: ")
                .append(if (java.io.File(usr, "lib/libzstd.so.1").exists()) "OK" else "✗ 缺失").append('\n')
        }
    }

    fun deleteEnvironment() {
        root.deleteRecursively()
    }
}
