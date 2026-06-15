## Context

SCMobiGlas 已具备 SCM 登录态、自动刷新 Token、市场挂单列表与详情、一对一聊天页及 OkHttp WebSocket 收发能力。当前缺口是：市场详情仍通过网页完成交易；交易历史没有 App 入口；聊天只能从某个挂单的“联系”按钮进入，用户无法查看全部会话或在 App 前台获知其他会话的新消息。

后台 OpenAPI 已提供交易创建、重复交易检查、交易分页、交易详情、地址树、会话列表和未读数接口。唯一不闭合的契约是创建接口当前返回数据库主键 `Long`，而详情接口只接受字符串 `transactionNumber`。本设计以后台将创建成功响应调整为 `transactionNumber` 为前置条件。

现有 UI 以 Fragment + XML 为主，底部导航使用 Compose。新增页面继续沿用该结构，不引入新的 UI 框架或持久化数据库。

## Goals / Non-Goals

**Goals:**

- 用户可在现有市场详情页内对出售或求购挂单创建交易。
- 用户可查看自己的交易历史并按交易状态筛选。
- 用户可查看单笔交易的只读详情。
- SCM 账号页成为“我的交易”和“消息”的统一入口。
- 用户可查看聊天会话列表、未读数量，并进入现有聊天页。
- App 位于前台且 SCM 已登录时，通过 WebSocket 接收新消息事件并更新未读状态。
- 底部“个人信息”导航在存在未读消息时显示红点。

**Non-Goals:**

- 不支持在 App 内发布出售或求购挂单。
- 不支持取消、同意、发货、收货、评价、举报等交易状态操作。
- 不支持后台或进程被回收后的系统推送通知。
- 不新增本地数据库或离线交易历史缓存。
- 不为 OpenAPI 未定义的收货方式 `1..3` 虚构文案；本期仅提交网页当前使用的“面交”值。

## Decisions

### D1. 在市场详情页内嵌交易表单

`MarketDetailFragment` 在现有订单信息下方增加“交易设置”和“物品接收信息”区域，不创建独立下单页。表单仅对非本人挂单显示；未登录点击提交时复用 `requireScmLogin`。

字段包括：

- 数量：默认 `1`，范围 `1..remainingQuantity`。
- 收货位置：通过地址精简列表构建“星系 → 星球 → 站点”三级选择，提交末级 `locationId`，选中后显示末级站点名称。
- 运费：默认空，必填，必须是大于或等于 `0` 的 UEC 数值。
- 收货方式：显示只读“面交”，提交固定的后台枚举值 `0`。
- 交易总额：实时显示 `unitPrice × number`，运费单独显示，不计入交易总额。

出售和求购挂单均使用“收货位置”文案。

### D2. 将表单校验与金额计算抽成纯逻辑

新增 `TransactionDraft`、`TransactionValidation` 等纯 Kotlin 模型。UI 只负责读取输入和展示错误，数量边界、运费解析、必填项、总额计算由纯函数完成，避免校验散落在 Fragment。

提交顺序固定为：

1. SCM 登录校验。
2. 本地输入校验。
3. 调用 `/sc/order-transactions/check-ongoing`。
4. 已有进行中交易时停止创建，并提供“查看现有交易”入口。
5. 调用 `/sc/order-transactions/create`。
6. 成功后显示交易编号弹窗，用户可选择“查看详情”或留在当前页。

提交期间禁用按钮，防止双击重复请求。

### D3. 交易时间只作提醒，不作硬校验

复用订单的交易日、开始时间和结束时间计算当前是否处于交易时间。若不在交易时间，表单上方显示醒目提示“当前不在创建者交易时间内，建议先联系交易者”，但创建按钮保持可用，并保留现有“联系”入口。

时间判断异常或字段缺失时不阻止交易，仅不显示“当前在交易时间内”的肯定状态。

### D4. 后台创建接口返回交易编号

客户端将 `POST /sc/order-transactions/create` 的成功 `data` 按 `transactionNumber: String` 解析。后台需将当前 `CommonResultLong` 改为字符串或包含 `transactionNumber` 的响应对象。

客户端不使用“创建后查询最新分页记录”的方式推断交易编号，因为并发创建时可能匹配错误。若响应仍为纯数字数据库 ID，客户端显示明确的接口契约错误，不宣称创建详情可用。

### D5. 独立交易客户端与模型

新增 `TransactionClient`，统一通过 `ScmAuthStore.api()` 调用：

- `create(draft)`
- `checkOngoing(orderNumber)`
- `page(pageNo, pageSize, transactionStatus?)`
- `get(transactionNumber)`
- `addressList(language)`

新增 `TransactionRecord`、`TransactionPage`、`AddressNode` 等模型。JSON 解析集中在模型或客户端 companion object，Fragment 不直接解析响应。

### D6. 我的交易使用单列表与状态筛选

新增 `TransactionListFragment`，顶部提供“全部 / 进行中 / 已完成 / 已取消”筛选。筛选值分别映射为 `null / 1 / 2 / 3`，每次切换重置分页并从第一页加载。

列表项展示商品名称、交易编号、出售/求购类型、数量、金额、交易状态和创建时间。点击进入只读交易详情。列表支持继续加载、空状态和失败重试。

### D7. 交易详情严格只读

新增 `TransactionDetailFragment`，通过 `transactionNumber` 调用详情接口。页面展示：

- 商品与交易编号
- 出售/求购类型
- 买卖双方资料和在线状态
- 数量、交易金额、运费
- 收货位置和“面交”方式
- 交易状态与发货状态
- 创建、完成、取消和收货截止时间（存在时）

