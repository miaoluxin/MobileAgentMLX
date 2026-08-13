package com.mlx.app.data.store

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mlx.app.core.agent.EngineConfig
import com.mlx.app.core.common.MiniJson
import com.mlx.app.core.policy.Decision
import com.mlx.app.core.policy.PolicyRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.mlxDataStore by preferencesDataStore(name = "mlx_prefs")

/**
 * 应用配置存储（DataStore）。
 * API Key 经 KeystoreCrypto AES-GCM 加密后落盘，明文只在请求时驻留内存。
 */
class AppStore(private val context: Context) : EngineConfig {

    private val store get() = context.mlxDataStore
    private val crypto = KeystoreCrypto()

    private object Keys {
        val API_KEY_ENC = stringPreferencesKey("api_key_enc")
        val BASE_URL = stringPreferencesKey("base_url")
        val FLASH_MODEL = stringPreferencesKey("flash_model")
        val PRO_MODEL = stringPreferencesKey("pro_model")
        val MODEL_TIER = stringPreferencesKey("model_tier")
        val PROJECT_URI = stringPreferencesKey("project_uri")
        val PROJECT_NAME = stringPreferencesKey("project_name")
        val PROJECT_TYPE = stringPreferencesKey("project_type")   // saf | real（完整环境工作区）
        val PROJECT_PATH = stringPreferencesKey("project_path")   // real 类型的真实路径
        val POLICY_MODE = stringPreferencesKey("policy_mode")
        val BUDGET_USD = doublePreferencesKey("budget_usd")
        val PLAN_MODE = booleanPreferencesKey("plan_mode")
        val THEME = stringPreferencesKey("theme")
        val POLICY_RULES = stringPreferencesKey("policy_rules")
        val REASONING_MODE = stringPreferencesKey("reasoning_mode")
        val OUTPUT_STYLE = stringPreferencesKey("output_style")
        val SEARCH_BACKEND = stringPreferencesKey("search_backend")
        val TAVILY_KEY = stringPreferencesKey("tavily_key")
        val GOAL = stringPreferencesKey("goal")
        val COMPACT_RATIO = doublePreferencesKey("compact_ratio")
        val ALL_FILES_PROMPTED = booleanPreferencesKey("all_files_prompted") // "所有文件访问"授权引导已提示过
        val DELETED_SESSIONS = stringPreferencesKey("deleted_session_ids") // 已删除会话黑名单（JSON 数组，防备份复活）
        val PLAN_INTRO_SHOWN = booleanPreferencesKey("plan_intro_shown") // 计划模式首次引导已提示过
        val GOAL_INTRO_SHOWN = booleanPreferencesKey("goal_intro_shown") // 目标模式首次引导已提示过
        val BATTERY_PROMPT_SHOWN = booleanPreferencesKey("battery_prompt_shown") // 电池优化豁免引导已提示过（六批：后台保活）
    }

