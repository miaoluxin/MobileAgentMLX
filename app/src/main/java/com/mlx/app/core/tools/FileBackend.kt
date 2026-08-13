package com.mlx.app.core.tools

import com.mlx.app.data.saf.SafRepo
import java.io.File

/**
 * 文件后端抽象（M6 工作区迁移）：
 * - SafBackend：SAF 树（content://，旧项目兼容）
 * - RealBackend：真实 POSIX 路径（files/projects/，完整环境 shell/python/git 可直接操作）
 * 工具按当前项目类型路由（ToolContext.workspaceRoot 非空 → RealBackend）。
 */
interface FileBackend {
    data class ReadOutcome(val text: String, val truncated: Boolean)
    data class EditOutcome(val oldContent: String, val newContent: String, val diffText: String)
    data class Entry(val name: String, val relPath: String, val isDir: Boolean, val size: Long, val lastModified: Long)

    fun readText(relPath: String, maxBytes: Int): ReadOutcome?
    /** P3：读取原始字节（图片预览等） */
    fun readBytes(relPath: String, maxBytes: Int): ByteArray?
    /** P3：新建文件/文件夹 */
    fun createFile(relPath: String): Boolean
    fun createDir(relPath: String): Boolean
    fun writeText(relPath: String, content: String): Boolean
    fun editText(relPath: String, search: String, replace: String): EditOutcome?
    fun listTree(relPath: String, maxDepth: Int): List<Entry>
    fun listDir(relPath: String): List<Entry>
    fun search(query: String, root: String, maxResults: Int): List<Entry>
    fun move(from: String, to: String): Boolean
    /** P8：重命名/复制/删除（文件管理） */
    fun rename(path: String, newName: String): Boolean
    fun copy(from: String, to: String): Boolean
    fun delete(path: String): Boolean
    /** P13：文件内容搜索（grep） */
    fun grep(query: String, root: String, maxResults: Int): List<Pair<String, String>>
}

/** SAF 后端：委托 SafRepo */
class SafBackend(private val saf: SafRepo) : FileBackend {
    override fun readText(relPath: String, maxBytes: Int): FileBackend.ReadOutcome? =
        saf.readText(relPath, maxBytes)?.let { FileBackend.ReadOutcome(it.text, it.truncated) }

    override fun readBytes(relPath: String, maxBytes: Int): ByteArray? =
        kotlinx.coroutines.runBlocking { saf.readBytes(relPath, maxBytes) }
    override fun createFile(relPath: String): Boolean = saf.createTextFile(relPath)
    override fun createDir(relPath: String): Boolean = saf.createDirectory(relPath)

    override fun writeText(relPath: String, content: String): Boolean = saf.writeText(relPath, content)
    override fun editText(relPath: String, search: String, replace: String): FileBackend.EditOutcome? =
        saf.editText(relPath, search, replace)?.let { FileBackend.EditOutcome(it.oldContent, it.newContent, it.diffText) }

    override fun listTree(relPath: String, maxDepth: Int): List<FileBackend.Entry> =
        kotlinx.coroutines.runBlocking { saf.listTree(relPath, maxDepth) }.map { FileBackend.Entry(it.name, it.relPath, it.isDir, it.size, it.lastModified) }

    override fun listDir(relPath: String): List<FileBackend.Entry> =
        kotlinx.coroutines.runBlocking { saf.listDir(relPath) }.map { FileBackend.Entry(it.name, it.relPath, it.isDir, it.size, it.lastModified) }

    override fun search(query: String, root: String, maxResults: Int): List<FileBackend.Entry> =
        kotlinx.coroutines.runBlocking { saf.search(query, root, maxResults) }.map { FileBackend.Entry(it.name, it.relPath, it.isDir, it.size, it.lastModified) }

    override fun move(from: String, to: String): Boolean = saf.move(from, to)
    override fun rename(path: String, newName: String): Boolean = saf.rename(path, newName)
    override fun copy(from: String, to: String): Boolean = saf.copy(from, to)
    override fun delete(path: String): Boolean = saf.delete(path)
    override fun grep(query: String, root: String, maxResults: Int): List<Pair<String, String>> =
        kotlinx.coroutines.runBlocking { saf.grep(query, root, maxResults) }
}

