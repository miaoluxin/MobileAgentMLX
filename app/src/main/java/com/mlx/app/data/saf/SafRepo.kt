package com.mlx.app.data.saf

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * SAF 文件系统层（对应 PC 版文件工具的基础设施）。
 * - 树 URI 持久授权（takePersistableUriPermission）
 * - DocumentFile 相对路径解析 + 内存缓存
 * - 目录列表 TTL 缓存、外部变更指纹检测
 * - 唯一文件访问入口：禁止任何 java.io.File 绝对路径操作
 */
class SafRepo(private val context: Context) {

    @Volatile
    var treeUri: Uri? = null
        private set

    var rootName: String = ""
        private set

    private val resolver get() = context.contentResolver
    private val docCache = mutableMapOf<String, DocumentFile?>()
    private val listCache = mutableMapOf<String, Pair<Long, List<FileEntry>>>()
    private val LIST_TTL_MS = 10_000L

    data class FileEntry(
        val name: String,
        val relPath: String,
        val isDir: Boolean,
        val size: Long,
        val lastModified: Long,
    )

    data class ReadOutcome(val text: String, val truncated: Boolean, val fingerprint: String)

    data class EditOutcome(val oldContent: String, val newContent: String, val diffText: String)

    fun pickerIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        )
    }

    fun bindTree(uri: Uri, displayName: String?): Boolean {
        return try {
            resolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            treeUri = uri
            rootName = displayName
                ?: uri.lastPathSegment?.substringAfterLast(':')
                ?: "项目"
            docCache.clear()
            listCache.clear()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun hasPersistedPermission(uri: Uri): Boolean = try {
        resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
    } catch (e: Exception) {
        false
    }

    fun rootDoc(): DocumentFile? = treeUri?.let { DocumentFile.fromTreeUri(context, it) }

    /** 相对路径 → DocumentFile（带缓存；路径不存在返回 null） */
    fun resolve(relPath: String): DocumentFile? {
        if (relPath.isBlank()) return rootDoc()
        val key = relPath.trim('/')
        docCache[key]?.let { return it }
        var cur = rootDoc() ?: return null
        var ok = true
        for (p in key.split('/')) {
            if (p.isBlank()) continue
            val next = cur.findFile(p)
            if (next == null) { ok = false; break }
            cur = next
        }
        val doc = if (ok) cur else null
        docCache[key] = doc
        return doc
    }

    /** 目录列表（IO 阻塞；10s TTL 缓存） */
    fun listDirBlocking(relPath: String): List<FileEntry> {
        val key = relPath.trim('/')
        val cached = listCache[key]
        if (cached != null && System.currentTimeMillis() - cached.first < LIST_TTL_MS) return cached.second
        val doc = resolve(relPath) ?: return emptyList()
        if (!doc.isDirectory) return emptyList()
        val entries = doc.listFiles()
            .mapNotNull { f ->
                val name = f.name ?: return@mapNotNull null
                val rel = if (key.isEmpty()) name else "$key/$name"
                FileEntry(name, rel, f.isDirectory, f.length(), f.lastModified())
            }
            .sortedWith(compareBy<FileEntry> { if (it.isDir) 0 else 1 }.thenBy { it.name.lowercase() })
        listCache[key] = System.currentTimeMillis() to entries
        return entries
    }

    suspend fun listDir(relPath: String): List<FileEntry> = withContext(Dispatchers.IO) {
        listDirBlocking(relPath)
    }

    /** 递归目录树（depth 上限） */
    suspend fun listTree(relPath: String, maxDepth: Int): List<FileEntry> = withContext(Dispatchers.IO) {
        val out = mutableListOf<FileEntry>()
        fun walk(dir: String, depth: Int) {
            if (depth > maxDepth) return
            for (e in listDirBlocking(dir)) {
                out += e
                if (e.isDir) walk(e.relPath, depth + 1)
            }
        }
        walk(relPath.trim('/'), 0)
        out
    }

    /** 文件名关键字搜索（不搜内容；最多 50 条） */
    suspend fun search(query: String, root: String = "", maxResults: Int = 50): List<FileEntry> =
        withContext(Dispatchers.IO) {
            val q = query.lowercase()
            val out = mutableListOf<FileEntry>()
            fun walk(dir: String, depth: Int) {
                if (depth > 8 || out.size >= maxResults) return
                for (e in listDirBlocking(dir)) {
                    if (out.size >= maxResults) return
                    if (!e.isDir && e.name.lowercase().contains(q)) out += e
                    if (e.isDir) walk(e.relPath, depth + 1)
                }
            }
            walk(root.trim('/'), 0)
            out
        }

    /** 读取文本文件（默认上限 1MB，超限截断并标记） */
    fun readText(relPath: String, maxBytes: Int = 1_000_000): ReadOutcome? {
        val doc = resolve(relPath) ?: return null
        if (doc.isDirectory) return null
        val length = doc.length().toInt().coerceAtMost(Int.MAX_VALUE)
        val truncated = length > maxBytes
        val n = minOf(length, maxBytes)
        val bytes = resolver.openInputStream(doc.uri)?.use { ins ->
            val buf = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = ins.read(buf, off, n - off)
                if (r < 0) break
                off += r
            }
            buf.copyOf(off)
        } ?: return null
        return ReadOutcome(String(bytes, Charsets.UTF_8), truncated, fingerprint(relPath) ?: "")
    }

    /** 写入/覆盖文件（父目录须存在；P9：创建时按扩展名推断 MIME，防 provider 追加 .txt） */
    fun writeText(relPath: String, content: String): Boolean {
        val key = relPath.trim('/')
        if (key.isBlank()) return false
        val existing = resolve(key)
        val doc = existing ?: run {
            val parent = resolve(key.substringBeforeLast('/', "")) ?: return false
            val name = key.substringAfterLast('/')
            val created = parent.createFile(mimeFor(name), name) ?: return false
            // 部分 DocumentsProvider 对 text/plain 自动追加 .txt —— 名称不符则修正
            if (created.name != name && name.isNotBlank()) {
                created.renameTo(name)
            }
            docCache[key] = created
            created
        }
        return try {
            resolver.openOutputStream(doc.uri, "wt")?.use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
            } != null
            invalidateWrites(key)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 按扩展名推断 MIME（避免 text/plain 触发 .txt 追加） */
    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "md", "markdown" -> "text/markdown"
        "json" -> "application/json"
        "yaml", "yml" -> "application/yaml"
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "js", "mjs" -> "text/javascript"
        "csv" -> "text/csv"
        "xml" -> "application/xml"
        "pdf" -> "application/pdf"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "txt", "log" -> "text/plain"
        "kt", "kts" -> "text/x-kotlin"
        "java" -> "text/x-java-source"
        "py" -> "text/x-python"
        "go" -> "text/x-go"
        "rs" -> "text/x-rust"
        "c", "h" -> "text/x-c"
        "cpp", "hpp", "cc" -> "text/x-c++src"
        "sh", "bash" -> "application/x-sh"
        "sql" -> "application/sql"
        "toml" -> "application/toml"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

    /** 精确替换首个匹配片段，返回 diff 预览 */
    fun editText(relPath: String, search: String, replace: String): EditOutcome? {
        val cur = readText(relPath) ?: return null
        val idx = cur.text.indexOf(search)
        if (idx < 0) return null
        val newText = cur.text.substring(0, idx) + replace + cur.text.substring(idx + search.length)
        if (!writeText(relPath, newText)) return null
        return EditOutcome(cur.text, newText, makeDiff(cur.text, newText))
    }

    fun move(from: String, to: String): Boolean {
        val src = resolve(from) ?: return false
        val parent = resolve(to.substringBeforeLast('/', "")) ?: return false
        val movedUri = runCatching {
            DocumentsContract.moveDocument(resolver, src.uri, parent.uri, src.uri)
        }.getOrNull() ?: return false
        invalidateWrites(from)
        invalidateWrites(to)
        return true
    }

    /** 重命名（P8 文件管理） */
    fun rename(path: String, newName: String): Boolean {
        if (newName.isBlank() || newName.contains('/')) return false
        val doc = resolve(path) ?: return false
        val ok = runCatching { doc.renameTo(newName) }.getOrDefault(false)
        if (ok) {
            docCache.remove(path.trim('/'))
            invalidateWrites(path)
        }
        return ok
    }

    /** 复制（P8：内容复制到目标相对路径） */
    fun copy(from: String, to: String): Boolean {
        val src = resolve(from) ?: return false
        if (src.isDirectory) return false
        val text = readText(from) ?: return false
        return writeText(to, text.text)
    }

    /** 删除（P8；目录递归删除） */
    fun delete(path: String): Boolean {
        val doc = resolve(path) ?: return false
        val ok = runCatching { doc.delete() }.getOrDefault(false)
        if (ok) {
            docCache.remove(path.trim('/'))
            invalidateWrites(path)
        }
        return ok
    }

    /** 读取原始字节（P3 图片预览） */
    suspend fun readBytes(relPath: String, maxBytes: Int): ByteArray? = withContext(Dispatchers.IO) {
        val doc = resolve(relPath) ?: return@withContext null
        if (doc.isDirectory) return@withContext null
        val n = minOf(doc.length(), maxBytes.toLong()).toInt()
        resolver.openInputStream(doc.uri)?.use { ins ->
            val buf = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = ins.read(buf, off, n - off)
                if (r < 0) break
                off += r
            }
            buf.copyOf(off)
        }
    }

    /** 新建空文本文件（P3） */
    fun createTextFile(relPath: String): Boolean {
        val key = relPath.trim('/')
        if (key.isBlank()) return false
        if (resolve(key) != null) return false
        val parent = resolve(key.substringBeforeLast('/', "")) ?: return false
        val name = key.substringAfterLast('/')
        val created = parent.createFile(mimeFor(name), name) ?: return false
        if (created.name != name && name.isNotBlank()) created.renameTo(name)
        docCache[key] = created
        return true
    }

    /** 新建目录（P3） */
    fun createDirectory(relPath: String): Boolean {
        val key = relPath.trim('/')
        if (key.isBlank()) return false
        if (resolve(key) != null) return false
        val parent = resolve(key.substringBeforeLast('/', "")) ?: return false
        val name = key.substringAfterLast('/')
        val created = parent.createDirectory(name) ?: return false
        docCache[key] = created
        return true
    }

    /** 文件内容搜索（P13 grep；大小上限保护） */
    suspend fun grep(query: String, root: String = "", maxResults: Int = 50): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            val q = query.lowercase()
            val out = mutableListOf<Pair<String, String>>()
            fun walk(dir: String, depth: Int) {
                if (depth > 6 || out.size >= maxResults) return
                for (e in listDirBlocking(dir)) {
                    if (out.size >= maxResults) return
                    if (!e.isDir && e.size <= 512 * 1024) {
                        val text = readText(e.relPath)?.text ?: continue
                        val idx = text.lowercase().indexOf(q)
                        if (idx >= 0) {
                            val start = (idx - 60).coerceAtLeast(0)
                            val snippet = text.substring(start, (idx + 120).coerceAtMost(text.length)).replace('\n', ' ')
                            out += e.relPath to snippet
                        }
                    }
                    if (e.isDir) walk(e.relPath, depth + 1)
                }
            }
            walk(root.trim('/'), 0)
            out
        }

    /** 文件指纹：SHA-256(首1KB + 尾1KB + 长度)。用于外部变更检测。 */
    fun fingerprint(relPath: String): String? {
        val doc = resolve(relPath) ?: return null
        if (doc.isDirectory) return null
        return runCatching {
            val pfd = resolver.openFileDescriptor(doc.uri, "r") ?: return null
            pfd.use { fd ->
                val len = fd.statSize
                val sha = MessageDigest.getInstance("SHA-256")
                FileInputStream(fd.fileDescriptor).use { fis ->
                    val head = ByteArray(minOf(len, 1024L).toInt())
                    val hr = fis.read(head)
                    if (hr > 0) sha.update(head, 0, hr)
                    if (len > 2048) {
                        fis.skip(len - 1024 - fis.available())
                        val tail = ByteArray(1024)
                        val tr = fis.read(tail)
                        if (tr > 0) sha.update(tail, 0, tr)
                    }
                    sha.update(len.toString().toByteArray())
                }
                sha.digest().joinToString("") { "%02x".format(it) }
            }
        }.getOrNull()
    }

    /** 项目文件总数（Onboarding 展示用；深度上限 6） */
    suspend fun countFiles(maxDepth: Int = 6): Int = withContext(Dispatchers.IO) {
        var count = 0
        fun walk(dir: String, depth: Int) {
            if (depth > maxDepth) return
            for (e in listDirBlocking(dir)) {
                if (e.isDir) walk(e.relPath, depth + 1) else count++
            }
        }
        walk("", 0)
        count
    }

    private fun invalidateWrites(relPath: String) {
        val parts = relPath.trim('/').split('/')
        var prefix = ""
        listCache.remove("")
        for (p in parts) {
            prefix = if (prefix.isEmpty()) p else "$prefix/$p"
            listCache.remove(prefix)
        }
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
