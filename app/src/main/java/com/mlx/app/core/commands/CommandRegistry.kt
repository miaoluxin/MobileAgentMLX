package com.mlx.app.core.commands

/**
 * 指令面板命令注册表（对应 PC 版 50+ 斜杠命令的移动端映射）。
 * available=false 表示该命令已规划（见开发文档 M3–M5），MVP 点击提示待实现。
 */
data class CommandDef(
    val id: String,
    val name: String,
    val zhName: String,
    val category: String,
    val description: String,
    /** 怎么用/效果（优化 4b：命令面板副文本显示，降低"不知道功能怎么用"门槛） */
    val usage: String = "",
    val available: Boolean = true,
    /**
     * immediate=true：导航/表单/提示类命令，点击直接执行（页面切换本身有可见反馈）。
     * immediate=false：设置类命令，点击执行 + 命令文本回填输入框（可见反馈，可编辑重发）。
     */
    val immediate: Boolean = false,
)

object CommandRegistry {
    val all: List<CommandDef> = listOf(
        CommandDef("model.flash", "/model flash", "切换 Flash 模型", "模型", "切换默认模型为 flash（低成本）", "点击立即切换，无需输入命令"),
        CommandDef("model.pro", "/model pro", "切换 Pro 模型", "模型", "切换默认模型为 pro（更强）", "点击立即切换，无需输入命令"),
        CommandDef("mode.review", "/mode review", "审批模式·Review", "权限", "写操作全部询问用户", "点击立即切换，无需输入命令"),
        CommandDef("mode.auto", "/mode auto", "审批模式·Auto", "权限", "大部分操作自动放行", "点击立即切换，无需输入命令"),
        CommandDef("mode.yolo", "/mode yolo", "审批模式·Yolo", "权限", "最少询问，全部记录", "点击立即切换，无需输入命令"),
        CommandDef("plan.toggle", "/plan", "计划模式", "模式", "只读审计模式开关", "点击切换：只读分析输出方案，批准后才执行；再次点击关闭"),
        CommandDef("goal", "/goal", "目标模式", "模式", "设置长期目标，Agent 跨回合持续遵循", "点击弹出输入框，填写后 Agent 每回合遵循；再次点击可清除"),
        CommandDef("compact", "/compact", "压缩上下文", "上下文", "手动触发上下文压缩", "点击立即折叠旧消息，释放上下文空间"),
        CommandDef("budget", "/budget", "预算上限", "预算", "设置会话预算（元，0=不限）", "点击输入金额；达到上限自动停止回合"),
        CommandDef("websearch", "/websearch", "网络搜索", "工具", "联网搜索为 Agent 自动能力（默认 Bing 免 Key）；设置页可切换后端", "点击跳转设置页选择搜索后端", immediate = true),
        CommandDef("todo", "/todo", "任务清单", "工具", "打开当前会话的任务清单", "点击弹出任务清单，可查看/勾选/新增", immediate = true),
        CommandDef("new", "/new", "新建会话", "会话", "开启全新会话", "点击立即新建", immediate = true),
        CommandDef("sessions", "/sessions", "会话列表", "会话", "返回会话列表页", "点击返回列表页", immediate = true),
        CommandDef("branch", "/branch", "分支会话", "会话", "长按消息 → 从这里分支", "在对话中长按任意消息，选择「从这里分支」", immediate = true),
        CommandDef("rewind", "/rewind", "回退", "检查点", "长按用户消息 → 回退到此处（恢复文件+截断对话）", "在对话中长按用户消息，选择「回退到此处」", immediate = true),
        CommandDef("init", "/init", "生成 REASONIX.md", "记忆", "扫描项目生成指令文件（注入系统提示）", "点击自动扫描项目并生成，生成后 AI 更懂你的项目"),
        CommandDef("memory", "/memory", "记忆管理", "记忆", "查看/管理背景事实（BM25 自动召回）", "点击跳转设置页记忆区块，可查看/删除记忆", immediate = true),
        CommandDef("skills", "/skills", "技能管理", "技能", "新建/查看自定义技能（注入系统提示）", "点击跳转设置页技能区块；对话中长按 + 可直接选技能", immediate = true),
        CommandDef("mcp", "/mcp", "MCP 插件", "扩展", "添加/管理 MCP 服务器（HTTP 桥接远程工具）", "点击跳转设置页 MCP 区块，添加服务器地址", immediate = true),
        CommandDef("jobs", "/jobs", "后台任务", "任务", "后台任务列表", "点击查看后台任务，可终止", immediate = true),
        CommandDef("stats", "/stats", "成本统计", "成本", "查看成本与缓存命中统计", "点击查看会话/总成本与缓存数据", immediate = true),
        CommandDef("permissions", "/permissions", "权限策略", "权限", "打开权限策略设置", "点击跳转权限策略设置", immediate = true),
        CommandDef("sandbox", "/sandbox", "沙箱设置", "权限", "工作区/沙箱设置", "点击跳转工作区/沙箱设置", immediate = true),
        CommandDef("theme", "/theme", "主题切换", "界面", "浅色 / 深色 / 跟随系统", "点击跳转设置页选择主题", immediate = true),
        CommandDef("language", "/language", "语言切换", "界面", "中 / 英文界面（MVP 界面以中文为主）", "点击跳转设置页选择语言", immediate = true),
        CommandDef("output-style", "/output-style", "输出风格", "界面", "选择回答风格（标准/简洁/详细/结构化）", "点击跳转设置页选择回答风格", immediate = true),
        CommandDef("attach", "/attach", "附加文件", "工具", "选择项目文件附加到对话", "点击打开文件工作台；也可用输入框左侧 + 按钮", immediate = true),
        CommandDef("search", "/search", "搜索项目", "工具", "按文件名搜索项目", "点击打开文件工作台搜索框", immediate = true),
    )
}