/** 真实路径后端：java.io.File 直接操作（性能远优于 SAF） */
class RealBackend(private val root: File) : FileBackend {

    /**
     * 解析相对路径并校验：禁止逃出工作区（../ 越界、符号链接指向外部）。
     * 二十二批（审计 CRITICAL）：此前无规范化校验，read_file("../x") 可读写工作区外 ——
     * 与 BASE"文件工具只能在项目工作区内使用相对路径"承诺不符。
     * 越界返回 null（调用方按"文件不存在/操作失败"处理）。
     */
    private fun resolveSafe(relPath: String): File? {
        val key = relPath.trim('/')
        if (key.isEmpty()) return root
        return runCatching {
            val canonicalRoot = root.canonicalFile
            val candidate = File(root, key).canonicalFile
            if (candidate == canonicalRoot || candidate.path.startsWith(canonicalRoot.path + File.separator)) candidate
            else null
        }.getOrNull()
    }

    override fun readText(relPath: String, maxBytes: Int): FileBackend.ReadOutcome? {
        val f = resolveSafe(relPath) ?: return null
        if (!f.isFile) return null
        val length = f.length()
        val truncated = length > maxBytes
        val n = minOf(length, maxBytes.toLong()).toInt()
        val bytes = f.inputStream().use { it.readNBytes(n) }
        return FileBackend.ReadOutcome(String(bytes, Charsets.UTF_8), truncated)
    }

    override fun readBytes(relPath: String, maxBytes: Int): ByteArray? {
        val f = resolveSafe(relPath) ?: return null
        if (!f.isFile) return null
        val n = minOf(f.length(), maxBytes.toLong()).toInt()
        return f.inputStream().use { it.readNBytes(n) }
    }

    /** 新建文件（P3） */
    override fun createFile(relPath: String): Boolean {
        val f = resolveSafe(relPath) ?: return false
        f.parentFile?.mkdirs()
        return runCatching { f.createNewFile() }.getOrDefault(false)
    }

    /** 新建文件夹（P3） */
    override fun createDir(relPath: String): Boolean = resolveSafe(relPath)?.let { runCatching { it.mkdirs() }.getOrDefault(false) } ?: false

    override fun writeText(relPath: String, content: String): Boolean {
        val f = resolveSafe(relPath) ?: return false
        f.parentFile?.mkdirs()
        return runCatching { f.writeText(content, Charsets.UTF_8); true }.getOrDefault(false)
    }

    override fun editText(relPath: String, search: String, replace: String): FileBackend.EditOutcome? {
        val cur = readText(relPath, Int.MAX_VALUE) ?: return null
        val idx = cur.text.indexOf(search)
        if (idx < 0) return null
        val newText = cur.text.substring(0, idx) + replace + cur.text.substring(idx + search.length)
        if (!writeText(relPath, newText)) return null
        return FileBackend.EditOutcome(cur.text, newText, makeDiff(cur.text, newText))
    }

    override fun listTree(relPath: String, maxDepth: Int): List<FileBackend.Entry> {
        val start = resolveSafe(relPath) ?: return emptyList()
        val out = mutableListOf<FileBackend.Entry>()
        fun walk(dir: File, depth: Int) {
            if (depth > maxDepth) return
            dir.listFiles()?.forEach { f ->
                val rel = f.relativeTo(root).path.replace('\\', '/')
                out += FileBackend.Entry(f.name, rel, f.isDirectory, if (f.isFile) f.length() else 0L, f.lastModified())
                if (f.isDirectory) walk(f, depth + 1)
            }
        }
        walk(start, 0)
        return out
    }

