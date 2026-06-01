#!/usr/bin/env python3
"""
Export blueprint reward-mission details from flowcld SCM into one JSON.

Source (public, no auth needed; header tenant-id:1):
    GET /app-api/product/blueprint/page?isReward=true        -> blueprint list (numeric id, name)
    GET /app-api/product/blueprint/reward-mission-details?id={numericBlueprintId}
        -> [ {missionGuid, missionType, title, titleCn, isCombatMission,
              rewardUec, rewardChance, locations(JSON str)} ]

Richer fields (faction / reputation / time limit / cooldown / legality tags) live
behind login and are intentionally NOT scraped here ("medium card" scope).

Output (default app/src/main/assets/blueprint/scm_blueprint_missions.json):
    {
      "version": N, "generatedAt": ts,
      "missionTypes": { "Refueling": "加油", ... },
      "missions": {
        "<missionGuid>": {
          "missionType": "Refueling",
          "title": "...", "titleCn": "...(占位符已清洗)",
          "isCombat": bool, "rewardUec": 238500, "rewardChance": 1.0,
          "locations": [ {"name","nameCn","system","systemCn","type"} ],
          "blueprints": [ {"nameEn": "...", "dropChance": 1.0} ]   # 反向掉落列表
        }, ...
      },
      "blueprintMissions": { "<blueprintNameEn>": ["<missionGuid>", ...] }
    }

Usage:
    python3 tools/gen_blueprint_missions.py --version N [--out-dir PATH]

Hot-update: same convention as other datasets (BlueprintDataRepository).
"""
import argparse
import json
import os
import re
import ssl
import sys
import time
import urllib.request

BASE = "https://origin.flowcld.top/app-api"
HEADERS = {"User-Agent": "Mozilla/5.0", "Accept": "application/json", "tenant-id": "1"}
PAGE_SIZE = 100
SLEEP = 0.12

_SSL = ssl.create_default_context()
_SSL.check_hostname = False
_SSL.verify_mode = ssl.CERT_NONE

# 把未填充的本地化变量洗成可读中文占位（值本身是运行时变量，没有唯一正确值）。
_PLACEHOLDER = {
    "ship": "「舰船」", "location": "「地点」", "target": "「目标」",
    "targetname": "「目标」", "person": "「人物」", "company": "「公司」",
    "system": "「星系」", "planet": "「星球」", "moon": "「卫星」",
    "org": "「组织」", "faction": "「派系」", "item": "「物品」",
    "cargo": "「货物」", "amount": "「数量」", "quantity": "「数量」",
}
_MISSION_TOKEN = re.compile(r"~mission\((\w+)\)")
_BRACKET_TOKEN = re.compile(r"\[([A-Za-z][A-Za-z _]*)\]")


def _clean_placeholders(s):
    if not s:
        return s

    def repl_m(m):
        return _PLACEHOLDER.get(m.group(1).lower(), "「" + m.group(1) + "」")

    def repl_b(m):
        return _PLACEHOLDER.get(m.group(1).strip().lower(), "「" + m.group(1).strip() + "」")

    return _BRACKET_TOKEN.sub(repl_b, _MISSION_TOKEN.sub(repl_m, s))


def _get(url):
    req = urllib.request.Request(url, headers=HEADERS)
    with urllib.request.urlopen(req, timeout=25, context=_SSL) as r:
        return json.loads(r.read().decode("utf-8"))


def _parse_locations(raw):
    out = []
    try:
        arr = json.loads(raw) if raw else []
    except Exception:
        return out
    seen = set()
    for l in arr if isinstance(arr, list) else []:
        name = (l.get("name") or "").strip()
        key = (name, (l.get("system") or "").strip())
        if not name or key in seen:
            continue
        seen.add(key)
        out.append({
            "name": name,
            "nameCn": (l.get("nameCn") or "").strip() or None,
            "system": (l.get("system") or "").strip() or None,
            "systemCn": (l.get("systemCn") or "").strip() or None,
            "type": (l.get("type") or "").strip() or None,
        })
    return out


def fetch_blueprints():
    out, page = [], 1
    while True:
        url = f"{BASE}/product/blueprint/page?pageNo={page}&pageSize={PAGE_SIZE}&isReward=true"
        d = _get(url)
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
    ap.add_argument("--version", type=int, required=True)
    ap.add_argument("--out-dir", default=None)
    args = ap.parse_args()

    out_dir = args.out_dir or os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "app", "src", "main", "assets", "blueprint",
    )
    os.makedirs(out_dir, exist_ok=True)

    print("Fetching reward blueprints...", file=sys.stderr)
    bps = fetch_blueprints()
    print(f"  {len(bps)} blueprints", file=sys.stderr)

    # 全局 任务类型英->中 映射（来自 page 列表的并行数组）。
    mission_types = {}
    for it in bps:
        en = it.get("rewardMissionTypes") or []
        cn = it.get("rewardMissionTypesCn") or []
        if isinstance(en, str):
            try:
                en = json.loads(en)
            except Exception:
                en = []
        if isinstance(cn, str):
            try:
                cn = json.loads(cn)
            except Exception:
                cn = []
        for e, c in zip(en, cn):
            e, c = (e or "").strip(), (c or "").strip()
            if e and c and e not in mission_types:
                mission_types[e] = c

    missions = {}
    blueprint_missions = {}
    n_with = 0
    for i, it in enumerate(bps):
        bid = it.get("id")
        name_en = it.get("blueprintName")
        if bid is None or not name_en:
            continue
        try:
            d = _get(f"{BASE}/product/blueprint/reward-mission-details?id={bid}")
        except Exception as e:
            print(f"  {name_en} ({bid}) error: {e}", file=sys.stderr)
            continue
        rows = d.get("data") or []
        if not rows:
            continue
        n_with += 1
        guids = []
        for m in rows:
            guid = m.get("missionGuid")
            if not guid:
                continue
            chance = m.get("rewardChance")
            chance = float(chance) if chance is not None else None
            if guid not in missions:
                missions[guid] = {
                    "missionType": (m.get("missionType") or "").strip() or None,
                    "title": (m.get("title") or "").strip() or None,
                    "titleCn": _clean_placeholders((m.get("titleCn") or "").strip()) or None,
                    "isCombat": bool(m.get("isCombatMission")),
                    "rewardUec": m.get("rewardUec"),
                    "rewardChance": chance,
                    "locations": _parse_locations(m.get("locations")),
                    "blueprints": [],
                }
            missions[guid]["blueprints"].append({"nameEn": name_en, "dropChance": chance})
            guids.append(guid)
        if guids:
            blueprint_missions[name_en] = guids
        if (i + 1) % 50 == 0:
            print(f"  ...{i + 1}/{len(bps)}", file=sys.stderr)
        time.sleep(SLEEP)

    out = {
        "version": args.version,
        "generatedAt": int(time.time()),
        "missionTypes": mission_types,
        "missions": missions,
        "blueprintMissions": blueprint_missions,
    }
    out_path = os.path.join(out_dir, "scm_blueprint_missions.json")
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, separators=(",", ":"))

    size_kb = max(1, os.path.getsize(out_path) // 1024)
    print(
        f"Done -> {out_path} ({size_kb} KB, {len(missions)} missions, "
        f"{n_with} blueprints with missions, {len(mission_types)} types)",
        file=sys.stderr,
    )


if __name__ == "__main__":
    main()
