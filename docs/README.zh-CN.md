# MLX 🧠📱

**Make Learn Extraordinary!** 🚀

**口袋里的 AI 程序员** —— [DeepSeek-Reasonix](https://github.com/esengine/DeepSeek-Reasonix) 的安卓手机版。它**融合两大体系的优点**：PC 版的**完整能力集**（27 项功能对齐：引擎、工具、技能、检查点、成本核算）+ 现代编程 Agent 的**交互哲学**（原则式提示词、委派纪律、执行可视化）。在手机上直接和你的代码库对话：读、改、搜、跑命令、写真实文件，全部在本机完成。

![License](https://img.shields.io/badge/license-MIT-blue.svg)
![Kotlin](https://img.shields.io/badge/Kotlin-2.1.21-purple.svg)
![Compose](https://img.shields.io/badge/Jetpack%20Compose-M3-green.svg)
![Tests](https://img.shields.io/badge/tests-241%20passing-brightgreen.svg)
![minSdk](https://img.shields.io/badge/minSdk-26-orange.svg)
![APK](https://img.shields.io/badge/APK-149%20MB-blueviolet.svg)

---

## ✨ 亮点

| | |
|---|---|
| 🧊 **前缀缓存经济性** | DeepSeek 自动前缀缓存被当作**产品级原则**对待：字节稳定的不可变前缀（系统提示词 + 工具 + 指令文件）换来 **≥90% 缓存命中率**——成本约为直连的 **1/5**。 |
| 📂 **目录即工作区 2.0** | 手机上任选一个文件夹 → 它就是工作区。Agent 的改动**直接落真实磁盘文件**，无镜像、无同步。文件管理器里看到的，就是 Agent 产出的。 |
| 🐧 **完整 Linux 零下载** | Termux 发行形态**内嵌进 APK**（156MB）。bash、git、python3、apt 全仓库约 2900 个包，开箱即用、离线可用。 |
| 🛠 **35 个工具全集** | 文件读写编辑、grep/glob/搜索、网页抓取/搜索、shell、python_exec、后台任务、待办、**子代理**、**规划者**、记忆（remember/forget）、MCP 桥接…… |
| 🧩 **20 个内置技能** | 工作流技能（explore/review/research/test/init）、场景技能（麦肯锡级 HTML 汇报、数据分析、PPT 演示稿）、以及从主流编程 Agent 生态中提炼、为移动端重新工程化的剧本。 |
| 🤖 **子代理编排** | 并行只读子代理（最多 4 并发）。委派契约**融合 Claude Code 的委派纪律**（prompt 自包含、输出以证据为先）**与 PC 版的子代理机制**（推理预算、流式过程可见）——两大 Agent 设计的优点合体。 |
| 🛡 **计划模式 + 权限引擎** | 规划 → 审批 → 执行两阶段，引擎级写拦截（连 shell 命令都做只读白名单校验）；allow/ask/deny 策略、不可逆决策弹 choice。 |
| 🧪 **241 测试、23 批迭代** | 19.3k 行 Kotlin、85 个源文件、36 个测试文件——每一批都由真机反馈与对抗式审计驱动。 |

---

## 🏗 架构

```
┌─────────────────────────────────────────────────────────────┐
│  UI（Jetpack Compose, M3）                                   │
│  对话 · 会话 · 设置 · 统计 · 工作台 · 分屏                    │
├─────────────────────────────────────────────────────────────┤
│  Agent 引擎（Kotlin）                                        │
│  回合状态机 → SSE 流 → 修复管线 → 工具执行                   │
│  ContextManager（三区 + 前缀缓存）· PlanGate ·               │
│  TurnTracker · SubAgentManager · PolicyEngine · 技能体系    │
├─────────────────────────────────────────────────────────────┤
│  工具（35 个）与数据                                          │
│  FileBackend（Real/SAF）· ShellTaskRunner · WebSearch ·      │
│  MCP 桥接 · 会话/检查点/工程存储 · Keystore 加密              │
├─────────────────────────────────────────────────────────────┤
│  内置 Termux（bash · git · python3 · apt）                   │
└─────────────────────────────────────────────────────────────┘
```

**缓存纪律一句话**：动态内容（日期、项目名、窗口状态）**永不进 prefix**；静态规则走独立 suffix——缓存的头部跨回合字节不变。

---

## 🚀 快速开始

### 方式 A：直接装 APK（推荐）
从 [Releases](https://github.com/miaoluxin/MobileAgentMLX/releases) 下载 `MLX.apk`，侧载安装（允许未知来源），设置里填入 DeepSeek API Key，选一个项目文件夹，开始。

### 方式 B：源码构建

**前置**：JDK 21（JDK 26 会构建失败）、Android SDK（API 36）。

```bash
# 1. 构建内置 Termux 环境（生成 app/src/main/assets/termux-root.tar，约 332MB）
python3 scripts/build_termux_root.py

# 2. 测试与构建（Windows）
export JAVA_HOME="<JDK21安装路径>"
./gradlew.bat :app:testDebugUnitTest     # 241 测试
./gradlew.bat :app:assembleDebug         # → app/build/outputs/apk/debug/app-debug.apk
```

> ℹ️ Termux 归档超过 GitHub 100MB 单文件限制，不进本仓库——上面的构建脚本可从 Termux 官方 .deb 包复现。

---

## 🧰 技术栈

| 层 | 选型 |
|---|---|
| 语言 | Kotlin 2.1.21 |
| UI | Jetpack Compose（BOM 2025.06.01）、Material 3 |
| 网络 | OkHttp 4.12.0（SSE 流式） |
| 异步 | Coroutines 1.10.1 / Flow |
| 存储 | DataStore、SAF（DocumentFile）、Android Keystore（API Key 加密） |
| 构建 | AGP 8.11.1、Gradle 8.13、minSdk 26 / targetSdk 34 |
| 模型 | DeepSeek V4（flash / pro），1M token 上下文 |

---

## 📁 仓库结构

```
core/agent/       回合状态机、系统提示词、PlanGate、子代理、步骤树
core/context/     ContextManager —— 三区上下文 + 前缀缓存短路
core/llm/         DeepSeek SSE 客户端（流式 + 退避重试）
core/tools/       35 个工具：文件/搜索/shell/python/联网/后台任务/记忆
core/skills/      技能引擎：索引、匹配、注入、20 个内置技能
core/memory/      事实记忆（BM25 召回）、技能存储
core/policy/      allow/ask/deny 权限引擎
data/store/       会话、工程、检查点、级联删除
ui/chat/          对话 UI：流式、思维链、审批、计划审批
scripts/          Termux 环境构建脚本
```

---

## 📖 文档索引

| 文档 | 内容 |
|---|---|
| [开发文档.md](../开发文档.md) | 17 章设计总纲：引擎、SAF、权限、检查点/回退、记忆/技能/MCP、分屏、成本仪表盘、PC 对标矩阵 |
| [变更记录.md](../变更记录.md) | 23 批变更记录——每个修复、根因与测试数（96 → 241） |
| [docs/系统提示词清单.md](系统提示词清单.md) | 系统提示词全清单：BASE + 5 个 suffix + 子代理/规划者契约 |
| [问题.md](../问题.md) | 真机测试问题日志 |
| [Android端与Cloud版Agent能力差距分析20260810.md](../Android端与Cloud版Agent能力差距分析20260810.md) | 与 Cloud 版 14 项差距分析（已全部修复） |

## ⏳ 迭代历程

4 天 23 批——每一批都是真机反馈闭环或对抗式审计：

`96 测试 → 241 测试` · 停止延迟 `15s → <1s` · 缓存纪律经受 3 轮全量审计 · 提示词从命令清单重构为原则体系 · 子代理契约融合 Claude Code 委派设计与 PC 版代理机制。

## 🤝 贡献

见 [CONTRIBUTING.md](../CONTRIBUTING.md)。发现 Bug？按 [SECURITY.md](../SECURITY.md) 报告。

## 📄 许可证

MIT —— 见 [LICENSE](../LICENSE)。本项目移植自 [DeepSeek-Reasonix](https://github.com/esengine/DeepSeek-Reasonix)（MIT，Copyright (c) 2026 MLX Contributors）。内置 Termux 环境按各组件自身许可证分发（含 GPL 组件如 busybox，作为独立系统工具）。

**隐私**：API Key 在设备端加密存储（Android Keystore）；一切在本机执行，无云端中转。
