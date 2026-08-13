package com.mlx.app.data.store

import android.content.Context
import com.mlx.app.core.agent.AgentEngine
import com.mlx.app.core.common.MiniJson
import com.mlx.app.core.cost.CostRecord
import java.io.File
import java.util.UUID

/**
 * 会话持久化（MVP：JSON 文件存储于 app 私有目录；M3 迁移 Room，
 * 接口语义与 PC 版会话/转录一致）。
 */
class SessionStore(sessionsDir: File) {

    private val sessionsDir: File = sessionsDir.apply { mkdirs() }

    fun create(model: String, title: String = "新会话", projectId: String = "", projectName: String = ""): Session {
        val now = System.currentTimeMillis()
        return Session(
            id = "s_" + UUID.randomUUID().toString().substring(0, 8),
            title = title,
            createdAt = now,
            updatedAt = now,
            model = model,
            projectId = projectId,
            projectName = projectName, // 工程名快照：重启后注册表异常也不丢名字
        )
    }

    fun load(id: String): Session? {
        val f = File(sessionsDir, "$id.json")
        if (!f.exists()) return null
        return runCatching { fromJson(f.readText()) }.getOrNull()
    }

    fun save(session: Session) {
        runCatching {
            val tmp = File(sessionsDir, "${session.id}.tmp")
            tmp.writeText(toJson(session))
            tmp.renameTo(File(sessionsDir, "${session.id}.json"))
        }
    }

    /**
     * 级联删除钩子（由 MlxApp 装配 SessionCascade）：删除主文件后同步清理
     * 各工程 .mlx-backup/{id}.json、检查点与删除黑名单（防备份复活）。
     * 仅删除该会话 id 对应的单文件，绝不触碰备份目录本身与其他会话文件。
     */
    var onDeleted: ((String) -> Unit)? = null

    fun delete(id: String) {
        File(sessionsDir, "$id.json").delete()
        onDeleted?.invoke(id)
    }

    /** 复制会话为全新副本（对应 PC --copy fork：原会话只读保留） */
    fun fork(id: String): Session? {
        val src = load(id) ?: return null
        val now = System.currentTimeMillis()
        val copy = Session(
            id = "s_" + UUID.randomUUID().toString().substring(0, 8),
            title = src.title + "（副本）",
            createdAt = now,
            updatedAt = now,
            model = src.model,
            projectId = src.projectId,
            projectName = src.projectName, // 副本继承工程名快照
            messages = src.messages.toMutableList(),
            costs = mutableListOf(), // 成本不复制，新会话重新记账
        )
        save(copy)
        return copy
    }

    /** 分支：从某条消息之后分叉出新会话（对应 /branch；原会话保留） */
    fun branch(id: String, afterMessageIndex: Int): Session? {
        val src = load(id) ?: return null
        val idx = afterMessageIndex.coerceIn(0, src.messages.size)
        val now = System.currentTimeMillis()
        val copy = Session(
            id = "s_" + UUID.randomUUID().toString().substring(0, 8),
            title = src.title + "（分支）",
            createdAt = now,
            updatedAt = now,
            model = src.model,
            projectId = src.projectId,
            projectName = src.projectName, // 分支继承工程名快照
            // 十八批修复（审计）：分支不携带孤立注入消息（[技能注入] 属回合级临时上下文，
            // 下个回合引擎会重新注入；带过去会让分支历史里出现无触发回合的剧本消息）
            // 二十二批：同口径补滤 [记忆回顾]（同样每回合重新召回注入）与计划审批反馈
            messages = src.messages.take(idx).filterNot {
                it.content.startsWith("[技能注入] ") ||
                    it.content.startsWith("[记忆回顾]") ||
                    AgentEngine.PLAN_FEEDBACK_PREFIXES.any { p -> it.content.startsWith(p) }
            }.toMutableList(),
            costs = mutableListOf(),
        )
        save(copy)
        return copy
    }

