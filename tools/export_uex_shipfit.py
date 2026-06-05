#!/usr/bin/env python3
"""
UEX Corp API 2.0 飞船配装数据导出工具。

抓取:
  - vehicles?is_spaceship=1     (飞船列表)
  - items?id_category=<id>      (组件列表，按分类分批)

输出: app/src/main/assets/shipfit/uex_shipfit_dataset.json

用法:
    python3 tools/export_uex_shipfit.py
    python3 tools/export_uex_shipfit.py --out app/src/main/assets/shipfit
"""
import argparse
import json
import os
import ssl
import time
import urllib.request

BASE = "https://uexcorp.space/api/2.0"
HEADERS = {"User-Agent": "BugApp-ShipfitSync/1.0"}
TIMEOUT = 20
RETRY = 3

# category_id -> type 映射 (来自 uex_shipfit_dataset.json 的历史数据)
COMPONENT_CATEGORIES = {
    19: "cooler",
    21: "power_plant",
    22: "quantum_drive",
    23: "shield_generator",
    32: "weapon_gun",
    33: "missile_rack",
    34: "missile",
    35: "turret",
    79: "point_defense",
    82: "flight_blade",
    83: "radar",
}

_SSL = ssl.create_default_context()
_SSL.check_hostname = False
_SSL.verify_mode = ssl.CERT_NONE


def _get(url):
    last = None
    for i in range(RETRY):
        try:
            req = urllib.request.Request(url, headers=HEADERS)
            with urllib.request.urlopen(req, timeout=TIMEOUT, context=_SSL) as r:
                return json.loads(r.read())
        except Exception as e:
            last = e
            time.sleep(1.0 * (i + 1))
    raise last


def fetch_ships():
    d = _get(f"{BASE}/vehicles")
    raw = d.get("data") or []
    ships = []
    for s in raw:
        ships.append({
            "id": s["id"],
            "uuid": s.get("uuid"),
            "slug": s.get("slug"),
            "name": s.get("name"),
            "name_full": s.get("name_full"),
            "company": s.get("company_name"),
            "scu": s.get("scu"),
            "crew": s.get("crew"),
            "size": {
                "length": s.get("length"),
                "width": s.get("width"),
                "height": s.get("height"),
            },
            "mass": s.get("mass"),
            "url_photo": s.get("url_photo"),
            "url_photos": s.get("url_photos"),
            "url_store": s.get("url_store"),
        })
    return ships


def fetch_components():
    components = []
    for cid, ctype in COMPONENT_CATEGORIES.items():
        d = _get(f"{BASE}/items?id_category={cid}")
        raw = d.get("data") or []
        for c in raw:
            components.append({
                "id": c["id"],
                "uuid": c.get("uuid"),
                "name": c.get("name"),
                "type": ctype,
                "category_id": cid,
                "category": c.get("category"),
                "section": c.get("section"),
                "company_name": c.get("company_name"),
                "vehicle_name": c.get("vehicle_name"),
                "grade": None,
                "size": c.get("size"),
            })
        print(f"  cat {cid} ({ctype}): {len(raw)} 条")
        time.sleep(0.3)
    return components


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="app/src/main/assets/shipfit")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)

    print("[1/2] 抓取飞船列表 ...")
    ships = fetch_ships()
    print(f"      {len(ships)} 艘")

    print("[2/2] 抓取组件列表 ...")
    components = fetch_components()
    print(f"      合计 {len(components)} 个组件")

    out = {
        "source": "uexcorp",
        "generatedAt": int(time.time()),
        "shipsCount": len(ships),
        "componentsCount": len(components),
        "ships": ships,
        "components": components,
    }

    path = os.path.join(args.out, "uex_shipfit_dataset.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, separators=(",", ":"))
    print(f"\n写出 {path}  ({os.path.getsize(path)/1024:.1f} KB)")


if __name__ == "__main__":
    main()