    // ---- 配置流（UI 观察） ----
    val hasApiKeyFlow: Flow<Boolean> = store.data.map { !(it[Keys.API_KEY_ENC] ?: "").isBlank() }
    val baseUrlFlow: Flow<String> = store.data.map { it[Keys.BASE_URL] ?: DEFAULT_BASE_URL }
    val flashModelFlow: Flow<String> = store.data.map { it[Keys.FLASH_MODEL] ?: DEFAULT_FLASH_MODEL }
    val proModelFlow: Flow<String> = store.data.map { it[Keys.PRO_MODEL] ?: DEFAULT_PRO_MODEL }
    val modelTierFlow: Flow<String> = store.data.map { it[Keys.MODEL_TIER] ?: "flash" }
    val projectUriFlow: Flow<String?> = store.data.map { it[Keys.PROJECT_URI] }
    val projectNameFlow: Flow<String?> = store.data.map { it[Keys.PROJECT_NAME] }
    val projectTypeFlow: Flow<String> = store.data.map { it[Keys.PROJECT_TYPE] ?: "saf" }
    val projectPathFlow: Flow<String?> = store.data.map { it[Keys.PROJECT_PATH] }
    val policyModeFlow: Flow<String> = store.data.map { it[Keys.POLICY_MODE] ?: "auto" }
    val budgetUsdFlow: Flow<Double> = store.data.map { it[Keys.BUDGET_USD] ?: 0.0 }
    val planModeFlow: Flow<Boolean> = store.data.map { it[Keys.PLAN_MODE] ?: false }
    val themeFlow: Flow<String> = store.data.map { it[Keys.THEME] ?: "system" }
    val rulesFlow: Flow<List<PolicyRule>> = store.data.map { parseRules(it[Keys.POLICY_RULES] ?: "") }
    /** 思考模式：auto（标准，官方默认）/ off（关闭思考）/ max（深度思考） */
    val reasoningModeFlow: Flow<String> = store.data.map { it[Keys.REASONING_MODE] ?: "auto" }
    /** 输出风格：standard / concise / detailed / json */
    val outputStyleFlow: Flow<String> = store.data.map { it[Keys.OUTPUT_STYLE] ?: "standard" }
    /** 网络搜索后端与 Key */
    val searchBackendFlow: Flow<String> = store.data.map { it[Keys.SEARCH_BACKEND] ?: "BING" }
    val tavilyKeyFlow: Flow<String> = store.data.map { it[Keys.TAVILY_KEY] ?: "" }
    val goalFlow: Flow<String> = store.data.map { it[Keys.GOAL] ?: "" }
    /** 自动压缩阈值（对应 PC compact_ratio，默认 0.8；soft 提示阈值 = ratio - 0.3 派生） */
    val compactRatioFlow: Flow<Double> = store.data.map { (it[Keys.COMPACT_RATIO] ?: 0.8).coerceIn(0.5, 0.95) }

    // ---- 写操作 ----
    suspend fun setApiKey(plain: String) {
        val enc = if (plain.isBlank()) "" else crypto.encrypt(plain)
        store.edit { it[Keys.API_KEY_ENC] = enc }
    }

    suspend fun setBaseUrl(v: String) = store.edit { it[Keys.BASE_URL] = v.trim().ifBlank { DEFAULT_BASE_URL } }
    suspend fun setFlashModel(v: String) = store.edit { it[Keys.FLASH_MODEL] = v.trim().ifBlank { DEFAULT_FLASH_MODEL } }
    suspend fun setProModel(v: String) = store.edit { it[Keys.PRO_MODEL] = v.trim().ifBlank { DEFAULT_PRO_MODEL } }
    suspend fun setModelTier(tier: String) = store.edit { it[Keys.MODEL_TIER] = tier }
    suspend fun setProject(uri: String, name: String) = store.edit {
        it[Keys.PROJECT_URI] = uri
        it[Keys.PROJECT_NAME] = name
        it[Keys.PROJECT_TYPE] = "saf"
    }

    /** 完整环境项目（真实路径工作区） */
    suspend fun setRealProject(path: String, name: String) = store.edit {
        it[Keys.PROJECT_TYPE] = "real"
        it[Keys.PROJECT_PATH] = path
        it[Keys.PROJECT_NAME] = name
    }

    /**
     * 统一工程（目录即工作区 2.0）：真实路径 + SAF 授权句柄并存。
     * - uri：SAF tree URI（保留授权句柄，checkpoint/备份恢复仍用）
     * - path：真实路径（文件工具/shell/python 的统一工作区）
     */
    suspend fun setUnifiedProject(uri: String, path: String, name: String) = store.edit {
        it[Keys.PROJECT_URI] = uri
        it[Keys.PROJECT_TYPE] = "real"
        it[Keys.PROJECT_PATH] = path
        it[Keys.PROJECT_NAME] = name
    }
    suspend fun setPolicyMode(mode: String) = store.edit { it[Keys.POLICY_MODE] = mode }
    suspend fun setBudgetUsd(v: Double) = store.edit { it[Keys.BUDGET_USD] = v }
    suspend fun setPlanMode(v: Boolean) = store.edit { it[Keys.PLAN_MODE] = v }
    suspend fun setTheme(v: String) = store.edit { it[Keys.THEME] = v }

