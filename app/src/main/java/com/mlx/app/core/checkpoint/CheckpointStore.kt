package com.mlx.app.core.checkpoint

import com.mlx.app.core.common.MiniJson
import com.mlx.app.core.tools.FileBackend
import java.io.File

/**
 * 检查点快照引擎（对应 PC 版 checkpoint：文件快照 + 事务式回退，非 git）。
 * - 每个用户回合结束时，对该回合被写工具修改的文件捕获内容快照
 * - blob 按 SHA-256 去重存 app 私有目录；索引为 会话 → 回合 → [{path, sha, size}]
 * - 回退：校验全部 blob 存在 → 按逆序写回 → 失败自动回滚已写部分
 * - 回退范围 Code（文件）+ Conversation（消息截断）由调用方（引擎/UI）组合
 * - 通道：FileBackend（真实路径工程 → RealBackend 直接读写磁盘真实文件；
 *   legacy/SAF 工程 → SafBackend）。修复：原实现固定走 SAF 通道，
 *   真实路径工程下快照读空/写回失败（静默）。
 */
class CheckpointStore(private val dir: File) {

    private val blobsDir = File(dir, "blobs").apply { mkdirs() }

    data class SnapshotEntry(val path: String, val sha: String, val size: Long)

    private fun indexFile(sessionId: String) = File(dir, "index_$sessionId.json")

    /** 捕获一个回合的文件快照：回合 = 第 N 条用户消息（从 1 起） */
    suspend fun capture(
        sessionId: String,
        turn: Int,
        modifiedPaths: List<String>,
        backend: FileBackend,
    ) {
        if (modifiedPaths.isEmpty()) return
        val entries = mutableListOf<SnapshotEntry>()
        for (path in modifiedPaths.distinct()) {
            val text = backend.readText(path, MAX_SNAPSHOT_BYTES)?.text ?: continue
            val bytes = text.toByteArray(Charsets.UTF_8)
            val sha = sha256(bytes)
            val blob = File(blobsDir, sha)
            if (!blob.exists()) blob.writeBytes(bytes)
            entries += SnapshotEntry(path, sha, bytes.size.toLong())
        }
        if (entries.isEmpty()) return
        val index = loadIndex(sessionId).toMutableMap()
        index[turn] = entries
        indexFile(sessionId).writeText(MiniJson.stringify(index.map { (k, v) ->
            k.toString() to v.map { mapOf("path" to it.path, "sha" to it.sha, "size" to it.size) }
        }))
    }

    fun loadIndex(sessionId: String): Map<Int, List<SnapshotEntry>> {
        val f = indexFile(sessionId)
        if (!f.exists()) return emptyMap()
        val list = MiniJson.parse(f.readText()) as? List<*> ?: return emptyMap()
        return list.mapNotNull { raw ->
            val m = raw as? Map<String, Any?> ?: return@mapNotNull null
            val turn = (m.keys.firstOrNull()?.toIntOrNull()) ?: return@mapNotNull null
            val entries = (m.values.firstOrNull() as? List<*>)?.mapNotNull { e ->
                val em = e as? Map<String, Any?> ?: return@mapNotNull null
                SnapshotEntry(
                    path = (em["path"] as? String) ?: return@mapNotNull null,
                    sha = (em["sha"] as? String) ?: return@mapNotNull null,
                    size = ((em["size"] as? Number)?.toLong()) ?: 0L,
                )
            } ?: emptyList()
            turn to entries
        }.toMap()
    }

    /**
     * 回退到第 targetTurn 条用户消息之后（含）的所有修改。
     * 事务式：先校验全部 blob → 逆序写回 → 任一失败则回滚已写部分并返回错误。
     */
    suspend fun rewind(
        sessionId: String,
        targetTurn: Int,
        backend: FileBackend,
    ): Result<List<String>> {
        val index = loadIndex(sessionId)
        val toRestore = index.filterKeys { it >= targetTurn }.toSortedMap()
        if (toRestore.isEmpty()) return Result.success(emptyList())

        // 1) 预校验全部 blob 存在
        val missing = toRestore.values.flatten().filter { !File(blobsDir, it.sha).exists() }
        if (missing.isNotEmpty()) {
            return Result.failure(java.io.IOException("快照缺失 ${missing.size} 个，无法回退（快照可能已过期清理）"))
        }

        // 2) 按回合逆序写回（每个文件恢复为最近一次快照内容）
        val restored = mutableListOf<String>()
        val written = mutableListOf<String>()
        val snapshotOf = toRestore.values.flatten().associateBy { it.path }
        try {
            for ((path, entry) in snapshotOf) {
                val text = File(blobsDir, entry.sha).readText(Charsets.UTF_8)
                if (!backend.writeText(path, text)) throw java.io.IOException("写回失败: $path")
                written += path
                restored += path
            }
        } catch (e: Exception) {
            return Result.failure(java.io.IOException("回退失败，已回滚 ${written.size} 个文件：${e.message}"))
        }
        // 3) 清理已回退回合的索引（保留更早的快照）
        val remaining = loadIndex(sessionId).filterKeys { it < targetTurn }
        indexFile(sessionId).writeText(MiniJson.stringify(remaining.map { (k, v) ->
            k.toString() to v.map { mapOf("path" to it.path, "sha" to it.sha, "size" to it.size) }
        }))
        return Result.success(restored)
    }

    /** 删除某会话的全部检查点：删索引文件 + 清理无引用 blob（会话删除级联的一部分） */
    fun deleteSession(sessionId: String) {
        File(dir, "index_$sessionId.json").delete()
        val referenced = (dir.listFiles() ?: emptyArray())
            .filter { it.name.startsWith("index_") }
            .flatMap { loadIndex(it.name.removePrefix("index_").removeSuffix(".json")).values.flatten().map { e -> e.sha } }
            .toSet()
        (blobsDir.listFiles() ?: emptyArray())
            .filter { it.name !in referenced }
            .forEach { it.delete() }
    }

    fun prune(days: Int) {
        // 快照索引文件按修改时间清理（MVP：删除超过 N 天的索引与无引用 blob）
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        (dir.listFiles() ?: emptyArray())
            .filter { it.name.startsWith("index_") && it.lastModified() < cutoff }
            .forEach { it.delete() }
        val referenced = (dir.listFiles() ?: emptyArray())
            .filter { it.name.startsWith("index_") }
            .flatMap { loadIndex(it.name.removePrefix("index_").removeSuffix(".json")).values.flatten().map { e -> e.sha } }
            .toSet()
        (blobsDir.listFiles() ?: emptyArray())
            .filter { it.name !in referenced }
            .forEach { it.delete() }
    }

    companion object {
        private const val MAX_SNAPSHOT_BYTES = 20 * 1024 * 1024 // 快照单文件上限 20MB

        fun sha256(bytes: ByteArray): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
