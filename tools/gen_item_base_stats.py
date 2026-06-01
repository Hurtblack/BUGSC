#!/usr/bin/env python3
"""
Export base item stats from the star-citizen.wiki API for every craftable blueprint.

sc-craft.tools only provides quality MODIFIERS (which stats change and by how much),
not the absolute base values (e.g. 单发伤害 165, 射速 30). This script pulls those
base values from api.star-citizen.wiki and stores only the stats that our blueprints
actually modify (see STAT_PATHS), keyed by the same EN name sc-craft uses.

Usage:
    python3 tools/gen_item_base_stats.py [--version N] [--out-dir PATH]

Output:
    item_base_stats.json  — { version, generatedAt, stats: { "<EN name>": { "<statLocKey>": value } } }

Notes:
- Recoil (smoothness/handling/kick) has no stable absolute base exposed by API,
  so it is intentionally omitted — the app shows only ±% change for recoil.
- Armor Damage Mitigation now reads from structured suit_armor/clothing data.
- Hot-update flow mirrors sccraft_blueprints.json: bump --version, upload to CDN.
"""

import argparse
import json
import os
import ssl
import sys
import threading
import time
import urllib.parse
import urllib.request

WIKI = "https://api.star-citizen.wiki/api/v2"
HEADERS = {"User-Agent": "Mozilla/5.0 (compatible; SC-ItemStats-Export/1.0)", "Accept": "application/json"}
CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE

# statLocKey -> dotted path (or candidate paths) into the item detail JSON where the
# base value lives. Missing path => stat omitted for that item.
STAT_PATHS = {
    "statname_gpp_weapon_damage": "personal_weapon.damage_per_shot",
    "statname_gpp_weapon_firerate": "personal_weapon.rpm",
    "statname_gpp_armor_temperaturemax": "temperature_resistance.max",
    "statname_gpp_armor_temperaturemin": "temperature_resistance.min",
    "statname_gpp_armor_radiationdissipation": "radiation_resistance.radiation_dissipation_rate",
    "statname_gpp_armor_radiationcapacity": [
        "suit_armor.radiation_resistance.maximum_radiation_capacity",
        "clothing.radiation_resistance.maximum_radiation_capacity",
        "radiation_resistance.maximum_radiation_capacity",
    ],
    "statname_gpp_armor_gforceresistance": [
        "suit_armor.gforce_resistance",
        "clothing.gforce_resistance",
        "gforce_resistance",
    ],
    "statname_gpp_armor_signature_em": [
        "suit_armor.signature.electromagnetic",
        "clothing.signature.electromagnetic",
    ],
    # Use structured resistance map first; fallback to clothing branch.
    # Values are usually negative (e.g. -0.4 = 40% damage reduction).
    "statname_gpp_armor_damagemitigation": [
        "suit_armor.damage_resistance_map.physical_change",
        "clothing.damage_resistance_map.physical_change",
    ],
    "statname_gpp_armor_damagemitigation_energy": [
        "suit_armor.damage_resistance_map.energy_change",
        "clothing.damage_resistance_map.energy_change",
    ],
    "statname_gpp_armor_damagemitigation_distortion": [
        "suit_armor.damage_resistance_map.distortion_change",
        "clothing.damage_resistance_map.distortion_change",
    ],
    "statname_gpp_armor_damagemitigation_thermal": [
        "suit_armor.damage_resistance_map.thermal_change",
        "clothing.damage_resistance_map.thermal_change",
    ],
    "statname_gpp_armor_damagemitigation_biochemical": [
        "suit_armor.damage_resistance_map.biochemical_change",
        "clothing.damage_resistance_map.biochemical_change",
    ],
    "statname_gpp_armor_damagemitigation_stun": [
        "suit_armor.damage_resistance_map.stun_change",
        "clothing.damage_resistance_map.stun_change",
    ],
    "statname_gpp_health_maxhealth": "durability.health",
    "statname_gpp_shield_maxhealth": "shield.max_health",
    "statname_gpp_itemresource_coolantgeneration": "cooler.coolant_segment_generation",
    "statname_gpp_itemresource_powergeneration": "power_plant.power_segment_generation",
    "statname_gpp_radar_minaimassistdistance": "radar.aim_assist.distance_min_assignment",
    "statname_gpp_radar_maxaimassistdistance": "radar.aim_assist.distance_max_assignment",
    "statname_gpp_quantum_speed": "quantum_drive.standard_jump.drive_speed",
    "statname_gpp_quantum_fuelrequirement": "quantum_drive.quantum_fuel_requirement",
    "statname_gpp_tractor_fullstrengthdistance": "tractor_beam.range.full_strength_distance",
    "statname_gpp_tractor_maxdistance": "tractor_beam.range.max",
    "ui_weapons_tractor_beamforce": "tractor_beam.force.max",
    "statname_gpp_tractor_maxvolume": "tractor_beam.force.max_volume",
    "statname_gpp_hullscraping_radius": "salvage_modifier.radius_multiplier",
    "statname_gpp_hullscraping_speed": "salvage_modifier.salvage_speed_multiplier",
    "statname_gpp_hullscraping_efficiency": "salvage_modifier.extraction_efficiency",
}

