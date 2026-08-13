package com.mlx.app.core.skills

/**
 * 内置技能清单（离线静态数据，零网络；架构级 12）。
 * 两类：
 * 1. 工作流对齐 PC 版（init/explore/research/review/test）
 * 2. 用户场景（frontend-report-html / data-analysis / doc-outline / ppt-html）
 * 内置技能不可删除/覆盖（SkillStore 对 scope=builtin 拒绝）。
 */
object BuiltinSkills {

    private val workflow = listOf(
        Skill(
            name = "init",
            description = "初始化项目：扫描结构、生成指令文件（REASONIX.md）",
            category = "工作流",
            scope = "builtin",
            triggers = listOf("初始化项目", "init", "生成指令文件"),
            content = """
## 任务
为当前项目生成/更新 REASONIX.md 指令文件，作为后续工作的项目上下文。

## 步骤
1. 用 list_files 扫描项目结构（深度 2，重点目录与文件清单）
2. 阅读核心配置/入口文件（README、package.json、build.gradle 等），提炼技术栈与约定
3. 生成 REASONIX.md：项目结构摘要 + 技术栈 + 工作约定（修改前先读、精确替换、遵循既有风格）
4. 写入项目根目录，并向用户汇报文件清单

## 规范
- 摘要只列关键内容（目录树截断、文件清单取前 200）
- 约定条目具体可执行，不写空话
""",
        ),
        Skill(
            name = "explore",
            description = "探索项目：快速摸清代码结构与关键模块",
            category = "工作流",
            scope = "builtin",
            triggers = listOf("探索项目", "项目结构", "explore"),
            content = """
## 任务
快速摸清项目的整体结构与关键模块，输出结构地图。

## 步骤
1. list_files 全量扫描（深度 3），识别目录骨架
2. 按 入口 → 核心模块 → 数据/配置 → 测试 的顺序读取关键文件（每个文件读头部/关键函数，不必全文）
3. 输出结构地图：目录职责 / 核心类与职责 / 数据流 / 入口点 / 扩展点
4. 标注值得深入的位置（复杂逻辑、潜在问题），供后续任务引用

## 规范
- 探索只读，不做任何修改
- 结论引用具体文件路径
""",
        ),
        Skill(
            name = "research",
            description = "研究问题：多来源检索、交叉验证、输出研究报告",
            category = "工作流",
            scope = "builtin",
            // 十八批修复（审计）："研究"过泛（任何含该词任务命中，与 consulting-analysis"行业研究"双触发）→ 收窄
            triggers = listOf("调研", "研究报告", "研究一下", "research"),
            content = """
## 任务
对给定主题做结构化研究，输出带结论与依据的研究报告。

## 步骤
1. 明确研究问题与范围（不确定时先向用户确认）
2. 检索：web_search 多角度检索（官网/文档/社区），web_fetch 抓取关键页面
3. 交叉验证：同一结论至少 2 个独立来源；标注矛盾点
4. 输出报告：结论摘要（≤5 条）/ 详细分析 / 来源列表（带 URL）/ 存疑事项

## 规范
- 区分"事实"与"推测"；无法验证的内容明确标注
- 报告写完后询问是否需要落盘为 md 文件
""",
        ),
        Skill(
            name = "review",
            description = "代码审查：按维度检查改动并输出问题清单",
            category = "工作流",
            scope = "builtin",
            triggers = listOf("审查", "review", "代码检查", "帮我看看代码"),
            content = """
## 任务
对指定文件/改动做代码审查，输出按严重度排序的问题清单。

## 步骤
1. 定位审查范围（用户指定文件 / 会话内本轮改动）
2. 逐文件审查，维度：正确性（边界/空值/并发）→ 安全问题 → 性能 → 可读性/一致性
3. 每个问题给出：文件:行号 / 问题描述 / 失败场景 / 修复建议
4. 输出：P0（必须修）/ P1（建议修）/ P2（可选）三级清单 + 总结

## 规范
- 只报告可复现/可论证的问题，不写主观偏好
- 修复前先与用户确认范围
""",
        ),
        Skill(
            name = "test",
            description = "测试：编写/运行单元测试并修复失败",
            category = "工作流",
            scope = "builtin",
            triggers = listOf("测试", "写测试", "test"),
            content = """
## 任务
为目标代码编写/补充单元测试并确保通过。

## 步骤
1. 了解测试框架与既有测试风格（读取现有测试文件）
2. 设计用例：正常路径 / 边界值 / 异常路径 / 空输入
3. 编写测试（对齐既有命名与断言风格）
4. 运行测试（bash_output），失败则定位修复（若涉及被测代码缺陷，先与用户确认再改）
5. 汇报：用例清单 / 通过数 / 覆盖率变化

## 规范
- 测试只测行为不测实现
- 不修改被测代码的公共行为（除非用户同意）
""",
        ),
    )

