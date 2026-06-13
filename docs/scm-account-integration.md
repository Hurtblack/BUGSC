# SCM 账号体系对接笔记（登录 / 注册 / 个人信息）

> 数据来源：网站开发(Rookie) 提供的 `默认模块.openapi.json`（member 模块，148 接口）+ 实测 `system/captcha/get`。
> 适用：在「个人」页加 SCM 登录/注册/个人信息，并为后续 SCM 交易打基础。
> 状态：登录/注册/个人信息字段已确认；**订单/支付模块未拿到**（见文末 TODO）。

## 0. 基本约定

- **Base URL**：`https://flowcld.xyz/app-api`
- **租户头**：所有请求带 `tenant-id: 1`
- **鉴权头**：登录后所有受保护接口带 `Authorization: Bearer <accessToken>`
- **Session 模型**：登一次拿到 `accessToken`，**个人信息 / 交易 / IM / WebSocket 全部复用同一个 token**。交易不需要单独登录，依赖的就是这个登录态。

## 1. 整体结论

| 能力 | 方案 | WebView？ |
|---|---|---|
| 人机验证码 | AJ-Captcha **文字点选**，原生画图+点击+AES | ❌ 不需要 |
| 邮箱密码登录 | 原生表单 | ❌ |
| 邮箱验证码注册 | 原生表单 | ❌ |
| 个人信息 | 原生 | ❌ |
| 社交登录(QQ/微信/Discord) | OAuth 跳转（后续可选） | 仅此项需要 |

**整条核心链路纯原生，零 WebView。** Cloudflare Turnstile 后端未开启，无需处理。

---

## 2. 人机验证码：AJ-Captcha 文字点选（强制）

后端强制开启。实测 `captchaType=blockPuzzle` 也只返回文字点选数据 → 当前部署就是**文字点选(clickWord)**：图上散布文字，用户按 `wordList` 顺序点击。

### 2.1 取验证码
`POST /system/captcha/get`
```json
{ "captchaType": "clickWord" }
```
返回 `data`：
| 字段 | 含义 |
|---|---|
| `token` | 本次验证码会话 token，后续 check 要回传 |
| `secretKey` | AES 密钥（16位），用于加密坐标 |
| `originalImageBase64` | 背景图 PNG(base64)，实测尺寸 **310×155** |
| `wordList` | 需依次点击的文字，如 `["流","难","野"]` |

> 注意：`secretKey` 可能为空。**为空时不加密**，直接传明文 pointJson。

### 2.2 原生实现步骤
1. base64 解码 `originalImageBase64` → Bitmap → ImageView 显示，提示「请依次点击：流、难、野」。
2. 监听点击，记录每次点击在 **原图 310×155 坐标系** 下的 `{x, y}`（ImageView 缩放显示时要把触摸坐标换算回原图分辨率）。
3. 收集成 pointList：`[{"x":140,"y":80},{"x":210,"y":50},...]`，转成 `pointJson` 字符串。
4. 计算 `captchaVerification`：
   - AJ-Captcha 规则：`captchaVerification = AES_encrypt(token + "---" + pointJson, secretKey)`，结果 Base64。
   - AES 模式 `AES/ECB/PKCS5Padding`，key=`secretKey`(UTF-8 16字节)。
   - `secretKey` 为空则 `captchaVerification = token---pointJson`（不加密）。
5. （可选）`POST /system/captcha/check` 先校验，body `{ token, pointJson(加密后), captchaType }`；返回 `repCode=0000` 通过。
6. 把最终 `captchaVerification` 带进登录请求。

> 校验接口 `/system/captcha/check` 未在导出里，但 AJ-Captcha 是固定协议，路径与 get 平级。若 check 报错需找 Rookie 确认 system 模块导出。

---

## 3. 登录（邮箱 + 密码）

`POST /member/auth/emailLogin`，body `AppAuthEmailLoginReqVO`：
| 字段 | 必填 | 说明 |
|---|---|---|
| `email` | ✅ | 邮箱 |
| `password` | ✅ | 密码 |
| `captchaVerification` | 视情况 | 人机验证 token（强制开启时必传，见 §2） |
| `cfTurnstileToken` | — | CF 未开，忽略 |
| `deviceId` | 建议 | 前端生成并持久化的 UUID（设备信任，见 §5） |
| `emailVerCode` | 条件 | **新设备未受信任**时，需先发邮箱验证码再带上 |
| `socialType/socialCode/socialState` | — | 账密登录用不到 |

返回 `AppAuthLoginRespVO`：`{ userId, accessToken, refreshToken, expiresTime, openid? }`

