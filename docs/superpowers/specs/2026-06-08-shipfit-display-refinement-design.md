# 改船配船完善 — 显示打磨与采矿层级 + 电源尺寸修复

> 日期：2026-06-08
> 范围：`shipfit` 模块展示/筛选层
> 状态：设计已确认，待写实现计划

## 背景

有用户反馈改船配船功能存在多处问题，集中在三类：

1. **槽位标签难读**：如「冷却 · S2-S2 · hardpoint_cooler」——尺寸冗余、直接抛出英文 key、left/right 不翻译、同类多个无法区分。雷达、电源等同样有此问题。
2. **交互冗余**：某分类只有一个槽位时（如量子驱动），仍弹出「选择槽位」下拉框。
3. **采矿层级不合理**：采矿模组本应从属于采矿头，却被当成与电源/冷却同级的顶级分类。
4. **电源尺寸 bug**：只支持 S3 的船，组件下拉里却冒出 S4（伊德里斯独有）发电机。

调查另发现「组件等级/类别」（军用A级、民用C级）当前数据缺失，需独立立项（见末尾）。

## 范围

**仅改动展示/筛选层**，文件：

- `app/src/main/java/com/euedrc/bugsc/shipfit/ShipLoadoutFragment.kt`
- `app/src/main/res/layout/fragment_ship_loadout.xml`
- `ShipFitDataRepository.loadComponents()`（仅加一处过滤）

**不动**：配船码编解码（`ShipFitCodec`）、电量计算（`ShipPowerCalculator`）、数据集结构。

**明确不做（拆为独立 change）**：组件等级 grade(A/B/C/D) + 类别 class(Military/Civilian/Industrial/Stealth/Competition) 的展示——当前 627 个组件 `grade` 全为 null，且无 class 字段，需重新拉取/补全数据源，单独立项。

## 设计

### 1. 槽位标签重写 · `slotLabel()`

新格式：`类型 · 尺寸[· 方位/编号]`，彻底去掉英文 key。

**尺寸折叠**
- `min == max` → `S2`
- `min != max` → `S1-S3`
- 仅单边 → `S2`（仅 min）/ `≤S2`（仅 max）
- 全无 → 省略尺寸段

**方位/描述词词典**（从 key 剥掉 `hardpoint_`/`fallback_`/`wiki_` 前缀及类型词后，对剩余 token 查词典）：

| 英文 | 中文 |
|---|---|
| left | 左 |
| right | 右 |
| front | 前 |
| rear / back | 后 |
| top / upper | 上 |
| bottom / lower | 下 |
| center / mid | 中 |
| nose | 前 |
| cockpit | 座舱 |
| main | 主 |
| remote | 遥控 |

组合词按出现顺序拼接（如 `frontleft` → 前左）。词典覆盖不到的 token 不直接显示。

**同类去重兜底**：在当前分类的槽位列表内，按 `(类型, 尺寸)` 分组：
- 组内只有 1 个 → 不加后缀，例：`冷却 · S2`
- 组内多个，且每个都能解析出可读方位/描述词 → 用方位，例：`电源 · S2 · 左` / `电源 · S2 · 右`
- 组内多个但无法解析（如 `fallback_shield_1..4`，或词典外的 token）→ 按序编号，例：`护盾 · S2 · ①②③④`

**采矿模组子槽位**：维持缩进显示，如 `　模组 N · 采矿模组`；从属关系已由层级（见 §3）体现，可省略原「来自 XX」文案。

### 2. 单槽位隐藏下拉 · `refreshSlotsByCategory()` + 布局

当 `categorySlots.size == 1` 时：
- 将「2) 选择该分类下的槽位」标题 TextView 与 `sp_slot` 下拉设为 `View.GONE`
- 自动选中该唯一槽位，直接进入组件选择

当 `> 1` 时恢复 `View.VISIBLE`。

布局改动：给 layout 中「2)」那个 TextView 补 `android:id`（如 `@+id/tv_step_slot`），以便控制可见性。

### 3. 采矿模组归到采矿头下

- `buildCategories()` 不再把 `mining_module` 列为顶级分类——顶级只到「采矿头」。
- 进入「采矿头」分类时，`categorySlots` = 采矿头槽位 +（选定某采矿头后）该头的模组子槽位，排序为「头在前、模组缩进随后」。
- 模组子槽位仍由现有 `miningModuleSlots()` 生成（key 形如 `parentKey/module_N`），仅把它们的归类从「采矿模组」改挂到「采矿头」分类下。
- 选中模组子槽位时，组件下拉照常筛 `mining_module` 类型组件。
- 选采矿头后沿用现有 `refreshSlots()` 调用链刷新模组槽位数量。

### 4. 电源/尺寸 bug 修复（A + B + C）

根因有二：组件侧有 8 个发电机（及大量 flight_blade/missile/turret 等）`size` 为空，`isSizeCompatible()` 中 `c.size ?: return true` 使其匹配任意槽位；另有 55 个 `vehicle_name` 非空的整船独有件（如 Reclaimer/伊德里斯原厂焊死件）混入可选池。数据现实约束：最小发电机为 S1，无 S0。

- **A · 排除整船独有件** · `loadComponents()`：`vehicle_name` 非空的组件不进可选池（55 个焊死、不可拆装件）。
- **B · 无尺寸组件不匹配有尺寸槽位** · `isSizeCompatible()`：组件无尺寸时，若槽位有任一尺寸约束 → 判不兼容；若槽位本身也未知 → 放行（避免误伤）。
- **C · 未知尺寸槽位兜底** · 槽位 `min/max` 均未知（0/0，全为地面/小型载具）时，按 `max = 1`（≤S1）处理，避免大尺寸件涌入。

## 实现方式

集中重构 `ShipLoadoutFragment.kt` 的若干私有方法（`slotLabel`、`refreshSlotsByCategory`、`buildCategories`、`isSizeCompatible`、新增方位词典 map 与去重编号逻辑）；`ShipFitDataRepository.loadComponents()` 加 `vehicle_name` 过滤；布局给「2)」标题加 id。无新文件、无新依赖。

主要联动风险：采矿头 ↔ 模组子槽位的刷新，沿用现有 `refreshSlots()` 调用链；以及单槽位隐藏/恢复时下拉可见性的状态切换需在每次 `refreshSlotsByCategory()` 重新计算。

## 测试要点

- 标签：取多船验证 `S2-S2→S2`、左右翻译、`fallback_shield_1..4` 编号、座舱雷达描述词翻译。
- 单槽位：量子驱动等单槽分类隐藏下拉并自动选中；多槽位分类恢复显示。
- 采矿：prospector / mole / ROC 选采矿头后模组以子槽位缩进出现，顶级分类无「采矿模组」。
- 电源：地面载具（cyclone/ursa/nox 等）不再出 S4；Reclaimer 等独有件不在可选池；S3 槽位只出 ≤S3。

## 后续（独立 change）

组件等级/类别展示：需重新拉取带 grade + class 的组件数据并嵌入数据集，再在组件下拉显示「军用A级」等。本设计不含。
