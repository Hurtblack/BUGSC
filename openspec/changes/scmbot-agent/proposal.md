## Why

用户需要一个 App 内置的小型 Agent，用于回答 Star Citizen 资料、蓝图、矿物、任务、船只和攻略类问题。现有 App 已沉淀本地数据、查询页面和若干外部查询 API，适合由 App 本地编排 Skill，再交给用户配置的 DeepSeek 模型整理回答。

## What Changes

- 新增工具页 `SCMBOT` 工作名入口，点击后直接进入 Agent 聊天页。
- 新增独立 Agent 聊天页，不复用 SCM 后端会话、不接入 SCM WebSocket、不影响 SCM 私聊未读数。
- 新增 DeepSeek 设置能力，用户填写自己的 API Key，第一版只支持 DeepSeek，内部保留 provider 抽象。
- 新增 App 本地 Agent Runtime：接收用户问题，执行 Skill 查询，构造上下文，调用 DeepSeek，展示回答。
- 新增本地 Agent 人物卡，用于聊天页展示、自我介绍、能力边界、隐私说明和 system prompt。
- 新增 Skill 体系，第一版覆盖本地数据、当前已知查询 API、可选 SCM 查询 API。
- 新增本地 Agent 历史，保存 SCMBOT 工作名会话消息和失败状态。
- 未配置 DeepSeek 时仍允许进入聊天页，由聊天页引导配置。
- Agent 工作名暂用 `SCMBOT`，但架构和文案避免绑定 SCM，后续允许改名。

## Capabilities

### New Capabilities

- `app-agent-assistant`: App 内置 Agent 助手，包含工具页入口、DeepSeek 配置、Agent 聊天、Skill 查询编排和本地历史。

### Modified Capabilities

- 无。当前仓库没有已归档 main capability，本次以新增 capability 表达；现有 SCM 私聊、交易消息、工具页其他入口只受集成影响，不改变其外在需求。

## Impact

- Android UI：
  - `ToolsFragment` 和 `fragment_tools.xml` 新增 Agent 入口卡片。
  - `nav_graph.xml` 新增 Agent 聊天页和设置页目的地。
  - 新增 Agent 聊天页与 DeepSeek 设置页布局。
- Android 代码：
  - 新增 `agent` 或等价包，包含设置存储、DeepSeek 客户端、Agent Runtime、PromptBuilder、Skill Registry、历史存储和 Skill 实现。
  - 可能提取聊天气泡渲染辅助类，供 Agent 聊天页复用现有聊天视觉风格。
  - 只读复用现有 Repository、Client、assets 解析逻辑；不改变普通 SCM 私聊行为。
- 数据与网络：
  - 用户 API Key 保存在本机。
  - 对话内容、工具结果和必要上下文会发送给 DeepSeek API。
  - Skill 可调用本地数据、已知公开查询 API，以及用户已登录 SCM 时可用的带 token 查询接口。
- 测试：
  - 新增 DeepSeek 请求/响应解析、设置存储、PromptBuilder、Skill 命中和降级逻辑单元测试。
  - 新增工具页入口和 Agent 聊天关键路径的人工验证项。