    override fun listDir(relPath: String): List<FileBackend.Entry> {
        val dir = resolveSafe(relPath) ?: return emptyList()
        if (!dir.isDirectory) return emptyList()
        return (dir.listFiles() ?: emptyArray())
            .map { f ->
                val rel = f.relativeTo(root).path.replace('\\', '/')
                FileBackend.Entry(f.name, rel, f.isDirectory, if (f.isFile) f.length() else 0L, f.lastModified())
            }
            .sortedWith(compareBy<FileBackend.Entry> { if (it.isDir) 0 else 1 }.thenBy { it.name.lowercase() })
    }

    override fun search(query: String, rootPath: String, maxResults: Int): List<FileBackend.Entry> {
        val start = resolveSafe(rootPath) ?: return emptyList()
        val q = query.lowercase()
        val out = mutableListOf<FileBackend.Entry>()
        fun walk(dir: File, depth: Int) {
            if (depth > 8 || out.size >= maxResults) return
            dir.listFiles()?.forEach { f ->
                if (out.size >= maxResults) return
                if (f.isFile && f.name.lowercase().contains(q)) {
                    out += FileBackend.Entry(f.name, f.relativeTo(root).path.replace('\\', '/'), false, f.length(), f.lastModified())
                }
                if (f.isDirectory) walk(f, depth + 1)
            }
        }
        walk(start, 0)
        return out
    }

    override fun move(from: String, to: String): Boolean {
        val src = resolveSafe(from) ?: return false
        val dst = resolveSafe(to) ?: return false
        dst.parentFile?.mkdirs()
        return runCatching { src.renameTo(dst) }.getOrDefault(false)
    }

    override fun rename(path: String, newName: String): Boolean {
        if (newName.isBlank() || newName.contains('/')) return false
        val src = resolveSafe(path) ?: return false
        val dst = File(src.parentFile, newName)
        return runCatching { src.renameTo(dst) }.getOrDefault(false)
    }

    override fun copy(from: String, to: String): Boolean {
        val src = resolveSafe(from) ?: return false
        if (!src.isFile) return false
        val dst = resolveSafe(to) ?: return false
        dst.parentFile?.mkdirs()
        return runCatching { src.copyTo(dst, overwrite = true); true }.getOrDefault(false)
    }

    override fun delete(path: String): Boolean {
        val f = resolveSafe(path) ?: return false
        return runCatching { if (f.isDirectory) f.deleteRecursively() else f.delete() }.getOrDefault(false)
    }

    override fun grep(query: String, rootPath: String, maxResults: Int): List<Pair<String, String>> {
        val start = resolveSafe(rootPath) ?: return emptyList()
        val q = query.lowercase()
        val out = mutableListOf<Pair<String, String>>()
        fun walk(dir: File, depth: Int) {
            if (depth > 6 || out.size >= maxResults) return
            dir.listFiles()?.forEach { f ->
                if (out.size >= maxResults) return
                if (f.isFile && f.length() <= 512 * 1024) {
                    runCatching {
                        val text = f.readText(Charsets.UTF_8)
                        val idx = text.lowercase().indexOf(q)
                        if (idx >= 0) {
                            val start = (idx - 60).coerceAtLeast(0)
                            val snippet = text.substring(start, (idx + 120).coerceAtMost(text.length)).replace('\n', ' ')
                            out += f.relativeTo(root).path.replace('\\', '/') to snippet
                        }
                    }
                }
                if (f.isDirectory) walk(f, depth + 1)
            }
        }
        walk(start, 0)
        return out
    }

    private fun makeDiff(old: String, new: String): String {
        val o = old.split('\n')
        val n = new.split('\n')
        val sb = StringBuilder()
        var i = 0
        while (i < o.size || i < n.size) {
            val ol = o.getOrNull(i)
            val nl = n.getOrNull(i)
            when {
                ol != null && nl == null -> sb.append("- ").append(ol).append('\n')
                ol == null && nl != null -> sb.append("+ ").append(nl).append('\n')
                ol == nl -> if (sb.length < 400) sb.append("  ").append(ol).append('\n')
                else -> {
                    sb.append("- ").append(ol).append('\n')
                    sb.append("+ ").append(nl).append('\n')
                }
            }
            i++
        }
        return sb.toString().take(2000)
    }
}
