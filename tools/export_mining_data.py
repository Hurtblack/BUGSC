#!/usr/bin/env python3
"""
Star Miner (sm.scmdb.net) 采矿数据导出工具。

抓取 sm.scmdb.net 的两个核心 JSON:
  - mining_data         (矿物 / 组合 / 地点)
  - mining_equipment    (激光器 / 模块 / gadget / FPS 工具)

文件名带 SC 版本 + build 号 (例: mining_data-4.7.0-live.11518367.json),
每次 SC 版本更新会变, 所以脚本先抓 SPA 首页 -> JS bundle -> 正则提取实际文件名,
再用线程池并发下载, 避免硬编码。

用法:
    python3 tools/export_mining_data.py
    python3 tools/export_mining_data.py --out app/src/main/assets/mining
"""
import argparse
import concurrent.futures as cf
import json
import os
import re
import ssl
import sys
import time
import urllib.request

BASE = "https://sm.scmdb.net"
HEADERS = {"User-Agent": "Mozilla/5.0 (compatible; BugApp-MiningSync/1.0)"}
TIMEOUT = 25
RETRY = 3

_SSL = ssl.create_default_context()
_SSL.check_hostname = False
_SSL.verify_mode = ssl.CERT_NONE


def _get(url, binary=False):
    last = None
    for i in range(RETRY):
        try:
            req = urllib.request.Request(url, headers=HEADERS)
            with urllib.request.urlopen(req, timeout=TIMEOUT, context=_SSL) as r:
                data = r.read()
                return data if binary else data.decode("utf-8")
        except Exception as e:
            last = e
            time.sleep(0.5 * (i + 1))
    raise last


def discover_data_files():
    """首页 HTML -> JS bundle -> data 文件名 (含版本号)。"""
    html = _get(f"{BASE}/")
    m = re.search(r'src="(/assets/index-[A-Za-z0-9_-]+\.js)"', html)
    if not m:
        raise RuntimeError("未在首页找到 JS bundle, 站点结构可能已变")
    js_path = m.group(1)
    js = _get(f"{BASE}{js_path}")

    files = re.findall(r"/data/([a-zA-Z0-9_.-]+\.json)", js)
    files = sorted(set(files))
    if not files:
        raise RuntimeError("未在 JS bundle 中找到 /data/*.json 引用")
    return js_path, files


def download_one(name, out_dir, inject_version=None):
    """下载 + 写盘 + 可选注入 _version 字段 (用于 App 端热更新版本比对)。"""
    url = f"{BASE}/data/{name}"
    raw = _get(url, binary=True)
    base = re.sub(r"-[\d.]+-[a-z]+\.\d+", "", name)  # mining_data-4.7.0-live.11518367.json -> mining_data.json
    path = os.path.join(out_dir, base)
    if inject_version is not None:
        # 解析 -> 插入 _version (int, 来自 SC build 号) -> 重写
        obj = json.loads(raw.decode("utf-8"))
        obj["_version"] = int(inject_version)
        with open(path, "w", encoding="utf-8") as f:
            json.dump(obj, f, ensure_ascii=False, separators=(",", ":"))
    else:
        with open(path, "wb") as f:
            f.write(raw)
    return name, base, os.path.getsize(path)


def extract_version(files):
    """从任一文件名中解析 SC 版本+build 号, 写入 manifest。"""
    for n in files:
        m = re.search(r"-(\d+\.\d+\.\d+)-([a-z]+)\.(\d+)\.json$", n)
        if m:
            return {"gameVersion": m.group(1), "branch": m.group(2), "build": m.group(3)}
    return {"gameVersion": None, "branch": None, "build": None}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=".",
                    help="输出目录 (默认仓库根), App 用建议 app/src/main/assets/mining")
    ap.add_argument("--workers", type=int, default=8)
    args = ap.parse_args()

    os.makedirs(args.out, exist_ok=True)

    print(f"[1/3] 探测 sm.scmdb.net 当前数据文件 ...")
    js_path, files = discover_data_files()
    print(f"      JS bundle: {js_path}")
    for f in files:
        print(f"      data: {f}")

    version_info = extract_version(files)
    build_int = int(version_info["build"]) if version_info.get("build") else int(time.time())

    print(f"[2/3] 并发下载 (workers={args.workers}, _version={build_int}) -> {args.out}")
    t0 = time.time()
    results = []
    with cf.ThreadPoolExecutor(max_workers=args.workers) as ex:
        futs = {ex.submit(download_one, n, args.out, build_int): n for n in files}
        for fut in cf.as_completed(futs):
            orig, base, size = fut.result()
            print(f"      ok  {base:32s}  {size/1024:7.1f} KB  (<- {orig})")
            results.append({"source": orig, "saved": base, "bytes": size})

    print(f"[3/3] 写 manifest.json")
    manifest = {
        "_version": build_int,
        "fetchedAt": int(time.time()),
        "source": BASE,
        **version_info,
        "files": sorted(results, key=lambda x: x["saved"]),
    }
    with open(os.path.join(args.out, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)

    print(f"完成, 用时 {time.time()-t0:.2f}s")


if __name__ == "__main__":
    main()