页面不调用更新状态、同意、取消或评价接口。

### D8. SCM 账号卡增加业务入口

登录状态下，`ProfileFragment` 的 SCM 账号卡增加“我的交易”和“消息”两个入口。消息入口显示精确未读数；未读为 `0` 时隐藏数字。退出登录后两个入口隐藏，并清空内存中的未读状态。

### D9. 新增聊天会话列表

新增 `ChatConversationListFragment`，调用 `/member/chat/conversations/list?limit=...`。列表项展示头像、昵称、在线状态、最后消息、最后消息时间和未读数量。点击后携带 `otherUserId` 与昵称进入现有 `ChatFragment`。

进入聊天页时使用 `/member/chat/conversations/{conversationId}/mark-read` 标记已读；返回会话列表或个人页时刷新未读总数。

### D10. 全局未读状态使用进程内单一数据源

新增 `ChatUnreadStore`，以 `StateFlow<Int>` 保存当前总未读数，提供：

- 从 `/member/chat/unread/count` 刷新；
- 收到新消息时递增或触发服务端重拉；
- 会话已读后刷新；
- SCM 退出登录时清零。

`ProfileFragment` 和 `MainActivity` 只观察该 Store，不各自维护未读副本。

### D11. 全局 WebSocket 与会话 WebSocket 分离

现有 `ChatSocket` 继续负责打开中的单一会话订阅、发消息和收消息。新增 `ChatInboxSocket` 或将底层连接抽成可配置组件，专门负责 App 前台全局新消息事件，不发送聊天内容。

生命周期规则：

- `MainActivity.onStart` 且 SCM 已登录时获取一次性 ticket 并建立全局连接。
- `MainActivity.onStop` 关闭连接，不使用前台服务常驻。
- Token 刷新或重新登录后重新取 ticket。
- 断线时使用有上限的指数退避重连；进入后台后停止重连。
- 收到 `new-chat-message` 后刷新 `ChatUnreadStore`，避免客户端自行推断服务端未读规则。

若后台 WebSocket 协议要求显式“全局订阅”，该订阅命令由后台提供；客户端不复用某个 `otherUserId` 伪造全局订阅。

### D12. 底部导航仅显示红点

扩展 `MobiGlasItem` 或 `MobiGlasBottomBar` 参数，使“个人信息”项可显示无数字红点。`MainActivity` 观察 `ChatUnreadStore.count > 0`，仅在 SCM 登录且未读大于零时显示。

账号卡的消息入口仍展示精确数字，实现“全局轻提醒 + 页面内精确计数”。

### D13. 错误处理遵循服务端可读消息

所有接口错误优先显示后端 `msg`，网络错误显示统一网络提示。地址加载失败允许点击重试；创建交易失败保留用户输入；列表加载失败不清空已有数据；详情失败显示错误状态而不是空白页。

401 继续由现有 `ScmApi` 自动刷新 Token；刷新失败时清除登录态并通过登录门禁返回 SCM 登录页。

## Test Strategy

### 值得测试的部分

- `TransactionDraft` 校验：数量最小值、最大可交易数量、运费空值/负数/非法格式、地点必选。
- 交易总额计算：只计算单价乘数量，不包含运费，并处理大额 UEC。
- 地址树构建：根据 `parentId` 正确生成三级结构、过滤非末级选择、输出末级站点名称与 ID。
- `TransactionClient` JSON 解析：创建响应必须取得字符串交易编号；分页、详情、地址和服务端错误消息解析。
- 交易筛选映射和分页状态：全部/进行中/已完成/已取消映射、切换筛选后重置。
- `ChatClient` 会话列表和未读数解析。
- `ChatUnreadStore`：刷新、收到消息、已读刷新、退出清零。
- WebSocket 重连策略的纯状态部分：前台允许重连、后台停止、重试上限和延迟增长。
- 交易时间判断：交易日、跨天时间、缺失或异常字段。

### 不适合单元测试的部分

- Fragment 的 XML 控件绑定、弹窗和导航跳转属于 Android UI 胶水代码，使用真机/模拟器端到端验证。
- OkHttp WebSocket 的真实握手、ticket 有效性和后台全局订阅协议依赖线上服务，使用集成验证。
- Compose 底部导航红点的视觉位置与动画使用截图或人工观察验证。

### 结论

本次变更需要新增单元测试。核心输入校验、金额计算、地址层级、API 解析、筛选分页和未读状态必须由 JVM 单元测试覆盖；真实 API、WebSocket 和页面交互通过模拟器或真机验证。

## Risks / Trade-offs

- [创建接口仍返回数据库 ID] → 将后台返回 `transactionNumber` 作为发布前置条件；客户端检测纯数字旧契约并提示，不做不可靠的分页匹配。
- [全局 WebSocket 协议未明确订阅命令] → 在实现前与后台确认连接成功后是否自动接收当前用户全部消息；协议不明确时仅通过前台定时/页面刷新未读数，不能假装实时。
- [Android 前后台切换导致连接频繁重建] → 仅在 `onStart/onStop` 管理连接，并采用有上限退避；不使用常驻前台服务。
- [地址数据层级可能不是严格三级] → 内部按通用树处理，UI 展示前三层并允许末级节点提前结束，避免因脏数据无法选择。
- [交易记录字段存在空值或时间格式差异] → 模型采用可空字段和兼容解析，详情页只展示存在的数据。
- [大额 UEC 使用浮点数产生精度问题] → 金额在客户端以 `BigDecimal` 解析和计算，提交时按后端接受的十进制 JSON 数值输出。
