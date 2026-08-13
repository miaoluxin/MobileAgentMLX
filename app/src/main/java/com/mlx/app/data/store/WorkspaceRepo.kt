package com.mlx.app.data.store

import android.content.Context
import android.net.Uri
import com.mlx.app.core.common.MiniJson
import com.mlx.app.data.saf.SafRepo
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 工程工作区（目录即工作区 2.0 —— 注册表模式）：
 * - **工程注册表（project-registry.json）是唯一真相源**：工程建立/绑定/重命名/删除全部
 *   经本类同步登记，启动时读注册表还原树状结构，不再扫描磁盘目录发现工程
 * - 真实路径工程（legacy=false）：root = 磁盘真实目录（/storage/emulated/0/...），
 *   所有工具直接操作真实文件，零镜像零回写
 * - 旧镜像工程（legacy=true）：root = 私有镜像目录 files/projects/<id>/，
 *   保留 bindSource/syncToSource/ensureMirrored 旧闭环（迁移过渡期/非 ExternalStorageProvider 降级）
 * - 工程 id = "p_" + sha256(rootPath) 前 8 位（同一目录重复绑定 → 同一工程）
 */
class WorkspaceRepo(
    projectsDir: File,
    private val registry: ProjectRegistry,
    private val context: Context? = null, // null = 仅元数据模式（测试用；授权/回写不可用）
) {

    private val projectsDir: File = projectsDir.apply { mkdirs() }

    data class Project(
        val id: String,
        var name: String,
        val root: File,               // 真实路径（real）或私有镜像目录（legacy）
        val sourceUri: String = "",   // 磁盘源目录 SAF 树 URI（空 = 应用内空工程）
        val sourceDir: String = "",   // 磁盘目录显示名
        val legacy: Boolean = false,  // true = 旧镜像模式（私有目录 + 回写）
        val createdAt: Long = 0L,
    ) {
        val path: String get() = root.absolutePath
    }

    /** 注册表快照（供 UI/迁移读取条目级信息） */
    fun registryEntries(): List<ProjectRegistry.Entry> = registry.list()

    /**
     * 创建工程。
     * - rootPath 非空 → 真实路径工程（id=路径哈希，注册表同步登记）
     * - rootPath 为空 → legacy 空工程（私有镜像目录，可随后 bindSource）
     * 同一真实路径重复创建返回已存在的同一工程（幂等）。
     */
    fun create(name: String, rootPath: String? = null): Project {
        if (rootPath != null) {
            val real = File(rootPath)
            val existing = registry.list().firstOrNull { it.rootPath == real.absolutePath }
            if (existing != null) {
                // 幂等复用：同目录重复绑定 → 同一工程；用户输入了新名字 → 覆盖旧名
                // （修复：复用旧工程时名字不更新，会话树显示的还是旧名）
                if (name.isNotBlank() && name != existing.name) {
                    registry.upsert(existing.copy(name = name))
                }
                return fromEntry(registry.find(existing.id) ?: existing)
            }
            val id = projectIdFor(real.absolutePath)
            val p = Project(id, name.ifBlank { real.name }, real, createdAt = System.currentTimeMillis())
            writeMeta(p)
            return p
        }
        val id = "p_" + java.util.UUID.randomUUID().toString().substring(0, 8)
        val root = File(projectsDir, id)
        root.mkdirs()
        val p = Project(id, name.ifBlank { "工程" }, root, legacy = true, createdAt = System.currentTimeMillis())
        writeMeta(p)
        return p
    }

    /** 绑定磁盘源目录（legacy 专用）：复制 SAF 树为镜像 + 记录 sourceUri/sourceDir */
    suspend fun bindSource(project: Project, uri: Uri, dirName: String, onProgress: (Int, Int) -> Unit = { _, _ -> }): Project? {
        val ctx = context ?: return null
        if (!ctx.contentResolver.persistedUriPermissions.any { it.uri == uri }) {
            // 无持久权限则先授权
            if (!tryTakePersist(ctx, uri)) return null
        }
        val saf = SafRepo(ctx)
        if (!saf.bindTree(uri, dirName)) return null
        val all = saf.listTree("", 4)
        val files = all.filter { !it.isDir }
        var copied = 0
        for (f in files) {
            val out = saf.readText(f.relPath, 20 * 1024 * 1024) ?: continue
            val target = File(project.root, f.relPath)
            target.parentFile?.mkdirs()
            target.writeText(out.text, Charsets.UTF_8)
            copied++
            if (copied % 50 == 0) onProgress(copied, files.size)
        }
        onProgress(copied, files.size)
        val updated = project.copy(sourceUri = uri.toString(), sourceDir = dirName)
        writeMeta(updated)
        return updated
    }

    /**
     * 回合末自动回写（legacy 专用）：镜像改动文件写回磁盘源目录。
     * deletedPaths：镜像中已不存在的文件（移动/重命名源）→ 同步删除磁盘源目录对应文件。
     */
    suspend fun syncToSource(project: Project, modifiedPaths: List<String>, deletedPaths: List<String> = emptyList()): Int {
        if (project.sourceUri.isBlank() || (modifiedPaths.isEmpty() && deletedPaths.isEmpty())) return 0
        val ctx = context ?: return 0
        return withContext(Dispatchers.IO) {
            var synced = 0
            try {
                val saf = SafRepo(ctx)
                if (!saf.bindTree(Uri.parse(project.sourceUri), project.sourceDir)) return@withContext 0
                for (rel in modifiedPaths.distinct()) {
                    val src = File(project.root, rel)
                    if (src.isFile) {
                        if (saf.writeText(rel, src.readText(Charsets.UTF_8))) synced++
                    }
                }
                // 镜像已无该文件 → 磁盘源目录同步删除（重命名/移动闭环）
                for (rel in deletedPaths.distinct()) {
                    if (!File(project.root, rel).exists() && saf.delete(rel)) synced++
                }
            } catch (e: Exception) {
                // 回写失败不阻塞回合（下次回写重试）
            }
            synced
        }
    }

    /** 读路径（legacy 专用）：镜像优先；镜像缺失从源目录懒拉取（ensureMirrored） */
    suspend fun ensureMirrored(project: Project, relPath: String): Boolean {
        if (File(project.root, relPath).isFile) return true
        if (project.sourceUri.isBlank()) return false
        val ctx = context ?: return false
        return withContext(Dispatchers.IO) {
            try {
                val saf = SafRepo(ctx)
                if (!saf.bindTree(Uri.parse(project.sourceUri), project.sourceDir)) return@withContext false
                val out = saf.readText(relPath, 20 * 1024 * 1024) ?: return@withContext false
                val target = File(project.root, relPath)
                target.parentFile?.mkdirs()
                target.writeText(out.text, Charsets.UTF_8)
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    /** 从注册表读全部工程（不扫描磁盘目录） */
    fun list(): List<Project> = registry.list().map { fromEntry(it) }

    fun find(id: String): Project? = registry.find(id)?.let { fromEntry(it) }

    /** 按真实路径查找（路径已规范化，供幂等绑定用） */
    fun findByPath(path: String): Project? =
        registry.list().firstOrNull { it.rootPath == File(path).absolutePath }?.let { fromEntry(it) }

    /** 会话备份：写入工程根目录 .mlx-backup/<id>.json（真实工程=磁盘目录；legacy=镜像，随回写落盘） */
    fun backupSession(project: Project, sessionId: String, sessionJson: String): String {
        val backupDir = File(project.root, ".mlx-backup")
        backupDir.mkdirs()
        val f = File(backupDir, "$sessionId.json")
        f.writeText(sessionJson)
        return f.relativeTo(project.root).path.replace('\\', '/')
    }

    /** 枚举工程内全部会话备份（恢复用） */
    fun backupSessionsOf(project: Project): List<File> {
        val backupDir = File(project.root, ".mlx-backup")
        if (!backupDir.isDirectory) return emptyList()
        return (backupDir.listFiles() ?: emptyArray()).filter { it.extension == "json" }
    }

    /** 重命名（更新注册表） */
    fun rename(id: String, newName: String): Boolean {
        val e = registry.find(id) ?: return false
        if (newName.isBlank()) return false
        registry.upsert(e.copy(name = newName.trim()))
        return true
    }

    /** 删除：移除注册表条目；legacy 镜像目录一并删除；真实目录不删（用户磁盘文件） */
    fun delete(id: String) {
        val e = registry.find(id) ?: return
        if (e.legacy) File(e.rootPath).deleteRecursively()
        registry.remove(id)
    }

    fun sizeBytes(id: String): Long {
        val e = registry.find(id) ?: return 0L
        val root = File(e.rootPath)
        return if (root.exists()) root.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
    }

    private fun writeMeta(p: Project) {
        registry.upsert(
            ProjectRegistry.Entry(
                id = p.id,
                name = p.name,
                rootPath = p.root.absolutePath,
                sourceUri = p.sourceUri,
                sourceDir = p.sourceDir,
                legacy = p.legacy,
                createdAt = p.createdAt,
            )
        )
    }

    private fun fromEntry(e: ProjectRegistry.Entry): Project = Project(
        id = e.id,
        name = e.name,
        root = File(e.rootPath),
        sourceUri = e.sourceUri,
        sourceDir = e.sourceDir,
        legacy = e.legacy,
        createdAt = e.createdAt,
    )

    /** 稳定工程 id：真实路径哈希（同一目录重复绑定 → 同一工程） */
    private fun projectIdFor(path: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(path.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "p_" + hex.substring(0, 8)
    }

    private fun tryTakePersist(ctx: Context, uri: Uri): Boolean = try {
        ctx.contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        true
    } catch (e: Exception) {
        false
    }
}