### 登录是否要验证码？
`GET /member/auth/login-captcha-required?email=xxx` → 返回该邮箱本次登录要不要人机验证。Rookie 说强制开，可直接默认要；用这个接口做兜底判断更稳。

---

## 4. 注册（邮箱验证码）

1. `POST /member/auth/emailVerCode` —— 发邮箱验证码
   body `AppAuthEmailSendReqVO`：`{ email*, type*, captchaVerification?, cfTurnstileToken? }`
2. `POST /member/auth/verifyAndRegister` —— 校验验证码并注册
   body `AppAuthEmailValidateReqVO`：`{ email*, password*, code*(邮箱验证码), deviceId?, captchaVerification? }`

> 区分两种「验证码」：
> - **邮箱验证码** = 发到邮箱的数字码（`code` / `emailVerCode`），注册、改密、新设备登录用。
> - **人机验证码** = AJ-Captcha 文字点选（`captchaVerification`），防机器人。

相关：`resetPassword`(忘记密码重置)、`changePassword`(改密)。

---

## 5. 设备信任 (deviceId)

- 前端首次启动生成一个 UUID 作为 `deviceId`，**持久化**（不要每次新建），所有登录/注册请求都带上。
- 新设备/未受信任设备登录 → 后端要求 `emailVerCode`（邮箱验证码）二次确认。
- 受信任设备列表管理：`/member/security/devices`、`device/{id}/trust|untrust`、`devices/untrust-all`。

---

## 6. Token 存储与刷新

- 登录成功：安全存储 `accessToken` + `refreshToken` + `expiresTime`（建议 EncryptedSharedPreferences）。
- 每个受保护请求带 `Authorization: Bearer <accessToken>`。
- 过期/401 → `POST /member/auth/refresh-token?refreshToken=xxx` 换新 token；refresh 也失效则回登录页。
- 校验有效性：`GET /member/user/validate-token`。
- 登出：`POST /member/auth/logout`。

---

## 7. 个人信息

`GET /member/user/get` → `AppMemberUserInfoRespVO`（查自己，含私有字段）。主要字段：

| 字段 | 说明 |
|---|---|
| `id / nickname / avatar / profileBackground` | 基本资料 |
| `mark` | 简介 |
| `email / userMobile` | 邮箱 / 联系方式（`userMobile` 隐私，展示自己 ok） |
| `language` | 0中文 1英文 |
| `verifyKeyStatus` | 游戏账户key 0未设置 1已设置 |
| `rsiAccurate` | RSI 账号有效性 1有效 0失效 null未验证 |
| `gameAccountCreatedAt / createTime` | 游戏账号 / 网站账号注册时间（秒级时间戳） |
| `organization` | 舰队信息 |
| `achievement` | 成就信息 |
| `sponsorLevel` | 赞助等级 0~3 |
| `isInvisible / lastTime / signInStatus` | 隐身 / 最后在线 / 状态 |
| **`tradeTime`** | 周一~周日交易开关，长度7数组，0关1开 |
| **`tradeStartTime / tradeEndTime`** | 交易时段 HH:mm |
| **`sellOrderCount / buyOrderCount`** | 出售 / 收购订单数 |

> 交易配置直接挂在用户资料上 → 印证「交易依赖登录用户」。

看别人：`GET /member/user/get-public`（无私有字段）。
改资料：`PUT /member/user/update`、`update-email`、`update-status`、`refresh-rsi-data`。

---

## 8. 交易对接的 Session 依赖（回答「交易依赖登录还是怎么弄」）

**依赖同一个 `accessToken`，不是另一套登录。** 流程：
1. 用户在「个人」页 SCM 登录 → 拿 accessToken 存本地。
2. 进入交易：所有 `/app-api/sc/orders/*` 受保护接口带 `Authorization: Bearer <accessToken>`。
3. IM 聊价：`/member/chat/*` + WebSocket（先 `POST /member/chat/ws-ticket` 或 `GET /member/websocket/temp-token` 换票据再连）。
4. token 过期统一走 §6 刷新。

浏览行情（订单列表/详情）此前实测**公开可读、无需登录**；只有**下单/成交/聊价**才需要登录态。

---

## TODO（待 Rookie 补充）

- [ ] **订单/支付模块 OpenAPI**：下单、创建出售/求购、订单状态流转（成交/完成/取消/纠纷）、**支付是站内走还是线下面交**。本笔记的 member 模块里没有。
- [ ] `/system/captcha/check` 的确切 body/返回（AJ-Captcha 标准协议，建议直接导出 system 模块确认）。
- [ ] 社交登录(§登录的 social-*)的可用平台与回调 URL 约定（若要做）。
