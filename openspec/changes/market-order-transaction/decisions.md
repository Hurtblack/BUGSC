# Decisions: market-order-transaction

> 本文件记录 explore 阶段的所有需求决策。
> 由 edc-sdd:explore 自动维护，propose 阶段将读取本文件作为输入。

## Decision 1: 下单入口与页面形态
**Status:** [DECIDED]
**Options:**
- A. 在现有市场物品详情页内增加交易设置表单
- B. 跳转到独立下单页面
- C. 继续打开网页市场完成交易
**Choice:** A
**Reason:** 用户希望沿用现有物品详情页风格，在 App 内选择交易参数后直接调用后台 API。

## Decision 2: 创建交易的核心流程
**Status:** [DECIDED]
**Options:**
- A. SCM 登录校验后，选择数量、收货位置、运费和收货方式，调用 `/sc/order-transactions/create`
- B. 仅展示订单信息，不在 App 内提交
**Choice:** A
**Reason:** OpenAPI 已提供创建交易接口，必填字段与网页表单一致。

## Decision 3: 本版本交易功能范围
**Status:** [DECIDED]
**Options:**
- A. 仅完成创建交易，成功后显示交易编号
- B. 创建交易并提供交易详情查看
- C. 完整交易管理，包含详情、发货、收货、取消和收购订单同意
**Choice:** B
**Reason:** 本版本先形成下单与查看结果的闭环，暂不引入交易状态操作。

## Decision 4: 创建成功后的反馈
**Status:** [DECIDED]
**Options:**
- A. 成功后立即跳转交易详情页
- B. 弹窗显示交易编号，用户点击“查看详情”后跳转
- C. 留在商品详情页，仅提示创建成功
**Choice:** B
**Reason:** 用户可以确认交易已创建，同时保留留在商品详情页的选择。

## Decision 5: SCM 账号页业务入口
**Status:** [DECIDED]
**Options:**
- A. 增加“我的交易”和“消息”两个入口
- B. 仅增加“我的交易”
- C. 不在账号页增加入口
**Choice:** A
**Reason:** 交易详情后续需要从历史订单再次进入，现有私聊也需要统一的会话列表入口。

## Decision 6: 本版本是否同时实现两个列表页
**Status:** [DECIDED]
**Options:**
- A. 同时实现“我的交易”列表和“消息”会话列表
- B. 本版本只实现“我的交易”，消息入口后续补充
**Choice:** A
**Reason:** 私聊基础能力已经存在，本版本补齐会话列表入口，与交易历史共同形成 SCM 账号业务中心。

## Decision 7: 我的交易列表组织方式
**Status:** [DECIDED]
**Options:**
- A. 单列表，顶部筛选全部、进行中、已完成、已取消
- B. 使用三个分页签分别展示状态
- C. 只展示全部交易，用状态标签区分
**Choice:** A
**Reason:** 保留完整历史，同时沿用简单列表结构，减少页面复杂度。

## Decision 8: 下单表单字段
**Status:** [DECIDED]
**Options:**
- A. 数量、收货位置、运费、收货方式
- B. 仅数量和收货位置
**Choice:** A
**Reason:** 与网页现有交易表单及 `/sc/order-transactions/create` 必填字段保持一致。

## Decision 9: 收货位置与方式展示
**Status:** [DECIDED]
**Options:**
- A. 地址选择保留层级选择，选中后仅展示末级站点；收货方式当前固定为“面交”
- B. 展示完整地址路径，并开放四种未知收货方式
**Choice:** A
**Reason:** 网页当前行为是层级选择后展示末级地点，收货方式目前只有“面交”；不能为未定义枚举虚构文案。

## Decision 10: 后台消息提醒策略
**Status:** [DECIDED]
**Options:**
- A. 本版本只做 App 前台 WebSocket、会话红点和未读数
- B. 本版本同时接入系统推送，支持 App 后台或进程被回收后收到通知
**Choice:** A
**Reason:** 复用现有 OkHttp WebSocket 完成前台实时提醒；离线系统推送需要后台配合，留待后续独立实现。

## Decision 11: 未读提醒位置
**Status:** [DECIDED]
**Options:**
- A. 仅 SCM 账号卡的消息入口显示未读数字
- B. 消息入口显示未读数字，底部个人导航显示红点
- C. 两处都显示未读数字
**Choice:** B
**Reason:** 账号页提供精确未读数，底部导航提供全局但不干扰布局的提醒。

