# DeepSeek-Reasonix 手机版（Android）开发文档

> **产品名称**：Reasonix Mobile（安卓版 DeepSeek-Reasonix）
> **目标设备**：小米 17 Pro Max（直屏挖孔屏，Android 16 / API 36，2026）
> **基线工程**：https://github.com/esengine/DeepSeek-Reasonix （Go 重写版 main-v2，MIT 协议）
> **文档版本**：v1.8 ｜ 2026-08-10（v1.0 设计主体 + 附录 A 迭代记录，A.11 崩溃修复/契约强化 + A.12 配套对齐/过程可视化 + A.13 自洽性审计）
> **交付范围**：本版本实现 **MVP 可用 APK**（详见第 16 章 M1–M2 范围），M3–M5 功能在本文档中完成设计、代码预留接口。**实现进度见第 14 章末状态段 + 附录 A（2026-08-08 ~ 08-10 迭代记录）**。

---

## 目录

1. [项目概述](#1-项目概述)
2. [背景与现状分析](#2-背景与现状分析)
3. [技术选型与总体架构](#3-技术选型与总体架构)
4. [设计规范与平台适配基础](#4-设计规范与平台适配基础)
5. [页面与交互流程设计](#5-页面与交互流程设计)
6. [Agent 引擎设计（核心）](#6-agent-引擎设计核心)
7. [SAF 文件系统与文件工具层](#7-saf-文件系统与文件工具层)
8. [权限、沙箱与安全](#8-权限沙箱与安全)
9. [会话、检查点与回退](#9-会话检查点与回退)
10. [记忆、技能与 MCP](#10-记忆技能与-mcp)
11. [后台任务与通知](#11-后台任务与通知)
12. [分屏与多窗口适配](#12-分屏与多窗口适配)
13. [成本统计仪表盘](#13-成本统计仪表盘)
14. [功能对标矩阵](#14-功能对标矩阵)
15. [关键风险与应对](#15-关键风险与应对)
16. [开发路线图](#16-开发路线图)
17. [测试与验收标准](#17-测试与验收标准)

---

## 1. 项目概述

### 1.1 产品定位

Reasonix Mobile 是 PC 端 DeepSeek-Reasonix（DeepSeek 原生 AI 编程 Agent）的安卓手机版。核心目标：

1. **功能完全包含 PC 端**：PC 端全部 27 项功能在移动端有等价实现（见第 14 章对标矩阵），通过分屏/多窗口完整提供 PC 端能力。
2. **移动原生交互重设计**：不是把终端 TUI 搬上屏幕，而是按手机交互范式（底部导航、手势、底部弹层、长按操作）重新设计每一个页面。
3. **真项目工作目录**：通过 Android Storage Access Framework（SAF）选择手机磁盘上**任意文件夹**作为项目文件夹，并授予持久访问权限，Agent 的全部文件工具在该目录内工作。
4. **保留 PC 端灵魂——缓存经济性**：缓存优先三区上下文、flash 优先成本控制、工具调用修复管线全部保留，目标是手机端同样保持 ≥90% 前缀缓存命中率，让"随身 AI 程序员"成本可控。

### 1.2 名词表

| 名词 | 含义 |
|---|---|
| 三区上下文 | 缓存优先的上下文分区：IMMUTABLE_PREFIX（不可变前缀）/ APPEND_ONLY_LOG（追加式日志）/ VOLATILE_SCRATCH（易变草稿） |
| 前缀缓存命中率 | DeepSeek API 按前缀缓存计费，命中 token 仅按 ~10% 计费；命中率越高成本越低 |
| SAF | Storage Access Framework，Android 系统文件访问框架，以 URI 授权代替 POSIX 绝对路径 |
| 树 URI | SAF 中整个目录树的授权句柄（`content://…/tree/…`），可持久化 |
| 挖孔屏 | 屏幕顶部居中开孔放置前置摄像头的直屏（小米 17 Pro Max 形态） |
| 窗口尺寸类 | Jetpack WindowManager 的 WindowSizeClass：compact(<600dp)/medium(600–840dp)/expanded(≥840dp) |
| 工具调用修复管线 | 针对 DeepSeek API 四类失败模式的修复机制（展平/搜刮/截断/风暴） |
| glob 策略 | allow/ask/deny 三级权限规则，按路径通配符匹配 |

### 1.3 与 PC 版的关系

| 维度 | PC 版 | 手机版 |
|---|---|---|
| 运行形态 | 终端 TUI / `reasonix serve` Web UI / Wails 桌面 | 原生 Android App（单 APK） |
| 核心引擎 | Go 重写 | Kotlin 重写（行为对齐 PC SPEC/ARCHITECTURE 文档） |
| 文件系统 | POSIX 绝对路径 | **目录即工作区**：选磁盘目录为工程，镜像+改动自动回写（SAF 树授权为底层通道，对用户隐藏） |
| 会话 | JSONL 转录文件 | Room 数据库 |
| 交互 | 键盘快捷键 + 斜杠命令 | 手势 + 底部弹层 + 指令面板 |

---

## 2. 背景与现状分析

### 2.1 PC 端核心机制（16 项特性）

| # | 特性 | 说明 | 移动端处置 |
|---|---|---|---|
| 1 | 缓存优先三区上下文 | 不可变前缀 + 追加式日志 + 易变草稿，命中率 90–99.82% | **移植**（第 6 章） |
| 2 | 工具调用修复管线 | 展平/搜刮/截断/风暴四道，针对 DeepSeek API 实测失败模式 | **移植**（第 6 章） |
| 3 | 分层成本控制 | flash 优先、`<<<NEEDS_PRO>>>` 自报告、3 次编辑错误自动升级、辅助调用硬编码 flash、40%/80% 压缩 | **移植**（第 6 章） |
| 4 | Executor + Planner 双模型 | 执行者 + 只读规划者，独立缓存稳定会话 | 移植（M4） |
| 5 | MCP 插件 | stdio/SSE/HTTP 三传输，工具桥接注册表 | **适配**（第 10 章：HTTP/SSE 已实现；stdio 受平台限制） |
| 6 | 内建工具 13 项 | 文件 7 + shell + 搜索 8 后端 + 抓取 + 子代理 + todo + 计划 + 记忆 + choice | **适配**（第 7 章） |
| 7 | 权限与沙箱 | allow/ask/deny glob 策略、工作区根/允许写/禁读 | **重写**（第 8 章） |
| 8 | 检查点与回退 | SHA-256 去重快照、事务恢复、Code/Conversation/Both | 移植（M3） |
| 9 | 指令文件与记忆 | AGENTS.md/REASONIX.md/CLAUDE.md 层级、@import、/init、facts+BM25 | 移植（M3/M4） |
| 10 | 技能 | Markdown 剧本、项目级/全局级、runAs 子代理 | 移植（M4） |
| 11 | 会话管理 | 续接/分支/复刻/租赁锁/剪枝/临时模式 | 移植（M3） |
| 12 | 50+ 斜杠命令 | 模型/会话/成本/检查点/分支/技能/MCP/记忆/任务/模式/权限/预算等 | **重设计**（第 5.4 指令面板） |
| 13 | 目标模式 / 计划模式 | 长期目标持久化 / 只读审计 | 移植（M2/M3） |
| 14 | 后台任务 | /jobs /kill /logs | **重写**（第 11 章） |
| 15 | 成本统计 | stats/diff 转录分解、预算上限 | **重写**（第 13 章） |
| 16 | serve/桌面/VS Code 扩展 | Web UI、Wails 桌面、ACP 扩展 | N/A（分屏桌面模式替代） |

### 2.2 必须重写的判定（平台差异）

| 平台差异 | 影响 | 处置 |
|---|---|---|
| Android 无 POSIX 文件语义 | PC 文件工具用 os 路径，无法操作 SAF URI | 文件层整体 Kotlin 重写（第 7 章） |
| Android 禁止任意子进程 | MCP stdio 插件无法直接 spawn | 进程桥适配（第 10 章） |
| 无 shell | bash 工具不可用 | 受限沙箱 Shell（只读命令白名单） |
| 无终端 | TUI 交互消失 | 原生 Compose 交互重设计（第 5 章） |
| 作用域存储 | 无全局路径访问 | SAF 树授权即天然沙箱（第 8 章） |

---

## 3. 技术选型与总体架构

### 3.1 技术选型（Kotlin 全量重写核心）

**决策：用 Kotlin 重写 Agent 核心引擎，而非 gomobile 编译 Go 核心。** 理由：

1. **文件层必须重写**：Android 无 POSIX 文件语义，PC 核心的 os 路径文件工具无法操作 SAF URI——文件工具层无论如何要 Kotlin 重写，跨语言桥接只会徒增复杂度。
2. **进程模型不兼容**：Android 禁止任意子进程，MCP stdio 插件需进程桥适配，Go 代码不可直接移植。
3. **无 shell**：bash 工具需沙箱化替代，Go 核心的 shell 实现不可用。
4. **核心是纯逻辑**：缓存优先循环、成本核算、修复管线均为与平台无关的逻辑（字符串/JSON/状态机），按 PC 端 SPEC.md / ARCHITECTURE.md 忠实移植，工作量主要在 UI 与存储层。
5. **生态整合**：单 APK、可调试、深度整合 Android 生命周期（SAF 持久授权、分屏多实例、WorkManager、前台服务、通知、Keystore）。

### 3.2 技术栈

| 层 | 选型 |
|---|---|
| 语言 | Kotlin 2.x（coroutines + Flow） |
| UI | Jetpack Compose + Material 3（自适应布局） |
| 数据库 | Room（会话/消息/工具调用/成本记录/facts/检查点索引） |
| 配置 | DataStore（Preferences + Proto） |
| 密钥 | Android Keystore（AES-GCM）+ EncryptedSharedPreferences |
| 网络 | OkHttp（SSE 流式）+ Ktor client（MCP SSE/HTTP） |
| 窗口 | Jetpack WindowManager（WindowSizeClass、insets） |
| 构建 | Gradle 8.x + AGP 8.x，JDK 21，compileSdk 36 / targetSdk 36 / minSdk 26 |

### 3.3 模块划分（Gradle 多模块规划，MVP 以单 app 模块内分包实现）

```
:app                 入口壳：MainActivity、导航图、主题、insets、双实例参数
:feature-chat        会话列表 + 对话主界面 + 指令面板 + 工具卡片
:feature-workspace   SAF 文件工作台 + 预览/Diff + 检查点时间线
:feature-approval    审批弹层 + 权限策略编辑 + 沙箱设置
:feature-stats       成本统计仪表盘
:feature-jobs        后台任务列表 + 日志
:feature-settings    设置中心 + MCP 管理 + 技能/记忆管理
:core-engine         Agent 引擎：状态机、ContextManager、RepairPipeline、CostAccount、ModelRouter、Compaction
:core-llm            流式 SSE 客户端（OkHttp）、DeepSeek API 类型、鉴权
:core-tools          ToolSpec/ToolRegistry、内置工具实现（文件/搜索/todo/choice/受限shell）
:core-saf            SAF 层：树 URI、路径解析、内容缓存、外部变更检测、CheckpointStore
:core-mcp            MCP 客户端（Ktor SSE/HTTP）+ 进程桥适配器 + 插件注册表
:data                Room + DataStore + Keystore
:core-common         模型类、token 估算、BM25 实现、工具函数
```

### 3.4 依赖方向（与 PC 端一致的无环依赖）

```
app → feature-* → core-{engine,tools,saf} → data / core-common
core-engine → core-llm / core-tools / core-mcp
```

---

## 4. 设计规范与平台适配基础

> **2026-08-08 更新**：UI 已按 Anthropic frontend-design skill 原则全面重设计 —— 品牌 Design System（品牌蓝 60% + 蓝紫 30% + 冷青绿 10% 点缀、中性色向品牌蓝偏移、无纯黑纯白）、Outfit 展示字体（标题高对比）、圆角 6/10/14/20/28 体系、欢迎页 staggered 亮相动效（≤300ms）、Canvas 品牌 Logo 组件。下列规则仍为页面设计总纲。

本章定义全 App 页面设计的**总规则**，后续所有页面遵循。

### 4.1 导航框架

| 窗口尺寸类 | 布局形态 |
|---|---|
| compact（<600dp） | 底部 NavigationBar 5 Tab：`对话｜文件｜任务｜统计｜设置` |
| medium（600–840dp，分屏宽格/折页） | 左侧 NavigationRail（图标+文字） |
| expanded（≥840dp，横屏全屏/桌面模式） | 三段式：左 Rail + 中央主内容 + 右侧可切换面板（文件/审批/统计/会话树） |

### 4.2 手势总表

| 手势 | 动作（对应 PC 语义） |
|---|---|
| 右滑返回 / 下拉 60% 屏高 | **Esc 中止**当前模型回合（流式中） |
| 连续两次右滑 | **Esc-Esc 回退选择器**（检查点时间线，对应 rewind picker） |
| 长按 | 上下文操作菜单（对应斜杠命令集） |
| 底部上滑（输入区） | 唤出指令面板 |
| 左滑/右滑（列表项） | 会话列表：继续 / 删除 |
| 下拉刷新 | 拉取新消息 / 最新会话 |

### 4.3 挖孔屏与安全区准则（直屏挖孔：顶部居中）

1. **edge-to-edge 总则**：全 App `enableEdgeToEdge()`；所有 Scaffold 通过 `WindowInsets.systemBars` + `displayCutout` 组合产生 `contentPadding`，**禁止硬编码状态栏高度**。
2. **竖屏挖孔（顶部居中）**：应用栏内容起始 y = statusBar 高 + cutout 高；**顶栏中央 120dp 范围禁止放置任何可交互元素**（挖孔正下方区域），标题置左、芯片置右，正中留白。
3. **横屏/分屏**：挖孔位于左缘或右缘，`displayCutout` 在窗口左右两侧均取 max 内边距；审批弹层、发送键、滑动操作条必须避开挖孔侧。
4. **手势导航区**：底部手势条区域（gesture inset）为输入区与底部导航保留额外 24–32dp；全屏消息预览（代码阅读）时深色背景吞噬切口并在顶部留 16dp。
5. **双实例**：两个窗口实例各自独立计算 insets；分屏中缝不产生安全区，由内容自适。

### 4.4 缓存在 UI 中的可见性

所有关键界面展示"缓存命中率 + 成本 + token 用量"三合一芯片，点击进入统计页——对应 PC TUI 底部状态面板。**缓存经济性是产品灵魂，UI 必须让它可见**（用户可随时确认 Agent 是否在省钱地工作）。

### 4.5 Material 3 设计令牌

- 主题：动态取色（跟随系统壁纸）+ 固定深/浅色覆盖；语言：中/英切换
- 密度：紧凑型（手机上信息密度优先级高于舒适度）
- 动效：消息流增量出现 150ms、审批弹层 spring 滑入、工具卡状态色切换

---

## 5. 页面与交互流程设计

### 5.0 页面总览

| # | 页面 | 入口 | 对应 PC 功能 |
|---|---|---|---|
| 1 | 首次启动向导 | 冷启动无配置时 | 首次配置 + `/init` |
| 2 | 会话列表（主页） | 底部导航 Tab 1 / 冷启动默认 | `-c/-r`、`/branch /tree /switch`、`--copy`、prune |
| 3 | 对话主界面 | 会话列表点卡片 | TUI 主窗口（全量） |
| 4 | 指令面板 | 输入区上滑 / 点 `+` | 50+ 斜杠命令 |
| 5 | 项目文件夹工作台 | 底部导航 Tab 2 | 文件工具 7 项 + 检查点视图 |
| 6 | 工具审批与权限中心 | 审批弹层 + 设置 Tab | 权限策略 + Review/Auto/Yolo + 沙箱 |
| 7 | 设置中心 | 底部导航 Tab 5 | 配置系统（TOML 等价） |
| 8 | MCP 管理页 | 设置 > MCP | `/mcp list/search/install/inspect/browse` |
| 9 | 技能与记忆管理 | 设置 > 技能/记忆 | 技能 + 指令文件 + facts 记忆 |
| 10 | 成本统计面板 | 底部导航 Tab 4 / 成本条点击 | `reasonix stats`、`--budget` |
| 11 | 后台任务管理 | 底部导航 Tab 3 / 通知点击 | `/jobs /kill /logs` |
| 12 | 分屏/多窗口形态 | 系统分屏/横屏自动 | 桌面模式替代 |

### 5.1 首次启动流程（Onboarding）

**目的**：极简配置 —— 欢迎页 → 输入 API Key → 后台验证 → 欢迎页 2 → 开始（工程选择移出首启，主页空态引导）。

**流程**（品牌渐变背景 + Logo + staggered 亮相动效）：

1. **欢迎页**：品牌 Logo + 特性卡片（缓存省钱/全功能工具/本地工程/联网检索），"下一步"。
2. **API Key 页**：仅输入 Key（其他配置全部按官方默认自动设置）；点"下一步"→ **后台自动验证**（`GET /models`，IO 线程）：失败弹窗区分"网络连接失败"与"API 不正确"；成功静默保存（Keystore 加密）→ 进入欢迎页 2。对应 PC setup 向导的自动配置语义。
3. **欢迎页 2**：特性简览卡片 + "开始使用"按钮。
4. **完成**：进入主页（会话页树状工程结构），无工程时空态引导"新建工程（输入项目名 + 选择手机磁盘目录）"。

### 5.2 主页 / 会话列表（树状工程结构）

**布局**：

- 顶部：标题 + 跨工程搜索框（按标题/内容关键词定位会话，结果标注工程归属）
- **树状结构**：一级节点 = 工程（`📁 项目名` + 副标题 `磁盘: 目录名` + 会话数 + 当前标记），点击展开/收起（默认展开当前工程）；二级 = 该工程下会话卡片（渐变头像色块 + 标题 + 预览 + 时间/模型/命中率）
- 无归属旧会话归入"未分组"节点
- FAB `+`：新建会话 → 弹层选择目标工程（新建工程/已有工程）

**工程管理**：

- **新建工程**：对话框 = 输入项目名 + 选择磁盘目录（系统目录选择器）→ 创建后自动在该工程下新建会话并进入
- **工程长按/菜单**：重命名 / 删除工程（明示"磁盘源目录不受影响"）
- 会话卡片菜单：继续 / 复制为副本 / 删除

**关键一致性**：会话 ↔ 工程绑定 —— 打开会话时 Agent 文件操作范围自动切到该工程。

### 5.3 对话主界面（核心页面）

**布局（自上而下）**，见线框图 W1：

- **挖孔安全区**（留白 56–72dp，正中不可交互）
- **顶部应用栏**：左：会话标题（下拉展开会话菜单）；中：**模型芯片 `[Flash|Pro] ▾`**（对应 `/model`；模型自报告 `<<<NEEDS_PRO>>>` 时弹出升级建议）；右：**模式芯片 `[Review|Auto|Yolo]`**（对应 Shift+Tab）、**计划锁 🔒**（对应 `/plan`）、**目标徽章 🎯**（对应 `/goal`）
- **上下文/成本条**：`缓存命中 98.2% ｜ 成本 ¥0.031 ｜ 12.4k/64k` + 上下文用量圆环（40% 变琥珀、80% 变红并自动压缩，对应 PC 40% 主动/80% 紧急阈值）；点击进入压缩面板（对应 `/compact`）
- **消息流**（LazyColumn 反向滚动）：
  - 用户消息右对齐气泡；助手消息左对齐卡片；**reasoning 思考内容折叠块**（流式渲染）
  - **工具调用卡片**（核心交互对象）：`工具图标 + 名称 + 参数摘要(折叠)`，四态色——`待审批(琥珀) / 执行中(旋转) / 成功(绿) / 失败(红,可重试)`；展开显示完整 JSON 参数、输出预览、"查看 Diff / 回退该操作"；失败卡触发"3 次编辑错误自动升级 Pro"横幅
  - 消息长按 → 操作菜单：`复制 / 重新生成 / 从这里分支 / 回退到此处`
- **快捷操作栏**（输入框上方横向滑动，对应常用命令）：`📁 附加文件`（跳文件工作台选择回填）、`🔍 搜索项目`（search_files）、`🌐 网络搜索开关`（切换 8 后端）、`🎯 目标模式`、`⏱ 后台任务`
- **输入区**：`[+] 指令面板 | 输入框 | 发送键`；输入框支持 `/` 触发指令面板搜索、`@` 触发文件引用（SAF 选择器）
- **底部导航**：5 Tab + 审批红点角标（有待审批工具调用时显示计数）

**手势**：流式中右滑返回/下拉 60% = Esc 中止（确认弹窗）；中止后快速右滑第二次 = Esc-Esc 回退选择器；上滑输入区 = 指令面板。

**PC 映射**：流式输出→TUI 流式渲染；工具卡片→工具执行状态行；审批卡片→tool approval dialogs；模型芯片→/model+Shift+Tab；计划锁→/plan+Ctrl+P；成本条→stats panel；指令面板→/ 提示符补全；Todo→todo 工具（底部弹层）；上下文圆环→context 占比。

```
线框图 W1：对话主界面（竖屏，顶部居中挖孔）

┌───────────────────────────────────────┐
│ (状态栏/挖孔区——仅留白, 不放可交互元素)      │
│ ┌───────────────────────────────────┐ │
│ │ 重构登录模块   [Flash▾] [Review▾]🔒│ │
│ │ 缓存98.2% · ¥0.031 · 12.4k/64k ◐   │ │ ← 成本/上下文条(40%变琥珀)
│ ├───────────────────────────────────┤ │
│ │ ▢ 用户: 重构登录模块,支持扫码登录      │ │
│ │ ▢ 助手: 我先分析现有代码结构…         │ │
│ │  ┌ 工具卡片 ──────────────┐        │ │
│ │  │📖 read_file src/Auth.kt│        │ │
│ │  │ ✓ 完成 1.2s [详情]      │        │ │
│ │  └────────────────────────┘        │ │
│ │  ┌ 工具卡片(待审批·琥珀) ────┐        │ │
│ │  │✏️ edit_file src/Auth.kt │        │ │
│ │  │ ⏳ 等待审批 [查看Diff]     │        │ │
│ │  └────────────────────────┘        │ │
│ │ ▢ 助手: 找到3处需要改动…(流式游标)     │ │
│ ├───────────────────────────────────┤ │
│ │ [＋] 输入消息…( / 唤出指令 )     [发送]│ │
│ │  [📁][🔍][🌐][🎯][⏱]               │ │ ← 快捷操作栏
│ ├───────────────────────────────────┤ │
│ │ 对话  ●文件  ○任务●  ○统计  ○设置     │ │ ← 底导(审批红点角标)
│ └───────────────────────────────────┘ │
└───────────────────────────────────────┘
```

### 5.4 指令面板（50+ 斜杠命令的移动形态）

**形态**：从输入区上滑或点 `+` 唤起的**全屏底部弹层**。

- 顶部搜索框（输入即过滤，支持拼音首字母）
- 分类 chips：`模型｜会话｜检查点｜分支｜技能｜MCP｜记忆｜任务｜模式｜权限｜预算｜界面`
- 列表项：`命令名（中英对照）+ 一行说明 + 常用标记`，点击即执行；参数型命令展开参数表单（如 `/budget` 弹金额输入、`/mcp install` 弹 URL 输入）
- 底部"最近使用"横向条
- **常用命令直接跳页**：`/jobs`→任务 Tab、`/stats`→统计 Tab、`/permissions`→设置>权限、`/memory`→设置>记忆

**代表性映射**：

| PC 命令 | 移动端落点 |
|---|---|
| /model /pro /flash | 对话页模型芯片 |
| /plan /goal | 对话页计划锁/目标徽章 |
| /compact | 上下文条点击→压缩面板 |
| /sessions /branch /tree /switch /fork | 会话列表长按面板 + 分支树页 |
| /checkpoint /rewind | 快速右滑→检查点时间线 |
| /init @import | 设置>工作区>指令文件 |
| /jobs /kill /logs | 任务 Tab |
| /mcp list/search/install/inspect/browse | 设置>MCP 管理页 |
| /memory /skills | 设置>记忆 / 设置>技能 |
| /permissions /sandbox | 设置>权限与沙箱 |
| /theme /language /output-style | 设置>外观 |
| /websearch | 对话页快捷操作栏 🌐 |
| /budget | 统计页预算环形控件 |

### 5.5 文件工作台（当前工程目录树 + 文件管理）

**布局（竖屏单栏）**：

- 顶栏：`文件：当前工程名` + 完整环境标记
- 搜索框（文件名搜索）
- **主区：目录树列表**（懒加载）：**real 工程显示工作区真实目录树**（完整环境直连）；无工程显示引导"去会话页创建工程"
- **长按文件** → 底部操作面板：`预览 / 重命名 / 复制到… / 移动到… / 删除 / 附加到对话`（文件管理需求）
- 点击文件 → 预览弹层（只读，可"附加到对话"回填输入框）

**PC 映射**：文件工具全部可视化 + 基础文件管理（增删改查/重命名/复制/移动）。

**目录即工作区**：Agent 文件改动由回合末自动写回磁盘源目录 —— 用户在手机文件管理器看到的始终是最新成果。

```
线框图 W2：文件工作台（分屏左/单栏模式）

┌────────────────────────────────────────┐
│ ⬅ 工作台: MyProject            [沙箱🔒] │
│ /app/src/main/                    [🔍] │
│ ────────────────────────────────────── │
│ 📁 app                    (主分支树)     │
│ ├─📁 src                              │
│ │ ├─📁 main                           │
│ │ │ ├─📄 MainActivity.kt   1,024行     │
│ │ │ ├─📄 LoginActivity.kt     368行 ●   │ ← ●=本轮已修改
│ │ │ ├─📄 AuthViewModel.kt     512行 !   │ ← !=检测到外部变更
│ │ │ └─📁 res/ …                        │
│ │ └─📁 test                           │
│ └─📄 build.gradle.kts                   │
│ ────────────────────────────────────── │
│ [＋新建] [⏳ 索引中 2,341 文件]          │
└────────────────────────────────────────┘
```

### 5.6 工具审批与权限中心

**审批形态（底部审批弹层）**，见线框图 W3：

- 触发：Agent 发起匹配"询问"策略的工具调用（写文件、shell、破坏性 choice 工具、MCP 工具）
- 内容：`工具名 + 目标路径 + 未命中任何规则(需询问)`；**编辑类工具必须展示 Diff 变更预览**；参数 JSON 折叠；底部三按钮：`仅本次允许 | 总是允许 | 拒绝`；"总是允许"自动写入全局 glob 规则（对应 allow 持久化）
- 顶部 **SegmentedButton：Review | Auto | Yolo**（对应 PC Shift+Tab 循环；Yolo 不再弹审批仅记录）
- 底部附加：`⚙ 策略设置`（进规则编辑）、`回复 Agent`（文本输入，"先别改，解释一下"——对应拒绝并回话）

**权限中心管理页（设置 Tab 内）**：

- 策略规则列表：每行 `glob 模式 + 动作(allow/ask/deny) + 范围(全局/项目)`；`+` 新增规则（模式输入、动作选择、**测试匹配**——输入样例路径验证命中）
- 沙箱设置：工作区根（已授权树 URI）、允许写入目录、禁止读取目录
- 审批历史：今日已审批操作列表（时间、工具、结果）

```
线框图 W3：审批底部弹层

┌────────────────────────────────────────┐
│⌄  等待审批 (队列 2)                      │
│  ✏️ edit_file   [工具卡片]              │
│  /app/src/main/java/LoginActivity.kt   │
│  命中策略: 未匹配任何规则 → 需要询问        │
│  ┌ 变更预览(Diff) ──────────────┐       │
│  │ - if(!ok){ showError(); }   │       │
│  │ + if(!ok) return            │       │
│  │ - private val key = ""      │       │
│  │ + val key = BuildConfig.K   │       │
│  └──────────────────────────────┘       │
│  模式:  (•)Review  ( )Auto  ( )Yolo     │
│  ┌──────────┬──────────┬──────────┐     │
│  │ 仅本次允许 │ 总是允许  │   拒绝   │     │
│  └──────────┴──────────┴──────────┘     │
│  [回复 Agent…]            [⚙ 策略设置]   │
└────────────────────────────────────────┘
```

### 5.7 设置中心

分组列表：

- **Providers 与模型**：provider 卡片（名称/base URL/API Key 掩码/状态灯）、默认模型（flash/pro）、planner_model、subagent_models、自升级阈值（编辑错误次数、`<<<NEEDS_PRO>>>` 自报告开关）
- **预算**：日/会话预算上限（对应 `--budget`）、超限行为（停止/降级 flash/仅提醒）
- **外观**：主题（跟随系统/浅/深）、语言（中/英）、输出风格、字体大小
- **会话**：保留天数、自动剪枝、自动恢复策略
- **网络**：搜索后端顺序（8 后端多选排序）、代理、超时
- **关于**：版本、缓存命中统计汇总、开源许可（MIT）

### 5.8 MCP 管理页

- 插件列表：`名称 + 传输类型标签(进程桥/SSE/HTTP) + 状态灯 + 启用开关 + 工具数量徽章`；点击展开：工具清单（inspect）、连接参数、超时、日志尾部
- `＋ 添加插件`：选择传输类型；SSE/HTTP 填 URL；"本地进程桥"列出内置适配插件
- `浏览目录`：远程插件目录搜索（对应 browse）
- 故障提示：进程桥插件崩溃显示"已隔离重启"

### 5.9 技能与记忆管理

- **技能页**：技能列表（项目级/全局级）、新建走 Markdown 编辑器、预览与试运行、runAs 子代理开关
- **指令文件查看器**：AGENTS.md / REASONIX.md / CLAUDE.md（用户全局→工作区根→目标路径解析顺序展示）、@import 依赖树（最多 5 层）、一键编辑、"重新生成 REASONIX.md"（对应 /init）
- **记忆页**：facts 列表（类型/版本号/创建更新时间/召回时间）、**召回预览**（展示"下一回合将自动召回哪些 facts（最多 4 条/2400 字符）"及 BM25 得分）、手动增删改、过期清理

### 5.10 成本统计面板

- 顶部概览卡：今日总成本、本月累计、环比；**预算环形图**（已用/上限，超限红色）
- 图表区（Compose Canvas 自绘）：近 7 日成本柱状图、缓存命中率折线（目标 ≥90%）、token 构成环形（缓存输入/非缓存输入/输出）、各模型成本堆叠条（flash vs pro vs 辅助调用）
- 会话明细表：每会话成本、缓存命中率、token 分解、模型切换次数；按日筛选
- 自动升级事件时间线（"3 次编辑错误→升级 Pro"等）

### 5.11 后台任务管理

- 任务列表：`任务名 + 类型(构建/测试/服务器/扫描) + 进度 + 状态(运行/成功/失败/已终止) + 时长 + 所属会话`
- 展开：日志尾部（自动跟随滚动可暂停；环形缓冲 500 行，对应 `/logs`）；`[终止 /kill] [重试] [复制日志]`
- 后台：前台服务常驻通知（进度 + "结束/查看"）；App 被杀后 WorkManager 续跑并在会话列表打角标
- 小米系设备引导用户加入电池白名单

### 5.12 分屏/多窗口形态（关键需求：分屏完整提供 PC 功能）

**1. 窗口尺寸类响应**：compact → 底部导航单栏；medium → NavigationRail；expanded → 三段式（左 Rail + 中央对话 + 右侧可切换面板：文件工作台/审批中心/上下文统计/会话分支树），抽屉（指令面板、Todo、分支树）改为常驻面板。

**2. 双实例分屏（手机分屏各占半屏）**：

- 两个窗口可为本应用两个实例（Android 16 多实例支持），**每个实例是完整可用的独立 App**（全部 5 Tab 可用），实例间通过 `arguments` 携带窗口角色（主窗=聊天、次窗=文件，可选）
- **会话租赁锁**（复用 PC lease 语义）：同一会话仅允许一个实例写操作，另一实例只读并在输入区显示"会话被另一窗口占用，只读"；防止并发写损坏上下文
- **分屏配对建议横幅**：进入分屏且另一半空白时弹出建议卡：`聊天+文件 / 聊天+任务日志 / 聊天+审批列表 / 双会话(flash 与 pro 各一窗)`
- **IME 行为**：键盘只压缩所在窗格，另一窗格保持可视（边看 Diff 边打字）；输入区 `imePadding`，消息列表自动滚底；键盘弹出期间聊天页顶部挖孔区不压缩
- **multi-resume**：两实例同时 RESUMED，各自接收生命周期回调，流式渲染互不阻塞

**3. "PC 类桌面模式"布局**，见线框图 W4：

- 左 Rail：5 Tab 图标 + 当前会话成本小徽章
- 中央：对话主界面（消息 + 工具卡片 + 输入区）
- 右面板：可切换 `文件工作台｜审批中心｜上下文统计｜会话分支树`，带收起按钮
- 审批在桌面模式：不弹底部弹层，作为右面板审批列表出现，点击展开 Diff
- 每个窗格均保持"单窗格可用"：右面板关闭后中央区自适应

```
线框图 W4：分屏/桌面模式

[分屏双实例 —— 左窗 A / 右窗 B]
┌─────────────────┬─────────────────┐
│ (A) 挖孔区       │ (B) 挖孔区        │
│ 对话标题 [Flash] │ 工作台: MyProj    │
│ 成本条           │ 📁 目录树         │
│ 消息流           │ ───────────      │
│ ┌工具卡片─┐      │ 文件预览/Diff     │
│ │read_file│     │ ───────────      │
│ └────────┘      │ [新建][沙箱🔒]    │
│ 输入栏 + 快捷条  │                  │
│ 底导(缩为Rail)   │ 底导(缩为Rail)    │
└─────────────────┴─────────────────┘

[单实例 expanded 桌面模式]
┌──────────────────────────────────────────┐
│ (状态栏 + 挖孔留白区)                       │
│ ┌───┬───────────────────────┬──────────┐ │
│ │ R │  对话主界面             │ 右面板(可切)│ │
│ │ a │  消息流 + 工具卡片       │ [文件|审批 │ │
│ │ i │  上下文/成本条           │  |统计|树]│ │
│ │ l │  输入区 + 快捷操作       │  Diff 视   │ │
│ │   │                        │  图/列表  │ │
│ │   │  (计划锁/模型芯片置顶)    │  [收起▷]   │ │
│ └───┴───────────────────────┴──────────┘ │
└──────────────────────────────────────────┘
```

---

## 6. Agent 引擎设计（核心）

### 6.1 状态机（AgentEngine，单例，协程驱动）

```
IDLE → BUILD_CONTEXT → STREAM_LLM → PARSE → [REPAIR×4] →
  ├─ 需要工具 → APPROVAL_CHECK → EXECUTE_TOOL → 追加日志 → BUILD_CONTEXT
  ├─ 需压缩 → COMPACT(40%主动/80%紧急) → BUILD_CONTEXT
  ├─ 需汇总 → SUMMARIZE(flash 硬编码) → BUILD_CONTEXT
  └─ 完成 → 回合记账 → IDLE
错误路径: 连续3次编辑错误 → 自动升级 pro(记录事件) / 预算超限 → 停止并通知
```

### 6.2 三区上下文实现（缓存经济性的核心）

| 区 | 内容 | 规则 |
|---|---|---|
| `IMMUTABLE_PREFIX` | system + 工具 spec（扁平化后）+ few-shots | 会话期间**字节稳定**，构建一次缓存；仅当工具集/系统提示版本变更时重建（`prefixVersion` 触发强制重建） |
| `APPEND_ONLY_LOG` | assistant/tool 回合 | **只追加不重写**；压缩时仅做摘要替换尾部 |
| `VOLATILE_SCRATCH` | thought/plan 草稿 | 仅本次请求有效，**永不上送** |

- 每请求计算 prefix 的 SHA-256 作为缓存键；读取响应 `usage.prompt_cache_hit_tokens` 核验命中
- miss 时 UI 提示"缓存未命中（前缀变更）"并归因
- **写入纪律**：日期、项目名、窗口信息等动态内容一律进 APPEND_ONLY_LOG 或 SCRATCH，**禁止进入 prefix**（见风险 D3）

### 6.3 工具调用修复管线（逐字移植 PC 规则）

| 道 | 失败模式 | 机制 |
|---|---|---|
| Flatten | schema >10 叶子参数或深度 >2 导致调用截断 | 点号标记扁平化呈现，派发时重新嵌套 |
| Scavenge | tool_call JSON 出现在 `<think>` 内 | 正则 + JSON 解析器扫描 `reasoning_content` 找回 |
| Truncation | max_tokens 截断产生半截 JSON | 检测不平衡 JSON，基于状态栈闭合，或请求续写 |
| Storm | 相同 (tool, args) 连续重复 | 滑动窗口内容哈希去重，注入反思回合 |

均为纯字符串/JSON 处理，与平台无关。

### 6.4 分层成本控制

- 内置价格表：flash/pro/辅助模型单价；**缓存命中 token 按 10% 计费**
- 每回合记账：缓存输入/非缓存输入/输出 token × 单价 → Room `cost_records`
- 模型路由：flash 优先；`<<<NEEDS_PRO>>>` 自报告；**连续 3 次编辑错误 → 本回合余下步骤升级 pro**
- 辅助调用（汇总、子代理）硬编码 flash
- 预算上限检查（超限→按配置停止/降级/仅提醒）

### 6.5 流式客户端

- OkHttp + SSE 逐行解析 `choices[0].delta`，三类事件流分发：
  - `reasoning_delta` → 折叠块增量
  - `content_delta` → 正文增量
  - `tool_call_delta` → 增量 JSON 装配，完成时校验并进入修复管线
- Flow 推送至 Compose 层，LazyColumn 增量渲染
- 断线重连（指数退避）、半消息恢复、发送前完整快照

### 6.6 压缩（Compaction）

- 上下文条达 40%：UI 提示 + 可手动触发；80%：紧急自动压缩并中断执行
- 压缩 = 尾部 log 摘要（flash 执行）+ 摘要产物进入 log（**不进 prefix**，保持前缀缓存键稳定）
- 工具结果 >3000 token 在回合末收缩

---

## 7. SAF 文件系统与文件工具层

### 7.1 树 URI 持久化

- `ACTION_OPEN_DOCUMENT_TREE` 成功 → `takePersistableUriPermission()` → URI 存 DataStore
- 启动时校验权限；失效则**非破坏式**引导重新授权（保留会话，仅重新绑定树 URI）
- 授权后重建索引

### 7.2 路径解析与访问

- `relativePath → DocumentFile` 逐段 `findFile`（内存缓存；目录项数 >1000 分页读取）
- `SafFileSystem` 是**唯一文件访问入口**，禁止任何 `java.io.File` 绝对路径操作

### 7.3 工具实现（ToolSpec 模式，schema 走 Flatten 规则）

| 工具 | 实现要点 |
|---|---|
| `read_file` | `contentResolver.openInputStream`，UTF-8，上限默认 1MB（超限截断并标记），优先命中缓存 |
| `write_file / edit_file / multi_edit` | `openFileDescriptor(uri, "rwt")` 读改写；diff 用 java-diff-utils 生成 unified diff（供 Diff UI 与工具结果）；**写前必须过权限策略** |
| `list_files / search_files` | `DocumentFile.listFiles()` 递归（depth 上限默认 4）分批；搜索走缓存索引（后台预扫描增量更新） |
| `move_file` | `DocumentFile.moveTo` |

### 7.4 内容缓存与外部变更检测

- 磁盘 LRU（app-private filesDir，上限 512MB）+ 内存 LRU（64MB），键 = `(uri, lastModified, length)`
- 每次读取对比 `lastModified + SHA-256(首尾1KB)` 指纹；变化 → 工具结果附加 `(文件已被外部修改)` + 工作台标 `!`，模型可重新读取（对应 PC watch 敏感性）

### 7.5 权限映射

- 工具调用 → `PolicyEngine.match(glob)` 三态：allow 直行 / ask 弹审批 / deny 拒绝并返回说明
- 写类工具默认 ask；Yolo 全 allow 但记账留痕

### 7.6 性能对策

- 索引预扫描用 WorkManager 充电/空闲时跑；目录列表 300ms 防抖；大文件流式读入仅当模型请求

---

## 8. 权限、沙箱与安全

### 8.1 策略引擎（allow/ask/deny）

```
PolicyEngine.match(glob) → Decision(ALLOW | ASK | DENY)
规则优先级: deny > allow > ask(默认)
规则格式: "Bash(rm -rf*)" / "edit_file:/app/src/**" — 工具名 + 路径 glob
```

### 8.2 运行时模式

`Mode` 枚举（Review/Auto/Yolo）→ 审批弹层行为开关 + 权限中心 SegmentedButton；模式切换记录审计日志。

### 8.3 沙箱（SAF 天然沙箱）

- `workspaceRoot` = 已授权树 URI（读写边界）
- `allowWrite` = 授权子目录清单；`forbidRead` = 禁读清单
- SAF 本身无全局文件访问 → 沙箱即"当前授权树"，策略引擎在树内实施 read/write 白黑名单
- **受限 Shell**（bash 替代）：内部沙箱命令执行器，只读命令白名单（git status/log/diff 等），写入命令拒绝并返回说明；标记为"受限模式"

### 8.4 安全

- API Key：Keystore（AES-GCM）+ EncryptedSharedPreferences；**禁止明文持久化、禁止进日志**
- MCP 进程隔离（第 10 章）
- 网络：HTTPS 强制，自定义 provider 需用户确认非官方端点

---

## 9. 会话、检查点与回退

### 9.1 会话模型

- Room `sessions`（id/title/created_at/model/成本累计）+ `messages`（role/content/reasoning/tool_calls/ts）+ `tool_calls`（独立表）
- 租赁锁：DB 层 CAS，同一会话仅一个实例可写（双实例场景，第 12 章）
- 剪枝：保留天数配置（对应 `prune-sessions --days N`）；临时模式（不持久化）

### 9.2 分支与复刻

- `/branch`：从任意消息节点分叉 → `branch_tree` 表 + 分支树 UI（对应 `/tree`）
- fork：复制会话为新写会话（原会话只读保留，对应 `--copy`）

### 9.3 检查点快照引擎（M3）

- 每个用户回合结束：该回合被修改文件内容复制为 blob（SHA-256 去重存 `filesDir/checkpoints/blobs/`），索引存 Room `checkpoint_index`（turnId/uri/relPath/sha/size/ts/scope）
- 恢复：**事务式**——校验全部 blob 存在 → 按逆序写回 → 失败自动回滚已写部分
- 回退范围 Code / Conversation / Both（Conversation = 消息表截断 + 代码恢复）
- 保留 ~30 天；支持跨会话持久
- UI：对话页快速右滑 → 检查点时间线；文件页"时间线"按钮

---

## 10. 记忆、技能与 MCP

### 10.1 指令文件

- `AGENTS.md / REASONIX.md / CLAUDE.md` 解析顺序：用户全局 → 工作区根 → 目标路径（更深覆盖更广）
- `.local.md` 变体覆盖；`@import` 支持（最多 5 层）
- `/init` 等价：扫描项目生成 REASONIX.md
- **指令文件内容进 IMMUTABLE_PREFIX**（静态规则，缓存稳定）

### 10.2 后台事实记忆（M4）

- facts 存 Room：`id / revision / type(user|feedback|project|reference) / created_at / updated_at / last_recalled_at`
- 召回：每回合前 BM25 检索，**最多 4 条 / 2400 字符**，作为低优先级后缀（不进 prefix）
- 分词：英文空白+词干化；中文 jieba-android（回退 2-gram）
- 类型新鲜度窗口：user/feedback 90/365 天、project 30/180 天、references 14/45 天
- 自动创建仅限 project/reference；全局 facts 需用户确认

### 10.3 技能（M4）

- Markdown 剧本（frontmatter：name/description/runAs）存项目 `.reasonix/skills/` 或全局
- 技能列表 → 指令面板"技能"分类；`runAs: subagent` 以子代理隔离执行

### 10.4 MCP 在 Android 的适配

| 传输 | Android 方案 |
|---|---|
| stdio | **进程桥**：插件作为独立 Android 进程（`android:process=":mcp_<id>"`）运行，宿主与插件进程间 LocalSocket 对（宿主编排线程充当 stdin/stdout 管道），插件侧用标准 stdio JSON-RPC 协议读写 socket；崩溃隔离与重启。内置插件（文件/搜索）可进程内注册 |
| SSE | Ktor 客户端原生支持，原样可用 |
| streamable-HTTP | 同上 |

- 注册表统一桥入 ToolRegistry（MCP 工具名 → 路由对应插件）
- 超时/启用开关/状态灯统一管理；插件清单标注"平台支持度"

---

## 11. 后台任务与通知

- 任务模型入 Room `jobs`：短任务 WorkManager（expedited）；长任务**前台服务**（`FOREGROUND_SERVICE_TYPE_DATA_SYNC` + 常驻通知带进度与"结束/查看"动作）
- 日志环形缓冲（内存 500 行 + 可选落盘）；`/kill` = 服务 stopRequest + 协程取消 + 状态"已终止"
- App 被杀 → WorkManager 兜底重跑；任务状态落库可恢复
- 小米系设备：引导加入电池白名单（设置页引导项）

---

## 12. 分屏与多窗口适配

（页面层设计见 5.12；此处为技术要点）

- `WindowSizeClass` 监听 → `AdaptiveLayout.kt` 统一分发（Rail/三段式/单栏）
- 多实例：`arguments` 携带角色；会话租赁锁（9.1）防并发写
- IME：输入区 `imePadding`；键盘仅压缩所在窗格（系统分屏行为）
- 右面板状态（当前面板类型、收起态）随实例内存保持，不跨实例同步（各自独立）
- 桌面模式下审批不弹层，改为右面板列表（第 5.12 节）

---

## 13. 成本统计仪表盘

- 数据源：Room `cost_records`（会话/回合/模型/构成）+ `events`（升级/压缩/预算事件）
- 指标：今日/本月成本、缓存命中率（目标 ≥90%）、token 构成、模型成本占比、预算剩余
- 图表：Compose Canvas 自绘（柱状/折线/环形/堆叠），避免引入重型图表库
- 会话明细 + 事件时间线（自动升级、超限停机）

---

## 14. 功能对标矩阵

| # | PC 功能 | 移动端实现 | 里程碑 |
|---|---|---|---|
| 1 | 缓存优先三区上下文 | `ContextManager` 三区 + prefix SHA-256 校验 | **M1** |
| 2 | 4 道工具修复管线 | `RepairPipeline`（Flatten/Scavenge/Truncation 先行） | **M1** |
| 3 | flash 优先 / `<<<NEEDS_PRO>>>` / 3 次失败升级 | `ModelRouter` + 对话页升级横幅 | **M1/M2** |
| 4 | 40%/80% 压缩 | 上下文条环形指示 + 压缩面板 | **M1** |
| 5 | 执行者+规划者双模型 | planner 独立会话（只读） | M4 |
| 6 | MCP stdio/SSE/HTTP | 进程桥 / Ktor 客户端 | M4 |
| 7 | 文件工具 7 项 | `SafFileSystem` 全套 | **M2** |
| 8 | bash/shell | 受限沙箱 Shell（只读白名单），原生 shell 标不支持 | M2（受限） |
| 9 | 8 后端网络搜索 | `WebSearchProvider`（Bing/Tavily/百度 HTTP 直连） | M2 |
| 10 | 子代理 depth2/并发6 | `SubAgentManager` 协程并发池 | M4 |
| 11 | todo / choice / plan 只读 | Todo 抽屉 / 审批门控 / 计划锁 | **M2** |
| 12 | allow/ask/deny + glob | `PolicyEngine` + 策略编辑 UI | **M2** |
| 13 | Review/Auto/Yolo | 审批弹层 SegmentedButton | **M2** |
| 14 | 检查点/回退 | `CheckpointStore` + 时间线 UI + 双滑手势 | M3 |
| 15 | 会话 branch/fork/lease/prune | 会话列表长按面板 + 租赁锁 | M3 |
| 16 | 指令文件 + @import + /init | 指令文件查看器（5 层树） | M3 |
| 17 | facts BM25 记忆 | facts + BM25 召回 + **remember/forget 自主记忆工具** + 召回预览 | M4 已实现 |
| 18 | 技能 + runAs | 技能编辑器 + 子代理 | M4 已实现 |
| 19 | 50+ 斜杠命令 | 指令面板（分类+搜索） | **M2** |
| 20 | /jobs /kill /logs | 任务 Tab + 通知 | M5 |
| 21 | reasonix stats / --budget | 统计仪表盘 + 预算环 | M5 |
| 22 | 主题/语言/输出风格 | 设置>外观 | **M2** |
| 23 | 目标模式 | 目标徽章 + 持久化 | M3 |
| 24 | 计划模式 | 计划锁（只读审计） | **M2** |
| 25 | 网络搜索开关 | 快捷操作栏 🌐 | **M2** |
| 26 | 临时模式（--no-session） | 新建会话选项"不保存" | M3 |
| 27 | serve/桌面/VS Code 扩展 | N/A —— 分屏桌面模式替代 | — |

**实现状态（2026-08-10 当前）：M1–M6 全部完成 + 十三轮迭代（195 测试全绿）** —— 内置完整 Linux 环境（Termux 发行形态打包进 APK，首启本地解压零下载，apt 全仓库可用）；**UI 按 frontend-design 原则全面重设计**（品牌 Design System/Outfit 字体/Logo/欢迎页动效）；**目录即工作区 2.0**（工程注册表唯一真相源 + SAF 真实目录统一，Agent 改动直接落真实磁盘文件）；PC 工具集补全 10 项 + remember/forget 自主记忆；会话页树状工程结构（工程折叠记忆）；158 项单元测试通过。stdio 进程桥受 Android 平台限制标注"平台限制"；MCP HTTP 桥接、受限 shell、Bing/Tavily 搜索后端、BM25 记忆（「请记住」自动存入 + 回合前召回 4 条/2400 字符 + 陈旧性声明）、技能生态（详见附录 A）、子代理/规划者、检查点快照回退、会话分支/复制/剪枝/租赁锁、目标模式、计划模式状态机、后台任务前台服务、成本仪表盘（模型分组/双树/折线图）均已落地。**2026-08-10 六批（158 测试）新增：Agent 执行保活（前台服务 + 唤醒锁 + 电池优化豁免 + 通知实时意图 + 完成通知）、同批只读工具并行（上限 4）、会话渲染对齐 Claude CLI（意图优先：提示词先意图后工具 / StepRecord+ToolCallRecord 携带 intent / 三处 UI 折叠单行 + 工具名小字）。2026-08-09 二批（96 测试）与 08-10 三批（14 项 Cloud 差距，128 测试）+ 四/五批审查修正（134 测试）的完整记录见附录 A。**

---

## 15. 关键风险与应对

| # | 风险 | 缓解措施 |
|---|---|---|
| D1 | SAF 性能：大项目（万级文件）递归列举与随机读延迟高 | 后台预索引（WorkManager 空闲/充电时）、懒加载分页、目录级缓存、文件大小上限、搜索走索引 |
| D2 | 挖孔/横屏/分屏 insets 误判 | WindowInsets 统一封装；真机矩阵测试；顶栏中央禁交互区规则进 review |
| D3 | **缓存经济性被 UI 破坏**：动态信息（日期、窗口状态）误入 IMMUTABLE_PREFIX → 命中率崩塌 | prefix 写入纪律（仅静态内容）+ 构建期断言；每会话记录命中率，<90% 日志告警并归因（diff 出 prefix 变化点） |
| D4 | MCP 移植受限：依赖真实 POSIX 的 stdio 插件无法移植 | 进程桥提供隔离；插件清单标注平台支持度；不支持的标"不可安装"而非静默失败 |
| D5 | 分屏 + IME：键盘压缩半个窗格 | 输入区 `imePadding` + 消息列表滚底；键盘只压缩所在窗格；两窗格各自处理 |
| D6 | 双实例并发写同一会话 | 会话租赁锁（DB 层 CAS），被占实例切只读并提示 |
| D7 | 后台任务被厂商杀（小米省电策略） | 前台服务 + 常驻通知；引导加入电池白名单；WorkManager 兜底；状态落库可恢复 |
| D8 | SAF 权限失效/被撤销 | persistable 权限 + 启动校验；非破坏式引导重新授权（保留会话，仅重绑树 URI） |
| D9 | BM25 中文分词质量 | jieba-android + 2-gram 回退；召回预览页人工可验证 |
| D10 | 流式断网/弱网 | SSE 断线重连（指数退避）、半消息恢复、发送前完整快照、离线成本条降级 |
| D11 | 完整环境二进制无法执行（Android 10+ targetSdk≥29 禁 execve app-data，W^X） | targetSdk 34（避开 15/16 的 app-data dlopen 限制）+ termux-exec（Apache-2.0，Termux 官方）LD_PRELOAD 拦截 execve → /system/bin/linker64 加载；Kotlin 侧首跳同样走 linker64。assets/termux-exec-*.so 来源：packages.termux.dev termux-exec 2.5.0（许可证副本 assets/termux-exec-LICENSE-Apache-2.0.txt）；已知限制：静态链接二进制不可用、直调 syscall 的 exec 不被拦截 |

---

## 16. 开发路线图

| 里程碑 | 周期 | 范围 | 验收标准 |
|---|---|---|---|
| **M1 地基：聊天闭环** | 2–3 周 | 工程骨架、edge-to-edge+挖孔适配、Onboarding（API Key/SAF 选目录持久授权）、会话列表（最小）、SSE 流式聊天、三区上下文+prefix 缓存校验、修复管线（Flatten/Scavenge/Truncation）、基础成本记账、缓存命中显示 | 端到端对话可用；缓存命中率 ≥90%；挖孔/横屏不遮挡 |
| **M2 工具与安全** | 3 周 | 工具注册/执行、SAF 文件工具全套、审批底部弹层、权限策略引擎+编辑 UI、沙箱、Review/Auto/Yolo、计划模式、指令面板（全部斜杠命令）、Todo、搜索后端、受限 shell、40%/80% 压缩 | 文件读写+审批全流程；写操作无一漏审；指令面板覆盖 50+ 命令 |
| M3 会话与回退 | 2–3 周 | 会话完整管理（分支树/复制/租赁锁/剪枝）、检查点快照+事务恢复+双滑回退手势、指令文件+@import+/init、目标模式 | 任意回合可回退且代码一致；分支/切换无数据丢失 |
| M4 智能体深化 | 3 周 | facts 记忆+BM25+召回预览、技能编辑器、子代理、MCP（进程桥+SSE/HTTP）、planner 协作、自升级策略 | MCP 插件可安装/启用/隔离重启；记忆召回符合 4facts/2400 上限 |
| M5 多窗口与发布 | 2–3 周 | 分屏双实例+配对建议+会话锁、expanded 桌面模式、后台任务（前台服务+通知）、成本仪表盘+预算上限、打磨（动效/空态/无障碍）、商店发布 | 分屏下全部 PC 功能可用；预算超限正确停机；真机矩阵全绿 |

**MVP（本次交付）** = M1 全部 + M2 大部分（工具/审批/指令面板/计划模式），单 app 模块内实现，模块化分包，为后续里程碑保留接口。

---

## 17. 测试与验收标准

### 17.1 真机测试矩阵（主测机：小米 17 Pro Max）

| 场景 | 用例 |
|---|---|
| 挖孔/insets | 竖屏主界面、横屏全屏、左右分屏——顶栏内容不遮挡、中央无交互元素 |
| SAF | 授权流程、重启后权限保持、权限撤销后非破坏恢复、大目录（>1 万文件）索引 |
| 对话 | 流式渲染（reasoning/正文/工具卡）、中止、断网重连、上下文压缩触发 |
| 审批 | 写文件审批、Diff 预览、总是允许持久化、Yolo 模式留痕 |
| 分屏 | 双实例各 Tab 可用、租赁锁只读提示、配对建议横幅、键盘只压缩所在窗格 |
| 成本 | 缓存命中率 ≥90%（每版本 100 回合基准）、预算超限停机 |
| 后台 | 前台服务通知、杀后台恢复、电池白名单引导 |
| 性能 | 冷启动 <2s、消息列表 60fps、目录浏览无卡顿 |

### 17.2 单元测试（CI 常驻）

- glob 匹配（`edit_file:/app/src/**` 等规则集）
- 三区上下文前缀字节稳定性（动态信息注入断言不进 prefix）
- 成本记账（缓存 10% 计费、三构成分解）
- 修复管线（Flatten 重嵌套、Scavenge 从 think 块找回、Truncation 闭合）
- SAF 指纹检测（内容变更识别）
- BM25 召回（top-4/2400 字符上限、类型新鲜度）

### 17.3 发布清单

- 构建：`gradlew assembleRelease`（签名 keystore）、minify + 资源压缩
- 平台：上传应用商店前完成隐私政策（API Key 本地加密存储声明）
- 版本：v0.1.0-MVP

---

*文档完。MVP 实现范围为第 16 章加粗行，代码结构对应第 3.3 节分包规划。*

---

## 附录 A：迭代记录（2026-08-08 ~ 08-10）

### A.1 2026-08-09 二批（真机反馈驱动，96 测试全绿）

底部导航图标对齐（weight 等宽格）；文件页 combinedClickable 手势修复；**任务页重构**（TaskStore.update 就地修改落盘修复"永远运行中/日志 0 行"、任务按 工程→会话 树状展示、工程名快照）；todo 响应式（TodoUpdated 事件实时上屏）；输入行重排（BasicTextField 自绘紧凑）；返回键分层（内部页逐层返回 → 根部弹退出确认）；启动自动解压完整环境（进度可见）；会话页工程折叠 + userToggledProjects 防刷新复原；**分区存储工作区修复**（workspaceRoot 权限门降级 SafBackend + 启动授权引导）；工程注册表 2.0（目录即工作区：注册表唯一真相源、SAF 真实目录统一、真实路径工程直接操作磁盘）。

### A.2 2026-08-10 三批（Cloud 版能力差距 14 项全量，128 测试全绿）

依据《Android端与Cloud版Agent能力差距分析20260810.md》一次性全量修复：

- **删除会话级联清理**（最重要）：SessionCascade 按 id 精确清理各工程 `.reasonix-backup/{id}.json` + 检查点 + 删除黑名单（恢复流程命中跳过防复活）+ 删除确认弹窗；删工程级联清理（只动 App 元数据，不碰用户磁盘文件）
- **成本口径统一**：会话详情 ContextBar 恒显会话累计（与成本页同源全量求和），最近一步仅执行中附显
- **任务"时有时无"**：prune(10)→pruneByAge(30 天) 首次进页 + take(200)
- **斜杠命令**：点击回填输入框（设置类）+ 全部命令具体结果 toast
- **停止按钮**：OkHttp readTimeout 300s（原 0 无限等待致取消失效）+ aborting 即时反馈（三处按钮"正在停止…"）
- **锁/目标图标**：🎯 emoji→Material Place 图标 + Snackbar 反馈 + 首次使用引导 + 目标帮助文案
- **执行状态三处动效**：底部常驻执行指示条（spinner+动作文案轮播+计时）/ 中间文字"等待执行结果"提示 / todo 当前项脉冲高亮；工具链 ⏳→旋转动画
- **页签标题 + 底部导航文字**：五页 PageHeader + 底部导航图标文字标签
- **成本模型分组**：统计页模型分区（Pro/Flash 三档可展开）、会话卡分区分行、缓存节省金额、价格表+口径说明折叠区
- **计划模式代码级写拦截**：ToolRegistry.isWriteTool（补 delete_range）+ 拦截早于审批门控
- **执行轨迹步骤树**：TurnRecord/StepRecord 持久化（状态/耗时/产物引用），旧会话 backfillTurns 兼容，完成后消息流可展开复盘（失败红标）
- **Skill 生态（对齐 PC 版）**：SKILL.md frontmatter 解析互通；内置 9 技能（init/explore/research/review/test + frontend-report-html/data-analysis/doc-outline/ppt-html 离线可用）；URL 链接一键安装；run_skill/read_skill/install_skill 工具；技能索引注入（4KB 截断）+ 触发词匹配；点名缺失技能明确询问不静默回退
- **计划模式状态机**：PlanGate（规划→审批→执行）+ submit_plan 提交通道 + PlanReviewSheet 审批层（批准/驳回带意见/拒绝）
- **成本高级视图**：双树状图（模型→日期→会话→轮次）、命中率+成本双轴折线图（模型切换点/高 miss 高亮）、TOP5 排行、缓存节省

### A.3 2026-08-10 四批（全面审查修正，134 测试全绿）

三维度审查（引擎逻辑/UI 一致性/PC 对照）后修复：

- **PlanGate 跨会话污染（严重）**：per-session 隔离（planGateFor(sessionId) ConcurrentHashMap）
- **submit_plan 非计划模式误调用**：phase 前置检查 → FAILED 不挂起
- **planReady/aborting 残留**：reloadSession/Error/startTurn 三处复位
- **黑名单异步写入**：SessionCascade 改 runBlocking(IO) 同步 + 备份删除返回值检查
- **onSend 斜杠本地分发**：回填/手动输入的斜杠命令回车即执行（不发模型）+ FocusRequester 焦点
- **PC 对齐**：批准注入 plan 原文、PLAN_MODE_SUFFIX 分层计划指令（PC Marker）、预算 80% 预警告（BudgetWarning）、金额精度统一、折线图 Y 轴动态、TurnTreeCard diff、技能索引码点截断、prunedOnce rememberSaveable
- **P2 增强**：Skill 四字段（autoUse/readOnly/model/requires + AutoUse 路由）、/skills 开关与预览（disabledNames）、设置页命令直达（scrollToItem）、成本条余额显示

### A.4 2026-08-10 五批（二次审查：提示词/自动化 PC 对照，134 测试全绿）

- **sendInput 双重执行修复**：面板执行后回填 + 未编辑回车二次执行（/plan.toggle 切回原状态）→ lastExecutedCommand 标记跳过
- **URL 重装自动启用**：installSkillFromUrl 成功后 setEnabled(true)
- **记忆陈旧性声明**：`[记忆回顾]` 前缀加"可能已过时，仅作背景参考——使用时请现实验证"（对齐 PC memory.Compose）
- **技能索引调用哲学**：header 加"inline 技能加载成本低，可能相关即可先加载；subagent 较重确认需要才委派"
- **choice 升级 PC ask**：多题 questions（1-4 题，header 分组/multiSelect/recommendedFirst）+ 单题兼容；ChoiceCard 多题渲染（分组/复选/推荐⭐/统一提交）
- **技能顶级工具**：explore/research/review 独立 ToolSpec（对齐 PC boot.go），只读自动放行
- **输出风格补 explanatory/learning**（设置页 chips 五项）
- **语言自适应评估为不做**：当前用户固定中文，per-turn 语言注入需 BASE 分层重构才有缓存意义（记录差异）

### A.5 自动化程度与 PC 对照结论（五批审查）

| 维度 | 结论 |
|---|---|
| 审批模型 | 高度对齐（allow/ask/deny + glob + 只读默认放行；choice 现支持 PC ask 多题形态） |
| 技能系统 | 对齐（索引/工具/匹配/autoUse 分级；Android 有缺失主动询问优势） |
| 记忆 | 工具接口对齐；Android 用 BM25 动态召回注入（vs PC 固化 system prompt），已补陈旧性声明 |
| 规划 | PC 双模型全自动 vs Android 单模型手动开启计划模式（移动端可预测性取舍，合理） |
| 语言/环境 | PC 自动探测自适应 vs Android 硬编码中文/Termux 约束（中文用户无收益，记录差异） |
| 自动化整体 | PC 引擎层自动 vs Android 提示词层防御（工具纪律/Shell 规范更详细）——源于 DeepSeek 单模型与移动端可预测性需求 |

### A.6 2026-08-10 六批（执行保活 + 提速 + 渲染对齐 Claude CLI，158 测试全绿）

用户反馈：① 让 Agent 分析+写代码要等 1-2 小时，App 切后台睡觉醒来还没写完（只有一直盯着）；② 会话界面把工具名（shell/read_file）裸露给用户，用户要的是"正在做什么"而不是"调了什么工具"，参考 Claude Code CLI 的渲染逻辑。

#### A.6.1 后台保活（AgentKeepaliveService）

- **根因**：AgentEngine 跑在 viewModelScope 普通协程，无前台服务保护 → Doze 冻结协程（网络挂起/CPU 停止）→ 切后台即停滞
- **AgentKeepaliveService.kt**（新）：FGS(dataSync) 仅做保活锚点（引擎协程留原处——审批/choice 交互依赖 UI 弹层）；`PARTIAL_WAKE_LOCK`（2h 兜底超时防泄漏）；消费引擎新增 `activeTurn: StateFlow<ActiveTurnStatus>` 实时更新通知"正在执行：<意图>"；回合结束 IDLE **宽限 2s**（队列续回合到达取消停止）→ "执行完成/已中止"完成通知（含耗时）→ 停服
- **ActiveTurn.kt**（新）：`ActivePhase{IDLE,THINKING,STREAMING,TOOL_RUNNING,WAITING_USER}` + `ActiveTurnStatus`（intent/streamingTail/aborted）+ 纯函数 `notificationText`/`intentText`/`fmtSecs`；引擎各阶段更新快照（回合开始/流式正文/工具 RUNNING/审批·choice·计划审批挂起 WAITING_USER/结束与取消）
- **Manifest**：`POST_NOTIFICATIONS`（Android 13+ 运行时，首回合请求，被拒不阻塞）+ `WAKE_LOCK` + `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（特殊权限引导，ACTION_REQUEST... 失败回退设置页；AppStore 持久化 batteryPromptShown 只提示一次）
- 规避 Android 12+ 后台禁止启动 FGS：仅首回合/续回合（runJob 空闲）启动，队列续回合复用已运行服务；通知 id 11/12 与 TaskService（1/2）错开（同一 NotificationManager id 全局唯一）
- **ChatViewModel**：startTurn keepaliveStart；回合完成 snackbar"✓ 回合完成 · 耗时X"（turnStartedAt）

#### A.6.2 执行提速（同批只读工具并行）

- **根因**：同批次工具严格串行（5 个 read_file = 5 次往返）
- **ParallelPlanner.kt**（新）：`isParallelEligible`（非 choice/submit_plan + 非写工具 + 计划模式不拦截 + 审批自动放行/ALLOW）；`parallelGroupSpan`（最大连续可并行段，length≥2 才成组，单段回串行）；`ToolBatchRunner`（maxParallel=4 分块并发，结果按输入序收集）
- **AgentEngine**：for 循环改 while(i)；连续段整组 ①串行先发 RUNNING（步骤树/消息顺序稳定）→ ②并发执行 → ③父协程按原序回填；`applyResult(preResult)` 传 batchRunner 结果**防重复执行**（自查发现并修复：初版 applyResult 内部二次 doExecute，todo_add 会重复添加）；熔断补 DENIED 索引改 `span[openAt]+1`（beginTool 防重自动跳过已入树工具）
- **PolicyEngine**：readOnlyTools 加 todo_*（纯记账无副作用 → review 模式任务骨架不再每次弹审批，与提示词"先建 todo"自洽）

#### A.6.3 渲染对齐 Claude CLI（意图优先）

- **根因**：① StepRecord/ToolCallRecord 无意图字段；② 提示词明确禁止 LLM 输出意图文本（与 Claude Code 相反）；③ UI 三处直接渲染 tc.name
- **提示词**：执行纪律改"先用一句人类可读描述（'正在读取配置文件…'）→ 随即立即调工具"（保留反懒惰约束）；todo 文本规范（动词短语、不用工具名）；TodoAdd.description 同步
- **数据**：`intent: String = ""` 加入 ToolCallRecord/StepRecord（MiniJson 兼容，旧 JSON 缺省空串）；`ToolStatusChanged` 尾部加 intent + sessionId（默认值 → 现有位置参数构造点零改动）
- **引擎**：批次 intent = 模型调用前已输出的正文（`intentText` 纯函数：trim/合并空白/截 120；**只取 contentText 不取 reasoningText**）→ 批次首个工具携带，其余空串（UI 回退映射）
- **UI**：`toolActionLabel(name)` 统一映射（21 类工具；read_skill 必须先于 startsWith("read") 分支）；`runningActionLabel` intent 优先；TurnOverviewCard 头部"⚡ <意图>"/工具链折叠单行（意图主文案 + 工具名小字 + 点击展开）；ToolCard 折叠单行；TurnTreeCard StepRow 意图主显（TEXT 步骤"助手总结"）；ChatViewModel 按 sessionId 过滤 ToolStatusChanged（顺带修复跨会话事件泄漏缺陷）
- **通知联动**：服务通知直接消费 ActiveTurnStatus.intent（"正在执行：正在读取配置文件…"）

#### A.6.4 测试与验证

- 158/158 通过（新增 24：ParallelPlanner 8 / ActiveTurn 6 / RunningActionLabel 7 / SessionStore intent 2 / TurnTracker intent 1）；APK 156MB
- 自查修复：并行组重复执行（严重，todo_add 双写）、read_skill 前缀映射吞并、buildContext 需 suspend（config.workspaceRoot() 挂起）、通知 id 冲突、电池提示持久化

### A.7 2026-08-10 七批（底部任务区 + 历史收敛 + 界面精简，161 测试全绿）

用户反馈：① 学习 Claude CLI —— 执行时会话最下方常驻任务区（当前任务/待办/状态行/完成折叠），todo 不再藏弹层；② 执行完毕后历史不平铺工具调用和工具日志（只要结果）；③ 会话界面整体以 Claude 样式为主合并精简。

- **AgentStatusBar**（ExecutionFx.kt，替换 ExecutionIndicatorBar）：执行中常驻输入框上方——当前任务 ◼ 脉冲高亮 + 状态行 `耗时 · ↓X tokens · 动作`（对齐 Claude "5m 55s · ↓ 51.8k tokens · thinking"）+ 未完成待办 ◻（≤4 条超出折叠）+ 完成折叠 `… +N completed` + 排队行（逐条取消）；无待办退化为动作条；点击整块打开 TodoSheet；纯函数 statusBarStateLine/visibleTodos/doneCount 可单测
- **ChatViewModel**：ChatUiState.turnTokens 本回合 token 累计（CostUpdated 累加，startTurn 清零）
- **历史收敛**：assistant 工具卡 → `⚙ 工具调用（N）▸` 折叠汇总条（点击展开）；tool 结果行不再渲染（死代码 ToolResultLine/clickableText 删除）；TurnTreeCard 默认折叠
- **删除 TurnOverviewCard**（执行中总览卡，冗余自查确认）：头部摘要/任务清单/工具链 → AgentStatusBar；终止按钮 → InputRow 已有；思考链 → Claude 风格不展示（thinking 由状态行承担）；排队 → AgentStatusBar；MessageList 偏移 +2→+1
- 执行中界面 = 历史（收敛）+ 流式正文 + 底部任务区 + ModeBar + InputRow（对齐 Claude CLI 布局）
- 测试 158 → 161（3 新增）；APK 156MB

### A.8 2026-08-10 八批（全量审查修复，161 测试全绿）

三路并行审查（引擎链路 39 项 / UI 14 项 / 提示词与能力 20+ 项）。**确认闭环**：事件流 16 种全消费、PlanGate/审批/choice 全链路、工具注册-提示词-UI 三层对齐（35 个注册工具）、记忆/技能/MCP/进程链路、六批 while 循环无死循环。修复：

- **P0**：PythonExecTool files 参数未接线（删参数+描述修正）；RepairPipeline contentText 死参数（移除）；ApprovalSheet 裸英文工具名（toolActionLabel 主文案）；approvals/choices abort 悬挂清理；read_skill when 重复分支；色值系统统一（SuccessGreen=0xFF34A853/WarnAmber=0xFFF59E0B 全项目一致）；**审批规则持久化**（PolicyEngine.onRulesChanged → AppStore.saveRules + 启动 loadRules 恢复）；DeepSeekClient GlobalScope 重试泄漏（改 producerScope launch）
- **P1**：MAX_STEPS 30→100；提示词补 code_index/complete_step/update_goal/MCP 引导 + 4 处文案修正（search_files 定位/python_exec heredoc/截断声明如实）；**ContextManager buildPrefix 缓存短路**（输入未变直接复用，回合迭代省 20-30KB 重建；工具集按名称列表比较）；Storm key 改完整 argsJson 消除哈希碰撞；planModeBlocksWrite 标注测试专用
- **P2**：思考模式标签如实（快速/标准/深度）；EngineConfig 注释补 explanatory/learning；temperature 注释；completeOnboardingDone 空函数删除
- **不做项**：SafBackend runBlocking（已在 IO 线程）、并行跨写重排序（依赖风险）、preview/notebookedit/delete_symbol/gitignore（移动端取舍）、低价值 UI 项

### A.9 2026-08-10 九批（自动并行子任务 + 粘贴长文折叠，170 测试全绿）

用户对齐 Claude Code CLI 两个特性：

- **自动并行子任务**：PolicyEngine readOnlyTools 加 subagent/planner（review 自动放行 + 入并行组——模型同轮调多个 subagent 即自动并发，无需新编排层）；timeoutFor 超时映射（subagent/planner 300s 豁免，修复 120s 杀子代理 bug）；提示词子代理契约补并行引导（"同时调用多个 subagent，每个一个独立子问题，引擎自动并行最多 4 并发"）；UI parallelSubagentCount/statusBarAction——并行 ≥2 时底部任务区"● N 个并行子任务"（对齐 Claude "3 background agents launched"），消息流子代理独立行状态点
- **粘贴长文折叠**（阈值 500 字符/15 行）：pasteCollapseInfo 纯函数（增量超阈值判定粘贴，中文逐字计数）；折叠态输入框替换为摘要卡片"📋 已粘贴文字 +N行（M 字符）"（▸ 预览 / ✕ 删除）；PastePreviewDialog（全文滚动 + 发送全文/展开编辑/删除，展开编辑回填同步 previousInput 防重复折叠）；sendInput 用 pastedFullText 发送全文；remember 非 saveable（大文本 Bundle 风险）
- 测试 161 → 170（PasteCollapse 7 + parallelSubagentCount/statusBarAction 2）

### A.10 2026-08-10 十批（执行可见性 + 流畅度 + 审批放行，175 测试全绿）

用户反馈三大问题（PC 对比/性能/UI 三路探索定位根因）：看不到工具执行情况、手机比 PC 卡（同一 API）、Agent 只说没做。

- **根因**：① 工具折叠条执行中默认折叠（UI 不可见，误以为"只说没做"）；② 思考阶段 30-120s 黑屏（PC 流式显示 reasoning，手机只剩"思考中"三字）；③ 流式 UI 无节流（每 1-5 字符一次全 UI 重组 + 每 token 触发 300ms 滚动动画，1000 token 累计 3-10s 卡顿）；④ **默认审批模式 review**——写工具要审批，没点就无限挂起（awaitApproval 无超时）= "停住"；⑤ SAF 是真机默认路径（read 50-150ms vs RealBackend 1-5ms）
- **P0 展示**：工具折叠条执行中自动展开（RUNNING 强制展开，完成后保持）；StreamingBlock 思考链尾部实时展示（"💭 思考中 + reasoning 滚动"，对齐 PC 流式渲染）；ToolCard RUNNING 默认展开 + 实时输出渲染（toolOutputs 首次消费）；滚动 key 剥离 + 流式正文 200ms 节流直跳跟随
- **P0 性能**：流式 debounce（AssistantDelta/ReasoningDelta 累积 + 100ms 批量 flush，重组 200-1000 次 → ~10 次）
- **P0 审批**：默认 policyMode review → auto（工具默认放行）；awaitApproval/awaitChoice 60s 超时自动拒绝/取消 + 提示（防无限挂起）
- **P1**：globRootPrefix 定向遍历（"src/**/*.kt"→"src" 起点，SAF 下省 50-80%）；设置页审批/SAF 说明文案
- 测试 170 → 175（globRootPrefix 5）；踩坑：KDoc 注释内不能含 "**/"（块注释提前终止）

### A.11 2026-08-10 十一批（状态栏展开崩溃修复 + 子代理契约强化，183 测试全绿）

用户反馈重大 bug：子代理执行中点击输入框上方状态栏（AgentStatusBar）展开任务清单 → App 静默退出，无任何日志。双路探索 + 逐文件核对定位三个根因：

- **根因**：① **Todo id 同毫秒碰撞**（`t${System.currentTimeMillis()}`）→ TodoSheet LazyColumn key 重复 IllegalStateException，时机恰在"点击展开"（同款 bug 事实记忆已修过）；② **主线程同步大 I/O**（sessionStore.save/load + checkpointStore.capture + syncAndBackup 全在 Main）→ 子代理回合会话 JSON 膨胀 → ANR → 国产 ROM 静默杀进程；③ **零崩溃日志**（无 UncaughtExceptionHandler）——一切崩溃 = 静默退出无法取证
- **修复**：newTodoId() 毫秒+纳秒 + list 反向去重（保留最新，防历史重复 id）；引擎 3 处 save + capture + syncAndBackup 合并进 `withContext(IO)`；reloadSession/todoAdd/todoToggle/TodoUpdated 全部下放 IO 线程；**CrashLog.kt**（新包 core/diagnose：UncaughtExceptionHandler → filesDir/crashlogs 落盘，含机型/版本/堆栈，毫秒时间戳命名，保留 5 份轮转，链回系统默认 handler 不改变退出语义；设置页"关于"可查看/清空）；审批规则持久化 runBlocking → GlobalScope.launch(IO)；BashOutputTool runBlocking 核实无死锁仅注释
- **体验**：TodoSheet 新增"当前工具/子代理"区块（activeTurnTools 倒序 8 条：状态点 ⏳/✓/✗/✕/⏸ + 子代理/规划者角色 + 意图文案；toolStatusMark/agentStatusLine 纯函数）
- **子代理契约强化**（用户对比 Claude Code 委托设计后确认）：SystemPrompts 旧契约段移出 BASE → 独立 `SUBAGENT_CONTRACT_SUFFIX`（**何时派发** 4 信号：多独立主题/广撒网研究/跨域汇报/用户要全面；**何时不派** 3 边界：单点查询/需亲执工具/子代理间依赖；收益框架：不占主上下文+引擎并行 4+只综合；执行纪律：同批并发+prompt 自包含+不转发原文）；SubAgentTool/PlannerTool description 重写（when 信号+prompt 自包含进 schema 描述，模型重现度最高）；ContextManager 加 subagentSuffix 参数（默认 null 测试零破坏）
- 测试 175 → 183；踩坑：**distinctBy 保留首次出现**——要"保留后者"须 reversed 去重再 reversed；崩溃日志文件名秒级粒度同秒覆盖 → 毫秒

### A.12 2026-08-10 十二批（提示词-工具配套对齐 + 子代理过程可视化，195 测试全绿）

用户要求"致敬 Claude CLI 系统提示词与工具调用的一切配合"（提示词声称 ↔ 工具 ↔ 引擎三方必须配套）+ 子代理执行全过程可见可查：

- **配套审计修正 3 处 P0"声称未兑现"**：code_index（"建立索引后续更快"实为一次性扫描）、complete_step（"与 todo 联动"实为纯汇报）——提示词与工具描述同步修正；BASE 补【工具补充指引】段（multi_edit/delete_range/move_file、bash 三件套、技能类"何时用"——工具清单本身 prefix 已自动注入，缺的是指引）
- **MCP 真实参数 schema 注入**（实现级）：McpClient 保留 inputSchema（mapTool 纯函数）；McpToolBridge 直接铺开真实属性（保留 required/enum/嵌套），normalizeMcpSchema 纯函数（剔除 $schema/$id/title/additionalProperties/definitions，规模上限 4KB/40 props 超限回退通用 arguments 壳）；修复后模型能正确填 MCP 参数
- **ContextManager 缓存键指纹**（关键新发现）：原 specsKey 只比工具名 → MCP schema 每回合"先卸后注"的变化被短路缓存吞掉；改为 名字|描述|参数
- **子代理过程可视化三层联动**：SubAgentManager.runLoop 加 onDelta(content, reasoning) 回调（此前 reasoning 被丢弃；**子代理默认开思考模式 30-120s 无正文** → 黑屏风险）；ToolContext.onSubAgentDelta（默认 null 零破坏）；AgentEvent.SubAgentStream(callId, content, reasoning) 单事件双字段；buildContext 接线 tryEmit；SubAgentTool/PlannerTool/RunSkillTool(subagent 技能) 全透传；ChatUiState.subagentStreams（appendSubAgentDelta 8KB/4KB 尾部截断/subagentProgressText/applySubAgentDelta 纯函数）+ 按 callId 分区缓冲统一 100ms flush（4 并行不放大重组）+ 回合开始/结束清空不入 session
- **UI 三层**：① TodoSheet 子代理行进度文案（"· 思考中…"/"· 输出 N 字"）+ "查看 ▸" 入口；② SubAgentDetailSheet 新弹窗（任务描述摘要 + 实时思考链尾部 + 实时正文 + 完成后最终结果）；③ 消息流 ToolCard 执行中展开区实时流（💭 reasoning tail + content）
- 测试 183 → 195；踩坑：**字符串模板 `"$schema"` 被 Kotlin 当变量引用 → 必须 `"\$schema"` 转义**

### A.13 2026-08-10 十三批（全面自洽性审计修复，195 测试全绿）

用户要求"仔细排查所有逻辑是否自洽（功能/提示词/工具/交互/DeepSeek 缓存）"。四路并行审计（缓存逻辑/提示词一致性/功能状态机/交互生命周期）共修复 16 项：

- **DeepSeek 缓存逻辑（5 项）**：① specsKey 短路优化——非 specs 参数先判（O(1)），相同才计算工具指纹（原每轮迭代无条件 stringify 全部工具参数）；② **specsKey 按名称排序**——与 prefix 实际拼接的 sortedBy 一致，否则 MCP"先卸后注"改变插入顺序导致假 miss（内容字节相同仍每回合重建）；③ estimatedTokens/ratioUsed 全参数——首轮构建带 instruction/suffix（原首轮"短 prefix"被缓存为 builtPrefix 且 prefixVersion 虚增）；④ normalizeMcpSchema HashMap→LinkedHashMap（key 序可预测，防 MCP schema key 序变化导致 prefix 字节漂移缓存全量失效）；⑤ DeepSeekClient tools 按名称排序（与 prefix 一致，tools 数组顺序漂移截断服务端自动缓存命中）
- **功能状态机（5 项）**：① **【CRITICAL】runTurn 非取消异常兜底 catch**——原穿透导致 PlanGate 死锁（卡 PENDING_REVIEW）、approvals/choices 泄漏、进度不落盘、UI 卡 running；兜底统一收尾（FAILED + 清理 + IO 落盘 + Error 事件，不 rethrow）；② **【HIGH】超时精确杀进程**——ShellTaskRunner 加 taskIdOverride，shell/python 进程用 callId 注册，超时 destroy(callId)（原 destroyAll 误杀并行组其他工具进程）；③ **【HIGH】multi_edit 全路径**——新增 extractPaths 返回所有 edits 的 path（原只取第一个，检查点快照/磁盘回写漏文件）；④ 错误 break 路径标记 FAILED（步数/预算/Key 缺失三处，exitStatus 变量）；⑤ TodoStore/FactMemory @Synchronized（引擎 IO 与 UI 并发 read-modify-write 互相覆盖丢数据）
- **提示词-工具（3 项）**：① **【HIGH】update_goal 注入修复**——removeAll 旧 GOAL_PREFIX 再注入（原只查"是否存在"，中途变更目标被旧消息遮蔽、清除目标旧消息残留）；② delete_range 描述补"不可逆操作"标注（对齐 move_file）；③ MCP 措辞改"按需调用（review 审批模式下首次调用可能需用户确认）"
- **交互（3 项）**：① SubAgentStream/ToolOutput 加 sessionId 过滤（分屏另一窗口的流不再污染本窗口缓冲）；② flush 时 8KB/4KB 截断（原只截显示不截 state，长输出全量驻留内存）；③ CrashLogDialog 清空即时刷新（remember 快照 → mutableStateOf）
- **验证通过（未改）**：prefix 会话内字节稳定；ToolOutput tail 200；denyRest 边界；TurnFinished 与 reloadSession 时序；数值一致性（100 步/4 并行/120s/300s/32KB/8KB·4KB）
- **已知取舍（未修）**：指令/技能文件每回合读盘无指纹缓存（并发读概率低）；buildMessages 每轮全量重建 ApiMessage（性能）；子代理 max_tokens 32K（思考模式需要）；工具结果 32KB 截断持久化不可逆（对齐 PC maxToolOutputBytes）
