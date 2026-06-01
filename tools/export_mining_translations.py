#!/usr/bin/env python3
"""
采矿矿物英文名 -> 中文名 翻译表导出工具。

数据来源: flowcld SCM (origin.flowcld.top), 拉取分类:
  - cat 81  (矿石 Minerals)         12 条
  - cat 82  (战利品 Loot)           ~105 条
  - cat 201 (货物 Cargo)            ~1362 条, 14 页, 含原矿/精炼成品

匹配目标: app/src/main/assets/mining/mining_data.json 里 mineableElements 的英文名。

踩过的坑(都已固化):
  1. flowcld 搜索 ?name= 走中文模糊, 按英文名搜不到 -> 改用 categoryId 全量拉
  2. pageSize 上限 100 (>100 直接 400)
  3. cat 201 并发 >4 易超时 -> workers=4 + 重试 5 次
  4. sm 命名 vs SCM 命名差异 (Quantainium vs Quantanium / 带 (Ore) 后缀)
     -> 双向归一化 + 英美别名表
  5. cat 201 有缓存避免反复打 14 页

用法:
    python3 tools/export_mining_translations.py
    python3 tools/export_mining_translations.py --refresh   # 忽略缓存重抓
    python3 tools/export_mining_translations.py --out app/src/main/assets/mining
"""
import argparse
import concurrent.futures as cf
import json
import os
import ssl
import sys
import time
import urllib.parse
import urllib.request

BASE = "https://origin.flowcld.top/app-api"
HEADERS = {"User-Agent": "Mozilla/5.0 (BugApp-MiningSync/1.0)", "tenant-id": "1"}
CATS = [81, 82, 201]                         # 矿石 / 战利品 / 货物
CACHE_DIR = "/tmp/flowcld_mining_cache"
RETRY = 5
TIMEOUT = 30

# sm.scmdb.net 命名 -> SCM 命名 的别名对照 (英美拼写差异)
ALIAS = {
    "Aluminium": "Aluminum",
    "Quantainium": "Quantanium",
}

# 手动补漏 (SCM 里没有的条目)
MANUAL = {
    "Ice (Raw)": "冰 (原料)",
}

# 强制覆盖 (SCM 给出的译名不够区分时使用; 优先级最高)
# 例: SCM 把 Carinite 和 Carinitepure 都译成 "科力晶", 但后者实际是高纯度变体, 区分开
OVERRIDE = {
    "Carinitepure": "科力晶纯",
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
            time.sleep(1.0 + 0.5 * i)
    raise last


def _page(cid, p):
    return _get(f"{BASE}/product/items/search-list?categoryId={cid}&pageNo={p}&pageSize=100")


def fetch_category(cid, workers=4, use_cache=True):
    """全量拉取一个分类, 带磁盘缓存。"""
    os.makedirs(CACHE_DIR, exist_ok=True)
    cache = os.path.join(CACHE_DIR, f"cat_{cid}.json")
    if use_cache and os.path.exists(cache):
        items = json.load(open(cache))
        print(f"  cat {cid}: {len(items)} (cached)")
        return items

    first = _page(cid, 1)
    data = first.get("data") or {}
    total = data.get("total", 0)
    items = list(data.get("list") or [])
    pages = (total + 99) // 100
    if pages > 1:
        with cf.ThreadPoolExecutor(workers) as ex:
            for r in ex.map(lambda p: _page(cid, p), range(2, pages + 1)):
                items += (r.get("data") or {}).get("list") or []

    json.dump(items, open(cache, "w"), ensure_ascii=False)
    print(f"  cat {cid}: {len(items)} / {total} (fetched)")
    return items


def build_en_to_cn(item_pool):
    """聚合 EN -> CN, 首次出现优先 (cat 81 在前所以矿物精短名优先)。"""
    m = {}
    for it in item_pool:
        en = (it.get("itemNameEn") or "").strip()
        cn = (it.get("itemName") or "").strip()
        if en and cn:
            m.setdefault(en, cn)
    return m


def variants(name):
    """sm 端名字的所有可能形态, 用于匹配 SCM 端。"""
    bases = [name]
    stripped = name
    for suf in (" (Ore)", " (Raw)", " (Pure)"):
        stripped = stripped.replace(suf, "")
    stripped = stripped.strip()
    bases.append(stripped)
    if stripped.lower().endswith("pure"):
        bases.append(stripped[:-4].strip())
    # 英美别名
    for k, v in ALIAS.items():
        for b in list(bases):
            if k in b:
                bases.append(b.replace(k, v))
    # 去重保序
    return list(dict.fromkeys(bases))


def match(names, pool):
    """3 轮匹配: 精确 -> 大小写无关 -> 反向加后缀。"""
    low = {k.lower(): v for k, v in pool.items()}
    matched, missing = {}, []

    for n in names:
        hit = None
        for v in variants(n):
            if v in pool:
                hit = pool[v]; break
            if v.lower() in low:
                hit = low[v.lower()]; break
        if hit:
            matched[n] = hit
        else:
            missing.append(n)

    # 反向: sm 用精简名 (Jaclium), SCM 带后缀 (Jaclium (Ore))
    still = []
    for n in missing:
        hit = None
        for v in variants(n):
            for suf in (" (Ore)", " (Raw)"):
                if (v + suf) in pool:
                    hit = pool[v + suf]; break
            if hit: break
        if hit: matched[n] = hit
        else: still.append(n)

    # 手动补漏
    for n in still[:]:
        if n in MANUAL:
            matched[n] = MANUAL[n]; still.remove(n)

    # 强制覆盖 (优先级最高, 推翻 SCM 译名)
    for k, v in OVERRIDE.items():
        if k in matched or k in still:
            matched[k] = v
            if k in still: still.remove(k)

    return matched, still


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="app/src/main/assets/mining")
    ap.add_argument("--refresh", action="store_true", help="忽略 cargo 缓存重抓")
    ap.add_argument("--workers", type=int, default=4)
    args = ap.parse_args()

    src = os.path.join(args.out, "mining_data.json")
    if not os.path.exists(src):
        print(f"找不到 {src}, 先跑 export_mining_data.py", file=sys.stderr)
        sys.exit(1)

    print(f"[1/3] 拉取 flowcld SCM 分类 (workers={args.workers}) ...")
    t0 = time.time()
    pool_items = []
    for cid in CATS:
        pool_items += fetch_category(cid, workers=args.workers, use_cache=not args.refresh)
    pool = build_en_to_cn(pool_items)
    print(f"      聚合 EN->CN 词典: {len(pool)} 条")

    print(f"[2/3] 匹配 mineableElements ...")
    mining = json.load(open(src, encoding="utf-8"))
    names = sorted({e["name"] for e in mining["mineableElements"].values()})
    matched, missing = match(names, pool)
    print(f"      命中 {len(matched)}/{len(names)}")
    for n in missing:
        print(f"        缺失: {n}  (建议加到 MANUAL)")

    print(f"[3/3] 写出 element_translations.json")
    out_file = os.path.join(args.out, "element_translations.json")
    now = int(time.time())
    json.dump({
        "_version": now,
        "meta": {
            "matched": len(matched),
            "total": len(names),
            "source": f"flowcld SCM cats {'/'.join(map(str, CATS))}",
            "generatedAt": int(time.time()),
        },
        "translations": dict(sorted(matched.items())),
        "missing": missing,
    }, open(out_file, "w", encoding="utf-8"), ensure_ascii=False, indent=2)

    print(f"完成, 用时 {time.time()-t0:.2f}s -> {out_file}")


if __name__ == "__main__":
    main()
