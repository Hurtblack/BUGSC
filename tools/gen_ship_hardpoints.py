#!/usr/bin/env python3
"""
从 Star Citizen Wiki API 生成飞船「真实武器硬点」数据。

背景:
  app 原先的武器槽来自 erkul (erkul_ship_slots_live.json)，erkul 对「焊死炮塔」
  只暴露一个炮塔座 slot、不展开炮塔内部的实际武器位，导致 F8C 等船武器统计错误
  (F8C 被算成 2×S2+5×S3，正确应为 4×S2+4×S3)。

  SC Wiki API 的 hardpoints 递归带 children，炮塔内部的 WeaponGun 全部展开，
  且每个武器槽带 min_size/max_size，空槽也能拿到尺寸。本脚本据此重建武器硬点。

数据源:
  - 飞船清单: app/src/main/assets/shipfit/erkul_ship_slots_live.json  (取 localName 作为输出 key)
  - 硬点详情: https://api.star-citizen.wiki/api/v2/vehicles/{slug}?include=hardpoints

输出:
  app/src/main/assets/shipfit/ship_hardpoints.json
  结构: { "<localName>": { "WeaponGun": [{"min":2,"max":2}, ...],
                          "MissileLauncher": [...], "WeaponDefensive": [...] } }
  仅含武器三类(枪/导弹架/反制器)。非武器类(电源/护盾/冷却/量子/雷达)仍由 erkul 提供。

用法:
    python3 tools/gen_ship_hardpoints.py
    python3 tools/gen_ship_hardpoints.py --no-cache    # 忽略本地缓存重新拉取
"""
import argparse
import json
import os
import ssl
import sys
import time
import urllib.parse
import urllib.request

API = "https://api.star-citizen.wiki/api/v2"
HEADERS = {"User-Agent": "BugApp-HardpointSync/1.0"}
TIMEOUT = 40
RETRY = 3
SLEEP = 0.2  # 逐船限速

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ERKUL = os.path.join(ROOT, "app/src/main/assets/shipfit/erkul_ship_slots_live.json")
OUT = os.path.join(ROOT, "app/src/main/assets/shipfit/ship_hardpoints.json")
CACHE_DIR = os.path.join(ROOT, "tools/.cache/sc_wiki")

# 只保留这三类真实武器位; 其余(Shield/PowerPlant/...)交给 erkul。
WEAPON_TYPES = ("WeaponGun", "MissileLauncher", "WeaponDefensive")

_SSL = ssl.create_default_context()
_SSL.check_hostname = False
_SSL.verify_mode = ssl.CERT_NONE


def _http(url):
    last = None
    for i in range(RETRY):
        try:
            req = urllib.request.Request(url, headers=HEADERS)
            with urllib.request.urlopen(req, timeout=TIMEOUT, context=_SSL) as r:
                return json.loads(r.read())
        except Exception as e:  # noqa: BLE001
            last = e
            time.sleep(0.8 * (i + 1))
    raise last


def load_erkul_ships():
    """返回 [(localName, name)]，去重保序。"""
    data = json.load(open(ERKUL, encoding="utf-8"))
    out, seen = [], set()

    def walk(o):
        if isinstance(o, dict):
            if o.get("localName") and "slots" in o:
                ln = o["localName"]
                if ln not in seen:
                    seen.add(ln)
                    out.append((ln, o.get("name") or ln))
            else:
                for v in o.values():
                    walk(v)
        elif isinstance(o, list):
            for v in o:
                walk(v)

    walk(data)
    return out


def build_index(use_cache):
    """class_name.lower() -> slug，用于把 erkul localName 映射到 SC Wiki slug。"""
    cache = os.path.join(CACHE_DIR, "_index.json")
    if use_cache and os.path.exists(cache):
        return json.load(open(cache, encoding="utf-8"))
    idx = {}
    page = 1
    while True:
        d = _http(f"{API}/vehicles?page[size]=100&page[number]={page}")
        for v in d.get("data", []):
            cn = (v.get("class_name") or "").lower()
            if cn:
                idx[cn] = v.get("slug") or cn.replace("_", "-")
        meta = d.get("meta", {})
        if page >= meta.get("last_page", page):
            break
        page += 1
    os.makedirs(CACHE_DIR, exist_ok=True)
    json.dump(idx, open(cache, "w", encoding="utf-8"), ensure_ascii=False)
    return idx


def fetch_hardpoints(slug, name, use_cache):
    cache = os.path.join(CACHE_DIR, f"{slug}.json")
    if use_cache and os.path.exists(cache):
        return json.load(open(cache, encoding="utf-8"))
    # 优先用 slug 查，失败回退 name
    for key in (slug, name):
        try:
            d = _http(f"{API}/vehicles/{urllib.parse.quote(key)}?include=hardpoints")
            hp = d.get("data", {}).get("hardpoints")
            if hp is not None:
                os.makedirs(CACHE_DIR, exist_ok=True)
                json.dump(hp, open(cache, "w", encoding="utf-8"), ensure_ascii=False)
                time.sleep(SLEEP)
                return hp
        except Exception:  # noqa: BLE001
            continue
    return None


def collect_weapons(hardpoints):
    """递归收集真实武器位; 炮塔内部 WeaponGun 一并展开。返回 {type: [{min,max}]}。"""
    out = {t: [] for t in WEAPON_TYPES}

    def walk(h):
        t = h.get("type")
        if t in out:
            out[t].append({"min": h.get("min_size"), "max": h.get("max_size")})
        for c in h.get("children") or []:
            walk(c)

    for h in hardpoints or []:
        walk(h)
    return {t: v for t, v in out.items() if v}


def summarize(weapons):
    from collections import Counter
    parts = []
    for t in WEAPON_TYPES:
        c = Counter(s["max"] for s in weapons.get(t, []) if s.get("max") is not None)
        if c:
            parts.append(t + ":" + "+".join(f"{n}×S{s}" for s, n in sorted(c.items())))
    return "  ".join(parts) or "(无武器)"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--no-cache", action="store_true")
    ap.add_argument("--out", default=OUT)
    args = ap.parse_args()
    use_cache = not args.no_cache

    ships = load_erkul_ships()
    print(f"erkul 飞船数: {len(ships)}")
    print("建立 class_name -> slug 索引 ...")
    idx = build_index(use_cache)
    print(f"  SC Wiki 收录 {len(idx)} 艘")

    result = {}
    missing, empty = [], []
    for i, (local_name, name) in enumerate(ships, 1):
        slug = idx.get(local_name) or local_name.replace("_", "-")
        hp = fetch_hardpoints(slug, name, use_cache)
        if hp is None:
            missing.append(local_name)
            continue
        weapons = collect_weapons(hp)
        if not weapons:
            empty.append(local_name)
            continue
        result[local_name] = weapons
        if i <= 5 or local_name in ("anvl_lightning_f8c", "anvl_hawk"):
            print(f"  [{i}/{len(ships)}] {local_name}: {summarize(weapons)}")

    json.dump(result, open(args.out, "w", encoding="utf-8"),
              ensure_ascii=False, separators=(",", ":"))
    print(f"\n写出 {args.out}")
    print(f"  覆盖 {len(result)} 艘 / 共 {len(ships)}")
    if missing:
        print(f"  未匹配到 SC Wiki ({len(missing)}): {', '.join(missing[:15])}{' ...' if len(missing) > 15 else ''}")
    if empty:
        print(f"  无武器位 ({len(empty)}): {', '.join(empty[:15])}{' ...' if len(empty) > 15 else ''}")


if __name__ == "__main__":
    sys.exit(main())
