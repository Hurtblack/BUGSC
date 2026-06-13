# SCM / RSI 双登录账号体系设计

> 日期：2026-06-14
> 范围：把"登录"从各功能里抽出，做成 RSI + SCM 两个独立账号模块，统一门禁与返回，Profile 双账号展示，并把库存/角色修复搬家。
> 关联：[SCM 账号体系对接笔记](../../scm-account-integration.md)（接口字段来源）。
> 架构方案：**A —— 两个独立账号模块 + 一层共用门禁**（不抽象统一接口，尊重 RSI=cookie抓取 / SCM=REST token 的本质差异）。

## 1. 背景与现状

- 底栏 4 Tab：工具(ToolsFragment) / 资讯(NewsFragment) / 查询(QueryFragment) / 我的(ProfileFragment)。
- **RSI 登录现状**：登录 UI 直接嵌在 `InventoryFragment`，走 `RsiInventoryClient.login()` 打 RSI `api/launcher/v3/signin`（返回 `LoginResult`，含 `needCode` 二次验证 / `needCaptcha`），成功后把 `RsiSession` 存进 `RsiCookieStore`（cookie）。`CharacterRepairFragment` 自己没有登录，`injectIntoWebView` 复用已存 cookie，未登录只能干等。两者都挂在「我的」页。
- **SCM 登录现状**：无。交易市场(MarketFragment，挂在「查询」)目前只读浏览、公开 API。
- 已有单测基建：`app/src/test`，JUnit（参考 `ImageDiskCacheTest`、`ImageLoadRetryTest`）。

**本轮目标**：双登录模块 + 搬家 + Profile 双账号 + 统一门禁。**不含**交易下单/支付（接口未到，仅预留 SCM 门禁挂点）。

## 2. 导航结构与搬家

### 2.1 新增 destination（nav_graph.xml）
| Fragment | 作用 |
|---|---|
| `RsiLoginFragment` | RSI 独立登录页（账密 + 验证码 + 二次验证），从 InventoryFragment 抽出 |
| `ScmLoginFragment` | SCM 邮箱密码登录 + 文字点选验证码，含"去注册 / 忘密"入口 |
| `ScmRegisterFragment` | SCM 邮箱验证码注册 |
| `ScmPasswordFragment` | 忘密重置 / 改密（按 mode 区分，复用部分 UI） |

### 2.2 搬家（改入口）
- **工具(ToolsFragment)**：加卡「角色修复」→ `CharacterRepairFragment`。
- **查询(QueryFragment)**：加卡「库存查看」→ `InventoryFragment`。
- **我的(ProfileFragment)**：移除库存/角色两张卡，换成 RSI 账号卡 + SCM 账号卡（见 §6）。

### 2.3 Fragment 瘦身
- `InventoryFragment`：登录 UI 整段移到 `RsiLoginFragment`；自身只保留"库存展示 + 进页门禁跳转"。
- `CharacterRepairFragment`：改为进页门禁跳转（未登录 → RsiLoginFragment）。

### 2.4 登录页返回参数
- 登录类 Fragment 接收导航参数 `returnDestId: Int`（返回目标 destination id，0/缺省表示无）。
- 登录成功：`returnDestId != 0` → `popBackStack` 回该页并触发原动作；否则留在原地（从 Profile 进入的场景）刷新 UI。

## 3. 账号 session 层

### 3.1 RSI（复用现有，最小改动）
- `RsiCookieStore`（已有）继续作为登录态来源：`loadSession().isLoggedIn`。
- `RsiInventoryClient.login()`（已有）继续负责 signin，调用方从 InventoryFragment 改为 `RsiLoginFragment`。
- 不抽象、不包接口。

### 3.2 SCM（新建）
- **`ScmAuthStore`**（object，对标 RsiCookieStore）：
  - 用 EncryptedSharedPreferences 存 `accessToken / refreshToken / expiresTime / userId / deviceId`。
  - `session(): ScmSession`、`isLoggedIn`、`save(resp)`、`clear()`、`ensureDeviceId()`（首启生成持久化 UUID，不可每次新建）。
- **`ScmApi`**（按现有网络栈，OkHttp/Retrofit）：
  - base = `https://flowcld.xyz/app-api`。
  - 拦截器统一加 `tenant-id: 1` + （存在时）`Authorization: Bearer <accessToken>`。
  - **401 → 自动 `POST /member/auth/refresh-token` 重试一次**；refresh 也失败 → `ScmAuthStore.clear()` 并发登录态变更。

### 3.3 门禁 helper（方案 A 核心胶水）
```kotlin
fun Fragment.requireRsiLogin(returnDestId: Int, action: () -> Unit)
fun Fragment.requireScmLogin(returnDestId: Int, action: () -> Unit)
```
- 已登录 → 直接 `action()`。
- 未登录 → `navigate(对应 LoginFragment, returnDestId)`，登录成功 pop 回后触发。
- 库存 / 角色修复进页调 `requireRsiLogin`；交易买卖按钮调 `requireScmLogin`（本轮接好门禁，下单动作待接口到位再填）。

### 3.4 登录态变更通知
- `ScmAuthStore.stateFlow`（SharedFlow/LiveData），RSI 侧同理暴露一个登录态变更信号。
- Profile 与功能页观察它，登录/登出后自动刷新，不手动串回调。

## 4. SCM 文字点选验证码控件 `ScmCaptchaView`

实测 `system/captcha/get` 返回 `wordList` + 无滑块图 → 当前部署为 **AJ-Captcha 文字点选(clickWord)**。