ALWAYS_ARMOR_KEYS = {
    "statname_gpp_armor_damagemitigation",
    "statname_gpp_armor_damagemitigation_energy",
    "statname_gpp_armor_damagemitigation_distortion",
    "statname_gpp_armor_damagemitigation_thermal",
    "statname_gpp_armor_damagemitigation_biochemical",
    "statname_gpp_armor_damagemitigation_stun",
    "statname_gpp_armor_radiationcapacity",
    "statname_gpp_armor_radiationdissipation",
    "statname_gpp_armor_gforceresistance",
    "statname_gpp_armor_signature_em",
    "statname_gpp_armor_temperaturemax",
    "statname_gpp_armor_temperaturemin",
}


def get_json(url, retries=3):
    last = None
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers=HEADERS)
            with urllib.request.urlopen(req, timeout=30, context=CTX) as resp:
                return json.loads(resp.read())
        except Exception as e:
            last = e
            time.sleep(0.5 * (attempt + 1))  # backoff on SSL EOF / transient errors
    raise last


def dig(obj, path):
    cur = obj
    for part in path.split("."):
        if not isinstance(cur, dict):
            return None
        cur = cur.get(part)
        if cur is None:
            return None
    return cur


def dig_any(obj, path_or_paths):
    if isinstance(path_or_paths, str):
        return dig(obj, path_or_paths)
    for path in path_or_paths:
        v = dig(obj, path)
        if v is not None:
            return v
    return None


def stats_needed_by_blueprint(sccraft_path):
    """Map each blueprint EN name -> set of statLocKeys it modifies."""
    data = json.load(open(sccraft_path, encoding="utf-8"))
    out = {}
    for name, bp in data["blueprints"].items():
        keys = set()
        for slot in bp.get("slots", []):
            for e in slot.get("qualityEffects", []):
                k = e.get("statLocKey") or e.get("stat")
                if k in STAT_PATHS:
                    keys.add(k)
        category = (bp.get("category") or "").lower()
        if "armour" in category or "armor" in category:
            keys.update(ALWAYS_ARMOR_KEYS)
        out[name] = keys  # keep even empty so we know the universe of names
    return out


def fetch_detail(name):
    q = urllib.parse.quote(name)
    search = get_json(f"{WIKI}/items?filter[name]={q}&limit=10")
    items = search.get("data", [])
    exact = [x for x in items if x.get("name") == name] or items
    if not exact:
        return None
    # Prefer a craftable variant when several share the name.
    chosen = next((x for x in exact if x.get("is_craftable")), exact[0])
    return get_json(f"{WIKI}/items/{chosen['uuid']}").get("data")


def extract_values(name, needed):
    """Look up one blueprint's base stats. Returns dict of statLocKey->value (may be empty)."""
    detail = fetch_detail(name)
    if detail is None:
        return {}
    values = {}
    for key in needed:
        v = dig_any(detail, STAT_PATHS[key])
        if isinstance(v, (int, float)):
            values[key] = v
    return values


