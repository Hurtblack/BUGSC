## 1. 交易核心模型与 API

Constraints: D2, D4, D5, D13

- [x] 1.1 在 `app/src/test/java/com/euedrc/bugsc/market/transaction/` 编写交易草稿校验、金额计算、地址树、筛选状态和交易时间的失败测试
- [x] 1.2 新增 `TransactionModels.kt`、`TransactionRules.kt` 和 `TransactionClient.kt`，实现纯逻辑、请求构造及 JSON 解析
- [x] 1.3 编写创建响应、分页、详情、地址和错误消息解析测试，并检测旧的纯数字 ID 契约

Verify: `./gradlew :app:testDebugUnitTest --tests 'com.euedrc.bugsc.market.transaction.*'`

## 2. 市场下单与交易页面

Constraints: D1, D3, D6, D7, D8

- [x] 2.1 修改 `fragment_market_detail.xml` 和 `MarketDetailFragment.kt`，加入交易表单、三级地点选择、重复交易检查和成功弹窗
- [x] 2.2 新增 `TransactionListFragment.kt` 与布局，实现状态筛选、分页、空状态和重试
- [x] 2.3 新增 `TransactionDetailFragment.kt` 与布局，实现只读交易详情
- [x] 2.4 更新 `nav_graph.xml` 和 `ProfileFragment`/`fragment_profile.xml`，加入“我的交易”入口

Verify: `./gradlew :app:assembleDebug`

## 3. 消息中心与未读状态

Constraints: D9, D10, D11, D13

- [x] 3.1 先编写会话列表解析、未读状态和重连策略失败测试，再实现 `ChatUnreadStore` 与纯重连状态
- [x] 3.2 扩展 `ChatClient` 的会话列表、未读数和标记已读接口
- [x] 3.3 新增 `ChatConversationListFragment.kt` 与布局，并接入现有 `ChatFragment`
- [x] 3.4 新增前台 `ChatInboxSocket`，在 `MainActivity.onStart/onStop` 管理连接

Verify: `./gradlew :app:testDebugUnitTest --tests 'com.euedrc.bugsc.chat.*' && ./gradlew :app:assembleDebug`

## 4. 导航红点与账号卡联动

Constraints: D8, D10, D12

- [x] 4.1 扩展 `MobiGlasBottomBar.kt`，支持个人导航无数字红点
- [x] 4.2 修改 `MainActivity.kt` 观察总未读状态并更新红点
- [x] 4.3 修改 `ProfileFragment.kt` 展示消息未读数，并在退出 SCM 时清零

Verify: `./gradlew :app:assembleDebug`

## 5. [Review] 审查分组 1-4

Review Targets: 分组 1-4 改动的全部交易、聊天、账号、导航和测试文件

- [x] 5.1 **D1-D3 遵循**：详情页内嵌完整交易表单，校验顺序固定，非交易时间只提示不禁用
  验证：表单字段、总额、提交顺序、重复交易和交易时间行为与设计一致
- [x] 5.2 **D4-D7 遵循**：创建响应使用交易编号，历史筛选正确，详情严格只读
  验证：不存在分页猜测编号或交易状态更新调用
- [x] 5.3 **D8-D12 遵循**：账号入口、会话列表、单一未读状态、前台 socket 和导航红点一致
  验证：退出清零，后台关闭连接，不使用常驻服务
- [x] 5.4 **Scenario 对齐**：有效创建、无效输入、重复交易、筛选详情、会话未读和前后台切换
  验证：实现行为逐项对应 specs 中 WHEN/THEN
- [x] 5.5 **BREAKING 影响面已处理**：创建接口旧 Long 响应不会被误当交易编号
  验证：客户端检测旧契约并返回明确错误
- [x] 5.6 **无越界改动**：改动文件列表属于 proposal Impact 范围
- [x] 5.7 **构建通过**：`./gradlew :app:assembleDebug :app:testDebugUnitTest`
- [ ] 5.8 **无新增 lint 警告**：`./gradlew :app:lintDebug`
  当前仍有本次新增界面的国际化、可访问性等 warning；14 个阻断 error 均来自未修改的 `ImageDiskCache.kt` 与 `ShipFitCodec.kt`。
