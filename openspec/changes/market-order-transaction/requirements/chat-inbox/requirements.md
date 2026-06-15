## ADDED Requirements

### Requirement: 用户可查看全部私聊会话
SCM 账号区域 SHALL 提供“消息”入口，并显示当前未读消息数量。

#### Scenario: 进入消息
- **WHEN** 用户点击消息入口
- **THEN** 页面展示会话列表，点击会话可继续聊天

### Requirement: 前台未读提醒
App 位于前台时 SHALL 提醒用户有新的 SCM 私聊消息。

#### Scenario: 收到新消息
- **WHEN** 用户不在对应聊天页面且收到新消息
- **THEN** 消息入口更新未读数字，底部个人导航显示红点