    /** 剪枝：删除 N 天前未更新的会话（对应 prune-sessions --days N） */
    fun prune(days: Int): Int {
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        val doomed = list().filter { it.updatedAt < cutoff }
        doomed.forEach { delete(it.id) }
        return doomed.size
    }

    /** 全部会话（按更新时间由近到老） */
    fun list(): List<Session> =
        (sessionsDir.listFiles() ?: emptyArray())
            .filter { it.extension == "json" && !it.name.endsWith(".tmp") }
            .mapNotNull { runCatching { fromJson(it.readText()) }.getOrNull() }
            .sortedByDescending { it.updatedAt }

    /** 按工程过滤（P3：工程下多对话；空 = 不限；旧数据无工程归属视为跟随当前工程） */
    fun list(projectId: String?): List<Session> =
        if (projectId.isNullOrBlank()) list()
        else list().filter { it.projectId == projectId || it.projectId.isEmpty() }

    /** 关键词搜索（标题/首条消息/全部消息内容；P3 定位会话用） */
    fun search(projectId: String?, keyword: String): List<Session> {
        val k = keyword.trim()
        if (k.isBlank()) return list(projectId)
        return list(projectId).filter { s ->
            s.title.contains(k, ignoreCase = true) ||
                s.messages.any { it.content.contains(k, ignoreCase = true) }
        }
    }

    /** 自动清理：保留最近 keep 个会话（防任务页/列表刷屏；P10 语义扩展） */
    fun pruneTo(keep: Int = 50) {
        val all = list()
        if (all.size <= keep) return
        all.drop(keep).forEach { delete(it.id) }
    }

    /** 从备份文件导入会话（恢复机制：应用数据被清后从工程磁盘目录备份恢复） */
    fun importFromBackup(backupFile: File): Boolean {
        val id = backupFile.name.removeSuffix(".json")
        if (id.isBlank()) return false
        val dest = File(sessionsDir, backupFile.name)
        return runCatching {
            if (!dest.exists()) backupFile.copyTo(dest)
            true
        }.getOrDefault(false)
    }

    fun exists(id: String): Boolean = File(sessionsDir, "$id.json").exists()

    /** 诊断/恢复用：暴露会话目录绝对路径 */
    fun directoryForDiag(): String? = sessionsDir.absolutePath

