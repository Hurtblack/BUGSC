# 蓝图模块修复 + 任务详情卡 —— TODO & 调研记录

> 本文件追踪用户报告的所有蓝图相关 bug 和后续功能开发。
> 最后更新：2026-05-27

---

## 〇、用户最初报告的 Bug（汇总）

1. **中文翻译没补齐**
  - (a) 任务名是英文 ❌ 未修
  - (b) 材料名是英文 ✅ 已修（`bindSlots` 用 `t.itemName(slot.material)?.substringBefore("(")?.trim()`）
2. **品质 > 500 不加属性**（如十字弩）✅ 已修（2026-05-27）
  - ~~根因：sc-craft.tools 数据里十字弩的 qualityMax = 499~~（错误结论）
  - **真根因**：导出脚本 `gen_sccraft_blueprints.py` 只读 effect 顶层扁平字段，**忽略了上游的 `ranges` 分段数组**。十字弩冲击力是分段曲线：0~499 段 0.95/0.85→1.0，500~1000 段 1.0→**1.05(Savrilium)/1.15(Ouratite)**，第二段（正向加成）被整个丢弃，封顶卡 1.0
  - 上游 sc-craft.tools 数据**正确且最新**，不是新物品未更新
  - 影响面：1534 蓝图中 327 个带分段、959 条 effect（约 19%）被削平
  - 修复：脚本新增 `expand_effects()`，把每个 `range` 展开成一条 `ScCraftEffect`（相邻段在边界都=1.0，`aggregateDeltas` 按 stat 累加即精确还原分段）。App 端无需改动。重跑脚本 → 数据 version 2
3. **品质越高反而降低属性**
  - 根因：reverse curve（如后坐力 quality 0 是 1.2×，quality 1000 是 0.8×）
  - 越高 = 越好（数值越小越优），但 UI 用红色负数显示，语义反了

---

## 一、之前对话里做过的修改（审计）

| 改动 | 文件 | 状态 |
|---|---|---|
| 添加蓝图入口卡片 `cardBlueprint` | `HomeFragment.kt` | ✅ 已合 |
| 单元测试加 `org.json:json:20231013` | `app/build.gradle.kts` | ✅ 已合 |
| 材料名翻译（截括号后缀）| `BlueprintDetailSheet.bindSlots` | ✅ 已合 |
| 反向曲线提示文字（橙色 ⚠）| `BlueprintDetailSheet.refreshStats` | ✅ 已合 |
| 属性数值统一中性青色 | `BlueprintDetailSheet.refreshStats` | ✅ 已合 |
| `aggregateDeltas` 默认 quality = 0 | `ScCraftBlueprint.kt` | ✅ 已合 |
| **改错并需要回滚**：SeekBar `max = effectMax`（按效果区间限制）| `BlueprintDetailSheet.bindSlots` | ⚠️ 已修 |
| **遗留编译错误**：`$effectMax` 变量未定义 | `BlueprintDetailSheet.kt` 3 处 | ✅ 本轮已修 |

### 本轮（当前会话）修改
- ✅ 把 `BlueprintDetailSheet.kt` 中 3 处 `$effectMax` 改成 `MAX_QUALITY` 常量
- ✅ Companion object 加 `private const val MAX_QUALITY = 1000`
- ✅ `slotQuality[i] = 500` → `slotQuality[i] = 0`（与滑块默认 progress = 0 一致）
- ✅ 滑块文案改成「品质 0~1000」
- ✅ UEX API 调研：确认 UEX **不提供任务数据**

---

## 二、Phase 1：蓝图详情页修复收尾

- [x] 修编译错误（`$effectMax` → `MAX_QUALITY`）
- [x] SeekBar 恢复 0~1000，默认 0
- [x] `slotQuality` 默认 0（与 `aggregateDeltas` 默认值一致）
- [x] **运行 `./gradlew :app:assembleDebug` 验证编译通过**
- [x] **运行 `./gradlew :app:testDebugUnitTest` 验证测试通过**（顺手修了过时测试：默认品质 500→0，期望 delta 改 -0.1）
- [x] 修分段曲线数据 bug（见上 §〇.2），重跑脚本生成 version 2，已 installDebug
- [ ] 真机跑一遍，验证：
  - **十字弩冲击力：品质 1000 时 FRAME +5% + TENSIVE FIBRE +15% = 合计 +20%**（修复重点）
  - 0~499 段仍是减伤逐渐回正（0.85/0.95→1.0）
  - 后坐力等反向曲线：橙色 ⚠ 提示文字显示
  - 材料名带括号变体（如 "Iron (Mining Refined)"）正确截成 "铁"