    private val scenarios = listOf(
        Skill(
            name = "frontend-report-html",
            description = "麦肯锡级别 HTML 汇报页生成（18 页级大方案汇报）",
            category = "前端设计",
            scope = "builtin",
            triggers = listOf("front design", "麦肯锡", "汇报 html", "汇报页", "报告页面"),
            content = """
## 任务
把用户提供的方案/文档提炼为麦肯锡级别单文件 HTML 汇报页（可打印、可导航）。

## 页面规范（麦肯锡风格）
1. 封面：大标题 + 副标题 + 日期/汇报人；白底，主色为深蓝 #0F2B5B + 强调金 #C9A227（60/30/10 配色）
2. 目录页：章节导航（锚点链接，点击平滑滚动）
3. 正文页：每章一屏（min-height 100vh），左侧章节编号（01/02…），标题层级清晰；
   数据页用图表（纯 CSS/SVG 柱状图、环形图），结论先行（顶部 KPI 卡）
4. 每页底部页脚：页码 + 章节名；顶部固定导航条（章节锚点 + 返回顶部）
5. 附录页：术语表/来源/待办
6. 打印适配：@media print 每章分页、导航隐藏

## 结构模板
- 数据驱动：先提炼数据与核心结论（KPI 卡），再展开分析
- 每章结构：观点 → 依据（图表/数据）→ 建议行动

## 步骤
1. 阅读用户方案文档（30K+ 字符可分块读取）
2. 提炼 12~20 页结构（封面/目录/正文/附录），输出结构提纲给用户确认
3. 生成单文件 HTML（内联 CSS，无外部依赖）
4. 检查：交互导航可用、打印分页正确、中文字体回退

## 验收标准
- 单文件、零外部依赖、可离线打开
- 打印为 PDF 后页面完整、无错位
""",
        ),
        Skill(
            name = "data-analysis",
            description = "数据分析：读取 xlsx/csv 做统计与图表化输出",
            category = "数据分析",
            scope = "builtin",
            triggers = listOf("数据分析", "统计", "xlsx", "csv", "分析表格"),
            content = """
## 任务
对用户提供的数据文件（xlsx/csv）做统计分析，输出结论 + 图表 + 可选报告。

## 步骤
1. 定位文件（文件页确认路径），检查格式与编码（csv 注意 GBK/UTF-8）
2. 用内置 python 环境读取（openpyxl/csv），先看列结构、空值、异常值
3. 按用户问题做统计：分布 / 趋势 / 对比 / 占比；计算关键指标
4. 输出：结论摘要（每条带数据依据）+ 图表（python 生成 SVG/或 HTML 内联图表）+ 数据说明
5. 询问是否需要保存分析报告为 md/html

## 规范
- 报告结论必须可溯源到具体计算
- 数据量大时先抽样探路，避免超时
""",
        ),
        Skill(
            name = "doc-outline",
            description = "文档大纲：长文档/方案的结构化大纲与要点提炼",
            category = "文档",
            scope = "builtin",
            triggers = listOf("大纲", "outline", "结构化文档", "方案结构"),
            content = """
## 任务
为长文档/方案生成结构化大纲，或把已有内容提炼为要点化大纲。

## 步骤
1. 明确文档目的与读者（不确定先问）
2. 阅读源内容，提炼：核心主题 / 章节骨架 / 每节要点
3. 输出大纲：层级编号（1. / 1.1 / 1.1.1），每节一行要点 + 建议篇幅
4. 询问是否需要按大纲生成初稿（接 doc 写作流程）

## 规范
- 大纲覆盖完整、逻辑递进（背景 → 问题 → 方案 → 计划 → 附录）
- 要点精炼可执行，不堆砌形容词
""",
        ),
        Skill(
            name = "ppt-html",
            description = "HTML 演示稿：幻灯片式单文件演示（浏览器放映）",
            category = "前端设计",
            scope = "builtin",
            triggers = listOf("ppt", "演示稿", "幻灯片", "slides"),
            content = """
## 任务
生成幻灯片式单文件 HTML 演示稿（全屏放映、键盘翻页）。

## 规范
1. 每节 = 一屏 slide（100vh），深色或浅色统一主题（按内容选品牌色）
2. 键盘/触控翻页：←→ / 空格 / 点击边缘；进度条 + 页码
3. 封面 slide：标题 + 副标题；结尾 slide：总结 + 下一步
4. 每页信息密度低：一个核心观点 + 最多 3 个要点/1 个图表
5. 图表用纯 CSS/SVG；内联 CSS 零外部依赖；支持打印分页

## 步骤
1. 提炼内容为 N 页结构（标题/要点/图表），先给用户确认页数
2. 生成单文件 HTML
3. 自测翻页与缩放
""",
        ),
    )

