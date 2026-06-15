## Why

当前 Android App 已能浏览 SCM 市场挂单和与交易者私聊，但用户仍需跳转网页才能发起实际交易，也缺少统一入口查看历史交易与未读消息。本次变更补齐 App 内交易创建、交易查询和消息中心闭环，使现有市场与账号体系真正可用于日常交易。

## What Changes

- 在现有市场详情页内增加交易设置表单，支持数量、三级收货地点、运费和“面交”收货方式。
- 对出售和求购挂单调用 `/sc/order-transactions/create` 发起交易，并在提交前校验 SCM 登录状态、数量范围、必填字段和重复进行中交易。
- 非创建者交易时间仍允许提交，但显示醒目提示并引导用户联系交易者。
- 创建成功后显示交易编号，并允许进入只读交易详情页。
- 新增“我的交易”列表，支持全部、进行中、已完成、已取消筛选，并可进入交易详情。
- 在 SCM 账号卡增加“我的交易”和“消息”入口。
- 新增聊天会话列表，展示最后消息、时间和未读数量，并进入现有一对一聊天页。
- App 前台维持全局聊天 WebSocket，收到新消息时更新会话未读数；消息入口显示数量，底部“个人信息”导航显示红点。
- **BREAKING（后台 API 契约）**：`POST /sc/order-transactions/create` 的成功返回值需由交易主表数据库 ID 调整为 `transactionNumber` 字符串，或返回包含 `transactionNumber` 的对象。
- 不在本期实现发布新的出售/求购挂单、交易取消、发货、收货、同意收购或后台系统推送。

## Capabilities

### New Capabilities

- `market-transaction`: 在市场挂单详情中创建交易，查看交易历史和只读交易详情。
- `chat-inbox`: 在 SCM 账号体系中提供会话列表、未读数量和 App 前台实时消息提醒。

### Modified Capabilities

无。项目当前没有已登记的 OpenSpec capability。

## Impact

- Android 市场模块：`MarketDetailFragment`、市场模型与 API 客户端、市场详情布局。
- Android SCM 账号模块：`ProfileFragment`、账号卡布局、登录门禁。
- Android 聊天模块：`ChatClient`、`ChatSocket`、现有聊天页及新增会话列表。
- Android 顶层导航：`MainActivity`、Compose 底部导航红点状态、Navigation Graph。
- 新增交易 API 客户端、交易列表/详情页面、三级地址选择组件及对应布局。
- 后台接口：`/sc/order-transactions/create` 返回契约需要调整；其余复用现有交易、地址、聊天和未读接口。
- 测试：新增交易请求/响应解析、金额与输入校验、地址树构建、筛选状态、会话与未读状态的单元测试。
