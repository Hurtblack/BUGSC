#!/usr/bin/env python3
"""
Export SCMDB community language mission title translations.

SCMDB supports a `?lang=<json-url>` community language file. This script joins
the latest SCMDB merged mission data with that language file by `titleLocKey`
and writes the app's `mission_translations.json` format:

    { "version": N, "gameVersion": "...", "missions": { "EN title": "中文" } }

Existing translations can be preserved as a fallback so older sc-craft mission
names do not disappear when they are absent from SCMDB's current data.
"""

import argparse
import json
import os
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


def get_json(url):
    req = urllib.request.Request(url, headers=HEADERS)
    with urllib.request.urlopen(req, timeout=30, context=SSL_CONTEXT) as resp:
        return json.loads(resp.read().decode("utf-8"))


def latest_game_version():
    versions = get_json(f"{SCMDB_BASE}/data/game-versions.json")
    if not versions:
        raise RuntimeError("SCMDB game-versions.json is empty")
    current = versions[0]
    file_name = current.get("file") or current.get("file_path")
    if not file_name:
        raise RuntimeError("SCMDB game version entry is missing file")
    data_url = urllib.parse.urljoin(f"{SCMDB_BASE}/data/", file_name)
    return current.get("version") or "", data_url


def load_existing(path):
    if not path or not os.path.exists(path):
        return {}
    with open(path, "r", encoding="utf-8") as f:
        root = json.load(f)
    return root.get("missions") or {}


def translated_title(lang_keys, loc_key, fallback_title):
    if not loc_key:
        return None
    entry = lang_keys.get(loc_key) or lang_keys.get(loc_key.lstrip("@"))
    if not isinstance(entry, dict):
        return None
    tr = (entry.get("tr") or "").strip()
    en = (entry.get("en") or fallback_title or "").strip()
    if not tr or not en or tr == en:
        return None
    return en, tr.replace("\\n", "\n")


def build_translations(merged, lang, existing):
    lang_keys = lang.get("keys") or {}
    missions = dict(existing)
    added_or_updated = 0
    skipped = 0
    for contract in merged.get("contracts") or []:
        title = (contract.get("title") or "").strip()
        loc_key = contract.get("titleLocKey") or contract.get("titleKey")
        pair = translated_title(lang_keys, loc_key, title)
        if not pair:
            skipped += 1
            continue
        en, tr = pair
        if missions.get(en) != tr:
            added_or_updated += 1
        missions[en] = tr
    return missions, added_or_updated, skipped


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", type=int, required=True)
    parser.add_argument("--lang-url", default=DEFAULT_LANG_URL)
    parser.add_argument("--out-dir", default="app/src/main/assets/blueprint")
    parser.add_argument("--preserve-existing", action="store_true", default=True)
    args = parser.parse_args()

    game_version, data_url = latest_game_version()
    print(f"SCMDB game version: {game_version}")
    print(f"SCMDB data: {data_url}")
    print(f"Language: {args.lang_url}")

    merged = get_json(data_url)
    lang = get_json(args.lang_url)

    os.makedirs(args.out_dir, exist_ok=True)
    out_path = os.path.join(args.out_dir, "mission_translations.json")
    existing = load_existing(out_path) if args.preserve_existing else {}
    missions, changed, skipped = build_translations(merged, lang, existing)

    out = {
        "version": args.version,
        "gameVersion": game_version,
        "langVersion": lang.get("version"),
        "targetLanguage": lang.get("targetLanguage"),
        "generatedAt": int(time.time()),
        "source": {
            "scmdbData": data_url,
            "language": args.lang_url,
        },
        "missions": dict(sorted(missions.items())),
    }
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, separators=(",", ":"))

    size_kb = max(1, os.path.getsize(out_path) // 1024)
    print(
        f"Done -> {out_path} ({size_kb} KB, {len(missions)} pairs, "
        f"{changed} added/updated, {skipped} skipped)"
    )


if __name__ == "__main__":
    main()
