#!/usr/bin/env python3
"""
Export English -> Chinese mission name translations from flowcld SCM API.

Source: https://flowcld.xyz (origin.flowcld.top/app-api/product/blueprint/page)
Each reward blueprint exposes parallel arrays `rewardMissions` (EN) and
`rewardMissionsCn` (CN). We aggregate them into one global EN -> CN table,
matched at runtime by mission title text (sc-craft uses the same EN strings).

Usage:
    python3 tools/gen_mission_translations.py [--version N] [--out-dir PATH]

Output:
    mission_translations.json  (in app/src/main/assets/blueprint by default)

Format:
    { "version": N, "generatedAt": ts, "missions": { "EN title": "中文标题", ... } }

Hot-update: same convention as other datasets (BlueprintDataRepository).
Increment --version, upload to your CDN, App auto-fetches when remote > local.
"""
import argparse
import json
import os
import ssl
import sys
import time
import urllib.request

BASE = "https://origin.flowcld.top/app-api"
HEADERS = {
    "User-Agent": "Mozilla/5.0",
    "Accept": "application/json",
    "tenant-id": "1",
}
PAGE_SIZE = 100
SLEEP = 0.15

_SSL = ssl.create_default_context()
_SSL.check_hostname = False
_SSL.verify_mode = ssl.CERT_NONE


def _get(url):
    req = urllib.request.Request(url, headers=HEADERS)
    with urllib.request.urlopen(req, timeout=20, context=_SSL) as r:
        return json.loads(r.read().decode("utf-8"))


def _safe_arr(s):
    if not s:
        return []
    try:
        v = json.loads(s)
        return v if isinstance(v, list) else []
    except Exception:
        return []


def fetch_all_reward_blueprints():
    out, page = [], 1
    while True:
        url = f"{BASE}/product/blueprint/page?pageNo={page}&pageSize={PAGE_SIZE}&isReward=true"
        try:
            d = _get(url)
        except Exception as e:
            print(f"  page {page} error: {e}", file=sys.stderr)
            break
        if d.get("code") != 0:
            print(f"  page {page} api error: {d.get('msg')}", file=sys.stderr)
            break
        items = (d.get("data") or {}).get("list") or []
        out.extend(items)
        if len(items) < PAGE_SIZE:
            break
        page += 1
        time.sleep(SLEEP)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", type=int, required=True, help="Data version (increment per release)")
    ap.add_argument("--out-dir", default=None, help="Output dir (default: app/src/main/assets/blueprint)")
    args = ap.parse_args()

    out_dir = args.out_dir or os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "app", "src", "main", "assets", "blueprint",
    )
    os.makedirs(out_dir, exist_ok=True)

    print("Fetching reward blueprints...", file=sys.stderr)
    items = fetch_all_reward_blueprints()
    print(f"  {len(items)} blueprints scanned", file=sys.stderr)

    missions = {}
    conflicts = 0
    for it in items:
        en = _safe_arr(it.get("rewardMissions"))
        cn = _safe_arr(it.get("rewardMissionsCn"))
        for e, c in zip(en, cn):
            e = (e or "").strip()
            c = (c or "").strip()
            if not e or not c or e == c:
                continue
            prev = missions.get(e)
            if prev and prev != c:
                conflicts += 1
                continue
            missions[e] = c

    out = {
        "version": args.version,
        "generatedAt": int(time.time()),
        "missions": missions,
    }
    out_path = os.path.join(out_dir, "mission_translations.json")
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, separators=(",", ":"))

    size_kb = max(1, os.path.getsize(out_path) // 1024)
    print(f"Done -> {out_path} ({size_kb} KB, {len(missions)} pairs, {conflicts} conflicts ignored)", file=sys.stderr)


if __name__ == "__main__":
    main()
