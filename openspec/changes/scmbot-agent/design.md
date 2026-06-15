## Context

SCMobiGlas 已有工具页、SCM 私聊页、本地 assets 数据、若干 Repository/Client，以及维护脚本沉淀的 Star Citizen 查询数据。新的 App Agent 不依赖自有后端，也不应成为 SCM 后端用户；第一版以 `SCMBOT` 作为工作名，但架构、包名和能力边界应避免绑定 SCM 品牌，后续允许改名。

现有 `ChatFragment` 强绑定 SCM 登录、聊天 REST、WebSocket、公开资料接口和未读状态。Agent 聊天需要复用聊天视觉风格，但不复用 SCM 会话协议，否则会影响既有私聊和交易消息。

## Goals / Non-Goals

**Goals:**

- 在工具页新增 Agent 入口，点击直接进入聊天页。
- 未配置 DeepSeek 时仍允许进入聊天页，并在聊天页引导配置。
- 第一版仅支持用户自填 DeepSeek API Key，默认模型 `deepseek-v4-flash`，可选 `deepseek-v4-pro`。
- 建立 App 本地 Agent Runtime：分析问题、选择 Skill、查询事实、构造上下文、调用 DeepSeek、保存本地历史。
- 为 Agent 提供本地人物卡，用于聊天页展示、自我介绍、能力边界和 system prompt。
- Skill 第一版覆盖本地数据、当前已知查询 API、可选 SCM 查询 API。
- 已登录 SCM 时允许 Skill 使用带 token 的 SCM 查询接口；未登录时不阻塞 Agent。
- 普通 SCM 私聊、交易消息、WebSocket 和未读数保持不变。

**Non-Goals:**

- 不新增自有后端。
- 不把 Agent 写入 SCM 后端会话列表。
- 不接入 SCM WebSocket。
- 不做完全自主循环 Agent。
- 第一版不做流式输出。
- 第一版不做多供应商 UI。
- 第一版不保证所有外部 API 都完成深度接入，可先建立 Skill 框架和首批高价值 Skill。

## Decisions

### D1. Agent 是 App 本地 Runtime

Agent 会话、历史、Skill 执行、DeepSeek 调用都在 App 本地完成。SCM 后端只作为可选查询数据源之一，不承载 Agent 会话。

### D2. 工具页入口

`ToolsFragment` 新增 Agent 工具卡，点击进入 `AgentChatFragment`。入口不要求 DeepSeek 已配置，也不要求 SCM 登录。

### D3. 独立聊天页

新建 `AgentChatFragment` 和布局，复用现有聊天页视觉风格。不要把现有 `ChatFragment` 改成双模式，避免 SCM 私聊逻辑被 Agent 分支污染。

### D4. DeepSeek 设置本地保存

新增 `AgentSettingsStore` 保存 API Key、模型、连接测试状态。第一版使用 App 私有存储；API Key 不上传到 SCM 或其他自有服务。若项目后续引入安全存储，再迁移到 Android Keystore 或加密 SharedPreferences。

### D5. Tool-first 执行策略

Agent 不把用户问题直接交给模型。第一版固定流程为：

```text
User Message
  -> AgentRuntime
    -> QueryAnalyzer
    -> SkillRegistry
    -> SkillExecutor
    -> ContextBuilder
    -> DeepSeekClient
    -> AnswerRenderer
```

Skill 负责查事实，DeepSeek 负责理解、归纳和表达。

### D6. Agent 人物卡

新增本地 `AgentProfile`，作为人物卡配置，不依赖后端。人物卡服务三类场景：

- 聊天页顶部展示：显示工作名、头像/占位图、状态和简短定位。
- 自我介绍：用户问“你是谁”“你能做什么”“你和 SCM 什么关系”时，使用人物卡固定事实回答。
- System prompt：提供稳定角色边界，例如回答语言、数据来源优先级、隐私说明和不确定性表达。

建议字段：

```kotlin
data class AgentProfile(
    val displayName: String,
    val codename: String,
    val tagline: String,
    val roleDescription: String,
    val capabilities: List<String>,
    val dataSources: List<String>,
    val privacyNotes: List<String>,
    val limitations: List<String>,
)
```

第一版显示名可暂用 `SCMBOT`，但内部模块使用 `agent` 命名。人物卡内容必须明确：它是 App 内置助手，不是 SCM 官方机器人，不是 SCM 后端用户；DeepSeek API Key 保存在本机；对话和工具结果会发送给 DeepSeek 用于生成回答。

第一版人物卡草案：

