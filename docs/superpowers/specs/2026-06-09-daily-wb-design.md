# 每日 WB（官网限时折扣船）查询功能 — 设计

> 日期：2026-06-09
> 关联代码：`BlueprintDataRepository`（架构模板）、`RsiInventoryClient`（RSI token 套路）、`tools/gen_*.py`（导出脚本风格）

## 1. 目标

在 app 内查询 RSI 官网 pledge store 上带 **warbond** 标记的限时折扣船（每几小时可能变化），
展示船名（中英）、缩略图、WB 优惠价 / 原价、跳转官网购买链接。提供「同步」按钮拉取最新数据。

## 2. 核心决策：无后台，用 GitHub Actions 当「穷人后台」（方案 B）

app 自身**不直连 RSI**。脆弱的抓取/解析逻辑全部放在一个定时 CI 脚本里，在服务器端跑一次，
产出一份静态 `daily_wb.json` 托管在 public GitHub repo 的 raw 地址；app 的同步按钮只是去拉这份 JSON。

理由：
- RSI 接口需 CSRF token、字段会变。放 CI：接口变了改脚本即可，**不用发版重新上架 app**。
- 反爬只需在 CI 一处对付一次，而不是每台用户手机各闯一次。
- 与现有 `BlueprintDataRepository`（`filesDir 缓存 > assets 快照` + `refreshFromRemote`）模式完全一致。

**已打通的链路（2026-06-09 实测并落地于 `tools/export_daily_wb.py`）：**

warbond 优惠价/原价走的是**升级商店 v2 端点**，不是普通 `/graphql`。全自动匿名链路（无登录、无 Cloudflare）：

1. `GET  /en/pledge` → `Set-Cookie: Rsi-Token` + HTML `<meta name="csrf-token">`。
2. `POST /api/account/v2/setAuthToken` `{}` → `Rsi-Account-Auth`（匿名，USD 定价）。
3. `POST /api/ship-upgrades/setContextToken` `{}` → `Rsi-ShipUpgrades-Context`（mode:browse）。
4. `POST /pledge-store/api/upgrade/v2/graphql`，query `{ to{ ships{ id name skus{ id title price upgradePrice } } } }`
   → 全船 + SKU 价格（**price 单位是分**）。2~4 都带 `x-csrf-token` 头 + 累积 cookie。
5. `POST /graphql` 的 `store(name:"pledge",browse:true){ search(query:$query){...RSIShip{ id url imageComposer{slot url} }}}`
   → 按 ship id 关联补 购买链接 + 缩略图。

**warbond 船 = upgrade v2 里某 ship 的 skus 中有 `title` 含 "Warbond" 的**（通常 2 个 sku：
Warbond Edition=优惠价、Standard Edition=原价）。ship id 在 v2 与 browse `/graphql` 一致，可按 id join。
今日实测 4 船：Ironclad WB$525/原$600、Ironclad Assault WB$575/原$650、M80 WB$275/原$300、Tiburon WB$700/原$775。

## 3. 托管

`Hurtblack/BUGSC` 已是 **public** 仓库，直接用它托管，不另开仓库。在同一仓库内：
- `tools/export_daily_wb.py` — 抓取脚本（与其它 `gen_*.py` 同目录）
- `.github/workflows/wb.yml` — 定时任务（每 6 小时），跑脚本并把产出 commit 回仓库
- `wb/daily_wb.json`（仓库根的 `wb/` 目录）— 脚本产出的**线上热更新数据**，cron 提交
- `app/src/main/assets/wb/daily_wb.json` — 打包进 app 的**兜底快照**（首次/离线可用）

抓的是公开店面，**无需任何登录密钥**，workflow 用默认 `GITHUB_TOKEN` 即可 commit。
app 端 WB 专用 `RemoteConfig.baseUrl` 指向 `https://raw.githubusercontent.com/Hurtblack/BUGSC/main/wb`。

## 4. 数据契约 `daily_wb.json`

```json
{
  "version": 1,
  "generatedAt": "2026-06-09T08:00:00Z",
  "items": [
    {
      "nameEn": "Drake Cutlass Black",
      "warbondPrice": 110.0,
      "standardPrice": 125.0,
      "currency": "USD",
      "url": "https://robertsspaceindustries.com/en/pledge/...",
      "thumbnail": "https://media.robertsspaceindustries.com/..."
    }
  ]
}
```

- **中文船名不在脚本里做**。脚本只吐 `nameEn`。app 端在展示时用**已有的船名翻译表**
  （ShipFit aliases / sccraft translations）把 `nameEn → 中文`。降低耦合，避免数据仓库重复维护名表。
- 价格单位为美元主价；`standardPrice` 缺失时 UI 只显示 WB 价。
- `version` 单调递增（用 `generatedAt` 或自增），供 app 端 `缓存 > assets` 取高逻辑用。

## 5. 容错（脚本侧——绝不发布空数据）

脚本若发生以下任一，**非零退出且不覆盖旧 `daily_wb.json`**：
- 拿不到 token / GraphQL 报错；
- 解析出 0 条 warbond 船（疑似 schema 变更）。

CI 失败即告警（GitHub 默认邮件），用户端继续用上一份有效数据。

## 6. Android 侧

照 `BlueprintDataRepository` 模式新增，**不引入新网络库**（沿用 `HttpURLConnection` + `org.json`）：

- **`WbRepository`**
  - `loadWbItems(): List<WbItem>` — `filesDir 缓存 > assets/wb/daily_wb.json 内置快照`，按 version 取高，纯本地。
  - `refreshFromRemote()` — 阻塞式（调用方放 `Dispatchers.IO`），拉远程 JSON 写入 filesDir；失败抛出，UI 回退缓存。
  - 复用 app 已有的船名翻译表把 `nameEn → 中文`。
- **`WbFragment`** — 列表（船名中英 + 缩略图 + WB 价/原价 + 购买跳转）+ 顶部「同步」按钮。
  - 同步：禁用按钮 → `Dispatchers.IO` 调 `refreshFromRemote()` → 刷新列表 → toast 成功/失败。
  - 列表项点击 → `Intent.ACTION_VIEW` 打开 `url`。
  - 缩略图加载沿用 app 现有图片加载方式（与蓝图列表缩略图一致）。
- **入口** — 在 `ToolsFragment` 工具列表新增一张「每日WB」卡片，点进 `WbFragment`。
- **内置兜底快照** — 打包一份 `assets/wb/daily_wb.json`（可为空 items 或一份样例），保证首次离线可用。

## 7. 测试

- 脚本：本地运行产出真实 `daily_wb.json`，肉眼核对若干条与官网一致。
- `WbRepository`：单测喂 assets 快照，验证解析 / version 取高 / 名表对齐 / 远程失败回退。

## 8. 交付边界

- 我（Claude）实现：`tools/export_daily_wb.py`、`.github/workflows/wb.yml`、`WbRepository`、`WbFragment`、工具页卡片、assets 兜底快照。
- 你（用户）操作：把改动 push 到 BUGSC；在仓库 Settings → Actions 确认 workflow 权限可写（commit 回仓库）；首次手动触发跑一次验证。

## 9. 不做（YAGNI）

- 不做客户端直连 RSI 实时刷新（已确认快照够用）。
- 不做折扣百分比标签（用户未选）。
- 不做登录态 / 机库 / CCU 相关（与本功能无关）。
