## ADDED Requirements

### Requirement: 工具页 Agent 入口
系统 SHALL 在工具页提供 Agent 助手入口。

#### Scenario: 打开 Agent 聊天
- **WHEN** 用户在工具页点击 Agent 助手入口
- **THEN** 系统打开独立 Agent 聊天页
- **AND** 不要求用户已配置 DeepSeek
- **AND** 不要求用户已登录 SCM

#### Scenario: 入口不影响其他工具
- **WHEN** 工具页加载 Agent 助手入口
- **THEN** 既有工具卡片仍可按原路径打开
- **AND** Agent 入口不改变工具页服务器状态展示

### Requirement: DeepSeek 配置
系统 SHALL 支持用户在 App 内配置 DeepSeek 模型调用参数。

#### Scenario: 保存 DeepSeek Key
- **WHEN** 用户输入 DeepSeek API Key 并保存
- **THEN** 系统将 Key 保存在本机私有存储
- **AND** 后续 Agent 请求使用该 Key 调用 DeepSeek

#### Scenario: 模型选择
- **WHEN** 用户打开 DeepSeek 设置
- **THEN** 系统提供 `deepseek-v4-flash` 默认模型
- **AND** 系统提供 `deepseek-v4-pro` 作为可选模型

#### Scenario: 未配置时进入聊天
- **WHEN** 用户未配置 DeepSeek 并打开 Agent 聊天页
- **THEN** 系统显示配置引导
- **AND** 用户发送问题时系统提示先配置模型

### Requirement: Agent 人物卡
系统 SHALL 使用本地人物卡定义 Agent 的身份、能力、数据源、隐私说明和限制。

#### Scenario: 聊天页展示人物卡
- **WHEN** 用户进入 Agent 聊天页
- **THEN** 系统展示 Agent 显示名、状态和简短定位
- **AND** 展示内容来自本地人物卡

#### Scenario: 用户询问 Agent 身份
- **WHEN** 用户询问“你是谁”“你能做什么”“你和 SCM 什么关系”或类似问题
- **THEN** 系统优先使用本地人物卡回答
- **AND** 回答说明 Agent 是 App 内置助手，不是 SCM 官方机器人或 SCM 后端用户

#### Scenario: 用户询问隐私
- **WHEN** 用户询问 API Key、SCM 凭据或对话内容如何处理
- **THEN** 系统说明 DeepSeek Key 保存在本机
- **AND** 系统说明不会把 SCM token、Cookie 或账号凭据发送给模型
- **AND** 系统说明用户问题、必要上下文和工具查询结果会发送给 DeepSeek

### Requirement: Agent 问题分析
系统 SHALL 在调用模型前使用本地规则分析用户问题。

#### Scenario: 识别多意图问题
- **WHEN** 用户询问蓝图材料来源或任务获取方式
- **THEN** 系统识别蓝图和任务相关意图
- **AND** 系统计划调用相关 Skill，而不是直接让模型凭空回答

#### Scenario: 未知问题降级
- **WHEN** 用户问题无法被明确识别
- **THEN** 系统先执行本地全局搜索
- **AND** 仍无命中时允许模型按泛攻略回答
- **AND** 回答中标注未命中本地数据或当前数据不足

### Requirement: Skill 查询编排
系统 SHALL 通过 Skill 查询事实数据后再构造模型上下文。

#### Scenario: 船只问题调用船只 Skill
- **WHEN** 用户询问船只、硬点、火力、配装或组件问题
- **THEN** 系统调用船只相关 Skill 查询事实
- **AND** 模型回答应依据 Skill 返回的结果

#### Scenario: 矿物问题调用矿物 Skill
- **WHEN** 用户询问矿物、采集地点、采矿设备或精炼问题
- **THEN** 系统调用矿物相关 Skill 查询事实
- **AND** 模型回答应依据 Skill 返回的结果

#### Scenario: 蓝图和任务问题调用多个 Skill
- **WHEN** 用户询问蓝图制作、材料来源、任务来源或维科洛兑换
- **THEN** 系统可调用蓝图、任务和维科洛相关 Skill
- **AND** 系统将多个 Skill 结果合并为模型上下文

#### Scenario: 远程查询失败降级
- **WHEN** 某个远程查询 API 或 SCM 查询 API 请求失败
- **THEN** 系统保留其他 Skill 查询结果
- **AND** 系统仍尝试基于本地数据回答
- **AND** 回答中说明对应远程数据暂不可用

### Requirement: DeepSeek 回答生成
系统 SHALL 使用 DeepSeek 将人物卡、用户问题和 Skill 结果整理为中文回答。

#### Scenario: 基于工具结果回答
- **WHEN** Skill 返回可用事实数据
- **THEN** 系统将事实数据、来源和限制传入 DeepSeek
- **AND** 回答优先依据工具结果

#### Scenario: 保护敏感字段
- **WHEN** 系统构造 DeepSeek 请求
- **THEN** 请求上下文不包含 DeepSeek API Key
- **AND** 请求上下文不包含 SCM token、Cookie 或账号凭据

#### Scenario: DeepSeek 请求失败
- **WHEN** DeepSeek 请求失败、鉴权失败或额度不足
- **THEN** 系统在聊天页显示可读错误
- **AND** 保留用户消息和可重试状态

### Requirement: Agent 本地历史
系统 SHALL 在本机保存 Agent 聊天历史。

#### Scenario: 保存消息
- **WHEN** 用户发送问题或 Agent 返回回答
- **THEN** 系统保存消息内容、时间和状态
- **AND** 下次进入 Agent 聊天页时可恢复历史

#### Scenario: 不进入 SCM 消息体系
- **WHEN** Agent 聊天产生新消息
- **THEN** 系统不将消息写入 SCM 会话列表
- **AND** 不更新 SCM 未读数
- **AND** 不连接 SCM 聊天 WebSocket

### Requirement: 可改名的 Agent 架构
系统 SHALL 避免将 Agent 能力与 SCM 品牌强绑定。

#### Scenario: 后续改名
- **WHEN** 后续需要将显示名从 `SCMBOT` 改成其他名称
- **THEN** 系统可通过人物卡或显示文案调整名称
- **AND** Agent Runtime、Skill 和历史模块不依赖 SCM 品牌命名才能运行
