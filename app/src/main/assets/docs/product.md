# MLX 产品介绍

> **Make Learn Extraordinary!**

MLX 是运行在 Android 手机上的 AI 编程 Agent——内置完整 Linux 环境，让手机拥有与 PC 同级的人工智能编程能力。

## 核心能力

- **完整 Linux 环境**：内置 Termux 发行形态（bash/git/python3/apt），Agent 可执行任意命令、构建、数据处理；apt 全仓库 2906 个包随装随用
- **缓存省钱**：缓存优先三区上下文，前缀缓存命中率 90%+，成本约为直连的 1/5；成本按官方价实时统计（¥）
- **目录即工作区**：选择手机磁盘目录即为工程，Agent 的文件改动自动写回目录，与 PC 心智一致
- **全功能工具**：文件读写编辑/搜索/grep/glob/网页抓取/网络搜索/后台任务/子代理/规划者/自主记忆（remember/forget）
- **文件分析**：python_exec 可解析 xlsx/csv/json/md 等，生成 docx/pdf/图片/图表
- **会话管理**：工程下多会话树状组织、关键词定位、分支/复制/检查点回退

## 使用

1. 首次启动输入 DeepSeek API Key（后台自动验证）
2. 会话页「＋」新建工程：输入项目名 + 选择手机磁盘目录
3. 对话中让 Agent 完成读写/分析/生成任务；改动自动同步回目录

## 隐私与许可

- API Key 加密存储于设备（Android Keystore），本机执行，无云端中转
- 本产品移植自 MIT 协议开源项目 DeepSeek-Reasonix（原项目名 Reasonix Mobile 已更名 MLX）；内置完整环境含 GPL 组件（busybox 等），侧载分发
