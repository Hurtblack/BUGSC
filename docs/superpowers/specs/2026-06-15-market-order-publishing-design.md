# SCM 市场发布与我的挂单设计

## 范围

本次实现 App 内发布 SCM 市场挂单和管理自己的挂单。支持出售单、求购单、单物品订单、多物品清单订单、品质数值、物品图片上传审核。不支持以物易物，后续再用同一套物品行组件扩展 `exchangeItemsList`。

## 入口

`MarketFragment` 右下角显示固定 `+` 按钮。点击后复用 `requireScmLogin`：

- 已登录：进入 `MarketOrderEditFragment` 创建模式。
- 未登录：跳 `ScmLoginFragment`，登录成功后返回创建页。

`ProfileFragment` 的 SCM 账号业务区增加“我的挂单”入口，进入 `MyMarketOrdersFragment`。

## 创建订单

创建页使用原生 XML + Fragment，沿用现有 HUD 样式。字段：

- 订单类型：出售 `creatorType=1`、求购 `creatorType=0`。
- 交易类型：本版固定货币交易。
- 物品列表：通过 `/product/items/item-search?name=...&language=CN` 搜索并添加，至少 1 个。
- 每个物品：数量、单价、品质数值、图片上传状态/按钮。
- 地点：复用 `/sc/address/simple-list?language=CN` 三级选择。
- 有效期：7 天 `0`、14 天 `1`、永久 `2`。
- 状态：默认上架 `status=1`，可切换下架 `0`。

提交规则：

- 单物品订单：直接 `POST /sc/orders/create`，body 带 `items`。
- 多物品订单：先 `POST /sc/item-list/create` 创建清单，再 `POST /sc/orders/create`，body 带 `manifestId` 和 `items`。
- 品质数值写成 `qualityData={"quality":N}`，N 必须在 `0..1000`。未填写不传。
- 创建接口返回 `Long` 主键，不返回 `orderNumber`。创建成功后调用 `/sc/orders/page-of-own?pageNo=1&pageSize=20&ignoreStatus=true&includeDeleted=false&language=CN`，按当前用户最新订单找回 `orderNumber`，失败时仍提示创建成功并引导去“我的挂单”。

## 我的挂单

`MyMarketOrdersFragment` 使用 `/sc/orders/page-of-own` 展示自己的挂单，支持出售/求购筛选和继续加载。列表展示物品名、订单编号、数量、单价、上架状态、创建时间。

操作：

- 点击卡片进入现有 `MarketDetailFragment`。
- 编辑：打开 `MarketOrderEditFragment` 编辑模式，调用 `/sc/orders/update` 更新单价、数量、状态、有效期和品质。
- 上架/下架：调用 `/sc/orders/orderStatus?orderNumber=...&status=...`。
- 删除：确认后调用 `/sc/orders/delete?orderNumber=...`。
- 补数量：调用 `/sc/orders/quantity-add?orderNumber=...`。

编辑模式第一版只编辑订单级字段和第一个物品品质，清单物品组成通过后续清单编辑页扩展。

## 图片上传

物品行提供“上传图片”入口：

1. 查询 `/member/item/image/status?itemId=...`。
2. 若 `data.status == 0`，显示“审核中”并禁用上传。
3. 选择本地图片，支持 `image/jpeg`、`image/png`、`image/webp`。
4. 大小必须小于等于 1MB；不在本版做自动压缩。
5. 调 `GET https://scmitem.flowcld.com/generate-key` 获取一次性 `tempKey`。
6. 调 `POST https://scmitem.flowcld.com` multipart 上传 `image`、`itemId`、`tempKey`。
7. Worker 成功后自动创建审核记录，App 提示“提交成功，等待审核”并刷新状态。

App 不直接调用 `/member/item/image/submit`。

## 测试

新增纯逻辑单测覆盖：

- 发布表单校验：物品、数量、单价、品质、地点、有效期。
- `qualityData` JSON 生成。
- 单物品和多物品创建 body。
- 图片状态是否允许上传。
- 创建成功后从我的挂单列表选择最新订单编号。

