package com.mlx.app.core.provider

/**
 * 一键自动配置（对应 PC 端 setup 向导的机制移植）：
 * PC 端内置 Provider 预设表（deepseek-flash / deepseek-pro，含 base_url、模型、
 * 价格、上下文窗口），用户只需提供 API Key；可选地通过 GET /models 探测刷新模型列表。
 *
 * 本模块：
 * - 内置官方预设常量（与 PC config.go 的预设一致）
 * - pickModels() 从 /models 探测结果自动挑选 flash/pro（探测失败回退静态预设，
 *   对应 PC 的 familyStaticModels 回退）
 */
object AutoConfig {

    // 内置预设（对应 PC internal/config/config.go 的 Provider 预设）
    const val DEFAULT_BASE_URL = "https://api.deepseek.com/v1"
    const val PREFERRED_FLASH_MODEL = "deepseek-v4-flash"
    const val PREFERRED_PRO_MODEL = "deepseek-v4-pro"

    /**
     * 从 /models 返回的模型 id 列表自动挑选 flash 与 pro。
     * 挑选规则：优先 v4 系列；flash 回退旧别名 deepseek-chat，pro 回退 deepseek-reasoner；
     * 列表为空时回退内置预设（与 PC familyStaticModels 语义一致）。
     */
    fun pickModels(modelIds: List<String>): Pair<String, String> {
        val flash = modelIds.firstOrNull { it.contains("flash") }
            ?: modelIds.firstOrNull { it == "deepseek-chat" }
            ?: modelIds.firstOrNull()
        val pro = modelIds.firstOrNull { it.contains("pro") }
            ?: modelIds.firstOrNull { it.contains("reasoner") }
            ?: modelIds.firstOrNull()
        return (flash ?: PREFERRED_FLASH_MODEL) to (pro ?: PREFERRED_PRO_MODEL)
    }
}