    companion object {
        fun toJson(s: Session): String {
            val messages = s.messages.map { m ->
                mapOf(
                    "id" to m.id,
                    "role" to m.role,
                    "content" to m.content,
                    "reasoning" to m.reasoning,
                    "toolCallId" to m.toolCallId,
                    "createdAt" to m.createdAt,
                    "toolCalls" to m.toolCalls.map { tc ->
                        mapOf(
                            "id" to tc.id,
                            "name" to tc.name,
                            "argsJson" to tc.argsJson,
                            "status" to tc.status.name,
                            "resultText" to tc.resultText,
                            "diffText" to tc.diffText,
                            "retryCount" to tc.retryCount,
                            "intent" to tc.intent,
                        )
                    },
                )
            }
            val costs = s.costs.map { c ->
                mapOf(
                    "sessionId" to c.sessionId,
                    "turn" to c.turn,
                    "model" to c.model,
                    "hitTokens" to c.hitTokens,
                    "missTokens" to c.missTokens,
                    "completionTokens" to c.completionTokens,
                    "costUsd" to c.costUsd,
                    "at" to c.at,
                )
            }
            val turns = s.turns.map { t ->
                mapOf(
                    "id" to t.id,
                    "turnNumber" to t.turnNumber,
                    "userText" to t.userText,
                    "startedAt" to t.startedAt,
                    "finishedAt" to t.finishedAt,
                    "status" to t.status.name,
                    "costUsd" to t.costUsd,
                    "steps" to t.steps.map { st ->
                        mapOf(
                            "id" to st.id,
                            "kind" to st.kind.name,
                            "name" to st.name,
                            "status" to st.status.name,
                            "startedAt" to st.startedAt,
                            "finishedAt" to st.finishedAt,
                            "durationMs" to st.durationMs,
                            "argsJson" to st.argsJson,
                            "resultText" to st.resultText,
                            "diffText" to st.diffText,
                            "outputRefs" to st.outputRefs,
                            "intent" to st.intent,
                            "children" to st.children.map { c ->
                                mapOf(
                                    "id" to c.id, "kind" to c.kind.name, "name" to c.name, "status" to c.status.name,
                                    "startedAt" to c.startedAt, "finishedAt" to c.finishedAt, "durationMs" to c.durationMs,
                                    "argsJson" to c.argsJson, "resultText" to c.resultText, "diffText" to c.diffText, "outputRefs" to c.outputRefs,
                                    "intent" to c.intent,
                                )
                            },
                        )
                    },
                )
            }
            return MiniJson.stringify(
                mapOf(
                    "id" to s.id,
                    "title" to s.title,
                    "createdAt" to s.createdAt,
                    "updatedAt" to s.updatedAt,
                    "model" to s.model,
                    "projectId" to s.projectId,
                    "projectName" to s.projectName, // 工程名快照持久化（重启/注册表异常兜底）
                    "messages" to messages,
                    "costs" to costs,
                    "turns" to turns,
                )
            )
        }

        fun fromJson(text: String): Session {
            val root = MiniJson.toMap(MiniJson.parse(text))
            val messages = (root["messages"] as? List<*>)?.mapNotNull { it as? Map<String, Any?> }
                ?.map { m ->
                    val toolCalls = (m["toolCalls"] as? List<*>)?.mapNotNull { it as? Map<String, Any?> }
                        ?.map { tc ->
                            ToolCallRecord(
                                id = (tc["id"] as? String) ?: "",
                                name = (tc["name"] as? String) ?: "",
                                argsJson = (tc["argsJson"] as? String) ?: "{}",
                                status = runCatching { ToolStatus.valueOf((tc["status"] as? String) ?: "RUNNING") }
                                    .getOrDefault(ToolStatus.RUNNING),
                                resultText = (tc["resultText"] as? String) ?: "",
                                diffText = (tc["diffText"] as? String) ?: "",
                                retryCount = ((tc["retryCount"] as? Number)?.toInt()) ?: 0, // 缺省 0：旧文件兼容
                                intent = (tc["intent"] as? String) ?: "", // 缺省空：旧文件兼容
                            )
                        } ?: emptyList()
                    MessageRecord(
                        id = (m["id"] as? String) ?: "",
                        role = (m["role"] as? String) ?: "user",
                        content = (m["content"] as? String) ?: "",
                        reasoning = (m["reasoning"] as? String) ?: "",
                        toolCalls = toolCalls,
                        toolCallId = (m["toolCallId"] as? String) ?: "",
                        createdAt = ((m["createdAt"] as? Number)?.toLong()) ?: 0L,
                    )
                } ?: emptyList()
            val costs = (root["costs"] as? List<*>)?.mapNotNull { it as? Map<String, Any?> }
                ?.map { c ->
                    CostRecord(
                        sessionId = (c["sessionId"] as? String) ?: "",
                        turn = ((c["turn"] as? Number)?.toInt()) ?: 0,
                        model = (c["model"] as? String) ?: "",
                        hitTokens = ((c["hitTokens"] as? Number)?.toLong()) ?: 0L,
                        missTokens = ((c["missTokens"] as? Number)?.toLong()) ?: 0L,
                        completionTokens = ((c["completionTokens"] as? Number)?.toLong()) ?: 0L,
                        costUsd = ((c["costUsd"] as? Number)?.toDouble()) ?: 0.0,
                        at = ((c["at"] as? Number)?.toLong()) ?: 0L,
                    )
                } ?: emptyList()
            val session = Session(
                id = (root["id"] as? String) ?: "",
                title = (root["title"] as? String) ?: "会话",
                createdAt = ((root["createdAt"] as? Number)?.toLong()) ?: 0L,
                updatedAt = ((root["updatedAt"] as? Number)?.toLong()) ?: 0L,
                model = (root["model"] as? String) ?: "",
                projectId = (root["projectId"] as? String) ?: "",
                projectName = (root["projectName"] as? String) ?: "", // 旧数据无此 key → 空串，向后兼容
                messages = messages.toMutableList(),
                costs = costs.toMutableList(),
            )
            // 回合轨迹：旧 JSON 无 turns 键 → 空列表（兼容）+ 一次性回填合成
            val turns = (root["turns"] as? List<*>)?.mapNotNull { it as? Map<String, Any?> }
                ?.map { t ->
                    val steps = (t["steps"] as? List<*>)?.mapNotNull { it as? Map<String, Any?> }
                        ?.map { st ->
                            StepRecord(
                                id = (st["id"] as? String) ?: "",
                                kind = runCatching { StepKind.valueOf((st["kind"] as? String) ?: "TOOL") }.getOrDefault(StepKind.TOOL),
                                name = (st["name"] as? String) ?: "",
                                status = runCatching { ToolStatus.valueOf((st["status"] as? String) ?: "SUCCESS") }.getOrDefault(ToolStatus.SUCCESS),
                                startedAt = ((st["startedAt"] as? Number)?.toLong()) ?: 0L,
                                finishedAt = ((st["finishedAt"] as? Number)?.toLong()) ?: 0L,
                                durationMs = ((st["durationMs"] as? Number)?.toLong()) ?: 0L,
                                argsJson = (st["argsJson"] as? String) ?: "",
                                resultText = (st["resultText"] as? String) ?: "",
                                diffText = (st["diffText"] as? String) ?: "",
                                outputRefs = (st["outputRefs"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                                intent = (st["intent"] as? String) ?: "", // 缺省空：旧文件兼容
                                children = ((st["children"] as? List<*>)?.mapNotNull { it as? Map<String, Any?> }
                                    ?.map { c ->
                                        StepRecord(
                                            id = (c["id"] as? String) ?: "",
                                            kind = runCatching { StepKind.valueOf((c["kind"] as? String) ?: "TOOL") }.getOrDefault(StepKind.TOOL),
                                            name = (c["name"] as? String) ?: "",
                                            status = runCatching { ToolStatus.valueOf((c["status"] as? String) ?: "SUCCESS") }.getOrDefault(ToolStatus.SUCCESS),
                                            startedAt = ((c["startedAt"] as? Number)?.toLong()) ?: 0L,
                                            finishedAt = ((c["finishedAt"] as? Number)?.toLong()) ?: 0L,
                                            durationMs = ((c["durationMs"] as? Number)?.toLong()) ?: 0L,
                                            argsJson = (c["argsJson"] as? String) ?: "",
                                            resultText = (c["resultText"] as? String) ?: "",
                                            diffText = (c["diffText"] as? String) ?: "",
                                            outputRefs = (c["outputRefs"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                                            intent = (c["intent"] as? String) ?: "", // 缺省空：旧文件兼容
                                        )
                                    } ?: emptyList())?.toMutableList() ?: mutableListOf(),
                            )
                        } ?: emptyList()
                    TurnRecord(
                        id = (t["id"] as? String) ?: "",
                        turnNumber = ((t["turnNumber"] as? Number)?.toInt()) ?: 0,
                        userText = (t["userText"] as? String) ?: "",
                        startedAt = ((t["startedAt"] as? Number)?.toLong()) ?: 0L,
                        finishedAt = ((t["finishedAt"] as? Number)?.toLong()) ?: 0L,
                        status = runCatching { TurnStatus.valueOf((t["status"] as? String) ?: "SUCCESS") }.getOrDefault(TurnStatus.SUCCESS),
                        costUsd = ((t["costUsd"] as? Number)?.toDouble()) ?: 0.0,
                        steps = steps.toMutableList(),
                    )
                } ?: emptyList()
            session.turns.addAll(turns)
            // 旧会话（无 turns 键）一次性回填合成（幂等）
            if (turns.isEmpty()) backfillTurns(session)
            return session
        }
    }
}
