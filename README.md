# MLX 🧠📱

**Make Learn Extraordinary!** 🚀

**An AI coding agent in your pocket** — the Android port of [DeepSeek-Reasonix](https://github.com/esengine/DeepSeek-Reasonix), a native DeepSeek coding agent. It takes the best of both worlds and fuses them into one: the **PC edition's full capability set** (27-feature parity: engine, tools, skills, checkpoints, cost accounting) and the **interaction philosophy of modern coding agents** (principle-based prompts, delegation discipline, execution visibility). Talk to your codebase from your phone: it reads, edits, searches, runs commands, and writes real files — all on-device.

> 🇨🇳 中文版见 [docs/README.zh-CN.md](docs/README.zh-CN.md)

![License](https://img.shields.io/badge/license-MIT-blue.svg)
![Kotlin](https://img.shields.io/badge/Kotlin-2.1.21-purple.svg)
![Compose](https://img.shields.io/badge/Jetpack%20Compose-M3-green.svg)
![Tests](https://img.shields.io/badge/tests-241%20passing-brightgreen.svg)
![minSdk](https://img.shields.io/badge/minSdk-26-orange.svg)
![APK](https://img.shields.io/badge/APK-149%20MB-blueviolet.svg)

---

## ✨ Why it's different

| | |
|---|---|
| 🧊 **Prefix-cache economy** | DeepSeek's automatic prefix caching is treated as a *first-class product principle*: a byte-stable immutable prefix (system prompt + tools + instruction files) yields **≥90% cache hit rate** — ~**1/5 the cost** of naive direct calls. |
| 📂 **Directory-as-workspace 2.0** | Pick any folder on your phone → it *is* the workspace. Agent edits land directly on real disk files. No mirror, no sync. What you see in your file manager is what the agent produced. |
| 🐧 **Full Linux, zero download** | A Termux distribution is **embedded in the APK** (156 MB). Bash, git, python3, and the full apt repository (~2,900 packages) work offline, out of the box. |
| 🛠 **35-tool arsenal** | File read/write/edit, grep/glob/search, web fetch/search, shell, python_exec, background jobs, todo lists, **subagents**, **planner**, memory (remember/forget), MCP bridge, and more. |
| 🧩 **20 built-in skills** | Workflow skills (explore/review/research/test/init), scenario skills (McKinsey-grade HTML reports, data analysis, PPT decks), plus skills drawn from the broader coding-agent ecosystem, re-engineered for on-device execution. |
| 🤖 **Subagent orchestration** | Parallel read-only subagents (up to 4 concurrent). The delegation contract **fuses Claude Code's delegation discipline** (self-contained prompts, evidence-first output) **with the PC edition's subagent machinery** (reasoning budget, streaming visibility) — the best of both agent designs. |
| 🛡 **Plan mode + permission engine** | Plan → review → execute with engine-level write blocking (even shell commands are whitelist-checked for read-only); allow/ask/deny policies, choice dialogs for irreversible decisions. |
| 🧪 **241 tests, 23 iterations** | 19.3k lines of Kotlin, 85 source files, 36 test files — every batch driven by real-device feedback and adversarial audits. |

---

## 🏗 Architecture

```
┌─────────────────────────────────────────────────────────────┐
│  UI (Jetpack Compose, M3)                                    │
│  Chat · Sessions · Settings · Stats · Workspace · Split     │
├─────────────────────────────────────────────────────────────┤
│  Agent Engine (Kotlin)                                       │
│  Turn state machine → SSE stream → repair pipeline → tools  │
│  ContextManager (3-zone + prefix cache) · PlanGate ·        │
│  TurnTracker · SubAgentManager · PolicyEngine · Skills      │
├─────────────────────────────────────────────────────────────┤
│  Tools (35) & Data                                            │
│  FileBackend (Real/SAF) · ShellTaskRunner · WebSearch ·      │
│  MCP bridge · Session/Checkpoint/Project stores · Keystore   │
├─────────────────────────────────────────────────────────────┤
│  Embedded Termux (bash · git · python3 · apt)               │
└─────────────────────────────────────────────────────────────┘
```

**The cache discipline in one line:** dynamic content (dates, project names, window state) *never* enters the prefix; static rules live in independent suffixes so the cached head stays byte-identical across turns.

---

## 🚀 Getting started

### Option A — Install the APK (recommended)
Grab `MLX.apk` from the [Releases](https://github.com/miaoluxin/MobileAgentMLX/releases) page. Side-load it (Android allows sideloading; you'll be asked to permit unknown sources), open it, add your DeepSeek API key in Settings, pick a project folder, and go.

### Option B — Build from source

**Prerequisites:** JDK 21 (JDK 26 will fail the build — the path must point into the JDK 21 home), Android SDK (API 36).

```bash
# 1. Build the embedded Termux environment (generates app/src/main/assets/termux-root.tar, ~332 MB)
python3 scripts/build_termux_root.py

# 2. Build & test (Windows)
export JAVA_HOME="<path-to-jdk21>"
./gradlew.bat :app:testDebugUnitTest     # 241 tests
./gradlew.bat :app:assembleDebug         # → app/build/outputs/apk/debug/app-debug.apk
```

> ℹ️ The Termux archive exceeds GitHub's 100 MB file limit, so it's not tracked in this repository — the build script above reproduces it from official Termux `.deb` packages.

---

## 🧰 Tech stack

| Layer | Choice |
|---|---|
| Language | Kotlin 2.1.21 |
| UI | Jetpack Compose (BOM 2025.06.01), Material 3 |
| Network | OkHttp 4.12.0 (SSE streaming) |
| Async | Coroutines 1.10.1 / Flow |
| Storage | DataStore, SAF (DocumentFile), Android Keystore (API key encryption) |
| Build | AGP 8.11.1, Gradle 8.13, minSdk 26 / targetSdk 34 |
| Model | DeepSeek V4 (flash / pro), 1M-token context |

---

## 📁 Repository layout

```
core/agent/       Turn state machine, system prompts, PlanGate, subagents, tracker
core/context/     ContextManager — 3-zone context + prefix cache short-circuit
core/llm/         DeepSeek SSE client (streaming + retry with backoff)
core/tools/       35 tool specs: file, search, shell, python, web, jobs, memory
core/skills/      Skill engine: index, matching, injection, 20 built-in skills
core/memory/      Fact memory (BM25 recall), skill store
core/policy/      allow/ask/deny permission engine
data/store/       Sessions, projects, checkpoints, cascade deletes
ui/chat/          Chat UI: streaming, thinking-tree, approvals, plan review
scripts/          Termux environment build script
```

---

## 📖 Documentation

| Doc | What it covers |
|---|---|
| [开发文档.md](开发文档.md) | 17-chapter design spec: engine, SAF, permissions, checkpoint/rewind, memory/skills/MCP, split-screen, cost dashboard, PC parity matrix |
| [变更记录.md](变更记录.md) | 23-batch changelog — every fix, root cause, and test count (96 → 241) |
| [docs/系统提示词清单.md](docs/系统提示词清单.md) | Full system prompt inventory: BASE + all 5 suffixes + subagent/planner contracts |
| [问题.md](问题.md) | Field-test issue log |
| [Android端与Cloud版Agent能力差距分析20260810.md](Android端与Cloud版Agent能力差距分析20260810.md) | 14-gap analysis vs the Cloud edition (all fixed) |

## ⏳ Iteration journey

23 batches in 4 days — every batch a real-device feedback loop or an adversarial audit:

`96 tests → 241 tests` · stop-button latency `15s → <1s` · prefix-cache discipline hardened by 3 full audits · prompts rebuilt from command-lists to principles · subagent contracts fused from Claude Code's delegation design and the PC edition's agent machinery.

## 🤝 Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Found a bug? Check [SECURITY.md](SECURITY.md) for reporting.

## 📄 License

MIT — see [LICENSE](LICENSE). This project is a port of [DeepSeek-Reasonix](https://github.com/esengine/DeepSeek-Reasonix) (MIT, Copyright (c) 2026 MLX Contributors). The embedded Termux environment is distributed under its own component licenses (GPL components such as busybox are included as standalone system tools, as declared in the app's product notes).

**Privacy:** API keys are encrypted on-device (Android Keystore). Everything runs locally — no cloud relay.