---

## 二·B、基准属性值（基础/强化/变化三列）—— 2026-05-27 新增

### 背景
- sc-craft 只给品质**修正百分比**，不给绝对基准（如单发伤害 165、射速 30），无法像 SCM 那样显示「基础/强化/变化」。
- 数据源调研：**star-citizen.wiki API**（`api.star-citizen.wiki/api/v2`）有完整基准，按英文名精确匹配。sc-craft API 本身无此数据。
- 蓝图共用到 **24 个 distinct stat**；其中 21 个能从 SC wiki 映射到字段，映射表见 `tools/gen_item_base_stats.py` 的 `STAT_PATHS`。
- **护甲缺口更新**：Damage Mitigation 可从 `suit_armor/clothing.damage_resistance_map.physical_change` 读取基准；
  Integrity 仍可能缺失（部分护甲无 `durability.health`）。

### 已完成
- [x] 调研确认 SC wiki 为数据源，映射 24 个 stat 字段路径
- [x] 新建 `tools/gen_item_base_stats.py`：遍历蓝图英文名 → SC wiki 查询 → 输出 `item_base_stats.json`（statLocKey → 基准值，带 version）
- [x] `BlueprintDataRepository`：加 `Dataset.ITEM_STATS` + `loadItemBaseStatsFor(nameEn)`
- [x] `BlueprintDetailSheet.refreshStats`：改三列（属性 | 基础 | 强化 | 变化）；有基准显绝对值，无基准（后坐力/部分护甲血量）显 ±%
- [ ] 真机验证：Atzkav "Mirage" 单发伤害基础 165 / 强化值 / 变化%
- [ ] **可选 DPS 派生行**：SCM 显示武器 DPS（=伤害×RPM/60）。当前未做——需 rpm 基准，仅在 fire rate 被修正的武器上可得。

---

## ★ 2026-05-27 进展：任务详情卡（中档）已实现

- **接口已盲探确认**（详见 memory `reference_flowcld_api.md`）：
  - 公开：`GET /product/blueprint/reward-mission-details?id={数字蓝图id}`（头 `tenant-id:1`）→ 类型/标题/赏金/地点(带中文)/掉率/战斗标志
  - 登录墙后（401）：派系/声望/时限/冷却/标签 —— **未抓，留作"完整卡"二期**
- **占位符根因定性**：`~mission(targetname)`/`[SHIP]` 是运行时变量，静态数据无唯一正确值。导出脚本洗成「舰船」「地点」中文占位；卡片价值在结构化字段而非标题。
- **已落地**：
  - `tools/gen_blueprint_missions.py` → 生成 `scm_blueprint_missions.json`（missions{guid→详情}+blueprintMissions{名→[guid]}+missionTypes）
  - `BlueprintMissions.kt` 模型重写对齐真实字段
  - `BlueprintDataRepository`：`loadAllMissions()`/`loadMissionsForBlueprint(nameEn)`/`loadMissionByGuid(guid)`
  - `MissionDetailSheet`（中档卡）+ `bottom_sheet_mission_detail.xml`
  - `BlueprintDetailSheet.bindMissions` 行改用 flowcld 数据、可点击开卡
  - `BlueprintFragment` 加「任务」chip → 任务浏览模式 + 复用搜索
- **待办**：真机验证；二期登录抓完整卡字段。

---

## 三、Phase 2：SCM 任务接口调研（✅ 已完成）

### 背景
- `assets/blueprint/sccraft_blueprints.json` 的 `missions[]` 只含 `missionId` + `name(英文)` + `dropChance`
- `BlueprintMissions.kt` 数据模型已为完整任务信息准备好字段（`missionType` / `titleCn` / `rewardUec` / `locations`），但对应 JSON 文件 **`scm_blueprint_missions.json` 实际不存在**
- UEX API 调研完毕：80+ 端点中无 missions 类，**走 SCM 路线**