def write_output(out_path, version, stats):
    """Atomic checkpoint write so a kill never leaves a half-written file."""
    out = {"version": version, "generatedAt": int(time.time()), "stats": stats}
    tmp = out_path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, separators=(",", ":"))
    os.replace(tmp, out_path)


def main():
    ap = argparse.ArgumentParser(description="Export base item stats from star-citizen.wiki")
    ap.add_argument("--version", type=int, default=1)
    ap.add_argument("--out-dir", default="app/src/main/assets/blueprint")
    ap.add_argument("--sccraft", default="app/src/main/assets/blueprint/sccraft_blueprints.json")
    ap.add_argument("--limit", type=int, default=0, help="Debug: only process first N blueprints")
    ap.add_argument("--workers", type=int, default=10, help="Concurrent request workers")
    ap.add_argument("--checkpoint", type=int, default=100, help="Flush output every N completed items")
    ap.add_argument("--resume", action="store_true", help="Skip names already present in existing output file")
    ap.add_argument("--retry-unmatched", type=int, default=1, help="Retry rounds for unmatched items after first pass")
    args = ap.parse_args()

    needed = stats_needed_by_blueprint(args.sccraft)
    names = [n for n, ks in needed.items() if ks]  # only blueprints with at least one mappable stat
    if args.limit:
        names = names[: args.limit]

    os.makedirs(args.out_dir, exist_ok=True)
    out_path = os.path.join(args.out_dir, "item_base_stats.json")

    stats = {}
    if args.resume and os.path.exists(out_path):
        try:
            stats = json.load(open(out_path, encoding="utf-8")).get("stats", {})
            print(f"Resuming: {len(stats)} already done", file=sys.stderr)
        except Exception:
            stats = {}
    todo = [n for n in names if n not in stats]
    print(f"{len(names)} blueprints modify mappable stats; {len(todo)} to fetch "
          f"({args.workers} workers)", file=sys.stderr)

    from concurrent.futures import ThreadPoolExecutor, as_completed
    lock = threading.Lock()

    def run_round(candidates, round_idx):
        done = matched = unmatched = 0
        misses = []

        def task(nm):
            try:
                return nm, extract_values(nm, needed[nm]), None
            except Exception as e:
                return nm, None, e

        with ThreadPoolExecutor(max_workers=args.workers) as pool:
            futures = {pool.submit(task, nm): nm for nm in candidates}
            for fut in as_completed(futures):
                nm, values, err = fut.result()
                with lock:
                    done += 1
                    if err is not None:
                        unmatched += 1
                        misses.append(nm)
                        print(f"  [round {round_idx}] ERROR {nm}: {err}", file=sys.stderr)
                    elif values:
                        stats[nm] = values
                        matched += 1
                    else:
                        unmatched += 1
                        misses.append(nm)
                    if done % 50 == 0:
                        print(
                            f"  [round {round_idx}] {done}/{len(candidates)} — matched {matched}, unmatched {unmatched}",
                            file=sys.stderr,
                        )
                    if done % args.checkpoint == 0:
                        write_output(out_path, args.version, stats)
        return misses, matched, unmatched

    misses, _, _ = run_round(todo, 1)
    for i in range(args.retry_unmatched):
        if not misses:
            break
        print(f"Retry round {i + 1}: retrying {len(misses)} unmatched items...", file=sys.stderr)
        misses, _, _ = run_round(misses, i + 2)

    write_output(out_path, args.version, stats)
    unmatched_path = os.path.join(args.out_dir, "item_base_stats_unmatched.json")
    with open(unmatched_path, "w", encoding="utf-8") as f:
        json.dump(
            {"generatedAt": int(time.time()), "unmatched": sorted(set(misses))},
            f,
            ensure_ascii=False,
            separators=(",", ":"),
        )
    size_kb = os.path.getsize(out_path) // 1024
    print(
        f"Done -> {out_path} ({size_kb} KB) — {len(stats)} items with stats; "
        f"final unmatched={len(set(misses))} -> {unmatched_path}",
        file=sys.stderr,
    )


if __name__ == "__main__":
    main()
