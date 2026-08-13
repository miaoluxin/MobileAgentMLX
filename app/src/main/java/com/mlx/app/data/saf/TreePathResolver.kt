package com.mlx.app.data.saf

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import java.io.File

/**
 * SAF tree URI → 真实路径解析（目录即工作区 2.0 核心）。
 *
 * 形态：content://com.android.externalstorage.documents/tree/primary%3ADownload%2FMLXproject
 *   → getTreeDocumentId = "primary:Download/MLXproject" → 拆 volume("primary") + path("Download/MLXproject")
 *   → /storage/emulated/0/Download/MLXproject
 *
 * 限制：仅 ExternalStorageProvider（本地存储/SD 卡）可解析；网盘等 provider 返回 null（降级 SAF 模式）。
 */
object TreePathResolver {

    /** tree URI → 真实绝对路径；非 ExternalStorageProvider 或解析失败返回 null */
    fun resolve(context: Context, treeUri: Uri): String? {
        if (treeUri.authority != "com.android.externalstorage.documents") return null
        val docId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: Exception) {
            return null
        }
        val volumeRoots = runCatching {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            sm?.storageVolumes?.mapNotNull { v -> v.uuid?.let { it to v.directory } }.orEmpty()
        }.getOrDefault(emptyList()).toMap()
        return resolveDocId(docId, Environment.getExternalStorageDirectory(), volumeRoots)
    }

    /**
     * 纯解析（JVM 可测）：docId（"primary:Download/MLXproject" 或 "uuid:path"）→ 真实绝对路径。
     * primary → primaryRoot；其他卷按 UUID 匹配 volumeRoots；解析失败返回 null。
     */
    fun resolveDocId(docId: String, primaryRoot: File, volumeRoots: Map<String, File?>): String? {
        val volume = docId.substringBefore(':', "")
        val rel = docId.substringAfter(':', "")
        val root: File? = when {
            volume == "primary" -> primaryRoot
            else -> {
                val uuid = volume.lowercase()
                volumeRoots.entries.firstOrNull {
                    it.key.replace("-", "").lowercase() == uuid
                }?.value
            }
        }
        val r = root ?: return null
        return if (rel.isBlank()) r.absolutePath else File(r, rel).absolutePath
    }

    /** 交叉校验兜底：findDocumentPath（部分厂商 provider 可能抛 SecurityException，try-catch） */
    fun resolveViaFindDocumentPath(context: Context, treeUri: Uri): String? = try {
        DocumentsContract.findDocumentPath(context.contentResolver, treeUri)?.path?.joinToString("/")
    } catch (e: Exception) {
        null
    }

    /** 解析 + 存在性验证（需已授权 MANAGE_EXTERNAL_STORAGE；未授权时存在性检查会失败，返回 null） */
    fun resolveExisting(context: Context, treeUri: Uri): String? =
        resolve(context, treeUri)?.takeIf { File(it).isDirectory }
}
