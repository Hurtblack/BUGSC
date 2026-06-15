# SCMBOT App Agent 设计

## 背景

SCMobiGlas 需要在 App 内提供一个专门解决 Star Citizen 问题的助手角色。它不是 SCM 后端用户，也不依赖 SCM 聊天系统创建机器人账号。SCM 现有聊天页面只作为交互外壳复用，SCMBOT 的会话、历史、模型调用和工具调用都由 App 本地管理。

该功能不进入 1.0.5 发布范围，单独在 `codex/scmbot-agent` 分支开发。

## 第一版目标

- 在工具页提供 SCMBOT 助手入口，用户点击后直接进入 SCMBOT 对话。
- 用户填写自己的 DeepSeek API Key 后，可以在 SCMBOT 对话中发送问题并获得回答。
- SCMBOT 复用现有聊天页视觉风格，显示为固定角色 `SCMBOT`。
- App 先调用内置 Skill 查询结构化数据，再把工具结果交给 DeepSeek 归纳回答。
- Skill 第一版覆盖本地数据查询、当前已知查询 API 和可用的 SCM API 查询。
- 远程查询 API 不可用或 SCM 未登录时，Agent 降级使用本地数据，并在回答中说明远程数据不可用。

## 非目标

- 不新增自有后端。
- 不把 SCMBOT 写入 SCM 后端会话列表。
- 不接入 SCM WebSocket。
- 不改变普通 SCM 私聊和交易消息逻辑。
- 第一版不做多供应商 UI，仅支持 DeepSeek。
- 第一版不做复杂自主循环 Agent，只做可控的工具优先问答流程。

## 产品形态

工具页新增一个 `SCMBOT 助手` 工具卡：

- 点击工具卡直接打开 SCMBOT 聊天页。
- SCMBOT 聊天页顶部显示固定角色 `SCMBOT` 和简短状态。
- 未配置 DeepSeek 时仍可进入聊天页，但输入区显示配置引导，发送时弹出设置入口。
- 聊天页内提供 `设置模型` 入口，设置页包含 API Key、模型选择和连接测试。
- 配置完成后返回聊天页，用户可以直接开始对话。

第一版模型选择：

- 默认：`deepseek-v4-flash`
- 高质量选项：`deepseek-v4-pro`

DeepSeek 官方 OpenAI-compatible 配置：

- Base URL: `https://api.deepseek.com`
- Endpoint: `/chat/completions`

旧模型 `deepseek-chat` 和 `deepseek-reasoner` 将在 2026-07-24 15:59 UTC 废弃，第一版不作为默认值。

## 架构

### Chat UI

现有 `ChatFragment` 当前强绑定 SCM 登录、SCM 历史、WebSocket 和公开资料接口。为支持 SCMBOT，需要抽出会话后端边界：

- SCM 私聊继续使用现有 SCM REST 和 WebSocket。
- SCMBOT 使用本地 Agent 会话后端。
- SCMBOT 入口位于工具页，不依赖个人信息页或 SCM 登录态。

实现上可以选择：

- 轻量方案：保留 `ChatFragment` 作为 SCM 私聊，新建 `ScmBotChatFragment` 复用布局和气泡渲染代码。
- 长期方案：提取通用聊天渲染组件，由 SCM 私聊和 SCMBOT 两个 Fragment 共享。

第一版推荐轻量方案，避免影响已稳定的 SCM 私聊。

### Agent Runtime

SCMBOT 的处理流程：

1. 用户发送问题。
2. 本地保存用户消息并显示乐观气泡。
3. `AgentSkillRegistry` 根据问题选择候选 Skill。
4. Skill 查询本地 assets、现有 Repository、当前已知查询 API 或 SCM API。
5. `AgentPromptBuilder` 将用户问题、工具结果、来源和限制组装为 DeepSeek prompt。
6. `DeepSeekAgentClient` 调用 `/chat/completions`。
7. App 保存并展示 SCMBOT 回复。

模型只负责总结、比较、推理和表达。事实数据优先来自工具结果。

## 模块

### ToolsFragment 入口

工具页新增 SCMBOT 工具卡，行为与其他工具入口一致：

- 标题：`SCMBOT`
- 副标题：`游戏资料、蓝图、矿物、任务与攻略助手`
- 点击后导航到 `ScmBotChatFragment`
- 不因 DeepSeek 未配置而隐藏入口

DeepSeek 配置状态可以作为工具卡的次要状态展示，但不能阻止进入聊天页。配置引导由聊天页负责。

### AgentSettingsStore

保存 DeepSeek 设置：

- API Key
- model
- lastTestAt
- lastTestStatus

API Key 使用 Android Keystore 或加密 SharedPreferences 保存；如果项目暂未引入安全存储依赖，第一版使用私有 SharedPreferences，并在设计中保留迁移点。

### DeepSeekAgentClient

负责 DeepSeek Chat Completions：

- 构造 OpenAI-compatible JSON。
- 支持非流式响应。
- 设置合理超时。
- 解析 assistant message。
- 将 HTTP 错误、鉴权失败、额度不足、网络失败映射为用户可读错误。

第一版先不做流式输出，降低 UI 和取消逻辑复杂度。

### AgentHistoryStore

本地保存 SCMBOT 对话历史：

