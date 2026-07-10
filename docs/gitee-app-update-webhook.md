# 发版下载页更新 Webhook

## 目标

发版时把最新 APK 下载地址通知给后端，后端更新 App 下载页/更新页。Android 客户端仍通过现有更新检查逻辑读取最新版本。

## 推荐链路

优先使用 `release.sh` 主动通知后端：

1. 脚本推送 Gitee tag
2. 脚本创建 Gitee Release
3. 脚本上传 APK 附件
4. 脚本从 Gitee 上传响应里读取 `browser_download_url`
5. 脚本 POST 到后端 webhook，后端更新下载页

原因：Gitee `Tag Push` 事件到达时，Release 和 APK 附件可能还没创建完成，payload 里也没有 APK 下载地址。

## 发版脚本配置

发布前设置后端 webhook token：

```bash
export BUGSC_APP_PAGE_WEBHOOK_TOKEN='替换为后端保存的 webhook 密码'
```

如果后端地址不是默认值，再设置：

```bash
export BUGSC_APP_PAGE_WEBHOOK_URL='https://你的域名/app-api/system/app-page/gitee-webhook'
```

然后正常发版：

```bash
./release.sh 1.1.1 "更新说明"
```

不要把 webhook 密码写入仓库。聊天里出现过的密码应在上线前重新生成。

## 主动通知请求

## 接收校验

服务端收到请求后按顺序校验：

1. `POST` 方法
2. `X-Gitee-Token` 与服务端保存的 webhook 密码或签名一致
3. `X-Gitee-Event == "Tag Push Hook"`
4. `ref` 以 `refs/tags/v` 开头
5. `apk_url` 不为空且以 `https://` 开头

不满足条件时返回 `2xx` 并忽略，避免 Gitee 持续重试无关事件；鉴权失败返回 `401`。

请求体示例：

```json
{
  "hook_name": "tag_push_hooks",
  "event_name": "tag_push",
  "ref": "refs/tags/v1.1.1",
  "tag_name": "v1.1.1",
  "version_name": "1.1.1",
  "apk_url": "https://gitee.com/hurtblack/BUGSC/releases/download/v1.1.1/SCMobiGlas-release-v1.1.1.apk",
  "page_url": "https://gitee.com/hurtblack/BUGSC/releases/tag/v1.1.1",
  "body": "更新说明",
  "repository": {
    "full_name": "hurtblack/BUGSC",
    "html_url": "https://gitee.com/hurtblack/BUGSC"
  }
}
```

后端写入 App 更新记录时至少包含：

- `versionName`
- `tagName`
- `pageUrl`
- `apkUrl`
- `notes`
- `publishedAt`

## Gitee Tag Push 备用方案

如果仍然在 Gitee 后台配置 `Tag Push` webhook：

- URL：`https://你的域名/app-api/system/app-page/gitee-webhook`
- 事件：只勾选 `Tag Push`
- 密码：使用同一个 webhook token

后端可以继续接受标准 Gitee payload，并从 `ref` 解析 tag：

从 `ref` 解析 tag：

```text
refs/tags/v1.1.1 -> tag=v1.1.1 -> versionName=1.1.1
```

Tag Push 没有 `apk_url` 时，后端必须后台重试查询 Gitee Release：

- 30 秒
- 1 分钟
- 3 分钟

每次根据 tag 调 Gitee Release API，拿到 APK 附件后再更新 App 版本记录。若最终仍没有 APK，可以只记录 tag 和页面地址，但不要提示强制下载。

## 与 Android 客户端的关系

这个 webhook 属于服务端发布链路，不需要 Android 客户端携带 webhook 密码。客户端只读取更新接口或 Release API，不直接参与 Gitee webhook。
