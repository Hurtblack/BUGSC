# RSI 服务器状态灯修复设计

> 日期：2026-06-15
> 关联代码：`RsiStatusFeedParser`、`ToolsFragment`

## 1. 问题

RSI 的 `index.xml` 是完整事故历史，不是仅包含当前状态。现有解析器遍历全部条目，只忽略标题带
`[Resolved]` 的项目；历史中存在少数没有该前缀的旧条目，因此旧平台事故会被误判为当前平台故障。

此外，现有严重程度规则将维护归为降级，并将降级映射为橙色，不符合状态灯语义。

## 2. 当前状态识别

RSS 按时间倒序排列。当前事故位于顶部，并持续到第一个 `[Resolved]` 条目之前。因此解析器只处理
顶部连续的未解决条目；遇到第一个 `[Resolved]` 后停止，不再扫描历史。

## 3. 服务归属

- 平台：标题或描述包含 Platform、Launcher、Website、Spectrum、API。
- 宇宙：包含 Persistent Universe、Live Services、Live Service、Joining Shards、Player Inventories。
- 竞技场：包含 Arena Commander、AC Matchmaking。

一个事故可以影响多个服务，各服务独立取最高严重程度。

## 4. 严重程度和颜色

- `OPERATIONAL`：无当前事故，绿色 `sc_ok`。
- `DEGRADED`：性能下降、错误率升高、连接不稳定、正在调查，紫色 `sc_purple`。
- `OUTAGE`：明确维护、停机、离线、服务不可用、阻止访问，红色 `sc_danger`。

维护即使只写 `maintenance window`，也按红色处理。

## 5. 当前预期

针对 2026-06-09 起仍未解决的 `Live Services Disruption`：

- 平台：绿色。
- 宇宙：紫色。
- 竞技场：绿色。

## 6. 测试

- 顶部 Live Services 错误率升高应只将宇宙标为 `DEGRADED`。
- 第一个 `[Resolved]` 后的未标记历史事故不得影响状态。
- 平台维护应将平台标为 `OUTAGE`。
- Arena Commander 不可用应将竞技场标为 `OUTAGE`。
