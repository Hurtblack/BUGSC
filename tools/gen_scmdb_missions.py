#!/usr/bin/env python3
"""
Export SCMDB mission library into an app asset.

This is intentionally separate from blueprint data. SCMDB missions are the
source of truth for mission details; blueprint data only links blueprints to
mission IDs and drop chances.
"""

import argparse
import json
import os
import re
import ssl
import time
import urllib.parse
import urllib.request


SCMDB_BASE = "https://scmdb.net"
DEFAULT_LANG_URL = (
    "https://raw.githubusercontent.com/acewinner1999/SCMDB_RSUI/"
    "refs/heads/main/lang-cn-rsui.json"
)
HEADERS = {"User-Agent": "Mozilla/5.0", "Accept": "application/json,text/plain"}
SSL_CONTEXT = ssl.create_default_context()
SSL_CONTEXT.check_hostname = False
SSL_CONTEXT.verify_mode = ssl.CERT_NONE
TAG_RE = re.compile(r"<[^>]+>")


def get_json(url):
    req = urllib.request.Request(url, headers=HEADERS)
    with urllib.request.urlopen(req, timeout=45, context=SSL_CONTEXT) as resp:
        return json.loads(resp.read().decode("utf-8"))


def latest_game_version():
    versions = get_json(f"{SCMDB_BASE}/data/game-versions.json")
    if not versions:
        raise RuntimeError("SCMDB game-versions.json is empty")
    current = versions[0]
    file_name = current.get("file") or current.get("file_path")
    if not file_name:
        raise RuntimeError("SCMDB game version entry is missing file")
    return current.get("version") or "", urllib.parse.urljoin(f"{SCMDB_BASE}/data/", file_name)


def lang_text(lang_keys, key, fallback=None):
    if not key:
        return None
    entry = lang_keys.get(str(key).lstrip("@"))
    if not isinstance(entry, dict):
        return None
    text = (entry.get("tr") or "").strip()
    if not text:
        return None
    return clean_text(text)


def clean_text(value):
    if value is None:
        return None
    value = str(value).replace("\\n", "\n")
    value = TAG_RE.sub("", value)
    value = value.replace("\r\n", "\n").replace("\r", "\n")
    return "\n".join(line.rstrip() for line in value.split("\n")).strip() or None


def resolved_name(raw, lang_keys):
    if not isinstance(raw, dict):
        return None
    name = raw.get("name")
    name_cn = lang_text(lang_keys, raw.get("nameKey"), name)
    out = {"name": name}
    if name_cn and name_cn != name:
        out["nameCn"] = name_cn
    for key in ("guid", "minReputation", "scopeName", "scopeGuid", "includeWhenSharing"):
        if key in raw and raw.get(key) is not None:
            out[key] = raw.get(key)
    scope_cn = lang_text(lang_keys, raw.get("scopeDisplayNameKey"), raw.get("scopeDisplayName"))
    if raw.get("scopeDisplayName"):
        out["scopeDisplayName"] = raw.get("scopeDisplayName")
    if scope_cn and scope_cn != raw.get("scopeDisplayName"):
        out["scopeDisplayNameCn"] = scope_cn
    return out


def resolve_location(loc):
    if not isinstance(loc, dict) or not loc.get("name"):
        return None
    out = {"name": loc.get("name")}
    for key in ("type", "system", "planet", "moon"):
        if loc.get(key):
            out[key] = loc.get(key)
    return out


def resolve_locations(ids, pools):
    out = []
    seen = set()
    for loc_id in ids or []:
        loc = resolve_location(pools.get(loc_id))
        if not loc:
            continue
        key = (loc.get("name"), loc.get("system"), loc.get("planet"), loc.get("moon"))
        if key in seen:
            continue
        seen.add(key)
        out.append(loc)
    return out


def resolve_faction_rewards(index, scm, lang_keys):
    pools = scm.get("factionRewardsPools") or []
    if index is None or index >= len(pools):
        return []
    out = []
    factions = scm.get("factions") or {}
    scopes = scm.get("scopes") or {}
    for row in pools[index] or []:
        faction = factions.get(row.get("factionGuid")) or {}
        scope = scopes.get(row.get("scopeGuid")) or {}
        item = {
            "amount": row.get("amount"),
            "factionGuid": row.get("factionGuid"),
            "scopeGuid": row.get("scopeGuid"),
            "factionName": faction.get("name"),
            "scopeName": scope.get("displayName") or scope.get("name"),
        }
        faction_cn = lang_text(lang_keys, faction.get("nameKey"), faction.get("name"))
        scope_cn = lang_text(lang_keys, scope.get("displayNameKey") or scope.get("nameKey"), item["scopeName"])
        if faction_cn and faction_cn != item["factionName"]:
            item["factionNameCn"] = faction_cn
        if scope_cn and scope_cn != item["scopeName"]:
            item["scopeNameCn"] = scope_cn
        out.append({k: v for k, v in item.items() if v is not None})
    return out