- 用户消息
- 助手消息
- 时间
- 状态：sending、sent、failed
- 可选工具摘要

第一版可以用 SharedPreferences JSON 或轻量文件存储。若后续历史增长明显，再迁移 Room。

### AgentSkillRegistry

注册和执行 Skill：

- 每个 Skill 暴露 `canHandle(question)` 和 `execute(question)`。
- Registry 可返回多个工具结果。
- 工具结果必须包含来源、摘要和结构化内容，便于 prompt 控制。

### 本地数据 Skill

第一版优先覆盖已有资产和 Repository：

- `ShipSkill`：查询船只、硬点、组件、电力、配装相关数据。
- `MiningSkill`：查询矿物、采集地点、矿物属性、采矿装备。
- `BlueprintSkill`：查询蓝图、材料、任务来源、制作提示。
- `MissionSkill`：查询任务、阵营、地点、奖励、冷却、前置条件。
- `WikeloSkill`：查询维科洛兑换材料和兑换位置。

Skill 不直接输出最终自然语言攻略，只返回结构化事实和简短说明。

### 已知查询 API Skill

当前 App 已经掌握或维护过的远程查询 API，也可以封装为 Agent Skill。它们和 SCM API 并列，都是工具数据源，不是 Agent 后端。

第一版可按现有代码和维护脚本逐步纳入：

- `ScmdbQuerySkill`：查询 SCMDB 任务、版本、地点和任务奖励等数据。
- `ScCraftQuerySkill`：查询 sc-craft 蓝图、材料和制作相关数据。
- `UexQuerySkill`：查询 UEX 船只、组件、价格或配装相关数据。
- `ScWikiQuerySkill`：查询 Star Citizen Wiki 飞船、硬点、组件等补充资料。

这些 Skill 需要优先复用项目中已有 Client、Repository、生成脚本沉淀的字段映射和翻译逻辑。每个远程 API 都必须有超时、错误降级和来源标记，不能让单个 API 失败阻断 SCMBOT 回答。

### SCM API Skill

SCM API 是 Agent 的工具源之一，不是后端 Agent：

- 可使用无需登录的公开接口查询 SCM 物品、市场、蓝图等数据。
- 用户已登录 SCM 时，可调用带 token 的接口查询需要登录态的数据。
- 不要求 SCM 登录才能使用 SCMBOT。
- SCM API 请求失败时不阻断回答，返回降级结果。

需要对每个实际使用的 SCM 接口单独确认请求参数、返回结构和登录要求。

## Prompt 规则

系统提示词要求：

- 使用中文回答。
- 优先依据工具结果回答。
- 工具结果不足时明确说明“不确定”或“当前数据不足”。
- 不编造物品地点、任务奖励、价格和材料。
- 回答尽量给出可执行建议，例如位置、需要设备、任务来源、注意事项。

上下文格式：

- 用户问题
- 可用工具结果列表
- 每个结果的来源和时间
- 当前 App 数据版本信息，如可获得

## 错误处理

- 未配置 DeepSeek：输入区禁用或发送时引导设置。
- API Key 错误：提示用户检查 Key，不清空输入。
- DeepSeek 网络失败：保留用户消息，助手回复失败气泡可重试。
- 远程查询 API 或 SCM API 失败：记录为工具不可用，仍尝试本地数据回答。
- 本地 Skill 无结果：允许模型基于通用知识回答，但必须标注“未命中本地数据”。
- 回复过长：第一版限制工具上下文条数和 prompt 长度。

## 隐私与安全

- API Key 只保存在用户设备本地。
- App 不上传用户 API Key 到 SCM 或其他自有服务。
- 发送给 DeepSeek 的内容包括用户问题、必要会话上下文和工具查询结果。
- 设置页需要明确提示：启用 SCMBOT 后，对话内容会发送到 DeepSeek API。
- 不自动发送 SCM 登录凭据、Token、Cookie 或个人资料给模型。

## 测试

单元测试：

- DeepSeek 请求 JSON 构造。
- DeepSeek 响应解析和错误映射。
- AgentSettingsStore 保存和读取。
- Skill 命中规则与无结果降级。
- PromptBuilder 不包含敏感 token 字段。

集成或手工验证：

- 未配置 DeepSeek 时入口和提示正确。
- 工具页点击 SCMBOT 可直接进入聊天页。
- 填写 DeepSeek Key 后连接测试可用。
- 打开 SCMBOT 后可发送问题并显示回答。
- 查询矿物、蓝图、任务、船只时能命中本地数据。
- 已知查询 API 或 SCM API 不可用时仍能给出本地数据回答。
- 普通 SCM 私聊、未读数和 WebSocket 不受影响。

## 实施顺序

1. 新建 SCMBOT 分支和设计文档。
2. 在工具页新增 SCMBOT 入口。
3. 添加 DeepSeek 设置模型、存储和设置 UI。
4. 新建 SCMBOT 聊天页和本地聊天历史。
5. 实现 DeepSeekAgentClient 和连接测试。
6. 提取或复用聊天气泡渲染，完成 SCMBOT 对话页。
7. 实现第一批本地 Skill。
8. 接入当前已知查询 API Skill。
9. 接入 SCM API Skill 的公开查询能力。
10. 补充测试和实机验证。
