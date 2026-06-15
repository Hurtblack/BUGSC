> 本清单只列**人工操作或观察**的核心验证项。
> 代码结构、设计对齐、scenario 代码核对、单测覆盖等已由 tasks.md review 组自动覆盖。

## 1. 构建与测试

- [ ] `./gradlew :app:assembleDebug` 通过
- [ ] `./gradlew :app:testDebugUnitTest` 全绿（含新增 Agent 测试）
- [ ] `./gradlew :app:lintDebug` 无新增警告

## 2. 核心端到端验证

- [ ] 未配置 DeepSeek 时从工具页点击 Agent 入口 → 进入聊天页，顶部展示人物卡，发送问题时提示先配置模型
- [ ] 在设置页填写 DeepSeek Key 并测试连接 → 显示连接成功，返回聊天页后可发送问题
- [ ] 询问“你是谁/你和 SCM 什么关系/我的 Key 会上传吗” → Agent 回答来自人物卡，说明不是 SCM 官方机器人，Key 保存在本机
- [ ] 询问“量子矿哪里采” → Agent 返回矿物相关数据和来源，数据不足或远程失败时明确说明
- [ ] 询问“F7A 火力怎么样”或类似船只问题 → Agent 返回船只/硬点/组件相关事实和行动建议
- [ ] 询问蓝图材料或维科洛兑换问题 → Agent 合并蓝图、任务或兑换线索回答
- [ ] DeepSeek Key 错误或网络断开时发送问题 → 聊天页显示可读错误，用户消息保留且可重试
- [ ] 使用 Agent 聊天后进入 SCM 消息页 → SCM 会话列表和未读数没有新增 Agent 消息

## 3. 本次变更的特定风险

- [ ] 远程查询 API 失败时继续提问 → Agent 使用本地数据降级回答，并标注远程来源不可用
- [ ] 检查 DeepSeek 请求抓包或日志输出 → 不包含 DeepSeek API Key 明文、SCM token、Cookie 或账号凭据
- [ ] 将显示名从 `SCMBOT` 临时改为其他名称后运行 → 人物卡展示和自我介绍同步变化，Agent Runtime 和 Skill 不依赖 SCM 命名