    suspend fun setReasoningMode(mode: String) = store.edit { it[Keys.REASONING_MODE] = mode }
    suspend fun setOutputStyle(style: String) = store.edit { it[Keys.OUTPUT_STYLE] = style }
    suspend fun setSearchBackend(backend: String) = store.edit { it[Keys.SEARCH_BACKEND] = backend }

    /** "所有文件访问"授权引导是否已提示过（"暂不"后不再每次启动弹） */
    suspend fun allFilesPrompted(): Boolean =
        runCatching { store.data.first()[Keys.ALL_FILES_PROMPTED] ?: false }.getOrDefault(false)

    suspend fun markAllFilesPrompted() = store.edit { it[Keys.ALL_FILES_PROMPTED] = true }
    suspend fun setTavilyKey(key: String) = store.edit { it[Keys.TAVILY_KEY] = key.trim() }
    suspend fun setGoal(goal: String) = store.edit { it[Keys.GOAL] = goal.trim() }
    suspend fun setCompactRatio(ratio: Double) = store.edit { it[Keys.COMPACT_RATIO] = ratio.coerceIn(0.5, 0.95) }

    // ---- 已删除会话黑名单（防 .mlx-backup 备份复活；恢复流程命中则删备份并跳过） ----
    suspend fun isSessionDeleted(id: String): Boolean {
        val json = store.data.first()[Keys.DELETED_SESSIONS] ?: return false
        val list = MiniJson.parse(json) as? List<*> ?: return false
        return list.any { it as? String == id }
    }

    suspend fun markSessionDeleted(id: String) = store.edit { prefs ->
        val list = (MiniJson.parse(prefs[Keys.DELETED_SESSIONS] ?: "[]") as? List<*>)
            ?.mapNotNull { it as? String }?.toMutableList() ?: mutableListOf()
        if (id !in list) list.add(id)
        // 防无限膨胀：仅保留最近 1000 条
        while (list.size > 1000) list.removeAt(0)
        prefs[Keys.DELETED_SESSIONS] = MiniJson.stringify(list)
    }

    /** 计划/目标模式首次引导是否已提示过（"我知道了"后不再弹） */
    suspend fun planIntroShown(): Boolean =
        runCatching { store.data.first()[Keys.PLAN_INTRO_SHOWN] ?: false }.getOrDefault(false)

    suspend fun markPlanIntroShown() = store.edit { it[Keys.PLAN_INTRO_SHOWN] = true }

    suspend fun goalIntroShown(): Boolean =
        runCatching { store.data.first()[Keys.GOAL_INTRO_SHOWN] ?: false }.getOrDefault(false)

    suspend fun markGoalIntroShown() = store.edit { it[Keys.GOAL_INTRO_SHOWN] = true }

    /** 电池优化豁免引导是否已提示过（六批：后台保活双保险；提示过一次后不再打扰） */
    suspend fun batteryPromptShown(): Boolean =
        runCatching { store.data.first()[Keys.BATTERY_PROMPT_SHOWN] ?: false }.getOrDefault(false)

    suspend fun markBatteryPromptShown() = store.edit { it[Keys.BATTERY_PROMPT_SHOWN] = true }

    suspend fun saveRules(rules: List<PolicyRule>) {
        val json = MiniJson.stringify(
            rules.map { mapOf("pattern" to it.pattern, "decision" to it.decision.name, "global" to it.global) }
        )
        store.edit { it[Keys.POLICY_RULES] = json }
    }

