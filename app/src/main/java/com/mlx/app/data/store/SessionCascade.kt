package com.mlx.app.data.store

import android.content.Context
import android.util.Log
import com.mlx.app.core.checkpoint.CheckpointStore
import com.mlx.app.data.saf.SafRepo
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * 会话删除级联清理（修复：删除会话后 .mlx-backup 残留 → 重开 APP 已删会话"复活"）。
 *
 * 安全边界（硬约束）：
 * - 删除单个会话只删该会话 id 对应的文件：主存储 {id}.json（SessionStore 已删）+ 各工程
 *   `.mlx-backup/{id}.json`（按 id 精确单文件）+ 该会话检查点
 * - 绝不删除 `.mlx-backup` 目录本身、目录内其他会话备份、工程目录内任何用户文件/文件夹
 * - 黑名单记录已删 id，恢复流程命中则删备份并跳过（双保险，覆盖无法直接删除的备份）
 */
class SessionCascade(
    private val context: Context,
    private val workspaceRepo: WorkspaceRepo,
    private val checkpointStore: CheckpointStore,
    private val appStore: AppStore,
    private val safRepo: SafRepo,
) {

    /**
     * 会话删除后的级联清理（SessionStore.delete 钩子调用）。
     * 审查修复：黑名单同步写入（runBlocking）——原 GlobalScope 异步在进程被杀时丢失，
     * 与备份删除失败叠加会打开"会话复活"窗口。删除为低频操作，主线程短暂阻塞可接受。
     */
    fun onSessionDeleted(id: String) {
        // 1) 各工程 .mlx-backup/{id}.json（真实路径与 legacy 镜像统一经注册表遍历；按 id 精确单文件）
        var failed = 0
        for (p in workspaceRepo.list()) {
            val backup = File(p.root, ".mlx-backup").resolve("$id.json")
            // 审查修复：检查删除返回值（静默失败 → 残留备份打开复活窗口）
            if (backup.exists() && !backup.delete()) {
                failed++
                Log.w("SessionCascade", "删除备份失败: $backup")
            }
        }
        // 2) 检查点索引与孤儿 blob
        checkpointStore.deleteSession(id)
        // 3) SAF 授权目录残留备份 + 删除黑名单（同步完成，防进程被杀丢失）
        runBlocking(Dispatchers.IO) {
            deleteSafBackups(id)
            appStore.markSessionDeleted(id)
        }
        if (failed > 0) {
            Log.w("SessionCascade", "会话 $id 有 $failed 个备份删除失败，黑名单已兜底")
        }
    }

    /** 系统持久授权目录（恢复机制源2 同源）：按 id 精确删除单文件 */
    private fun deleteSafBackups(id: String) {
        for (perm in context.contentResolver.persistedUriPermissions) {
            if (!perm.isReadPermission) continue
            if (!safRepo.bindTree(perm.uri, null)) continue
            safRepo.delete(".mlx-backup/$id.json")
        }
    }
}
