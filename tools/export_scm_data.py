#!/usr/bin/env python3
"""
SCM 数据导出工具(后台维护脚本)。

用途:游戏版本更新后,重新生成「中文翻译表」+「蓝图配方线索」两个 JSON。
生成的文件结构与 App 内 assets/blueprint/ 一致,带 version 字段供热更新比对。

用法:
    python3 tools/export_scm_data.py --version 2 --game-version 4.2.0
    # 产物默认写到 app/src/main/assets/blueprint/
    # 部署热更新时,把同样的文件上传到你的 CDN/GitHub raw(BlueprintDataRepository.RemoteConfig.baseUrl 指向的目录)

注意:
  - 每次发布请把 --version 递增(整数),App 靠它判断是否需要下载新数据。
  - 这是「维护时」运行的脚本,App 运行时不依赖 SCM。
  - 按英文名(itemNameEn)对照,与 UEX 的英文名匹配。
"""
import argparse, json, os, ssl, sys, time, urllib.request

BASE = "https://origin.flowcld.top/app-api"
HEADERS = {"User-Agent": "Mozilla/5.0", "Accept": "application/json", "tenant-id": "1"}
PAGE_SIZE = 100
SLEEP = 0.2  # 友好限速,别给源站压力

# SCM 物品分类 id（来自 /product/items/category-list 的全部 id）
CATEGORY_IDS = [1,2,3,4,5,6,7,8,9,11,12,13,14,15,16,17,21,22,23,24,25,26,27,31,32,33,34,
                35,36,41,42,43,44,45,51,52,53,54,55,56,57,58,81,82,201,202,203,204,205,
                206,207,211,212,213,221,222,223]

_SSL = ssl.create_default_context()
_SSL.check_hostname = False
_SSL.verify_mode = ssl.CERT_NONE


def _get(url):
    req = urllib.request.Request(url, headers=HEADERS)
    with urllib.request.urlopen(req, timeout=20, context=_SSL) as r:
        return json.loads(r.read().decode("utf-8"))


def fetch_category(cid):
    out, page = [], 1
    while True:
        url = f"{BASE}/product/items/search-list?categoryId={cid}&pageNo={page}&pageSize={PAGE_SIZE}"
        try:
            d = _get(url)
        except Exception as e:
            print(f"  [cat {cid} page {page}] 错误: {e}", file=sys.stderr)
            break
        data = d.get("data") or {}
        lst = data.get("list") or []
        out.extend(lst)
        total = data.get("total", 0)
        if page * PAGE_SIZE >= total or not lst:
            break
        page += 1
        time.sleep(SLEEP)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", type=int, required=True, help="数据版本号(每次发布递增)")
    ap.add_argument("--game-version", default="", help="对应游戏版本,如 4.2.0")
    ap.add_argument("--out-dir", default=None, help="输出目录,默认 app/src/main/assets/blueprint")
    args = ap.parse_args()

    out_dir = args.out_dir or os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "app", "src", "main", "assets", "blueprint",
    )
    os.makedirs(out_dir, exist_ok=True)

    name_map, blueprint, seen = {}, {}, set()
    for i, cid in enumerate(CATEGORY_IDS, 1):
        for it in fetch_category(cid):
            iid = it.get("id")
            if iid in seen:
                continue
            seen.add(iid)
            en = (it.get("itemNameEn") or "").strip()
            cn = (it.get("itemName") or "").strip()
            if en and cn and en != cn:
                name_map[en] = cn
            qi = it.get("qualityInfo")
            if qi:
                try:
                    qj = json.loads(qi)
                    if qj.get("type") == "blueprint" and en:
                        blueprint[en] = qj
                except Exception:
                    pass
        print(f"[{i}/{len(CATEGORY_IDS)}] cat {cid}: 累计名 {len(name_map)}, 蓝图 {len(blueprint)}", file=sys.stderr)
        time.sleep(SLEEP)

    meta = {"version": args.version, "gameVersion": args.game_version, "generatedAt": int(time.time())}

    translations = {**meta, "items_by_en": name_map, "attributes": {}, "categories": {}}
    blueprints = {**meta, "blueprints": blueprint}

    with open(os.path.join(out_dir, "scm_translations.json"), "w", encoding="utf-8") as f:
        json.dump(translations, f, ensure_ascii=False, separators=(",", ":"))
    with open(os.path.join(out_dir, "scm_blueprint_hints.json"), "w", encoding="utf-8") as f:
        json.dump(blueprints, f, ensure_ascii=False, separators=(",", ":"))

    print(f"\n完成: version={args.version} 翻译 {len(name_map)} 条, 蓝图 {len(blueprint)} 条 -> {out_dir}", file=sys.stderr)


if __name__ == "__main__":
    main()