    /** 读取持久化审批规则（八批：启动时恢复"始终允许"等运行时规则，重启不丢失） */
    suspend fun loadRules(): List<PolicyRule> =
        runCatching { store.data.first()[Keys.POLICY_RULES]?.let { parseRules(it) } ?: emptyList() }
            .getOrDefault(emptyList())

    private fun parseRules(json: String): List<PolicyRule> {
        val list = MiniJson.parse(json) as? List<*> ?: return emptyList()
        return list.mapNotNull { raw ->
            val m = raw as? Map<String, Any?> ?: return@mapNotNull null
            val decision = runCatching {
                Decision.valueOf((m["decision"] as? String) ?: return@mapNotNull null)
            }.getOrNull() ?: return@mapNotNull null
            PolicyRule(
                pattern = (m["pattern"] as? String) ?: return@mapNotNull null,
                decision = decision,
                global = (m["global"] as? Boolean) ?: true,
            )
        }
    }

    // ---- EngineConfig ----
    override suspend fun apiKey(): String? = apiKeyPlain()
    override suspend fun baseUrl(): String = store.data.first()[Keys.BASE_URL] ?: DEFAULT_BASE_URL
    override suspend fun activeModelId(): String =
        if ((store.data.first()[Keys.MODEL_TIER] ?: "flash") == "pro") proModelId() else flashModelId()
    override suspend fun flashModelId(): String = store.data.first()[Keys.FLASH_MODEL] ?: DEFAULT_FLASH_MODEL
    override suspend fun proModelId(): String = store.data.first()[Keys.PRO_MODEL] ?: DEFAULT_PRO_MODEL
    override suspend fun policyMode(): String = store.data.first()[Keys.POLICY_MODE] ?: "auto"
    override suspend fun planMode(): Boolean = store.data.first()[Keys.PLAN_MODE] ?: false
    override suspend fun reasoningMode(): String = store.data.first()[Keys.REASONING_MODE] ?: "auto"
    override suspend fun outputStyle(): String = store.data.first()[Keys.OUTPUT_STYLE] ?: "standard"
    override suspend fun goal(): String? = store.data.first()[Keys.GOAL]?.takeIf { it.isNotBlank() }
    override suspend fun compactRatio(): Double =
        (store.data.first()[Keys.COMPACT_RATIO] ?: 0.8).coerceIn(0.5, 0.95)
    override suspend fun workspaceRoot(): java.io.File? {
        val prefs = store.data.first()
        if (prefs[Keys.PROJECT_TYPE] != "real") return null
        val path = prefs[Keys.PROJECT_PATH] ?: return null
        val f = java.io.File(path)
        if (!f.isDirectory) return null
        // 分区存储门：外部存储真实目录需"所有文件访问"权限，否则 File API listFiles() 返回 null
        // （isDirectory=true 但内容不可读 → 工具静默返回空目录）。无权限 → 返回 null → 工具降级 SAF 后端
        val ext = android.os.Environment.getExternalStorageDirectory().absolutePath
        if (path.startsWith(ext) && android.os.Build.VERSION.SDK_INT >= 30 &&
            !android.os.Environment.isExternalStorageManager()
        ) return null
        return f
    }
    override suspend fun budgetUsd(): Double = store.data.first()[Keys.BUDGET_USD] ?: 0.0
    override suspend fun upgradeToPro() {
        store.edit { it[Keys.MODEL_TIER] = "pro" }
    }

    suspend fun apiKeyPlain(): String? {
        val enc = store.data.first()[Keys.API_KEY_ENC] ?: return null
        if (enc.isBlank()) return null
        return runCatching { crypto.decrypt(enc) }.getOrNull()
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com/v1"
        // 2026-07-24 起官方停用 deepseek-chat/deepseek-reasoner 旧别名，改用 V4 系列
        const val DEFAULT_FLASH_MODEL = "deepseek-v4-flash"
        const val DEFAULT_PRO_MODEL = "deepseek-v4-pro"
    }
}
