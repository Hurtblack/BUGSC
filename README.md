# BUG公民

Star Citizen 中文玩家工具箱，Android 应用。

QQ群：330941212

> **当前数据对应游戏版本：Alpha 4.8.0-LIVE.1825000**（2026-05-14 上线）
> 下个补丁 4.8.1 预计 2026 年 6 月，届时请留意组件/飞船数据是否需要更新。

---

## 功能模块

底部三栏导航，进入默认在「工具」页：

| Tab | 模块 | 状态 | 说明 |
|---|---|---|---|
| **工具** | BUG 分享/解决 | ONLINE | PC 常见问题与解决方案，支持硬件标签筛选 |
| **工具** | 灯状态计时辅助 | ONLINE | 机库灯状态确认、倒计时、多端校准（接 exectimer.com） |
| **查询** | 蓝图图鉴 | LOCAL | 配方查询、品质计算器、任务获取路径 |
| **查询** | 飞船查找 | BETA | 飞船槽位配装 + 电力面板计算 |
| **查询** | 维克洛兑换 | 4.8.0 | 巴努交易清单、兑换材料反查、声望需求 |
| **查询** | 矿物查询 | 4.7.0 | 矿石分布、出现概率、稀有度、含量范围 |
| **个人信息** | 库存查看 | ONLINE | RSI 账号登录后拉取飞船/装备库存 |
| **个人信息** | 角色修复 | RSI | 跳转 RSI 账号重置页执行 CHARACTER REPAIR |

---

## 技术栈

- **语言**：Kotlin
- **最低 SDK**：API 24（Android 7.0）
- **目标 SDK**：API 36
- **UI**：View + ViewBinding 为主，Navigation Component 单 Activity；底部栏用 Jetpack Compose（mobiGlas 风格三栏导航，逐步向 Compose 迁移）
- **网络**：`HttpURLConnection`（库存/图片拉取）、`WebView`（RSI 登录）
- **异步**：Kotlin Coroutines
- **序列化**：`org.json`（系统内置）

---

## 项目结构

```
app/src/main/
├── java/com/euedrc/bugsc/
│   ├── data/
│   │   ├── Bug.kt               # BUG 数据模型
│   │   ├── BugData.kt           # 本地 BUG 条目（硬编码）
│   │   └── BugRepository.kt     # BUG 列表加载与筛选
│   ├── blueprint/
│   │   ├── ScCraftBlueprint.kt  # 蓝图数据模型
│   │   ├── BlueprintCalculator.kt # 品质/材料计算
│   │   ├── BlueprintDataRepository.kt
│   │   ├── BlueprintMissions.kt # 任务获取路径模型
│   │   ├── UexClient.kt         # UEX API 2.0 客户端
│   │   └── UexMapper.kt         # UEX DTO -> 内部模型映射
│   ├── shipfit/
│   │   ├── ShipFitDataRepository.kt  # 飞船/组件数据加载
│   │   ├── ShipPowerCalculator.kt    # 电力格计算
│   │   ├── PowerDistributionView.kt  # 电力面板自定义 View
│   │   ├── ShipFitCodec.kt           # BUGFIT 配装码编解码
│   │   └── ShipFitFragment.kt
│   ├── RsiInventoryClient.kt    # RSI 库存 HTTP 客户端
│   ├── RsiCookieStore.kt        # RSI Session Cookie 管理
│   ├── RsiWebViewSetup.kt       # WebView 登录桥
│   └── ...Fragment.kt           # 各页面
└── assets/
    ├── blueprint/               # 蓝图静态数据
    └── shipfit/                 # 飞船配装静态数据
```

---

## 飞船查找模块

### 数据来源

