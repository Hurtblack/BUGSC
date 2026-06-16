# SCMBOT Agent 架构收敛优化设计

## 背景

SCMBOT 第一版已经具备工具页入口、本地人物卡、DeepSeek 配置、本地资料查询、SCM 查询和订单草稿创建能力。当前实现能支撑 v1，但架构上已经出现两类扩展压力：

- `AgentChatFragment` 同时负责 UI、会话状态、普通问答、订单草稿解析、SCM 登录判断、订单创建和 runtime 组装。
- Agent 问答路径同时存在早期 `AgentSkillRegistry / AgentSkill` 和后续 `AgentPlanner / AgentToolRegistry / AgentTool` 两套抽象。

本次优化目标是做第一轮架构收敛，不扩大产品功能，不重做 UI，不引入流式输出，也不引入模型自主工具调用。优化后应让 SCMBOT 更接近主流 agent app 的基础分层：`Chat UI -> Session Controller -> Agent Runtime -> Tool Registry -> Data/Action Connectors`。

## 目标

- 让 `AgentChatFragment` 变薄，只负责渲染、输入、按钮点击和导航。
- 新增会话编排层，集中处理消息生命周期、历史写入、普通问答、订单草稿和 pending state。
- 普通问答统一走 `AgentPlanner + AgentToolRegistry + AgentTool` 主路径。
- 保留现有订单草稿产品行为，但把解析、追问、确认和创建流程迁出 Fragment。
- 给工具执行补上 per-tool timeout 和错误隔离，单个工具失败不阻断整轮回答。
- 保留后续升级到 LLM 主控 tool-calling 的扩展点，但本轮不实现。

## 非目标

- 不做流式输出。
- 不做多会话列表。
- 不做完整 `AgentRun / Step / Observation` 审计模型。
- 不让模型自主调用工具。
- 不改变 SCM 私聊、未读数、WebSocket 或普通交易消息逻辑。
- 不改变订单草稿的用户可见行为和确认规则。
- 不迁移 API Key 到 Keystore 或 EncryptedSharedPreferences；本轮只保持现有存储边界。

## 推荐方案

采用 `Session Controller` 收敛方案。

新增 `AgentSessionController` 作为 UI 和业务执行之间的唯一入口。它负责会话流程和 UI 事件输出；`AgentRuntime` 专注普通问答；订单草稿由独立 coordinator 处理；工具查询统一通过 `AgentToolRegistry` 执行。

没有选择更小的局部重构，因为那只能缓解 runtime 构建问题，无法解决 Fragment 过重和订单副作用混在 UI 层的问题。也没有选择一步到位的 `AgentRun / Step` 架构，因为它会把本轮优化变成较大重写，不符合当前 v1 稳定性要求。

## 架构边界

### AgentChatFragment

职责：

- 读取输入框文本。
- 渲染消息气泡。
- 渲染订单确认和取消按钮。
- 跳转设置页。
- 展示 Toast 或简单错误提示。

不再负责：

- 创建 `DeepSeekClient`。
- 创建 `AgentRuntime`。
- 直接调用 `ScmOrderDraftParser`、`ScmOrderDraftResolver`、`MarketPublishClient` 或 `TransactionClient`。
- 判断普通问答和订单草稿分支。
- 直接维护 pending order state。

### AgentSessionController

职责：

- 加载和渲染历史。
- 开始新对话。
- 处理用户发送文本。
- 检查 DeepSeek 是否已配置。
- 判断输入是否进入订单草稿流程。
- 调用 `AgentRuntime.answer()` 完成普通问答。
- 保存用户消息和助手消息。
- 维护 `conversationVersion` 或 session id，防止旧请求写入新对话。
- 维护 pending order parse 和 pending order draft。
- 接收确认或取消订单事件。

输出 UI 可消费的事件或状态，例如：

- `RenderMessages`
- `AppendMessage`
- `ShowOrderActions`
- `NavigateSettingsRequired`
- `ShowToast`
- `ShowError`

### AgentRuntime

职责：

- 使用 `QueryAnalyzer` 分析问题。
- 使用 `AgentPlanner` 生成工具调用计划。
- 使用 `AgentToolRegistry` 执行工具。
- 使用 `AgentPromptBuilder` 构造 DeepSeek messages。
- 使用 `DeepSeekClient` 生成回答。
- 做伪工具调用和明显矛盾回答的校验与 fallback。

不负责：

- Android View 操作。
- 历史落库。
- 订单创建。
- 用户确认。
- SCM 私聊或未读状态。

### AgentToolRegistry / AgentTool

`AgentTool` 作为普通问答唯一工具抽象。旧 `AgentSkill / AgentSkillRegistry` 不再作为新路径使用。为了降低风险，旧类可以先保留给既有测试或通过 adapter 兼容，但新的 runtime 主路径应统一走 `AgentTool`。

工具结果继续包含：

- `summary`
- `facts`
- `sources`
- `confidence`
- `error`

## 普通问答数据流

```text
User input
  -> AgentSessionController.sendUserText()
  -> append user message to AgentHistoryStore
  -> AgentRuntime.answer()
  -> QueryAnalyzer
  -> AgentPlanner
  -> AgentToolRegistry
  -> AgentPromptBuilder
  -> DeepSeekClient
  -> answer validation / fallback
  -> append assistant message to AgentHistoryStore
  -> UI event
```

关键规则：

- `AgentSessionController` 管消息生命周期，`AgentRuntime` 不直接读写历史。
- `AgentPromptBuilder` 只接收已经整理好的 tool results，不自行查数据。
- 单个工具失败或超时只变成失败结果，不能阻断其他工具。
- 开始新对话后，旧请求返回不能写入当前历史。

