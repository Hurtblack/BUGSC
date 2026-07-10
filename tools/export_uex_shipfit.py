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
import re
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


def normalize_ship_name(name):
    if not name:
        return ""
    text = name.lower()
    text = text.replace("&", " and ")
    text = re.sub(r"\b(anvil|crusader|aegis|drake|rsi|misc|origin|esperia|gatac|argo|tumbril|greycat|kruger)\b", " ", text)
    text = re.sub(r"\bmk\s+i\b", "", text)
    text = re.sub(r"\b(mk|mark)\s+([ivx]+|\d+)\b", r"\2", text)
    text = re.sub(r"\bstarlifter\b", " ", text)
    text = re.sub(r"\btank\b", " ", text)
    text = re.sub(r"[^a-z0-9]+", " ", text)
    return " ".join(text.split())


def load_sale_prices(path):
    if not path or not os.path.exists(path):
        return []
    with open(path, "r", encoding="utf-8") as f:
        doc = json.load(f)
    return doc.get("ships") or []


def apply_sale_prices(ships, prices):
    by_name = {}
    for row in prices:
        price = row.get("sale_price_cents")
        if not isinstance(price, int):
            continue
        key = normalize_ship_name(row.get("name"))
        if key:
            by_name[key] = price

    matched = 0
    for ship in ships:
        candidates = [
            normalize_ship_name(ship.get("name")),
            normalize_ship_name(ship.get("name_full")),
        ]
        price = next((by_name[k] for k in candidates if k in by_name), None)
        if price is None:
            continue
        ship["sale_price_cents"] = price
        matched += 1
    return matched


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
    ap.add_argument("--prices", default="app/src/main/assets/shipfit/rsi_ship_prices.json")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)

    print("[1/2] 抓取飞船列表 ...")
    ships = fetch_ships()
    print(f"      {len(ships)} 艘")
    prices = load_sale_prices(args.prices)
    if prices:
        matched = apply_sale_prices(ships, prices)
        print(f"      合并 RSI 售卖价: {matched} 艘")
    else:
        print("      未找到 RSI 售卖价快照，跳过 sale_price_cents")

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
