# MLX 项目说明（DeepSeek-Reasonix 安卓版）

AI 编程 Agent 手机版：Kotlin + Jetpack Compose + M3 + 协程/Flow + OkHttp(SSE) + SAF 文件系统 + Termux 内置完整 Linux 环境。产品灵魂是 **DeepSeek 前缀缓存经济性**（目标命中率 ≥90%）与**目录即工作区 2.0**（Agent 改动直接落真实磁盘文件）。

## 构建与测试（Windows）

```bash
export JAVA_HOME="<JDK21安装路径>"   # 必须是 JDK 21（注意嵌套目录，JDK 26 会构建失败）
./gradlew.bat :app:testDebugUnitTest   # 单元测试（当前 241 全绿）
./gradlew.bat :app:assembleDebug       # APK → app/build/outputs/apk/debug/app-debug.apk
```

## 架构地图（核心文件）

| 模块 | 职责 |
|---|---|
| `core/agent/AgentEngine.kt` | 回合状态机（runTurn 主循环）：流式收集→修复管线→工具执行→回合收尾（TurnFinished/TurnAborted/兜底三路径完整复位）；doExecute 三分支 catch（超时/取消 rethrow/异常）；`abortCurrentTurn()` 主动杀进程；`markAbortedTools` 收尾清 RUNNING；@skill 解析与技能注入 |
| `core/context/ContextManager.kt` | 三区上下文 + prefix 缓存短路（specs 指纹/非 specs 参数先判/排序稳定）；suffix 独立演进纪律 |
| `core/agent/SystemPrompts.kt` | BASE（原则式 7 段）+ 独立 suffix（FILE_ATTACHMENT/SUBAGENT_CONTRACT/DELEGATION_PROMPT/PLAN_MODE/OUTPUT_STYLE） |
| `core/tools/FileAttachments.kt` | @文件引用展开（REF_REGEX 排除 @skill:，32KB×5，幂等）；`FileBackend`（RealBackend 真实路径 / SafBackend SAF） |
| `core/llm/DeepSeekClient.kt` | SSE 流式 + 断线重试（producerScope）；readTimeout=120s（只约束"连续无数据"，非总时长） |
| `core/tools/ShellTaskRunner.kt` | 阻塞读边界（IOException→已取消转抛取消异常）+ finally destroyForcibly 防御 |
| `core/agent/TurnTracker.kt` | 步骤树持久化；`abort()` 收尾标 FAILED |
| `core/agent/PlanGate.kt` | 计划模式状态机；`respond` 有 PENDING_REVIEW 守卫（防迟到审批泄漏） |
| `ui/chat/ChatViewModel.kt` | abortAll（先 cancel 后 destroy）；回合结束事件后 dequeueNext（防竞态）；15s 兜底 |
| `ui/chat/ChatScreen.kt` | InputRow（+ 短按附加文件/长按技能选择器；/ 按钮已移除，输入 / 唤出面板）；回合计数须排除注入消息 |
| `core/skills/` + `core/memory/SkillStore.kt` | 技能体系（索引注入/require 强制注入/@skill 点名/run_skill 工具） |
| `core/commands/CommandRegistry.kt` | 30 命令 + usage 说明 |

## 核心纪律（改动前必读）

1. **缓存经济性**：动态信息（日期/项目名/窗口态）**禁止进 prefix**；新增静态规则走**独立 suffix**（对齐 SUBAGENT_CONTRACT_SUFFIX），不并入 BASE（否则全局缓存全失效）；tools/specs 序列化必须排序稳定（防假 miss）
2. **注入通道前缀**（引擎与 UI 计数必须同口径排除）：`[长期目标] ` / `[记忆回顾]` / `[技能注入] `；回合计数（turnNumber/userTurn/rounds/rewindTo/backfillTurns）全部按 `isInjected` 语义排除
3. **回合收尾三路径**（TurnFinished/TurnAborted/兜底 Exception）都必须：标记残留 RUNNING 工具、planGate.endTurn、落盘走 IO 线程、事件 emit；UI 复位靠 reloadSession（唯一 running=false 复位点）
4. **停止语义**：先 cancel 后 destroy（反了会错报"回合完成"）；阻塞 I/O 不感知协程取消 → 必须杀进程
5. **主线程禁同步大 I/O**（sessionStore/checkpoint/syncAndBackup 必须 withContext(IO)）——ANR 国产 ROM 静默杀进程无日志
6. **新功能优先纯函数 + 单测**（项目惯例：大量纯函数可单测，测试在 `app/src/test/java/com/mlx/mobile/` 镜像包）

## 已知坑（踩过）

- **JDK**：JAVA_HOME 必须是嵌套的 `jdk21/jdk-21.0.6+7`（JAVA_HOME 指向 jdk21 或 JDK26 都会构建失败）
- **GBK**：Windows 下批处理/输出中文乱码时检查编码
- **KDoc 注释内不能写 `**/`**（块注释提前终止 → 语法错误）
- **字符串模板里 `"$schema"`** 必须写 `"\$schema"`（Kotlin 当变量引用）
- **`distinctBy` 保留首次出现**；要保留后者须 reversed 去重再 reversed
- **IconButton 内层 clickable 与 combinedClickable 手势冲突** → 用 Box + combinedClickable
- **Todo id 同毫秒碰撞** → newTodoId 毫秒+纳秒
- **readTimeout 语义**：只约束连续无数据时长；120s 是为 max effort 长思考留的余量，勿降到 30-60s（误杀触发重试重复计费）
- **CrashLog**：任何崩溃先看设置页"关于"→ 崩溃日志（filesDir/crashlogs）

## 工程文档索引

| 文档 | 内容 |
|---|---|
| `开发文档.md` | 设计总纲（17 章）+ 附录 A 迭代记录（十三批） |
| `变更记录.md` | 十五~十九批变更（最新在前）——改代码前先查此处是否已有相关修复 |
| `2026年8月8日夜里发现的问题.md` | SAF 三套隔离世界根因分析（目录即工作区 2.0 的由来） |
| `Android端与Cloud版Agent能力差距分析20260810.md` | Cloud 版 14 项差距（三批已全量修复，对照参考） |
| `问题.md` | 用户反馈问题清单 |

## 交付约定

代码改动 + 测试全绿后，必须 `assembleDebug` 构建 APK，交付时附 APK 路径 + 真机验收清单（测试覆盖不到的交互点）。
