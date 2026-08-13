package com.mlx.app.core.tools

/**
 * @文件引用展开（Bug 2 修复）：消息中 "@相对路径" → 读取文件内容内联进用户消息。
 * 此前 @文件 只是 UI 纯文本拼接，文件内容从未进上下文 —— AI 手头没有内容，只能自己全量扫描。
 * 展开后 AI 必定拿到引用内容，配合 SystemPrompts 聚焦指令避免全项目遍历。
 *
 * 核心纯函数可单测；后端按真实路径/SAF 路由（与文件工具同源）。
 * 降级策略：文件不存在/不可读/内容为空 → 跳过该引用（原 @token 保留），永不阻塞发送。
 */
object FileAttachments {
    const val MAX_FILE_CHARS = 32_000 // 单文件上限（对齐工具输出收缩 32KB）
    const val MAX_FILES = 5           // 单条消息引用上限（防上下文爆炸）

    // 文件路径字符集：字母/数字/下划线/点/斜杠/连字符/常用汉字 ——
    // ① 不含冒号 → @skill:（技能标记）与旧 @附件: 天然排除；
    // ② 不含中文标点（，。等）→ @src/Main.kt， 自动在逗号处截断；
    // ③ (?<![/\w])@ → user@example.com（@ 前是字母）与 https://host/@path（@ 前是 /）都不误判
    private val REF_REGEX = Regex("(?<![\\w/])@(?!skill:)([A-Za-z0-9_./\\-\\u4e00-\\u9fff]+)")

    /** 提取消息中的 @引用路径（去重、保序） */
    fun extractRefs(text: String): List<String> =
        REF_REGEX.findAll(text).map { it.groupValues[1] }.distinct().toList()

    /**
     * 展开：读存在且可读的文件并追加内容块；失败路径原样保留 @token。
     * 输出格式与 SystemPrompts【@文件引用】指令约定的「── @附件: 路径 ──」一致。
     */
    fun expand(text: String, backend: FileBackend): String {
        val refs = extractRefs(text)
        if (refs.isEmpty()) return text
        var sb: StringBuilder? = null
        var count = 0
        for (ref in refs) {
            if (count >= MAX_FILES) break
            // 幂等：文本已含该文件的附件块（正常版或截断版）→ 跳过，防历史消息复制重发时二次注入
            if (text.contains("── 附件: $ref ──") || text.contains("── 附件: $ref（")) continue
            val outcome = backend.readText(ref, MAX_FILE_CHARS) ?: continue
            if (outcome.text.isBlank()) continue
            count++
            val b = sb ?: StringBuilder(text).append('\n').also { sb = it }
            // 注意：块前缀不带 @（REF_REGEX 会提取 @开头标记，避免展开块被自身正则误提取）
            b.append("\n── 附件: ").append(ref)
                .append(if (outcome.truncated) "（超过 $MAX_FILE_CHARS 字符，已截断）" else "")
                .append(" ──\n").append(outcome.text)
        }
        return sb?.toString() ?: text
    }
}
