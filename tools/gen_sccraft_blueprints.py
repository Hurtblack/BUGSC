#!/usr/bin/env python3
"""
Export full blueprint data (quality curves + missions) from sc-craft.tools public API.

Usage:
    python3 tools/gen_sccraft_blueprints.py [--version N] [--out-dir PATH]

Outputs:
    sccraft_blueprints.json  — full per-slot quality curves + mission list, keyed by EN name

Hot-update flow: increment --version, upload to CDN, users auto-update via BlueprintDataRepository.
"""

import json
import os
import ssl
import sys
import time
import urllib.request
import urllib.error
import argparse

BASE = "https://sc-craft.tools/api"
HEADERS = {
    "User-Agent": "Mozilla/5.0 (compatible; SC-Craft-Export/1.0)",
    "Accept": "application/json",
}
CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE


def get_json(path):
    url = BASE + path
    req = urllib.request.Request(url, headers=HEADERS)
    with urllib.request.urlopen(req, timeout=20, context=CTX) as resp:
        return json.loads(resp.read())


def fetch_all_blueprints():
    page = 1
    all_items = []
    while True:
        data = get_json(f"/blueprints?limit=100&page={page}")
        items = data.get("items", [])
        pagination = data.get("pagination", {})
        all_items.extend(items)
        print(f"  Page {page}/{pagination.get('pages', '?')} — {len(all_items)}/{pagination.get('total', '?')} blueprints", file=sys.stderr)
        if page >= pagination.get("pages", 1):
            break
        page += 1
        time.sleep(0.15)
    return all_items


def expand_effects(effects):
    """Flatten each quality effect into one or more linear ScCraftEffect segments.

    sc-craft.tools returns piecewise curves via a `ranges` array (e.g. flat 0-499
    then a +5%/+15% bonus 500-1000). The app's ScCraftEffect is a single linear
    segment, but a slot holds a LIST of effects and aggregateDeltas sums their
    deltas by stat — and adjacent segments meet at modifier 1.0, so emitting one
    segment per range reconstructs the piecewise curve exactly. When `ranges` is
    absent, fall back to the effect's flat fields.
    """
    out = []
    for e in effects:
        stat = e.get("stat", "")
        loc = e.get("stat_loc_key", "")
        ranges = e.get("ranges") or []
        if ranges:
            for r in ranges:
                out.append({
                    "stat": stat,
                    "statLocKey": loc,
                    "qualityMin": r.get("quality_min", 0),
                    "qualityMax": r.get("quality_max", 1000),
                    "modifierAtMin": r.get("modifier_at_min", 1.0),
                    "modifierAtMax": r.get("modifier_at_max", 1.0),
                })
        else:
            out.append({
                "stat": stat,
                "statLocKey": loc,
                "qualityMin": e.get("quality_min", 0),
                "qualityMax": e.get("quality_max", 1000),
                "modifierAtMin": e.get("modifier_at_min", 1.0),
                "modifierAtMax": e.get("modifier_at_max", 1.0),
            })
    return out


def convert_blueprint(item):
    """Convert sc-craft.tools API blueprint to our compact storage format."""
    ingredients = item.get("ingredients") or []
    slots = []
    for ing in ingredients:
        options = ing.get("options") or []
        effects = ing.get("quality_effects") or []
        # Take first option as the default material
        mat = options[0] if options else {}
        slot_obj = {
            "slot": ing.get("slot", ""),
            "slotLocKey": ing.get("slot_loc_key", ""),
            "material": mat.get("name", ing.get("name", "")),
            "materialLocKey": mat.get("loc_key", ""),
            "quantityScu": mat.get("quantity_scu", ing.get("quantity_scu", 0)),
            "minQuality": mat.get("min_quality", 1),
            "qualityEffects": expand_effects(effects),
        }
        # Include all material options for multi-option slots
        if len(options) > 1:
            slot_obj["allOptions"] = [
                {
                    "name": o.get("name", ""),
                    "locKey": o.get("loc_key", ""),
                    "quantityScu": o.get("quantity_scu", 0),
                    "minQuality": o.get("min_quality", 1),
                }
                for o in options
            ]
        slots.append(slot_obj)

    missions = [
        {
            "missionId": m.get("mission_id"),
            "name": m.get("name", ""),
            "dropChance": float(m.get("drop_chance") or 0),
        }
        for m in (item.get("missions") or [])
    ]

    return {
        "blueprintId": item.get("blueprint_id", ""),
        "category": item.get("category", ""),
        "craftTimeSeconds": item.get("craft_time_seconds", 0),
        "tiers": item.get("tiers", 1),
        "slots": slots,
        "missions": missions,
    }


def main():
    parser = argparse.ArgumentParser(description="Export sc-craft.tools blueprints")
    parser.add_argument("--version", type=int, default=1, help="Data version integer (increment each run)")
    parser.add_argument("--out-dir", default="app/src/main/assets/blueprint",
                        help="Output directory (default: app/src/main/assets/blueprint)")
    args = parser.parse_args()

    print("Fetching game version...", file=sys.stderr)
    try:
        versions = get_json("/versions")
        live = next((v for v in versions if v.get("channel") == "live" and v.get("active")), None)
        game_version = live["version"] if live else "unknown"
    except Exception as e:
        game_version = "unknown"
        print(f"  Warning: could not fetch version: {e}", file=sys.stderr)

    print(f"Game version: {game_version}", file=sys.stderr)
    print("Fetching blueprints...", file=sys.stderr)
    items = fetch_all_blueprints()

    blueprints = {}
    skipped = 0
    for item in items:
        name_en = (item.get("name") or "").strip()
        if not name_en:
            skipped += 1
            continue
        blueprints[name_en] = convert_blueprint(item)

    print(f"Converted {len(blueprints)} blueprints, skipped {skipped}", file=sys.stderr)

    out = {
        "version": args.version,
        "gameVersion": game_version,
        "generatedAt": int(time.time()),
        "blueprints": blueprints,
    }

    os.makedirs(args.out_dir, exist_ok=True)
    out_path = os.path.join(args.out_dir, "sccraft_blueprints.json")
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, separators=(",", ":"))

    size_kb = os.path.getsize(out_path) // 1024
    print(f"Done -> {out_path} ({size_kb} KB, {len(blueprints)} blueprints)", file=sys.stderr)


main()
