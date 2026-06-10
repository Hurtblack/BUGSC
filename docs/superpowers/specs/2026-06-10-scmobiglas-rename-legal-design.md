# SCMobiGlas 改名 + 启动更新提示 + 隐私合规 设计

日期：2026-06-10

## 背景

应用原名「BUG公民」，现因与他人合作（开发者负责 App，合作方负责网页与 QQ 机器人，同属 SCM 组织）需要：

1. 改名为 **SCMobiGlas**
2. 启动时自动检查更新并提示（兼任公告渠道）
3. 补齐隐私合规文档：隐私政策、用户协议、免责声明，首次启动征得同意

## 1. 改名 SCMobiGlas（仅显示层）

只改用户和文档可见的名称，**不改包名**（`com.euedrc.bugsc` 保持不变，老用户可覆盖升级），不改 GitHub 仓库名、不改代码内部类名。

改动点：

- `app/src/main/res/values/strings.xml`：`app_name` → `SCMobiGlas`
- `settings.gradle.kts`：`rootProject.name` → `SCMobiGlas`
- `app/build.gradle.kts`：APK 输出文件名 → `SCMobiGlas-{buildType}-v{versionName}.apk`
  - 现有 `AppUpdateClient.parseRelease` 优先匹配文件名含 `release` 的 apk 资产，新文件名仍兼容
- `README.md`：标题与正文中的应用名同步更新
- `release.sh`：涉及 apk 文件名/应用名之处同步更新

## 2. 启动自动检查更新（兼任公告）

- App 启动时（`MainActivity.onCreate` 后）在后台静默调用现有 `AppUpdateClient.fetchLatestRelease()`；任何失败静默忽略，不打扰用户。
- 有新版本时弹 `AlertDialog`：版本号 + Release body（更新说明即公告——发版时把要公告的内容写进 GitHub Release body）。
- 弹窗按钮：「下载更新」「忽略此版本」「取消」。
  - 「忽略此版本」把该版本号写入 SharedPreferences，之后启动不再为该版本弹窗。
  - 个人页手动「检查更新」按钮不受忽略影响，始终能弹。
- 复用 `ProfileFragment` 现有的版本比较与下载跳转逻辑，抽取公共部分（建议新建 `AppUpdateNotifier` 或类似辅助类，避免 MainActivity 和 ProfileFragment 重复代码）。
- 合规约束：首次启动时，自动检查必须发生在用户同意隐私政策**之后**；未同意前不发起任何网络请求。
- 双平台预留：将来要同时发 GitHub + Gitee Releases（国内下载与 API 可达性更好）。本期把 `AppUpdateClient` 改造为「更新源列表」结构：定义 `UpdateSource`（源名称 + latest-release API URL + 对应 JSON 解析），客户端按顺序逐源尝试，首个成功者生效。本期列表中只有 GitHub 一项；Gitee 仓库建立后只需追加一个源条目（Gitee API 为 `https://gitee.com/api/v5/repos/{owner}/{repo}/releases/latest`，字段与 GitHub 类似但 assets 结构略有差异，届时实现其解析）。
- `release.sh` 同样预留：发布步骤封装为「按平台发布」函数，本期只有 GitHub 实现，Gitee 平台留出注释位（推送第二 remote + Gitee Releases API 创建 release 并上传 apk）。

## 3. 隐私合规三件套

### 文档内容

三份 HTML 文档放在 `app/src/main/assets/legal/`，离线可读：

- `privacy_policy.html` 隐私政策：
  - 运营署名：SCM 组织（个人开发者维护）
  - 联系方式：GitHub 仓库 Issues（https://github.com/Hurtblack/BUGSC/issues，后续可能补充 Gitee）+ 邮箱 hurtblack@qq.com
  - 声明：应用本身不收集、不上传任何个人信息；无第三方统计/广告 SDK
  - RSI 账号登录仅在本地 WebView 内完成，登录 Cookie 仅保存在本机，不会发送给开发者
  - 列出应用访问的第三方服务及用途：robertsspaceindustries.com（账号/库存/商店数据）、issue-council.robertsspaceindustries.com（问题议会）、api.github.com 与 raw.githubusercontent.com（应用更新与数据文件）、api.uexcorp.uk（游戏数据）、exectimer.com（活动计时）
  - 权限说明：仅 INTERNET 与 ACCESS_NETWORK_STATE
- `user_agreement.html` 用户协议（简短）：使用规范、按现状提供、责任限制
- `disclaimer.html` 免责声明：非官方应用，与 Cloud Imperium Games / Roberts Space Industries 无任何关联；Star Citizen® 相关商标与素材归 CIG 所有

### 展示载体

- 新建 `LegalFragment`：内嵌 WebView，按导航参数加载 assets 中对应 HTML；加入 `nav_graph.xml`。

### 首次启动同意流程

- `MainActivity` 启动时检查 SharedPreferences 中的同意标记：
  - 未同意 → 弹不可取消的「用户协议与隐私政策」弹窗：摘要文字 + 可点击链接；点击链接时弹出二级全屏对话框（内嵌 WebView 加载对应 HTML），不离开同意流程
  - 「同意并继续」→ 写入同意标记（含协议版本号，便于将来协议更新时重新征求）→ 进入应用并触发自动更新检查
  - 「不同意」→ 退出应用（`finish()`）
- 已同意 → 直接进入，照常自动检查更新。

### 个人页入口

`ProfileFragment` 新增「关于」区块：

- 显示当前版本号
- 三个入口：隐私政策 / 用户协议 / 免责声明（导航到 LegalFragment）
- 现有「检查更新」按钮归入该区块

## 4. 测试

- `AppUpdateClient` 已有单测（`AppUpdateClientTest`），版本比较/解析逻辑不变。
- 新增逻辑可测点：忽略版本判断（给定 SharedPreferences 值与远端版本，决定是否弹窗）抽成纯函数测试。
- 同意流程、弹窗 UI 以手动验证为主。

## 5. 不做的事（YAGNI）

- 不做独立公告系统（Release body 即公告）
- 不改包名 `com.euedrc.bugsc`、不改 GitHub 仓库名
- 本期不实现 Gitee 源的具体解析与发布调用，但更新检查与 release.sh 均按多平台结构预留接缝（待 Gitee 仓库建立后填充）
