#!/usr/bin/env python3
"""
补全 component_power.json —— 查询 SC Wiki API 填充新增组件的功率数据。

只处理 uex_shipfit_dataset.json 中有 uuid 且尚未在 component_power.json 里的条目。
power_plant: 取 power_plant.power_segment_generation
其他:        取 resource_network.usage.power.maximum

用法:
    python3 tools/patch_component_power.py
    python3 tools/patch_component_power.py --dry-run   # 只打印，不写盘
"""
import argparse
import json
import os
import ssl
import time
import urllib.request

WIKI_BASE = "https://api.star-citizen.wiki/api/v2"
HEADERS = {"User-Agent": "BugApp-PowerSync/1.0", "Accept": "application/json"}
TIMEOUT = 20
RETRY = 3

POWER_TYPES = {"power_plant", "cooler", "quantum_drive", "shield_generator", "weapon_gun"}

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
            time.sleep(1.5 * (i + 1))
    raise last


def extract_power(detail, ctype):
    """从 SC Wiki item 详情中提取功率值。"""
    if ctype == "power_plant":
        # 发电机：取发电量
        pp = (detail.get("data") or {}).get("power_plant") or {}
        gen = pp.get("power_segment_generation")
        if gen is not None:
            return {"type": "generator", "value": float(gen)}
    else:
        # 耗电组件：取最大耗电
        rn = (detail.get("data") or {}).get("resource_network") or {}
        usage = (rn.get("usage") or {}).get("power") or {}
        val = usage.get("maximum")
        if val is not None:
            return {"type": "consumer", "value": float(val)}
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--assets", default="app/src/main/assets/shipfit")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    dataset_path = os.path.join(args.assets, "uex_shipfit_dataset.json")
    power_path = os.path.join(args.assets, "component_power.json")

    dataset = json.load(open(dataset_path, encoding="utf-8"))
    power = json.load(open(power_path, encoding="utf-8"))

    # 找出需要补全的组件（有 UUID，类型需要功率数据，尚未在 power 里）
    seen_uuids = set()
    to_patch = []
    for c in dataset["components"]:
        uid = str(c["id"])
        uuid = (c.get("uuid") or "").strip()
        if uid in power:
            continue
        if c["type"] not in POWER_TYPES:
            continue
        if not uuid:
            print(f"  SKIP (no uuid): [{c['type']}] {c['name']}")
            continue
        if uuid in seen_uuids:
            print(f"  SKIP (dup uuid): [{c['type']}] {c['name']} uuid={uuid}")
            continue
        seen_uuids.add(uuid)
        to_patch.append(c)

    print(f"\n需要查询 SC Wiki: {len(to_patch)} 个\n")

    ok, miss, err = 0, 0, 0
    for c in to_patch:
        uuid = c["uuid"].strip()
        ctype = c["type"]
        try:
            detail = _get(f"{WIKI_BASE}/items/{uuid}")
            result = extract_power(detail, ctype)
            if result:
                power[str(c["id"])] = result
                print(f"  OK  [{ctype}] {c['name']:40s} -> {result}")
                ok += 1
            else:
                print(f"  MISS[{ctype}] {c['name']:40s} (字段不存在)")
                miss += 1
        except Exception as e:
            print(f"  ERR [{ctype}] {c['name']:40s} : {e}")
            err += 1
        time.sleep(0.4)

    print(f"\n结果: OK={ok}  MISS={miss}  ERR={err}")

    if not args.dry_run and ok > 0:
        with open(power_path, "w", encoding="utf-8") as f:
            json.dump(power, f, ensure_ascii=False, separators=(",", ":"))
        print(f"已写入 {power_path}  ({os.path.getsize(power_path)/1024:.1f} KB)")
    elif args.dry_run:
        print("(dry-run, 不写盘)")


if __name__ == "__main__":
    main()
