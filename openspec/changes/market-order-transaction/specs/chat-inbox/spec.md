## ADDED Requirements

### Requirement: SCM 消息会话列表
系统 SHALL 为已登录 SCM 用户提供聊天会话列表。

#### Scenario: 查看会话
- **WHEN** 用户从 SCM 账号卡进入消息
- **THEN** 系统展示对方昵称、头像、最后消息、最后消息时间和未读数量

#### Scenario: 打开会话
- **WHEN** 用户点击会话
- **THEN** 系统进入现有一对一聊天页并将该会话标记为已读

### Requirement: 未读消息状态
系统 SHALL 使用单一进程内状态维护总未读数。

#### Scenario: 未读消息存在
- **WHEN** 服务端未读总数大于零
- **THEN** SCM 账号卡消息入口显示精确数字，底部个人导航显示无数字红点

#### Scenario: SCM 退出登录
- **WHEN** 用户退出 SCM 登录
- **THEN** 系统清空未读数并隐藏全部消息提醒

### Requirement: App 前台实时提醒
系统 SHALL 在 App 前台且 SCM 已登录时建立全局消息 WebSocket。

#### Scenario: 收到新消息
- **WHEN** 全局 WebSocket 收到新聊天消息事件
- **THEN** 系统刷新服务端未读数并更新消息入口和底部红点

#### Scenario: App 进入后台
- **WHEN** App 不再位于前台
- **THEN** 系统关闭全局 WebSocket并停止重连，不启动常驻前台服务

