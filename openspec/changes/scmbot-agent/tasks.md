## 1. Agent 核心模型、人物卡与设置

Constraints: D1, D4, D6, D10

- [ ] 1.1 新增 `app/src/main/java/com/euedrc/bugsc/agent/AgentModels.kt`，定义 `AgentProfile`、`AgentQuery`、`AgentIntent`、`ScoredIntent`、`ScoredEntity`、`SkillMatch`、`SkillResult`、`AgentFact`、`AgentSource` 和 Agent 消息模型
- [ ] 1.2 新增 `AgentProfileProvider.kt`，实现本地人物卡草案，显示名暂用 `SCMBOT`，内部类型保持 `agent` 命名
- [ ] 1.3 新增 `AgentSettingsStore.kt`，使用 App 私有存储保存 DeepSeek API Key、模型、连接测试状态，并默认 `deepseek-v4-flash`
- [ ] 1.4 新增 `AgentSettingsStoreTest.kt` 和 `AgentProfileTest.kt`，覆盖设置读写、默认模型、人物卡身份/隐私字段

Verify: `./gradlew :app:testDebugUnitTest --tests 'com.euedrc.bugsc.agent.*'`

## 2. 问题分析与 Skill 编排

Constraints: D5, D7, D8, D9

- [ ] 2.1 新增 `QueryAnalyzer.kt`，实现归一化、关键词意图打分、多意图命中和基于本地索引的实体候选匹配
- [ ] 2.2 新增 `AgentSkill.kt`、`AgentSkillRegistry.kt` 和 `AgentRuntime.kt`，实现最多 3-5 个 Skill 调用计划、Skill 并发执行、远程失败降级和结果合并
- [ ] 2.3 新增 `AgentSelfSkill.kt`，让身份、能力、隐私、使用方式问题优先使用人物卡固定事实回答
- [ ] 2.4 新增 `GlobalSearchSkill.kt`，在意图不明确时搜索本地实体索引 top N
- [ ] 2.5 新增 `QueryAnalyzerTest.kt`、`AgentSkillRegistryTest.kt`、`AgentSelfSkillTest.kt`，覆盖“量子矿哪里采”“F7A 火力怎么样”“蓝图材料哪里来”“你是谁”等路由场景

Verify: `./gradlew :app:testDebugUnitTest --tests 'com.euedrc.bugsc.agent.*'`

## 3. 首批数据 Skill

Constraints: D5, D8, D9

- [ ] 3.1 新增 `ShipSkill.kt`，只读复用 shipfit 资产或 Repository，返回船只、硬点、组件、电力和配装相关事实
- [ ] 3.2 新增 `MiningSkill.kt`，只读复用 mining Repository，返回矿物、地点、属性和设备事实
- [ ] 3.3 新增 `BlueprintSkill.kt`，只读复用 blueprint Repository，返回蓝图、材料、任务来源和制作提示
- [ ] 3.4 新增 `MissionSkill.kt`，只读复用任务资产，返回任务、阵营、奖励、地点、冷却和前置条件事实
- [ ] 3.5 新增 `WikeloSkill.kt`，只读复用 wikelo Repository，返回兑换材料和位置事实
- [ ] 3.6 新增远程查询 Skill 框架 `RemoteQuerySkill.kt` 和 `ScmQuerySkill.kt`，支持公开查询和已登录 SCM 时的带 token 查询降级，不强制 SCM 登录
- [ ] 3.7 新增首批 Skill 单元测试，使用 fake Repository/Client 覆盖命中、无结果、远程失败和来源标记

Verify: `./gradlew :app:testDebugUnitTest --tests 'com.euedrc.bugsc.agent.*'`

## 4. DeepSeek 客户端与 Prompt

Constraints: D4, D5, D6, D10

- [ ] 4.1 新增 `DeepSeekClient.kt`，实现 OpenAI-compatible `/chat/completions` 非流式请求、连接测试、响应解析和错误映射
- [ ] 4.2 新增 `AgentPromptBuilder.kt`，将人物卡、用户问题、少量历史、Skill 结果、来源和限制组装为 DeepSeek messages
- [ ] 4.3 在 `AgentRuntime.kt` 接入 `AgentPromptBuilder` 和 `DeepSeekClient`，Skill 有结果时优先让模型基于事实整理回答
- [ ] 4.4 新增 `DeepSeekClientTest.kt` 和 `AgentPromptBuilderTest.kt`，覆盖请求 JSON、模型字段、响应解析、错误映射、敏感字段过滤和未命中数据提示

