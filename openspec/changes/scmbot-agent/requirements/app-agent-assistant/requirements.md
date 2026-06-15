## ADDED Requirements

### Requirement: 用户从工具页进入 Agent
用户 SHALL 能从工具页直接打开 App 内置 Agent 助手。

#### Scenario: 直接进入聊天
- **WHEN** 用户点击工具页的 Agent 助手卡片
- **THEN** App 显示 Agent 聊天界面
- **AND** 未配置 DeepSeek 或未登录 SCM 都不阻止进入

### Requirement: 用户配置 DeepSeek
用户 SHALL 能在 App 内配置自己的 DeepSeek API Key 和模型。

#### Scenario: 未配置时引导
- **WHEN** 用户未配置 DeepSeek 并尝试向 Agent 发送问题
- **THEN** App 提示用户先配置模型
- **AND** 提供进入设置的操作

#### Scenario: 配置后使用
- **WHEN** 用户保存有效 DeepSeek 配置后回到聊天页
- **THEN** 用户可以发送问题并获得 Agent 回答

### Requirement: 用户理解 Agent 身份
用户 SHALL 能从 Agent 的展示和回答中理解它的身份边界。

#### Scenario: 询问身份
- **WHEN** 用户问 Agent 是谁或能做什么
- **THEN** Agent 说明自己是 App 内置资料分析助手
- **AND** Agent 说明自己不是 SCM 官方机器人或 SCM 后端用户

#### Scenario: 询问隐私
- **WHEN** 用户问 API Key 或账号凭据如何处理
- **THEN** Agent 说明 DeepSeek Key 保存在本机
- **AND** Agent 说明不会把 SCM token、Cookie 或账号凭据发送给模型

### Requirement: 用户获得基于数据的回答
用户 SHALL 能通过 Agent 获得基于 App 数据和可用查询源整理出的中文建议。

#### Scenario: 查询游戏资料
- **WHEN** 用户询问船只、矿物、蓝图、任务或维科洛兑换问题
- **THEN** Agent 查询相关本地或远程数据
- **AND** Agent 用中文整理回答

#### Scenario: 数据不足
- **WHEN** App 没有命中相关数据或远程查询不可用
- **THEN** Agent 明确说明数据不足或对应来源暂不可用
- **AND** 不把不确定内容说成确定结论

### Requirement: 用户的 SCM 消息不受影响
用户 SHALL 在使用 Agent 时不影响现有 SCM 私聊和未读消息体验。

#### Scenario: 使用 Agent 聊天
- **WHEN** 用户与 Agent 对话
- **THEN** SCM 会话列表不新增 Agent 会话
- **AND** SCM 未读数不因 Agent 消息变化
- **AND** SCM 私聊仍按原逻辑工作
