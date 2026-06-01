# 蓝图 / 翻译数据 —— 维护与热更新说明

本文件说明：游戏更新后如何重新生成数据、如何热更新到线上 App，以及待办（服务器托管）。

App 运行时**不依赖任何第三方站点**：默认读本地（filesDir 缓存 > assets 内置快照），
配置了远程地址后才会静默检查更新。对外部站点的依赖只发生在「维护时跑脚本」这一步。

---

## 一、数据文件

| 文件（assets/blueprint/ 与 远程同名） | 内容 | 来源 | 对照 key |
|----|----|----|-----|
| `sccraft_blueprints.json` | **完整**配方曲线 + 获取任务 | sc-craft.tools 公开 API | itemNameEn |
| `item_base_stats.json` | 物品**绝对基准属性值**（单发伤害/射速/护盾强度…） | star-citizen.wiki API | itemNameEn → statLocKey |
| `scm_translations.json` | 英文名 → 中文名 | SCM 站点 | itemNameEn |
| `scm_blueprint_hints.json` | 配方线索（简化 min-max）| SCM 站点 | itemNameEn |

所有文件顶层都有 `version`（整数）。App 用它判断是否需要下载新数据。

> **数据源分工**：
> - `sccraft_blueprints.json`（**sc-craft.tools**）：品质**修正百分比**曲线 + 任务列表。注意上游用分段
>   `ranges` 数组表达曲线（如 0~499 平缓、500~1000 加成），导出脚本会把每段展开成一条 effect。
> - `item_base_stats.json`（**star-citizen.wiki**）：sc-craft **不提供**的绝对基准值。两者按英文名关联，
>   App 用 `基准值 ×(1+修正)` 算出强化后属性，显示「基础/强化/变化」三列。
> - 护甲 **Damage Mitigation** 现已从 SC wiki 结构化字段
>   `suit_armor.damage_resistance_map.physical_change`（回退 `clothing...`）提取基准值。
> - 护甲 **Integrity/Health** 仍可能缺失（部分护甲条目无 `durability.health`），缺失时仅显示 ±% 变化，属预期行为。

---

## 二、游戏更新后的维护流程（Runbook）

> 触发时机：星际公民发新版本 / 蓝图数值调整时。全程在本机操作，**不改 App 代码、不发版**。

### 2.1 生成完整蓝图数据（sc-craft.tools，推荐）

```bash
python3 tools/gen_sccraft_blueprints.py --version 2
```

- 从 `https://sc-craft.tools/api/blueprints` 抓全量数据（16 页 × 100 条，约 24 秒）
- 生成 `sccraft_blueprints.json` 覆盖到 `app/src/main/assets/blueprint/`
- 包含：每个槽位的材料、完整品质修正曲线（qualityMin/Max + modifierAtMin/Max）、获取任务列表
- 上游分段曲线（effect 的 `ranges` 数组）由 `expand_effects()` 展开成多条 effect；相邻段在边界都=1.0，
  App 的 `aggregateDeltas` 按 stat 累加即精确还原分段（无需改 App 代码）
- 无需账号、无需 SSL 证书（脚本已绕过 macOS 默认证书链）

### 2.2 生成物品基准属性值（star-citizen.wiki，推荐）

```bash
python3 tools/gen_item_base_stats.py --version 2
```

- 遍历 `sccraft_blueprints.json` 中所有蓝图英文名，逐个在 `https://api.star-citizen.wiki/api/v2/items`
  按 `filter[name]` 精确匹配并取详情，提取该蓝图会被修正的那些属性的**绝对基准值**
- 生成 `item_base_stats.json`：`{ version, generatedAt, stats: { "<英文名>": { "<statLocKey>": 基准值 } } }`
- 字段映射表见脚本顶部的 `STAT_PATHS`（statLocKey → SC wiki 详情 JSON 的点路径）。游戏新增属性时在此扩充
- 耗时较长（约 1500 个蓝图、每个 2 次请求），是一次性维护操作；脚本带 `--limit N` 可先小批量验证
- 只存「sc-craft 里被品质修正引用」的属性；后坐力仍无稳定绝对基准，不入表