Verify: `./gradlew :app:testDebugUnitTest --tests 'com.euedrc.bugsc.agent.*'`

## 5. Agent 聊天 UI、工具入口与历史

Constraints: D2, D3, D4, D6, D11

- [ ] 5.1 修改 `fragment_tools.xml` 和 `ToolsFragment.kt`，新增 Agent 工具卡，点击导航到 Agent 聊天页并记录 feature click
- [ ] 5.2 修改 `nav_graph.xml`，新增 `AgentChatFragment` 和 `AgentSettingsFragment` 目的地
- [ ] 5.3 新增 `fragment_agent_chat.xml` 和 `AgentChatFragment.kt`，复用现有聊天视觉风格，顶部展示人物卡，未配置 DeepSeek 时显示配置引导
- [ ] 5.4 新增 `fragment_agent_settings.xml` 和 `AgentSettingsFragment.kt`，实现 DeepSeek Key、模型选择、保存和连接测试
- [ ] 5.5 新增 `AgentHistoryStore.kt`，保存用户消息、助手消息、时间、状态和失败重试信息，不写入 SCM 消息体系
- [ ] 5.6 确认现有 `ChatFragment`、`ChatUnreadStore`、`ChatInboxSocket` 无 Agent 分支依赖，Agent 对话不影响 SCM 未读数

Verify: `./gradlew :app:assembleDebug`

## 6. [Review] 审查分组 1-5

Review Targets: 分组 1-5 新增和修改的 agent 包、工具页、导航、布局、测试文件

- [ ] 6.1 **D1 遵循**：Agent 会话、历史、Skill 和模型调用都在 App 本地完成
  验证：Agent 代码不创建 SCM 会话，不调用 SCM 聊天 WebSocket，不写入 SCM 未读状态
- [ ] 6.2 **D2 遵循**：工具页入口不要求 DeepSeek 配置或 SCM 登录
  验证：`ToolsFragment` 点击路径直接进入 Agent 聊天页，配置引导由聊天页处理
- [ ] 6.3 **D3 遵循**：Agent 使用独立聊天页
  验证：`ChatFragment` 未被改成 SCM/Agent 双模式，SCM 私聊参数和逻辑保持原样
- [ ] 6.4 **D4 遵循**：DeepSeek 设置由用户本机保存
  验证：不存在内置开发者 Key，`AgentSettingsStore` 默认模型和保存读取测试覆盖
- [ ] 6.5 **D5-D9 遵循**：问题分析、人物卡、Skill 编排和使用时机符合设计
  验证：`QueryAnalyzerTest`、`AgentSelfSkillTest`、`AgentSkillRegistryTest` 覆盖身份、矿物、船只、蓝图和未知问题
- [ ] 6.6 **D10 遵循**：Prompt 和 DeepSeek 请求不包含敏感字段
  验证：`AgentPromptBuilderTest` 覆盖 API Key、SCM token、Cookie 过滤
- [ ] 6.7 **D11 遵循**：Agent 历史不进入 SCM 消息体系
  验证：`AgentHistoryStore` 独立存储，未引用 `ChatClient` 会话创建、`ChatUnreadStore` 或 `ChatInboxSocket`
- [ ] 6.8 **Scenario 对齐**：工具入口、未配置引导、身份隐私回答、Skill 查询降级、DeepSeek 失败和本地历史恢复
  验证：实现行为逐项对应 `specs/app-agent-assistant/spec.md` 的 WHEN/THEN
- [ ] 6.9 **无越界改动**：改动文件列表属于 proposal Impact 范围，不修改 SCM 私聊协议和交易消息行为
- [ ] 6.10 **构建通过**：`./gradlew :app:assembleDebug :app:testDebugUnitTest`
- [ ] 6.11 **无新增 lint 警告**：`./gradlew :app:lintDebug`