```kotlin
AgentProfile(
    displayName = "SCMBOT",
    codename = "MobiGlas Field Analyst",
    tagline = "星际公民资料检索与行动建议助手",
    roleDescription = "我是 SCMobiGlas 内置的资料分析助手，负责把 App 本地数据、已知查询 API 和可用的登录态查询结果整理成可执行的游戏建议。我不是 SCM 官方机器人，也不是 SCM 后端用户。",
    capabilities = listOf(
        "查询飞船、硬点、组件、电力和配装相关资料",
        "查询矿物、采集地点、采矿设备和精炼相关信息",
        "查询蓝图、材料、任务来源和维科洛兑换线索",
        "查询任务、阵营、奖励、地点、冷却和前置条件",
        "在可用时查询远程 API 或用户登录后可访问的数据",
        "把查询结果整理成中文行动建议"
    ),
    dataSources = listOf(
        "App 内置本地数据",
        "App 已知公开查询 API",
        "用户已登录 SCM 时可用的查询接口",
        "DeepSeek 用于语言理解和答案整理"
    ),
    privacyNotes = listOf(
        "DeepSeek API Key 只保存在本机",
        "不会把 SCM token、Cookie 或账号凭据发送给模型",
        "用户问题、必要上下文和工具查询结果会发送给 DeepSeek 生成回答"
    ),
    limitations = listOf(
        "游戏数据可能随版本变化而过期",
        "远程 API 可能失败、限流或返回不完整数据",
        "工具未命中时会明确说明数据不足，而不是编造确定结论",
        "价格、地点、任务奖励等实时信息应以标注来源为准"
    )
)
```

聊天页顶部可显示：

- 名称：`SCMBOT`
- 状态：`本地 Agent · DeepSeek`
- 简介：`资料检索、蓝图、矿物、任务与行动建议`

`AgentSelfSkill` 使用人物卡生成固定回答，例如：

> 我是 SCMobiGlas 内置的资料分析助手，第一版工作名叫 SCMBOT。我不是 SCM 官方机器人，也不是 SCM 后端用户。我会优先查询 App 本地数据、已知查询 API 和你已登录后可用的查询结果，再用 DeepSeek 整理成中文建议。你的 DeepSeek API Key 保存在本机，不会上传到 SCM；但你的问题、必要上下文和工具查询结果会发送给 DeepSeek 用于生成回答。

### D7. 规则式问题分析

第一版 `QueryAnalyzer` 使用规则、关键词和本地实体索引，不先调用模型做路由。

分析流程：

```text
原始问题
  -> 归一化
  -> 意图识别
  -> 实体抽取
  -> Skill 调用计划
```

归一化包含小写化、去标点、压缩空白、常见别名统一。意图识别使用关键词打分，允许同时命中多个意图：

- `SHIP_INFO`: 船、飞船、硬点、火力、配装、组件、shield、weapon。
- `MINING`: 矿、采、挖、精炼、ore、mining。
- `BLUEPRINT`: 蓝图、材料、制作、craft、blueprint。
- `MISSION`: 任务、声望、奖励、合约。
- `MARKET`: 哪里买、价格、市场、出售、SCM。
- `WIKELO`: 维科洛、wikelo、兑换。
- `SELF_HELP`: 你是谁、你能做什么、怎么用、和 SCM 什么关系、Key 是否上传、用什么模型。
- `GUIDE` / `UNKNOWN`: 无强命中时的泛攻略或未知问题。

实体抽取不只靠正则，而是基于本地索引：

- 船只索引：船名、简称、中文名、别名。
- 矿物索引：英文名、中文名、别名。
- 蓝图索引：蓝图名、产物名、材料名。
- 任务索引：任务标题、中文标题、阵营、地点。
- 物品索引：物品名、中文名、分类。

当识别失败时，执行轻量 `GlobalSearchSkill` 搜索本地索引 top N；仍无命中时再作为泛攻略问题交给 DeepSeek，并标注未命中本地数据。

### D8. Skill 接口

每个 Skill 是小型事实查询模块，不负责最终聊天口吻。

```kotlin
interface AgentSkill {
    val id: String
    val name: String
    fun match(query: AgentQuery): SkillMatch
    suspend fun execute(query: AgentQuery): SkillResult
}
```

核心模型：

```kotlin
data class AgentQuery(
    val rawText: String,
    val normalizedText: String,
    val intents: List<ScoredIntent>,
    val entities: List<ScoredEntity>,
)

data class SkillResult(
    val skillId: String,
    val summary: String,
    val facts: List<AgentFact>,
    val sources: List<AgentSource>,
    val confidence: Float,
    val error: String? = null,
)
```

`SkillResult` 必须有来源，便于 PromptBuilder 告诉模型哪些内容来自本地数据、哪些来自远程 API。

### D9. Skill 使用时机

`AgentRuntime` 根据 `AgentQuery` 生成最多 3 到 5 个 Skill 调用计划：