- 取图：`POST /system/captcha/get` `{captchaType:"clickWord"}` → `originalImageBase64`(310×155) + `wordList` + `token` + `secretKey`。
- 显示图，提示「依次点击：流、难、野」；监听点击，把触摸坐标**换算回原图 310×155 坐标系**，收集 `pointList = [{x,y},...]` → `pointJson`。
- 生成 `captchaVerification = Base64(AES/ECB/PKCS5(token + "---" + pointJson, secretKey))`；`secretKey` 为空时取 `token---pointJson` 明文。
- 回调 `onVerified(captchaVerification)`；登录 / 注册 / 发邮件三处复用。
- 点错或失败 → 重新 `get` 刷新一张。
- check 接口（`/system/captcha/check`，AJ-Captcha 标准协议，路径与 get 平级）字段待 system 模块导出确认；若服务端要求先 check 再用，则在 onVerified 前插入一次 check。

## 5. SCM 业务时序

所有响应为 `CommonResult{code,data,msg}`；`code != 0` 弹 `msg`；验证码类错误自动刷新 `ScmCaptchaView`。

### 5.1 登录（ScmLoginFragment）
1. （兜底）`GET /member/auth/login-captcha-required?email=` 判断是否要验证码（强制开则直接要）。
2. `ScmCaptchaView` 点选 → `captchaVerification`。
3. `POST /member/auth/emailLogin` `{email,password,captchaVerification,deviceId}`。
4. 新设备要求二次邮箱验证 → 先 `POST /member/auth/emailVerCode` 发码，UI 收 `emailVerCode` 后重发登录。
5. 成功 → `ScmAuthStore.save(resp)`（`AppAuthLoginRespVO{userId,accessToken,refreshToken,expiresTime}`）→ 按 `returnDestId` 返回。

### 5.2 注册（ScmRegisterFragment）
1. 输邮箱 → `ScmCaptchaView` → `POST /member/auth/emailVerCode {email,type,captchaVerification}` 发邮箱验证码。
2. 60s 倒计时重发；用户输邮箱码 + 设密码。
3. `POST /member/auth/verifyAndRegister {email,password,code,deviceId,captchaVerification?}` → 成功后用返回 token 自动登录（无 token 则跳登录页）。

### 5.3 忘密 / 改密（ScmPasswordFragment）
- 忘密（mode=reset）：邮箱 → `ScmCaptchaView` → `emailVerCode` → 输码+新密码 → `POST /member/auth/resetPassword`。
- 改密（mode=change，已登录）：旧密码+新密码 → `POST /member/auth/changePassword`。

## 6. Profile 双账号布局

「我的」页顶部两张账号卡（移除原库存/角色卡）；检查更新、隐私/协议/免责不动。沿用现有卡片样式与深色主题。

### 6.1 RSI 账号卡
- 未登录：标题「RSI 账号」+「未登录」+【登录】→ `RsiLoginFragment`(returnDestId=0)。
- 已登录：展示 RSI handle/用户名（复用现有 `loadCachedProfile()`/`profileTitle()`）+【退出登录】（清 RsiCookieStore）。
- 抓取式信息有限，仅展示账号名 + 登录态，不强求头像。

### 6.2 SCM 账号卡（`GET /member/user/get` → `AppMemberUserInfoRespVO`）
- 未登录：「SCM 账号」+「未登录」+【登录】【注册】。
- 已登录：`avatar` + `nickname` + `email`；副行展示 `sellOrderCount`/`buyOrderCount`、`sponsorLevel`、`rsiAccurate`。
- 操作：【退出登录】(`POST /member/auth/logout` + 清 ScmAuthStore)、【修改密码】→ ScmPasswordFragment(change)。
- 进页 / 登录态变化拉一次 `user/get`；401 走 refresh，再失败显示为未登录。

## 7. 测试策略

### 7.1 单元测试（TDD，先写测试）
- `ScmCaptchaCryptoTest`：固定 `token/secretKey/pointJson` → 断言 AES/ECB/PKCS5 Base64 结果；`secretKey` 空走明文分支。
- `CaptchaCoordTest`：触摸坐标 → 原图 310×155 换算（多种缩放比）。
- `ScmAuthStoreTest`：token 存/取/清；`deviceId` 首生成后持久化不变。
- `ScmResultParseTest`：`CommonResult` 解析；`emailLogin` 各分支（成功 / 要二次码 / 错误码→msg）映射。
- `ScmRefreshInterceptorTest`（MockWebServer）：401 自动 refresh 重试成功；refresh 失败则清 session。
- `LoginGatingTest`：已登录直接执行 action；未登录触发导航且不执行 action。

### 7.2 手动 / 联调清单
- SCM 全流程：点选验证码 → 登录 → 注册 → 忘密 → 改密 → 退出。
- 新设备触发邮箱二次验证码分支。
- 门禁：未登录点库存 → 跳 RSI 登录 → 登录后自动回库存加载；未登录点交易买卖 → 跳 SCM 登录 → 返回。
- RSI 抽取后回归：库存、角色修复仍能登录/使用。
- Profile 双卡登录态、登出、信息刷新。

## 8. 待补 / 依赖（不阻塞本轮）
- 订单/支付模块 OpenAPI（交易下单/成交/支付）—— 本轮只接 SCM 门禁挂点，不接下单动作。
- `/system/captcha/check` 确切字段（建议导出 system 模块）。
- 社交登录(social-*) 平台与回调约定（后续可选，本轮不做）。

## 9. 非目标（本轮明确不做）
- 交易下单 / 成交 / 支付。
- RSI 登录机制本身的改造（仅抽取相对位置，行为不变）。
- 社交快捷登录。
- 统一 AuthProvider 抽象（方案 B，已否决）。