### 待办
- [ ] **抓 flowcld.xyz 蓝图详情页 Network 请求**（推荐由用户操作）
  1. 打开有任务的蓝图（如 "Atzkav Sniper Rifle"）
  2. F12 → Network → 刷新页面
  3. 找 XHR 请求：URL 形如 `https://origin.flowcld.top/app-api/.../mission...`
  4. 复制 URL + 返回 JSON 给我
- [ ] **或** 我自行盲探接口（备用方案，可能被 Cloudflare 拦）
  - 起点：`https://origin.flowcld.top/app-api/blueprint/page?nameEn=...`
  - 起点：`https://origin.flowcld.top/app-api/.../reward-mission-details`
- [ ] 确认接口字段：missionType / titleEn / titleCn / rewardUec / locations / 任务图标 URL / 描述 / NPC

---

## 四、Phase 3：写抓取脚本（未开始）

依赖 Phase 2 完成。

- [ ] 新建 `tools/gen_blueprint_missions.py`（参考 `export_scm_data.py` 套路）
- [ ] 输入：sccraft_blueprints.json 里所有蓝图英文名
- [ ] 对每个蓝图调 SCM 任务接口，组装数据
- [ ] 输出 `app/src/main/assets/blueprint/scm_blueprint_missions.json`，顶层带 `version` 字段
- [ ] 在 `tools/BLUEPRINT_DATA_MAINTENANCE.md` 里加这个脚本的运行说明

---

## 五、Phase 4：App 接入任务翻译（未开始）

依赖 Phase 3 完成。

- [ ] `BlueprintDataRepository`：复活 `Dataset.MISSIONS`，让 `loadBlueprintMissionsFor(nameEn)` 真正能拿到数据
- [ ] `BlueprintDetailSheet.bindMissions`：
  - 加载该蓝图的 `BlueprintMissions`
  - 用 `missionId` 关联 sccraft 的英文 mission 列表 与 SCM 的中文详情
  - 任务名优先 `titleCn`，回退 `titleEn`
  - 任务行加 `setOnClickListener`，触发详情卡（Phase 5）
- [ ] 单测：`BlueprintMissionsTest`（解析 + 关联逻辑）

---

## 六、Phase 5：任务详情卡 UI（未开始）

> 用户期望：点击任务，弹出和 flowcld 网站一样的任务详情卡

- [ ] 新建 `MissionDetailSheet : BottomSheetDialogFragment`（套用 `BlueprintDetailSheet` 模板）
- [ ] 新建 `res/layout/bottom_sheet_mission_detail.xml`
- [ ] 卡片显示字段（按 SCM 实际返回字段定）：
  - 任务图标 / 类型徽章（赏金/快递/运输/...）
  - 中文标题（大）+ 英文标题（小，monospace）
  - 起止地点（system + 中文名）
  - 奖励 UEC（高亮显示）
  - 任务描述（可能没有）
- [ ] 在 `BlueprintDetailSheet.bindMissions` 行点击事件里 `MissionDetailSheet.newInstance(missionId).show(...)`

---

## 七、Phase 6：清理 + 收尾（建议）

- [ ] 决定 `BlueprintDataRepository.Dataset.MISSIONS` 命运：
  - 若 Phase 3 完成 → 保留并启用
  - 若 Phase 2 死活找不到接口 → 删除避免误导
- [ ] 把 `BlueprintMissions.kt` 中没用上的字段（如 `internalName` / `recordGuid`）补齐对接，或在注释里标 TODO
- [ ] 数据热更新自测：远程 version 增加后，App 自动覆盖本地缓存（`refreshFromRemote`）

---

## 八、关键决策点（等用户拍板）

1. **任务接口**：由你抓 Network（最快），还是我盲探？
2. **十字弩 0-499 数据缺失**：UI 标注？/ 等上游？/ 自己补？
3. **sccraft 上游更新节奏**：你希望 App 多久拉一次远程 JSON？现在 `RemoteConfig.baseUrl` 是空的，纯本地。

---

## 九、风险 & 备注

- **Cloudflare 反爬**：origin.flowcld.top 可能要带 cookie / 特定 header，盲探不一定通
- **任务名通常带星际公民独有 NPC 名**：纯机翻效果差，最好直接用 SCM 已经做好的中文
- **sc-craft.tools 数据局限**：质量曲线区间可能截断（用户已确认是数据问题，不是 bug）
