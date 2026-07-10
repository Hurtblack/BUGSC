#!/usr/bin/env python3
"""
RSI pledge store ship price exporter.

Reads the public ship upgrade GraphQL endpoint and writes a compact price
snapshot that can be merged into the local UEX shipfit dataset.
"""
import argparse
import json
import os
import re
import ssl
import time
import urllib.request

BASE = "https://robertsspaceindustries.com"
UPGRADE_GRAPHQL = f"{BASE}/pledge-store/api/upgrade/graphql"
TIMEOUT = 30

QUERY = """
query initShipUpgrade {
  ships {
    id
    name
    msrp
    skus {
      id
      title
      available
      price
    }
  }
}
""".strip()

MANUAL_PRICE_OVERRIDES = [
    {
        "name": "F8C Lightning",
        "msrp_cents": 30000,
        "sale_price_cents": 30000,
        "source": "manual",
        "note": "Not returned by RSI upgrade store; maintained manually.",
    },
    {
        "name": "Javelin",
        "msrp_cents": 300000,
        "sale_price_cents": 300000,
        "source": "manual",
        "note": "Not returned by RSI upgrade store; maintained manually.",
    },
    {
        "name": "Kraken",
        "msrp_cents": 200000,
        "sale_price_cents": 200000,
        "source": "manual",
        "note": "Not returned by RSI upgrade store; maintained manually.",
    },
]

_SSL = ssl.create_default_context()
_SSL.check_hostname = False
_SSL.verify_mode = ssl.CERT_NONE


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


def _post_json(url, payload):
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=data,
        headers={
            "Accept": "application/json",
            "Content-Type": "application/json",
            "Origin": BASE,
            "Referer": f"{BASE}/pledge-store",
            "User-Agent": "BugApp-RsiShipPriceSync/1.0",
        },
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=TIMEOUT, context=_SSL) as r:
        return json.loads(r.read())


def fetch_upgrade_response():
    return _post_json(UPGRADE_GRAPHQL, {"query": QUERY})


def parse_upgrade_response(doc):
    ships = []
    for ship in ((doc.get("data") or {}).get("ships") or []):
        name = ship.get("name")
        if not name:
            continue
        msrp = ship.get("msrp")
        if not isinstance(msrp, int):
            continue
        skus = []
        for sku in ship.get("skus") or []:
            price = sku.get("price")
            if not isinstance(price, int):
                continue
            skus.append({
                "id": sku.get("id"),
                "title": sku.get("title"),
                "available": sku.get("available"),
                "price_cents": price,
            })
        ships.append({
            "id": ship.get("id"),
            "name": name,
            "msrp_cents": msrp,
            "sale_price_cents": msrp,
            "skus": skus,
            "source": "rsi_upgrade_store",
        })
    return ships


def with_manual_overrides(ships):
    result = list(ships)
    existing = {normalize_ship_name(s.get("name")) for s in result}
    for override in MANUAL_PRICE_OVERRIDES:
        key = normalize_ship_name(override.get("name"))
        if key and key not in existing:
            result.append(dict(override))
            existing.add(key)
    return result


def load_uex_dataset(path):
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def write_json(path, data):
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, separators=(",", ":"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="app/src/main/assets/shipfit/rsi_ship_prices.json")
    ap.add_argument("--uex", default="app/src/main/assets/shipfit/uex_shipfit_dataset.json")
    ap.add_argument("--no-patch-uex", action="store_true")
    args = ap.parse_args()

    print("[1/2] 抓取 RSI 升级商店飞船价格 ...")
    response = fetch_upgrade_response()
    ships = with_manual_overrides(parse_upgrade_response(response))
    manual_count = sum(1 for s in ships if s.get("source") == "manual")
    out = {
        "source": "rsi_upgrade_store",
        "generatedAt": int(time.time()),
        "shipsCount": len(ships),
        "manualOverridesCount": manual_count,
        "ships": ships,
    }
    write_json(args.out, out)
    print(f"      写出 {args.out} ({len(ships)} 艘, 手工 {manual_count} 艘)")

    if args.no_patch_uex:
        return

    print("[2/2] 合并 sale_price_cents 到 UEX shipfit 数据 ...")
    import export_uex_shipfit

    uex = load_uex_dataset(args.uex)
    matched = export_uex_shipfit.apply_sale_prices(uex.get("ships") or [], ships)
    write_json(args.uex, uex)
    print(f"      匹配 {matched} 艘，更新 {args.uex}")


if __name__ == "__main__":
    main()