| 文件 | 来源 | 内容 |
|---|---|---|
| `uex_shipfit_dataset.json` | UEX API | 飞船列表、可装配组件列表（628 条） |
| `erkul_ship_slots_live.json` | Erkul API | 每艘飞船的槽位配置（类型/尺寸限制） |
| `zh_aliases.json` | 手工维护 | 组件/飞船中文名对照表 |
| `component_power.json` | SC Wiki API（批量抓取） | 368 个组件的真实电量值 |
| `ship_fixed_power.json` | SC Wiki vehicles API | 每艘船的固定系统电耗（当前为占位数据） |

### 电力计算器

**发电机**：取 `component_power.json` 中的 `value`（对应 SC Wiki `power_segment_generation`），无数据则按尺寸兜底（S0=4 / S1=10 / S2=15 / S3=22 / S4=30）。

**耗电组件**：取 `component_power.json` 中的 `value`（对应 SC Wiki `resource_network.usage.power.maximum`），无数据则按尺寸兜底。

**分组显示**：

| 标签 | 包含类型 |
|---|---|
| WPN | weapon_gun · turret · missile_rack · missile · mining_laser · mining_module |
| SHD | shield_generator |
| QTM | quantum_drive |
| RDR | radar |
| COOL | cooler |

> **推进器和维生系统**：SC Wiki 推进器的 `usage.power.maximum = 0`（推进器消耗氢燃料而非电力格），维生系统在当前公开 API 中无单独类型，两项暂不纳入计算，待数据源补充后接入。

### BUGFIT 配装码

格式：`BUGFIT:v1:<Base64url>`，解码后为：

```json
{ "ship": "gladius", "s": { "slot_key": "component_id" } }
```

---

## 数据维护

### BUG 条目

编辑 `BugData.kt`，直接硬编码。字段说明见 `Bug.kt`。

### 蓝图数据

| 文件 | 更新方式 |
|---|---|
| `sccraft_blueprints.json` | 从 SC Craft 导出 |
| `scm_blueprint_missions.json` | flowcld SCM 公开接口 |
| `scm_translations.json` | flowcld SCM 翻译接口 |
| `item_base_stats.json` | SC Wiki / 手工整理 |
| `scm_blueprint_hints.json` | 手工维护备注提示 |

### 飞船配装数据

**更新 UEX 组件/飞船列表**（`uex_shipfit_dataset.json`）：
```bash
# 从 UEX API 2.0 拉取最新数据
curl https://api.uexcorp.uk/2.0/... > uex_shipfit_dataset.json
```

**更新 Erkul 槽位**（`erkul_ship_slots_live.json`）：从 Erkul 导出最新版本替换文件。

**重建电量数据**（`component_power.json`）：

项目根目录下运行以下脚本（需要 Python 3，联网）：

```python
# 脚本逻辑：
# 1. 读取 uex_shipfit_dataset.json 中所有有 UUID 的组件
# 2. 按 UUID 批量查询 https://api.star-citizen.wiki/api/v2/items/{uuid}
# 3. 发电机取 power_plant.power_segment_generation
# 4. 其他组件取 resource_network.usage.power.maximum
# 5. 输出 {uex_id: {type, value}} 写入 component_power.json
```

目前覆盖：发电机 73 条 · 量子驱动 56 条 · 护盾 62 条 · 冷却器 68 条 · 武器 107 条，共 368 条。雷达无 UUID，使用尺寸公式兜底。

**更新中文别名**（`zh_aliases.json`）：手工维护 `ships` 和 `components` 两个 key。

---

## 构建

```bash
./gradlew assembleDebug
# 输出：app/build/outputs/apk/debug/bug公民-debug-v{versionName}.apk
```

---

## 已知限制

- 雷达组件（53 条）无 SC Wiki UUID，电量使用 `size × 2` 估算
- 推进器 / 维生系统电耗暂不显示（数据源缺失）
- SC Wiki vehicles API 的 `used_segments_grouped` 当前对所有船返回相同占位值，`ship_fixed_power.json` 暂未使用
- 飞船配装码（BUGFIT）目前仅本地生成/解析，暂无云端同步