- 身份、能力、隐私、使用方式问题优先调用 `AgentSelfSkill`，直接使用人物卡固定事实回答。
- 具体船只资料、硬点、火力、配装问题调用 `ShipSkill`。
- 矿物、采集地点、采矿装备问题调用 `MiningSkill`。
- 蓝图制作、材料来源问题调用 `BlueprintSkill`，必要时联动 `MissionSkill` 和 `WikeloSkill`。
- 任务奖励、阵营、地点、冷却问题调用 `MissionSkill`。
- 维科洛兑换问题调用 `WikeloSkill`。
- 价格、市场、商品是否出售等实时性问题优先调用远程查询 Skill。
- 无明确意图时先调用 `GlobalSearchSkill`，再决定是否让模型按泛攻略回答。

Skill 可并发执行，但每个远程 Skill 必须有超时和错误降级。单个 Skill 失败不能阻断整次回答。

`AgentSelfSkill` 优先级最高。它处理产品身份、隐私和能力边界，不允许模型自由编造事实；可选让模型润色，但事实内容必须来自人物卡。

### D10. Prompt 构造

`AgentPromptBuilder` 将人物卡、用户问题、最近少量会话上下文、Skill 结果、来源和限制组装为 DeepSeek messages。系统提示词要求：

- 使用中文回答。
- 优先依据工具结果回答。
- 遵守人物卡中的身份、能力、隐私和限制说明。
- 工具结果不足时明确说明“不确定”或“当前数据不足”。
- 不编造地点、奖励、价格、材料和版本信息。
- 给出可执行建议。

PromptBuilder 必须过滤 SCM token、Cookie、API Key、邮箱等敏感字段。

### D11. DeepSeek 客户端

`DeepSeekClient` 使用 OpenAI-compatible `/chat/completions` 非流式请求。第一版支持：

- `https://api.deepseek.com`
- `deepseek-v4-flash`
- `deepseek-v4-pro`
- 连接测试
- HTTP 错误、鉴权失败、额度不足、网络失败的用户可读错误

### D12. 本地历史

新增 `AgentHistoryStore` 保存 Agent 单会话历史：

- 用户消息
- 助手消息
- 时间
- 状态：sending、sent、failed
- 可选工具摘要

第一版可用 SharedPreferences JSON 或文件存储。历史不进入 SCM 消息列表，不参与 SCM 未读数。

## Test Strategy

### 值得测试的部分

- `QueryAnalyzer`：验证关键词意图打分、多意图命中、实体索引匹配、未知问题降级。
- `AgentSelfSkill` 和 `AgentProfile`：验证身份、能力、隐私和 SCM 关系问题返回固定事实。
- `AgentSkillRegistry`：验证按意图和实体选择 Skill，限制最大调用数量，远程 Skill 失败不阻断本地结果。
- `AgentPromptBuilder`：验证人物卡和工具结果进入 prompt，敏感字段不会进入 prompt，未命中数据时带上限制说明。
- `DeepSeekClient`：验证请求 JSON、模型字段、响应解析、错误映射。
- `AgentSettingsStore`：验证 API Key 和模型保存读取。
- 首批 Skill：验证本地数据命中和无结果返回格式。

### 不适合单元测试的部分

- `ToolsFragment` 工具卡点击和 `AgentChatFragment` 纯 UI 排版：更适合通过构建和模拟器/真机手工验证。
- 外部 API 的真实可用性：网络、接口限流和上游数据变化不可控，单元测试使用 fake client 覆盖成功/失败分支。
- DeepSeek 真实模型质量：通过连接测试和人工提问验证，不把模型内容质量写成确定性单测。

### 结论

本次变更需要新增单元测试。重点覆盖 Agent 分析、Skill 编排、Prompt 安全、DeepSeek 请求/响应和设置存储；UI 和真实网络质量通过人工验证补充。

## Risks / Trade-offs

- [模型幻觉] → Prompt 明确要求优先依据工具结果，工具结果不足时必须说明不确定。
- [外部 API 不稳定] → 每个远程 Skill 设置超时和错误降级，单个 API 失败不阻断回答。
- [API Key 泄露] → Key 仅本地保存，PromptBuilder 过滤敏感字段，不上传 SCM token、Cookie 或用户凭据。
- [影响 SCM 私聊] → 新建独立 Agent 聊天页和历史，不接入 SCM WebSocket 和未读数。
- [后续改名成本] → 包名和内部类尽量使用 `agent` 命名，`SCMBOT` 只作为第一版显示名和工作名。
- [身份回答不一致] → 使用本地人物卡和最高优先级 `AgentSelfSkill` 固定身份、能力和隐私说明。
