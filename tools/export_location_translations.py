#!/usr/bin/env python3
"""
采矿地点 / 星系 / 类型 中文翻译表导出。

数据源: 手工维护 (SC 中文社区通用译名 + citizenwiki.cn 校对)。
SC 地名变化频率极低, 不必动态抓取。

输出: app/src/main/assets/mining/location_translations.json
  {
    "systems":   { "Stanton": "斯坦顿", ... },
    "locations": { "Hurston": "赫尔斯顿", ... },
    "types":     { "planet": "行星", ... }
  }

运行: python3 tools/export_location_translations.py
新增地点未翻译时, 脚本会列出 "MISS"; 直接编辑下面的字典补齐。
"""
import argparse, json, os, sys, time

SYSTEMS = {
    "Stanton": "斯坦顿",
    "Pyro": "派罗",
    "Nyx": "尼克斯",
}

# location_type
TYPES = {
    "planet": "行星",
    "moon": "卫星",
    "belt": "小行星带",
    "cluster": "小行星群",
    "lagrange": "拉格朗日点",
    "cave": "洞穴",
    "event": "事件点",
    "special": "特殊地点",
}

# 50 个地点的中文译名 (来自 citizenwiki.cn / SC 中文社区通用译法)
LOCATIONS = {
    # ── Stanton 行星/卫星 ──
    "Hurston": "赫尔斯顿",
    "Aberdeen": "阿伯丁",
    "Arial": "艾莉尔",
    "Magda": "玛格达",
    "Ita": "伊塔",
    "microTech": "微科技",
    "Calliope": "卡利俄佩",
    "Clio": "克里俄",
    "Euterpe": "欧忒耳佩",
    "Daymar": "戴马",
    "Cellin": "塞林",
    "Yela": "耶拉",
    "Lyria": "莱里亚",
    "Wala": "瓦拉",
    # ── Stanton 小行星带/特殊点 ──
    "Aaron Halo": "亚伦光环",
    "Yela Asteroid Belt": "耶拉小行星带",
    "Glaciem Ring": "冰川环",
    "Hathor Caves": "哈索尔洞穴",
    "Ship Graveyard": "飞船坟场",
    "Space Derelict": "废弃太空船",
    # ── Stanton 拉格朗日点 ──
    "Lagrange (Occupied)": "拉格朗日点(已占用)",
    "Lagrange A": "拉格朗日点 A",
    "Lagrange B": "拉格朗日点 B",
    "Lagrange C": "拉格朗日点 C",
    "Lagrange D": "拉格朗日点 D",
    "Lagrange E": "拉格朗日点 E",
    "Lagrange F": "拉格朗日点 F",
    "Lagrange G": "拉格朗日点 G",
    # ── Pyro 行星/卫星 ──
    "Pyro I":            "派罗一号",
    "Pyro II (Monox)":   "派罗二号 (莫诺斯)",
    "Pyro III (Bloom)":  "派罗三号 (绽放)",
    "Pyro IV":           "派罗四号",
    "Pyro V-a (Ignis)":  "派罗五号-a (伊格尼斯)",
    "Pyro V-b (Vatra)":  "派罗五号-b (瓦特拉)",
    "Pyro V-c (Adir)":   "派罗五号-c (阿迪尔)",
    "Pyro V-d (Fairo)":  "派罗五号-d (费罗)",
    "Pyro V-e (Fuego)":  "派罗五号-e (弗埃戈)",
    "Pyro V-f (Vuur)":   "派罗五号-f (弗尔)",
    "Pyro VI (Terminus)":"派罗六号 (终端)",
    # ── Pyro 小行星带 ──
    "Pyro Belt (Cool 1)": "派罗带 (冷区 1)",
    "Pyro Belt (Cool 2)": "派罗带 (冷区 2)",
    "Pyro Belt (Warm 1)": "派罗带 (暖区 1)",
    "Pyro Belt (Warm 2)": "派罗带 (暖区 2)",
    "Pyro Deep Space Asteroids": "派罗深空小行星",
    "Akiro Cluster": "阿基罗星团",
    "Keeger Belt": "基格带",
    # ── 通用/事件 ──
    "Asteroid Cluster (Low Yield)":    "低产小行星群",
    "Asteroid Cluster (Medium Yield)": "中产小行星群",
    "Breaker Stations Interior":   "解体站内部",
    "Breaker Stations Large Geode": "解体站大型晶洞",
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="app/src/main/assets/mining")
    args = ap.parse_args()

    src = os.path.join(args.out, "mining_data.json")
    if not os.path.exists(src):
        print(f"找不到 {src}, 先跑 export_mining_data.py", file=sys.stderr)
        sys.exit(1)

    data = json.load(open(src, encoding="utf-8"))
    sys_set  = {l["system"] for l in data["locations"]}
    loc_set  = {l["locationName"] for l in data["locations"]}
    type_set = {l["locationType"] for l in data["locations"]}

    print("[校验] 字典覆盖情况")
    for label, want, mapping in (("systems", sys_set, SYSTEMS),
                                  ("locations", loc_set, LOCATIONS),
                                  ("types", type_set, TYPES)):
        missing = sorted(want - mapping.keys())
        print(f"  {label}: {len(want & mapping.keys())}/{len(want)} hit, {len(missing)} miss")
        for m in missing:
            print(f"    MISS: {m}")

    now = int(time.time())
    out = {
        "_version": now,
        "meta": {
            "generatedAt": now,
            "systemsTotal": len(sys_set),
            "locationsTotal": len(loc_set),
            "typesTotal": len(type_set),
        },
        # 只保留 mining_data.json 里实际出现的 key, 避免冗余
        "systems":   {k: v for k, v in SYSTEMS.items() if k in sys_set},
        "locations": {k: v for k, v in LOCATIONS.items() if k in loc_set},
        "types":     {k: v for k, v in TYPES.items() if k in type_set},
    }
    path = os.path.join(args.out, "location_translations.json")
    json.dump(out, open(path, "w", encoding="utf-8"),
              ensure_ascii=False, indent=2)
    print(f"\n写出 {path}")
    print(f"  systems   {len(out['systems'])}/{len(sys_set)}")
    print(f"  locations {len(out['locations'])}/{len(loc_set)}")
    print(f"  types     {len(out['types'])}/{len(type_set)}")


if __name__ == "__main__":
    main()
