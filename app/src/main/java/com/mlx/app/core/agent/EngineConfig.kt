package com.mlx.app.core.agent

/** 引擎运行所需动态配置（由 AppStore 实现；接口隔离使引擎可单元测试） */
interface EngineConfig {
    suspend fun apiKey(): String?
    suspend fun baseUrl(): String
    suspend fun activeModelId(): String
    suspend fun flashModelId(): String
    suspend fun proModelId(): String
    suspend fun policyMode(): String          // review | auto | yolo
    suspend fun planMode(): Boolean
    suspend fun reasoningMode(): String       // auto | off | max（DeepSeek V4 思考模式）
    suspend fun outputStyle(): String         // standard | concise | detailed | json | explanatory | learning（八批：补记后两种）
    suspend fun goal(): String?               // 目标模式：跨回合持久目标（/goal）
    suspend fun compactRatio(): Double        // 自动压缩阈值（对应 PC compact_ratio，默认 0.8）
    suspend fun workspaceRoot(): java.io.File? // 完整环境项目真实路径（null = SAF 项目）
    suspend fun budgetUsd(): Double           // 0 = 无上限
    suspend fun upgradeToPro()                // 3 次编辑失败自动升级
}