## Decision 12: 重复进行中交易处理
**Status:** [DECIDED]
**Options:**
- A. 检测到已有进行中交易时禁止重复下单，并提供查看现有交易入口
- B. 提示风险后仍允许继续创建
- C. 不预检查，完全依赖创建接口
**Choice:** A
**Reason:** 使用 `/sc/order-transactions/check-ongoing` 提前阻止重复交易，减少误操作。

## Decision 13: 非交易时间下单
**Status:** [DECIDED]
**Options:**
- A. 仍允许下单，显示醒目提示并建议联系交易者
- B. 禁止下单，等待进入交易时间
- C. 二次确认后允许下单
**Choice:** A
**Reason:** 交易时间用于提示双方活跃时段，不作为创建交易的硬限制；页面复用现有“联系”按钮。

## Decision 14: 交易详情页能力
**Status:** [DECIDED]
**Options:**
- A. 只读展示交易双方、数量、金额、地点、运费、收货方式和状态
- B. 额外提供取消交易
- C. 根据身份提供完整状态操作
**Choice:** A
**Reason:** 本版本聚焦创建交易和查看详情，不扩展交易状态流转。

## Decision 15: 收货位置选择方式
**Status:** [DECIDED]
**Options:**
- A. 星系、星球、站点三级联动
- B. 可搜索列表展示完整路径，选中后表单仅显示末级站点
- C. 普通下拉列表仅展示末级站点
**Choice:** A
**Reason:** 用户更正选择，要求沿用网页的星系、星球、站点层级选择方式；最终表单显示末级站点。

## Decision 16: 求购订单的位置字段文案
**Status:** [DECIDED]
**Options:**
- A. 出售订单显示“收货位置”，求购订单显示“发货位置”
- B. 出售和求购订单都沿用网页文案“收货位置”
- C. 本版本不支持向求购订单出售
**Choice:** B
**Reason:** 与网页现有字段文案保持一致，减少 App 与网页行为差异。

## Decision 17: 数量与运费默认值
**Status:** [DECIDED]
**Options:**
- A. 数量默认 1，运费默认 0
- B. 数量默认 1，运费留空且必须手动填写
- C. 数量和运费都留空
**Choice:** B
**Reason:** 数量提供安全默认值，运费要求用户明确确认后再提交。

## Decision 18: 交易总额计算
**Status:** [DECIDED]
**Options:**
- A. 交易总额等于单价乘数量，运费单独显示
- B. 交易总额包含运费
- C. 同时显示商品金额和应付合计
**Choice:** A
**Reason:** 与网页当前展示行为保持一致，避免改变用户对交易金额字段的理解。

## Decision 19: create 返回值语义
**Status:** [DECIDED]
**Options:**
- A. 返回的 Long 是数据库主键 id
- B. 返回的 Long 是交易编号 transactionNumber，仅类型标错
- C. 不确定，需要后台确认
**Choice:** A
**Reason:** 后台确认「long 只有 id 和地点」，transactionNumber 是字符串编号（T2025013100001 形式），不可能是 Long。故 create 返回的 Long 为数据库主键 id，与 /get 所需的 transactionNumber 是两个不同标识。

## Decision 20: 创建成功后如何进入详情（id → 详情 桥接）
**Status:** [DECIDED]
**Options:**
- A. 后台提供/已有「按 id 查交易详情」接口，create 后直接用 id 查
- B. 推动后台让 create 直接返回 transactionNumber（或同时返回）
- C. 创建成功后刷新「我的交易」列表，从列表项取 transactionNumber 再进详情
- D. 不确定，需继续问后台
**Choice:** B
**Reason:** 创建后可以直接可靠显示交易编号并跳转详情，避免通过分页结果进行并发条件下不可靠的记录匹配。该接口契约变更需要后台配合。

## Decision 21: 是否实现发布挂单（求购/卖出）
**Status:** [DECIDED]
**Options:**
- A. 本期不实现发布挂单，App 只对已有挂单发起交易；发布求购/卖出仍走网页
- B. 本期一并实现 App 内发布求购/卖出挂单
**Choice:** A
**Reason:** 业务模型分两层——挂单层（发布求购/卖出）与交易层（响应挂单发起交易）。本期范围只覆盖交易层（创建交易、查看详情、我的交易、消息），不包含挂单层的发布功能。App 只「消费」挂单，不「生产」挂单。