def blueprint_rewards(raw):
    out = []
    for row in raw or []:
        item = {
            "blueprintPool": row.get("blueprintPool"),
            "poolName": row.get("poolName"),
            "chance": row.get("chance"),
            "trigger": row.get("trigger"),
        }
        out.append({k: v for k, v in item.items() if v is not None})
    return out


def convert_contract(contract, scm, lang_keys):
    title = clean_text(contract.get("title"))
    title_cn = lang_text(lang_keys, contract.get("titleLocKey") or contract.get("titleKey"), title)
    desc = clean_text(contract.get("description"))
    desc_cn = lang_text(lang_keys, contract.get("descriptionLocKey") or contract.get("descriptionKey"), desc)
    factions = scm.get("factions") or {}
    faction = factions.get(contract.get("factionGuid")) or {}
    faction_name = faction.get("name")
    faction_cn = lang_text(lang_keys, faction.get("nameKey"), faction_name)
    type_cn = lang_text(lang_keys, contract.get("missionTypeKey"), contract.get("missionType"))

    result = {
        "id": contract.get("id"),
        "debugName": contract.get("debugName"),
        "category": contract.get("category"),
        "missionType": contract.get("missionType"),
        "missionTypeCn": type_cn,
        "title": title,
        "titleCn": title_cn,
        "description": desc,
        "descriptionCn": desc_cn,
        "factionGuid": contract.get("factionGuid"),
        "factionName": faction_name,
        "factionNameCn": faction_cn,
        "canBeShared": contract.get("canBeShared"),
        "illegal": contract.get("illegal"),
        "onceOnly": contract.get("onceOnly"),
        "isCombat": bool(contract.get("shipEncounters")) or (contract.get("missionType") in {"Mercenary", "Bounty Hunting"}),
        "timeToComplete": contract.get("timeToComplete"),
        "rewardUec": contract.get("rewardUEC"),
        "buyIn": contract.get("buyIn"),
        "minStanding": resolved_name(contract.get("minStanding"), lang_keys),
        "maxStanding": resolved_name(contract.get("maxStanding"), lang_keys),
        "factionRewards": resolve_faction_rewards(contract.get("factionRewardsIndex"), scm, lang_keys),
        "availableSystems": contract.get("availableSystems") or contract.get("systems") or [],
        "pyroRegion": contract.get("pyroRegion"),
        "locations": resolve_locations(contract.get("locations"), scm.get("locationPools") or {}),
        "destinations": resolve_locations(contract.get("destinations"), scm.get("locationPools") or {}),
        "prerequisites": contract.get("prerequisites"),
        "blueprintRewards": blueprint_rewards(contract.get("blueprintRewards")),
        "hasPersonalCooldown": contract.get("hasPersonalCooldown"),
        "personalCooldownTime": contract.get("personalCooldownTime"),
        "abandonedCooldownTime": contract.get("abandonedCooldownTime"),
        "maxPlayersPerInstance": contract.get("maxPlayersPerInstance"),
        "availableInPrison": contract.get("availableInPrison"),
        "canReacceptAfterAbandoning": contract.get("canReacceptAfterAbandoning"),
        "canReacceptAfterFailing": contract.get("canReacceptAfterFailing"),
        "hideInMobiGlas": contract.get("hideInMobiGlas"),
    }
    return {k: v for k, v in result.items() if v is not None and v != []}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", type=int, required=True)
    parser.add_argument("--lang-url", default=DEFAULT_LANG_URL)
    parser.add_argument("--out-dir", default="app/src/main/assets/blueprint")
    args = parser.parse_args()

    game_version, data_url = latest_game_version()
    print(f"SCMDB game version: {game_version}")
    print(f"SCMDB data: {data_url}")
    print(f"Language: {args.lang_url}")
    scm = get_json(data_url)
    lang = get_json(args.lang_url)
    lang_keys = lang.get("keys") or {}

    missions = {}
    for contract in scm.get("contracts") or []:
        mission_id = contract.get("id")
        if not mission_id:
            continue
        missions[mission_id] = convert_contract(contract, scm, lang_keys)

    out = {
        "version": args.version,
        "gameVersion": game_version,
        "langVersion": lang.get("version"),
        "targetLanguage": lang.get("targetLanguage"),
        "generatedAt": int(time.time()),
        "source": {"scmdbData": data_url, "language": args.lang_url},
        "missions": missions,
    }
    os.makedirs(args.out_dir, exist_ok=True)
    out_path = os.path.join(args.out_dir, "scmdb_missions.json")
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, separators=(",", ":"))
    size_kb = max(1, os.path.getsize(out_path) // 1024)
    print(f"Done -> {out_path} ({size_kb} KB, {len(missions)} missions)")


if __name__ == "__main__":
    main()
