package com.mlx.app.data.store

import android.content.Context
import android.net.Uri
import com.mlx.app.core.common.MiniJson
import com.mlx.app.data.saf.TreePathResolver
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 旧镜像工程迁移（目录即工作区 1.0 → 2.0 注册表模式，启动时执行一次）。
 *
 * 对 files/projects/ 下每个旧镜像目录：
 * 1. sourceUri 可解析出真实路径 → 注册表登记真实路径工程（新哈希 id），会话 projectId 重绑，
 *    .mlx-backup 搬入真实目录，旧镜像归档 projects-archived/
 * 2. 无法解析（目录已删/provider 变化）或 sourceUri 为空 → 注册表登记 legacy 条目
 *    （rootPath=旧镜像目录，id=旧 id —— 会话归属零改动，功能不丢）
 *
 * 幂等：已登记条目跳过；会话重绑/备份搬运均幂等。可安全重复执行。
 */
object WorkspaceMigration {

    suspend fun run(
        context: Context,
        registry: ProjectRegistry,
        workspaceRepo: WorkspaceRepo,
        sessionStore: SessionStore,
    ): Int = withContext(Dispatchers.IO) {
        val oldDir = File(context.filesDir, "projects")
        if (!oldDir.isDirectory) return@withContext 0
        var migrated = 0
        for (old in oldDir.listFiles() ?: emptyArray()) {
            if (!old.isDirectory || old.name.startsWith(".")) continue
            // 已在注册表中 → 跳过（幂等）
            val known = registry.list().any { it.rootPath == old.absolutePath }
            if (known) continue
            val meta = readOldMeta(old)
            val name = meta?.get("name") as? String ?: old.name
            val sourceUri = meta?.get("sourceUri") as? String ?: ""
            val sourceDir = meta?.get("sourceDir") as? String ?: ""

            val realPath = if (sourceUri.isNotBlank()) {
                runCatching { TreePathResolver.resolve(context, Uri.parse(sourceUri)) }.getOrNull()
            } else null

            if (realPath != null && File(realPath).isDirectory) {
                // 真实目录可达 → 升级为真实路径工程
                val p = workspaceRepo.create(name, realPath) // 幂等：同路径同工程
                // 会话重绑（旧 uuid id → 新路径哈希 id）
                var rebound = 0
                for (s in sessionStore.list()) {
                    if (s.projectId == old.name) {
                        s.projectId = p.id
                        sessionStore.save(s)
                        rebound++
                    }
                }
                // 会话备份搬入真实目录（一次搬运，防丢）
                val oldBackup = File(old, ".mlx-backup")
                if (oldBackup.isDirectory) {
                    oldBackup.copyRecursively(File(realPath, ".mlx-backup"), overwrite = true)
                }
                // 旧镜像归档（不删除，防误删）
                val archiveDir = File(context.filesDir, "projects-archived")
                archiveDir.mkdirs()
                old.renameTo(File(archiveDir, old.name))
                migrated++
                android.util.Log.i("MLX-Migrate", "工程「$name」→ 真实路径 $realPath（会话重绑 $rebound 个，备份已搬运）")
            } else {
                // 降级 legacy：rootPath=旧镜像目录，id=旧 id（会话归属零改动）
                registry.upsert(
                    ProjectRegistry.Entry(
                        id = old.name,
                        name = name,
                        rootPath = old.absolutePath,
                        sourceUri = sourceUri,
                        sourceDir = sourceDir,
                        legacy = true,
                        createdAt = old.lastModified(),
                    )
                )
                migrated++
                android.util.Log.i("MLX-Migrate", "工程「$name」保留镜像模式（legacy，源目录不可达）")
            }
        }
        migrated
    }

    private fun readOldMeta(root: File): Map<String, Any?>? {
        val meta = File(root, "project.json")
        if (!meta.exists()) return null
        return runCatching { MiniJson.toMap(MiniJson.parse(meta.readText())) }.getOrNull()
    }
}