## 订单草稿迁移

订单草稿本轮不改变用户可见行为，只迁移职责边界。

新增 `AgentOrderDraftCoordinator`，负责：

- `parseOrMerge(text, pendingParse)`：解析新订单请求或合并追问。
- `resolve(parsed)`：查询商品和交易地址，返回缺字段、未命中或已解析结果。
- `create(resolved)`：用户确认后创建 SCM 订单，返回订单号或错误。

`AgentSessionController` 负责订单分支编排：

- 用户输入后先让 coordinator 判断是否订单意图。
- 如果是订单意图，保存用户消息并进入订单流程。
- 缺字段时追加助手追问，并保留 pending parse。
- 字段齐全时追加确认消息，并发出 `ShowOrderActions`。
- 用户点击确认后调用 coordinator 创建订单。
- 用户点击取消或开始新对话时清理 pending state。

`AgentChatFragment` 只负责显示确认和取消按钮，并把点击事件转发给 controller。

订单安全规则：

- 订单创建必须由用户点击确认后执行。
- 模型和普通问答 runtime 不能直接创建订单。
- Prompt 继续禁止模型声称订单已创建、已发布或已挂单。
- SCM 未登录时不创建订单，只返回登录要求。
- 创建失败后默认保留 pending draft 一次，允许用户重试确认；用户取消或新对话时再清理。

## 工具执行可靠性

`AgentToolRegistry` 应支持 per-tool timeout。

建议默认值：

- 本地工具：2 秒。
- 远程 SCM 工具：8 到 10 秒。

实现要求：

- 每个工具独立超时。
- 使用隔离执行方式，单个工具异常、超时或未注册不影响其他工具。
- 超时结果以 `AgentToolResult(error = "...")` 返回。
- Prompt 中可包含失败摘要，但最终回答不应把失败工具展开成噪声。

## 错误处理

普通问答：

- 未配置 DeepSeek：不保存用户消息，发出设置页导航事件。
- DeepSeek 请求失败：保留用户消息，追加可读失败助手消息。
- 工具有部分失败：保留成功工具结果，回答中只在必要时说明远程数据不可用。

订单草稿：

- 缺字段：追加追问并保留 pending parse。
- 商品或地址未命中：追加具体未命中原因，允许用户继续补充。
- 未登录 SCM：追加登录提示，不创建订单。
- 创建失败：追加失败原因，pending draft 保留一次以便重试。
- 创建成功：追加订单号或“可在我的挂单查看”。

新对话：

- 清空历史。
- 清空 pending parse 和 pending draft。
- 递增 session version，旧请求返回后必须丢弃。

## 未来 TODO：LLM 主控 Tool Calling

本轮继续采用 App 主控的 tool-first 模式：

```text
App 分析问题 -> App 规划工具 -> App 执行工具 -> App 把结果交给模型整理
```

未来可以评估新增 LLM 主控 tool-calling 执行器：

```text
模型请求工具 -> App 执行工具 -> 工具结果回填模型 -> 模型继续推理或回答
```

这是重要演进方向，但不进入本轮。原因是 LLM 主控模式需要额外设计：

- 工具调用协议。
- 循环上限。
- 权限确认。
- 副作用 action 安全策略。
- run-step 记录。
- 失败恢复和可观测性。

本轮架构需要为它预留空间：`AgentRuntime` 内部未来可以支持 `RuleBasedToolFirstExecutor` 和 `ModelToolCallingExecutor` 两种执行策略，但当前只实现或保留 rule-based tool-first。

## 测试策略

### AgentSessionControllerTest

覆盖：

- 未配置 DeepSeek 时发出设置引导。
- 普通问答成功时用户消息和助手消息按顺序写入历史。
- 普通问答失败时保留用户消息并追加失败助手消息。
- 开始新对话后旧请求结果不会写入新历史。
- 新对话清理 pending order state。

### AgentOrderDraftCoordinatorTest

覆盖：

- 新订单解析。
- 追问补全。
- SCM 未登录。
- 商品未命中。
- 地址未命中。
- 确认创建成功。
- 创建失败后 pending draft 可重试。

### AgentToolRegistryTest

覆盖：

- 工具成功。
- 工具抛异常。
- 工具超时。
- 多个工具中一个失败不阻断其他工具结果。
- 未注册工具返回失败结果。

### AgentRuntimeTest / AgentHermesWorkflowTest

调整为验证普通问答主路径：

- intent 到 tool plan。
- tool results 进入 prompt。
- DeepSeek 伪工具调用触发 fallback。
- 工具有可靠结果但模型声称未命中时触发 fallback。

旧 `AgentSkillRegistry` 测试可在迁移期保留，但不作为新主路径验收标准。

### 验证命令

至少运行：

```bash
./gradlew :app:testDebugUnitTest --tests 'com.euedrc.bugsc.agent.*'
```

如果改动触及 layout、navigation 或 Fragment 初始化，再运行：

```bash
./gradlew :app:assembleDebug
```

## 验收标准

- `AgentChatFragment` 不再直接调用订单解析、订单创建、SCM 地址查询、SCM 商品查询或 DeepSeek client 构造。
- 普通问答主路径统一通过 `AgentToolRegistry` 执行工具。
- 订单草稿行为与当前版本一致：缺字段追问、确认后创建、取消可放弃。
- 单个工具失败或超时不阻断整轮普通问答。
- 新对话后旧请求不能污染当前历史。
- 相关 agent 单元测试通过。