### 2.3 生成翻译表（SCM，可选，翻译不变时跳过）

```bash
python3 tools/export_scm_data.py --version 2 --game-version 4.2.0
```

- 生成 `scm_translations.json`（英文→中文）和 `scm_blueprint_hints.json`（简化配方线索）

### 2.4 上传到托管目录（文件名保持不变）

```
<baseUrl>/sccraft_blueprints.json
<baseUrl>/item_base_stats.json
<baseUrl>/scm_translations.json
<baseUrl>/scm_blueprint_hints.json
```

### 关键规则
- 每次发布 `--version` **必须递增**（整数）。App 仅在「远程 version > 本地 version」时下载。
- 文件名必须与 assets 中一致。

---

## 三、App 端接线示例

```kotlin
// 启动时配置一次远程地址（部署后填）
BlueprintDataRepository.RemoteConfig.baseUrl =
    "https://raw.githubusercontent.com/<you>/<repo>/main/blueprint"

val repo = BlueprintDataRepository(context)

// 读取完整蓝图（含品质曲线 + 任务）
val bp: ScCraftBlueprint? = repo.loadScCraftBlueprint("Atzkav Sniper Rifle")

// 读取中文翻译
val translations = repo.loadTranslations()
val nameCn = translations.itemName("Atzkav Sniper Rifle") // "阿兹卡夫狙击步枪"

// 计算品质影响（每槽品质 0~1000）
val deltas = bp?.aggregateDeltas(mapOf("FRAME" to 800, "CYCLER" to 600))

// 读取绝对基准值（statLocKey → 基准值），与 deltas 结合显示「基础/强化/变化」
// 强化值 = 基准 ×(1 + delta)；无基准的属性（后坐力/护甲血量·减伤）只显 ±%
val baseStats = repo.loadItemBaseStatsFor("Atzkav \"Mirage\" Sniper Rifle")

// 或转换为 BlueprintCalculator 格式做精确计算
val blueprint = bp?.toBlueprint(baseStats = mapOf("Integrity" to 100.0))
val result = BlueprintCalculator.finalStat(blueprint!!, CraftSelection(qualityBySlot = mapOf(0 to 800)), "Integrity")

// 后台静默热更新（IO 线程；拉不到自动回退本地，不抛异常）
withContext(Dispatchers.IO) { repo.refreshFromRemote() }
```

`baseUrl` 留空时完全使用本地数据，不发起网络请求。

---

## 四、TODO：服务器托管（未定）

只需要一个「能放静态文件、公开可读」的地方，**不用自己写后台服务**。任选其一：

- [ ] **GitHub raw（推荐起步）**：json 文件放进公开 repo，`baseUrl` =
      `https://raw.githubusercontent.com/<you>/<repo>/main/blueprint`。免费、零运维。
- [ ] **对象存储**：阿里云 OSS / Cloudflare R2 / AWS S3，开公共读。几乎免费。
- [ ] **自有静态站**：任意能托管静态文件的地方。

部署完成后，在 App 启动处设置 `RemoteConfig.baseUrl` 即可。在那之前 App 用内置本地数据，功能正常。

---

## 五、数据说明

- `sccraft_blueprints.json` 包含**完整品质曲线**（qualityMin/qualityMax + modifierAtMin/Max），
  计算器可以精确按每槽材料品质算出最终属性变化。
- 翻译表（`scm_translations.json`）为按英文名匹配；少量变体（颜色/特别版）未命中时回退英文显示。
- 简化曲线文件（`scm_blueprint_hints.json`）保留作兜底，如 sccraft 数据缺失时使用。
- sc-craft.tools 每个游戏版本后约 24 小时内更新，来源为游戏解包数据，数值权威。
- `item_base_stats.json` 的基准值来自 star-citizen.wiki（同样基于游戏解包），按英文名与 sccraft 关联；
  少量名称差异（引号/特别版）未命中时该物品无基准值，UI 自动回退为只显 ±%，不影响其他物品。
- 维护两份数据时建议两个脚本都用**相同的递增 version**，便于追踪它们对应同一游戏版本。
