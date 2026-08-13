package com.mlx.app.core.memory

import com.mlx.app.core.common.MiniJson
import java.io.File

/**
 * 背景事实记忆（对应 PC 版 facts 模型）：
 * - 每条 fact：不可变 id + 类型（user/feedback/project/reference）+ 内容 + 时间戳
 * - BM25 自动召回：回合前 top-4 / 2400 字符，作为低权威上下文注入（不进 prefix）
 * - 类型新鲜度窗口：user/feedback 90/365 天；project 30/180 天；references 14/45 天
 */
class FactMemory(private val dir: File) {

    data class Fact(
        val id: String,
        val type: String,          // user | feedback | project | reference
        val content: String,
        val createdAt: Long,
        val updatedAt: Long,
        val lastRecalledAt: Long = 0,
    )

    private val file = File(dir, "facts.json").apply { parentFile?.mkdirs() }

    fun list(): List<Fact> {
        if (!file.exists()) return emptyList()
        val list = MiniJson.parse(file.readText()) as? List<*> ?: return emptyList()
        return list.mapNotNull { raw ->
            val m = raw as? Map<String, Any?> ?: return@mapNotNull null
            Fact(
                id = (m["id"] as? String) ?: return@mapNotNull null,
                type = (m["type"] as? String) ?: "project",
                content = (m["content"] as? String) ?: "",
                createdAt = ((m["createdAt"] as? Number)?.toLong()) ?: 0L,
                updatedAt = ((m["updatedAt"] as? Number)?.toLong()) ?: 0L,
                lastRecalledAt = ((m["lastRecalledAt"] as? Number)?.toLong()) ?: 0L,
            )
        }
    }

    // 十二批修正：read-modify-write 加锁 —— 多窗口并发 add（"请记住"指令）后写覆盖先写丢事实
    @Synchronized
    fun add(type: String, content: String): Fact {
        val now = System.currentTimeMillis()
        // id 必须唯一：仅时间戳在同一毫秒内两次 add 会碰撞 → 按 id 删除时误删同毫秒的兄弟事实
        val fact = Fact("f_${now}_${System.nanoTime()}", type, content.trim(), now, now)
        save(list() + fact)
        return fact
    }

    @Synchronized
    fun delete(id: String) {
        save(list().filterNot { it.id == id })
    }

    // 二十二批（审计）：update 补锁（十二批只修了 add/delete，update 读改写同样竞态）
    @Synchronized
    fun update(id: String, content: String) {
        val now = System.currentTimeMillis()
        save(list().map { if (it.id == id) it.copy(content = content, updatedAt = now) else it })
    }

    private fun save(facts: List<Fact>) {
        file.writeText(
            MiniJson.stringify(
                facts.map { mapOf(
                    "id" to it.id, "type" to it.type, "content" to it.content,
                    "createdAt" to it.createdAt, "updatedAt" to it.updatedAt,
                    "lastRecalledAt" to it.lastRecalledAt,
                ) }
            )
        )
    }

    /** 回合前召回：BM25 打分 + 类型新鲜度过滤 + top-4/2400 字符 */
    fun recall(query: String, topK: Int = 4, maxChars: Int = 2400): List<Fact> {
        val now = System.currentTimeMillis()
        val fresh = list().filter { f ->
            val (recallWin, autoWin) = when (f.type) {
                "user" -> 90L to 365L
                "feedback" -> 90L to 365L
                "reference" -> 14L to 45L
                else -> 30L to 180L
            }
            now - f.updatedAt < recallWin * 86_400_000L
        }
        if (fresh.isEmpty() || query.isBlank()) return emptyList()
        // 只召回与查询相关的（score > 0），并按相关度降序
        val scored = fresh.map { fact ->
            fact to BM25.score(query, fact.content, fresh.size, fresh.count { f -> f.content.contains(query) })
        }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
        val result = mutableListOf<Fact>()
        var total = 0
        for (f in scored) {
            if (total + f.content.length > maxChars || result.size >= topK) break
            result += f.copy(lastRecalledAt = now)
            total += f.content.length
        }
        if (result.isNotEmpty()) {
            save(fresh.map { f -> if (result.any { it.id == f.id }) f.copy(lastRecalledAt = now) else f })
        }
        return result
    }
}

/** 简化 BM25：英文按空白分词，中文按 2-gram 回退（无外部分词依赖） */
object BM25 {
    const val K1 = 1.2
    const val B = 0.75

    fun tokenize(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val ascii = StringBuilder()
        for (c in text.lowercase()) {
            if (c.isLetterOrDigit() && c.code < 128) {
                ascii.append(c)
            } else {
                if (ascii.isNotEmpty()) { tokens += ascii.toString(); ascii.clear() }
                // 中文连续字符按 2-gram 切分
                val ch = c
                if (ch.code in 0x4E00..0x9FFF) tokens += ch.toString()
            }
        }
        if (ascii.isNotEmpty()) tokens += ascii.toString()
        // 相邻 CJK 组成 2-gram（近似分词）
        val cjk = tokens.filter { it.length == 1 && it[0].code in 0x4E00..0x9FFF }
        if (cjk.size >= 2) {
            for (i in 0 until cjk.size - 1) tokens += cjk[i] + cjk[i + 1]
        }
        return tokens
    }

    fun score(query: String, doc: String, corpusSize: Int, df: Int): Double {
        if (corpusSize == 0) return 0.0
        val qTokens = tokenize(query).distinct()
        if (qTokens.isEmpty()) return 0.0
        val docTokens = tokenize(doc)
        val avgLen = docTokens.size.toDouble().coerceAtLeast(1.0)
        val idf = Math.log(1.0 + (corpusSize - df + 0.5) / (df + 0.5))
        var s = 0.0
        for (t in qTokens) {
            val tf = docTokens.count { it == t }
            if (tf == 0) continue
            s += idf * (tf * (K1 + 1)) / (tf + K1 * (1 - B + B * docTokens.size / avgLen))
        }
        return s
    }
}