    /**
     * 十七批预置（源自用户收集的 Claude Code 生态技能，剥离桌面/浏览器/本地服务依赖后精简提炼；
     * 高频方法论进索引（suggest），低频专用不进索引（off，用户点名才调用，防索引膨胀与过度调用）。
     */
    private val seventeenBatch = listOf(
        Skill(
            name = "test-driven-development",
            description = "开发新功能或修复 bug 前强制先写失败测试，遵循红-绿-重构循环，确保代码有真实测试覆盖",
            category = "工作流",
            scope = "builtin",
            triggers = listOf("TDD", "先写测试", "红绿重构", "补测试", "测试驱动开发"),
            content = """
## 任务
写任何新功能或修 bug 前，先用测试描述期望行为；核心铁律：没有先失败的测试，不允许写实现代码。

## 步骤
1. RED：写一个最小测试，只测一个行为，命名清楚（如 test_rejects_empty_email），用真实代码少用 mock。
2. 运行测试确认失败：必须亲眼看到失败，且失败原因是被测功能不存在，不是拼写错误；测试直接通过说明测的是旧行为，改写测试。
3. GREEN：写刚好能让测试通过的最简实现，不添加多余特性。
4. 重新运行确认全绿，且原有测试不回归。
5. REFACTOR：仅在全绿后重构，去重、改名、提取公共代码，保持测试通过。
6. 循环：写下一个失败测试，直到功能完成。

## 规范
- 先写代码再补测试=违规，删掉重来；测试后补等于什么都没证明
- 每个新函数必须有测试，且亲眼看过它失败
- 不允许的理由："太简单不用测""先手动测过""删掉浪费"——一律视为跳过 TDD
- 调试集成：发现 bug 先写复现测试，再修复，防回归
""",
        ),
        Skill(
            name = "writing-plans",
            description = "拿到多步骤开发需求时，把需求拆成带完整代码与命令的逐项可勾选实施计划，再动手开发",
            category = "工作流",
            scope = "builtin",
            triggers = listOf("实施计划", "开发计划", "多步骤任务拆解", "需求转计划", "任务分解"),
            content = """
## 任务
拿到多步骤开发需求后，先产出可执行实施计划文档（write_file 保存），再动手写代码。

## 步骤
1. 范围检查：需求含多个独立子系统时拆成多份计划，每份独立可交付可测试。
2. 文件结构：列出新建/修改文件清单及各自职责，单一职责、小而聚焦。
3. 拆任务：每步一个动作（2-5 分钟）：写失败测试→跑测试确认失败→写最简实现→跑测试确认通过→提交。
4. 写文档：开头含 Goal/Architecture/Tech Stack；每个任务注明文件路径、复选框步骤、完整代码、运行命令与预期输出。
5. 自审：对照需求逐条查覆盖；扫占位符（TBD/TODO/"适当处理错误"）；核对前后任务函数名/类型一致。

## 规范
- 禁止占位符：每步必须给出实际代码与命令，不写"类似任务N"
- 精确路径、完整代码、DRY/YAGNI/TDD、频繁提交
- 计划完成后再按任务逐项勾选执行
""",
        ),
        Skill(
            name = "consulting-analysis",
            description = "需要市场/消费者/品牌/财务/行业/竞对等咨询级研究分析时，先产出框架再生成专业报告",
            category = "文档",
            scope = "builtin",
            triggers = listOf("市场分析", "消费者洞察", "品牌分析", "行业研究", "竞对情报", "投资尽调"),
            content = """
## 任务
用户要专业研究/分析类成果（市场、消费者、品牌、财务、行业、竞对、投资尽调）时，先产出分析框架，数据就绪后再写咨询级报告。

## 步骤
1. 定域：识别分析领域，列出该域自然分析维度。
2. 选框架：从框架库选 2-4 个互补且数据可得的，映射到章节。框架库：宏观 SWOT/PESTEL/波特五力/VRIO；增长 STP/BCG/Ansoff/生命周期/TAM-SAM-SOM；消费者 决策旅程/AARRR/RFM/马斯洛/JTBD；财务 杜邦/DCF/可比公司/EVA；竞争 对标/价值链/蓝海/感知图；行业 产业链/Gartner曲线/GE-McKinsey。
3. 搭骨架：每章写明分析目标、分析逻辑（引用框架）、核心假设、数据需求表（指标/类型/来源/搜索关键词/P0-P2 优先级）、可视化计划。
4. 成稿：按 摘要→引言→主体章节→结论→参考文献(GB/T 7714) 输出；每小节走 视觉锚点→数据对比表→整合分析。

## 规范
- 洞察按"数据→用户心理→策略启示"链；正文 What→Why→So What，每节末≥200 字综合段
- 严禁编造数据：数字必须可追溯到输入，缺失标注"数据不可得"
- 结论纯客观综述无建议；标题编号 1./1.1，禁 Chapter 前缀与 Decoding/DNA 等词
""",
        ),
        Skill(
            name = "hivesql-lineage-analysis",
            description = "分析 HiveSQL/SparkSQL 复杂 ETL 语句，梳理目标表、CTE、源表与字段级数据血缘",
            category = "数据分析",
            scope = "builtin",
            triggers = listOf("数据血缘", "HiveSQL", "字段级血缘", "ETL 逻辑", "SQL 溯源"),
            content = """
## 任务
分析 HiveSQL/SparkSQL 复杂 ETL，产出结构化数据血缘文档：目标表、CTE 中间层、源表清单、字段级血缘、核心过滤条件。

## 步骤
1. 通读 SQL：定位目标表（INSERT OVERWRITE/INSERT INTO/CTAS）、WITH 定义的 CTE 及其依赖、主查询驱动表。
2. 拆解每个 CTE：记录用途（去重/行转列/取最新/过滤）、源表、转换逻辑（GROUP BY 聚合、ROW_NUMBER 窗口取最新、CASE WHEN+MAX 行转列）、JOIN 类型及关联字段。
3. 逐字段追踪主查询 SELECT：判断直接映射、CASE WHEN 枚举、COALESCE 多级回退、计算派生；非语义化 CTE 名按内容推断含义。
4. 归类源表：明细事实表/码表维度表/DW 已有表/SAP 接口表，含被注释掉的表。
5. write_file 输出血缘文档：概述(目标表+数据流向)→CTE 结构→源表清单→字段级血缘表(序号/目标字段/中文含义/取值逻辑/数据来源)→核心过滤条件→CTE 详解。

## 规范
- 字段按业务语义分组；取值逻辑写白话不贴 SQL
- JOIN 判断：INNER 只留匹配、LEFT 保留主表、INNER+WHERE key IS NULL 为反连接
- 专票/红蓝票等 UNION ALL 分支需逐支追踪关联路径
""",
        ),
        Skill(
            name = "brainstorming",
            description = "任何新功能或创意开发前，通过逐个提问把想法打磨成设计文档并获得用户批准后再实现",
            category = "工作流",
            scope = "builtin",
            triggers = listOf("新功能设计", "需求澄清", "方案探讨", "创意落地", "设计评审"),
            content = """
## 任务
任何创意/功能开发前，先通过对话把想法打磨成设计并获得用户批准，禁止直接进入实现。

## 步骤
1. 先探查项目上下文：读目录结构、现有文档（glob/grep 扫描），了解现状。
2. 范围判断：若含多个独立子系统（如"平台+聊天+存储+计费"），先拆子项目，再逐个走设计流程。
3. 逐个提问：一次只问一个问题，优先给选项；聚焦目的、约束、成功标准。
4. 提出 2-3 个方案：说明取舍，给出推荐及理由。
5. 分节呈现设计：按复杂度控制篇幅（简单几行、复杂 200-300 字），每节征询确认；覆盖架构、组件、数据流、错误处理、测试。
6. 用户批准后 write_file 保存设计文档，自审（占位符/自相矛盾/范围/歧义），再请用户复核。
7. 转交实施计划（writing-plans），不调用其他实现技能。

## 规范
- 硬性门槛：未展示设计并获得批准，不得写任何代码
- 一次一个问题、多选优先、YAGNI 删减、增量确认、随时回退澄清
""",
        ),
        Skill(
            name = "frontend-design",
            description = "开发或美化前端页面/组件时，产出有辨识度、避免 AI 模板味的代码与视觉设计",
            category = "前端设计",
            scope = "builtin",
            autoUse = "off", // 低频专用：不进索引，用户点名才调用
            triggers = listOf("前端设计", "页面美化", "UI 设计", "落地页", "组件样式"),
            content = """
## 任务
开发/美化前端界面（页面、组件、落地页、仪表盘）时，产出有辨识度、避免"AI 味"的代码与视觉设计。

## 步骤
1. 定方向：写码前明确用途/受众，选定一个鲜明的美学基调（极简、粗野主义、复古未来、编辑杂志风、柔和粉彩等），再确认约束与差异化记忆点。
2. 落地代码：按方向实现 HTML/CSS/JS 或组件代码，功能完整、视觉统一。
3. 打磨细节：字体用有性格的展示字体+正文搭配，禁用 Arial/Inter/Roboto/系统默认；配色用 CSS 变量统一，主色+锐利强调色，忌紫底白字俗套；动效优先 CSS 动画，一次编排好的入场错峰揭示优于零散微交互；布局用非对称、重叠、破格、留白；背景用渐变网格/噪点纹理/几何图案营造层次，不用纯色平铺。

## 规范
- 复杂度匹配愿景：极繁配华丽动效，极简配克制与精确间距
- 每次生成的审美不重复，明暗主题、字体、风格轮换
""",
        ),
        Skill(
            name = "internal-comms",
            description = "撰写周报 3P、公司通讯稿、FAQ、项目更新等内部沟通文档，按对应公司模板格式化输出",
            category = "文档",
            scope = "builtin",
            autoUse = "off",
            triggers = listOf("周报 3P", "公司通讯稿", "FAQ 汇总", "项目更新", "内部沟通"),
            content = """
## 任务
撰写内部沟通文档（周报/公司通讯/FAQ/项目更新等），先判定类型，再按对应模板格式输出。

## 步骤
1. 判定类型：3P 更新/公司通讯稿/FAQ 问答/其他通用沟通。
2. 3P 更新：确认团队名与时间范围（通常过去一周进展与问题、未来一周计划）；正文三段各 1-3 句、数据驱动、30-60 秒可读完；格式固定：[emoji] 团队名(日期)+Progress/Plans/Problems。
3. 公司通讯稿：20-25 条要点分主题区块（公告/优先级进展/领导动态/社交动态），每条 1-2 句，用"我们"口吻并附链接；只放全公司相关事项，跳过团队细节与重复信息。
4. FAQ：筛选影响多数员工的高频困惑，格式：*问题*(1 句)+*答案*(1-2 句)；答案以官方口径为准，不确定要标明，附权威链接。
5. 通用沟通：先问受众、目的、语气、格式要求；最重要的信息置顶、主动语态、简洁。

## 规范
- 3P 格式严格，不得混用其他排版
- FAQ 要全局视角，不聚焦单一团队
""",
        ),
        Skill(
            name = "theme-factory",
            description = "给 PPT/文档/HTML 落地页统一应用 10 套预设主题配色字体，或按需生成自定义主题",
            category = "前端设计",
            scope = "builtin",
            autoUse = "off",
            triggers = listOf("套主题", "配色字体", "PPT 美化", "统一风格", "主题定制"),
            content = """
## 任务
为已有产物（PPT、文档、HTML 落地页、报告）应用统一主题配色与字体；也可按需现场生成新主题。

## 步骤
1. 列出 10 套预设主题供选择，按用途推荐：商务稳重型 Ocean Depths 海洋深蓝/Modern Minimalist 现代灰阶/Arctic Frost 北极冷冽；温暖活力型 Sunset Boulevard 日落大道/Golden Hour 金色黄昏；自然大地型 Forest Canopy 森林绿/Desert Rose 沙漠玫瑰/Botanical Garden 植物园；科技现代型 Tech Innovation 科技蓝/Midnight Galaxy 午夜银河。
2. 征询选择：说明适配场景（科技汇报选 Tech Innovation、新品发布选 Sunset Boulevard、极简画册选 Modern Minimalist），等用户明确确认。
3. 应用主题：按主题定义（配色 hex+标题/正文字体搭配）全文档统一应用，检查对比度与可读性，保持各页视觉一致。
4. 无合适主题时自定义：根据描述生成新主题（命名+配色+字体），先展示确认再应用。

## 规范
- 全文档只用一个主题色板，不混用
- 确保文字与背景对比度达标
""",
        ),
        Skill(
            name = "obsidian-markdown",
            description = "创建或编辑 Obsidian 笔记时，使用 wikilink、embeds、callouts、properties、tags 等专用语法",
            category = "文档",
            scope = "builtin",
            autoUse = "off",
            triggers = listOf("Obsidian 笔记", "双链", "callout", "wikilink", "frontmatter"),
            content = """
## 任务
创建或编辑 Obsidian 笔记时，使用 Obsidian 扩展 Markdown 语法（标准 Markdown 之外的专用语法）。

## 步骤
1. 文件开头加 frontmatter 属性块（title/tags/aliases 等）。
2. 内部笔记用双链 [[笔记名]]、[[笔记名|显示文本]]、[[笔记名#标题]]、[[笔记名#^块id]]；外部链接才用 [文字](url)。块 id 在段落末尾加 ^id。
3. 嵌入内容用 ![[笔记]]、![[笔记#标题]]、![[图片.png|宽度]]。
4. 高亮信息用 callout：> [!note] 标题 加内容行；类型有 note/tip/warning/info/example/quote/bug/danger/success/question/todo；折叠用 [!faq]-（默认折叠）或 +。
5. 标签：行内 #tag、层级 #nested/tag；也可在 frontmatter 的 tags 属性中定义。
6. 其他：==高亮文字==、%%隐藏注释%%、脚注 [^1]、数学公式 \$...\$/\$\$...\$\$、Mermaid 图。

## 规范
- 库内笔记一律 wikilink（重命名自动跟随），外部 URL 才用标准链接
- 编辑后保持语法完整可渲染
""",
        ),
        Skill(
            name = "beiqifoton-financial-report-assessment",
            description = "批量解析车企自由表头财务 Excel，归类同源报表、判定汇总明细层级，评估数仓+HiveSQL+永洪BI 人天",
            category = "数据分析",
            scope = "builtin",
            autoUse = "off",
            triggers = listOf("北汽福田", "财务报表人天", "自由表头 Excel", "同源报表", "永洪 BI", "数仓工作量"),
            content = """
## 任务
批量解析车企自由表头财务 Excel 报表，输出治理分析与数仓+HiveSQL+永洪BI 开发人天评估。

## 步骤
1. 盘点：glob 列出目标目录全部 Excel 文件。
2. 依赖（首次）：python_exec 执行前先 pip install pandas openpyxl（依赖缺失时安装，一次即可）。
3. 解析：python_exec+pandas 逐文件逐 Sheet 扫描全部单元格，识别表头维度/左列维度/混合三种形态；零遗漏提取指标（销量、收入、利润、成本、费用、资金、库存、债权等）与维度（产品线、品牌、动力类型、核算主体、时间、实际/同期/预测值等），过滤空行备注标题。
4. 归类同源报表：指标交集≥80% 且口径一致为同组；产品线版/汇总版同组。
5. 判定层级：汇总报表（聚合不可下钻）vs 明细报表（最小粒度可下钻）；基础配置表不评估。
6. 层级编码：汇总 1/2/3…，明细 1.1/2.1…，明细必须归属汇总。
7. 评估人天（最小 1 人天）：模型设计 1.0-4.5、HiveSQL+调度 1.0-6.5、永洪固定报表 1.0-5.0，按复杂度梯度。
8. 交付：write_file 写 Markdown 报告（盘点总览/同源分组/层级清单/工作量汇总/建议）；python 生成 12 列固定表头 Excel（指标、维度、分组号、三模块人天、评估理由）。

## 规范
- 零遗漏、口径统一、工时≥1 人天、理由结合复杂度与行业标准、层级闭环
""",
        ),
        Skill(
            name = "gh-cli",
            description = "用 gh 命令操作 GitHub 仓库、Issue、PR、Actions 与 Release 时的常用命令速查",
            category = "工具",
            scope = "builtin",
            autoUse = "off",
            // "github" 触发词：防止 missingSkillHint 把"用 GitHub 技能"误报为缺失技能（off 技能存在但不在索引）
            triggers = listOf("gh 命令", "GitHub", "github", "提 PR", "查 issue", "发布 release"),
            content = """
## 任务
GitHub 命令行（gh）常用命令速查。

## 步骤
- 安装与认证（首次）：pkg install gh 后执行 gh auth login；未安装/未登录时先完成这两步再继续。
- 认证状态：gh auth status
- 仓库：gh repo create / clone / view / list / fork / sync
- Issue：gh issue create --title --body / list / view 123 / close 123 / comment 123 --body
- PR：gh pr create / list / view 123 / checkout 123 / merge 123 --squash --delete-branch / review 123 --approve
- Actions：gh workflow list / run；gh run list / view 123 / rerun / cancel
- Release：gh release create v1.0.0 --notes / list / view / upload / download

## 规范
- 通用参数：--repo 指定仓库，--json 加 --jq 取结构化输出
- 记不清时先跑 gh <子命令> --help
""",
        ),
    )

    /** 全部内置技能 */
    val all: List<Skill> = workflow + scenarios + seventeenBatch
}
