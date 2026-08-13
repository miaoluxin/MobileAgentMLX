package com.mlx.app.data.store

import com.mlx.app.core.common.MiniJson
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 工程注册表 —— 全部工程的权威存储（单一 JSON 文件 filesDir/project-registry.json）。
 *
 * 设计约定（目录即工作区 2.0）：
 * - 工程建立/绑定/重命名/删除时经 WorkspaceRepo **唯一写入口** 同步登记（随工程建立同步生成）
 * - App 启动时一次读入注册表 → 还原全部工程节点与树状信息，不再依赖扫描磁盘目录"发现"工程
 * - 磁盘目录只是可选的 rootPath 目标；注册表是工程的唯一真相源，工程信息不因目录消失而丢失
 * - 原子落盘：先写临时文件再 rename，防止进程被杀产生半写文件
 */
class ProjectRegistry(private val registryFile: File) {

    data class Entry(
        val id: String,
        val name: String,
        val rootPath: String,
        val sourceUri: String = "",   // SAF 树 URI（授权句柄，checkpoint/备份恢复仍用）
        val sourceDir: String = "",   // 磁盘目录显示名
        val legacy: Boolean = false,  // true = 旧镜像模式工程（私有目录 root，无真实路径）
        val createdAt: Long = 0L,
        val updatedAt: Long = 0L,
    )

    private val lock = ReentrantLock()
    private var cache: List<Entry>? = null

    fun list(): List<Entry> = lock.withLock { cache ?: load() }

    fun find(id: String): Entry? = list().firstOrNull { it.id == id }

    fun upsert(entry: Entry) {
        lock.withLock {
            val now = System.currentTimeMillis()
            val all = list().filter { it.id != entry.id } +
                entry.copy(createdAt = entry.createdAt.let { if (it == 0L) now else it }, updatedAt = now)
            cache = all
            persist(all)
        }
    }

    fun remove(id: String): Boolean {
        lock.withLock {
            val all = list()
            val remaining = all.filter { it.id != id }
            if (remaining.size == all.size) return false
            cache = remaining
            persist(remaining)
            return true
        }
    }

    /** 批量重建（迁移/恢复用）：整表替换后落盘 */
    fun replaceAll(entries: List<Entry>) {
        lock.withLock {
            cache = entries
            persist(entries)
        }
    }

    private fun load(): List<Entry> {
        if (!registryFile.exists()) return emptyList()
        return try {
            val list = MiniJson.parse(registryFile.readText()) as? List<*> ?: return emptyList()
            list.mapNotNull { raw ->
                val m = raw as? Map<String, Any?> ?: return@mapNotNull null
                Entry(
                    id = m["id"] as? String ?: return@mapNotNull null,
                    name = (m["name"] as? String) ?: "",
                    rootPath = (m["rootPath"] as? String) ?: return@mapNotNull null,
                    sourceUri = (m["sourceUri"] as? String) ?: "",
                    sourceDir = (m["sourceDir"] as? String) ?: "",
                    legacy = (m["legacy"] as? Boolean) ?: false,
                    createdAt = (m["createdAt"] as? Number)?.toLong() ?: 0L,
                    updatedAt = (m["updatedAt"] as? Number)?.toLong() ?: 0L,
                )
            }
        } catch (e: Exception) {
            // 损坏自愈：备份原文件后再返回空（防后续 upsert 用空列表覆盖丢失原始数据，留待人工恢复）
            runCatching {
                val bak = File(registryFile.parentFile, registryFile.name + ".corrupt-" + System.currentTimeMillis())
                registryFile.copyTo(bak, overwrite = true)
                android.util.Log.e("MLX-Registry", "注册表解析失败（${e.message}），已备份到 $bak，将重建")
            }
            emptyList()
        }
    }

    private fun persist(entries: List<Entry>) {
        runCatching {
            registryFile.parentFile?.mkdirs()
            val json = MiniJson.stringify(
                entries.map { e ->
                    mapOf(
                        "id" to e.id,
                        "name" to e.name,
                        "rootPath" to e.rootPath,
                        "sourceUri" to e.sourceUri,
                        "sourceDir" to e.sourceDir,
                        "legacy" to e.legacy,
                        "createdAt" to e.createdAt,
                        "updatedAt" to e.updatedAt,
                    )
                }
            )
            val tmp = File(registryFile.parentFile, registryFile.name + ".tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(registryFile)) {
                // rename 失败（极少见）→ 直接覆盖写，保证注册表不丢
                registryFile.writeText(json)
            }
        }
    }
}
