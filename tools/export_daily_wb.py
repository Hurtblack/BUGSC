#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
导出「每日 WB」数据 —— RSI 官网 pledge store 上带 warbond 的限时折扣船。

数据来源（全自动匿名链路，无需登录、无 Cloudflare）：
  1. GET  /en/pledge                              → Rsi-Token cookie + <meta csrf-token>
  2. POST /api/account/v2/setAuthToken      {}    → Rsi-Account-Auth cookie（匿名，USD 定价）
  3. POST /api/ship-upgrades/setContextToken {}   → Rsi-ShipUpgrades-Context cookie（mode:browse）
  4. POST /pledge-store/api/upgrade/v2/graphql    → 全船 + SKU 价格（含 warbond / standard）
  5. /graphql 的 store.search（browse）→ 按 ship id 补 购买链接 + 缩略图

warbond 船 = upgrade v2 里某 ship 的 skus 中有 title 含 "Warbond" 的。
两个 sku：Warbond Edition（优惠价）+ Standard Edition（原价）；price 单位是「分」。

产物：写出 daily_wb.json（默认 wb/daily_wb.json）。
**绝不发布空数据**：拿不到 token / GraphQL 报错 / 0 条 warbond → 非零退出且不覆盖旧文件。

依赖：仅标准库。用法：
  python3 tools/export_daily_wb.py [-o wb/daily_wb.json]
"""

import argparse
import json
import re
import ssl
import sys
import time
import urllib.request
import urllib.error
from http.cookiejar import CookieJar

BASE = "https://robertsspaceindustries.com"
PLEDGE_URL = BASE + "/en/pledge"
AUTH_TOKEN_URL = BASE + "/api/account/v2/setAuthToken"
CONTEXT_TOKEN_URL = BASE + "/api/ship-upgrades/setContextToken"
UPGRADE_GQL = BASE + "/pledge-store/api/upgrade/v2/graphql"
STORE_GQL = BASE + "/graphql"

UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
TIMEOUT = 30
_SSL = ssl.create_default_context()
_SSL.check_hostname = False
_SSL.verify_mode = ssl.CERT_NONE

# upgrade v2：全船 + skus（id/title/price）。price 单位为分。
UPGRADE_QUERY = "query{ to{ ships{ id name skus{ id title price upgradePrice } } } }"

# /graphql store.search：按 ship 取链接 + 缩略图（仅 ship 级字段，避开 browse 里 skus/price 会崩的坑）。
BROWSE_QUERY = (
    "query($query: SearchQuery!){ store(name:\"pledge\", browse:true){ "
    "search(query:$query){ resources{ ... on RSIShip{ "
    "id url imageComposer{ slot url } } } } } }"
)


class WbError(RuntimeError):
    pass


def build_opener():
    jar = CookieJar()
    opener = urllib.request.build_opener(
        urllib.request.HTTPCookieProcessor(jar),
        urllib.request.HTTPSHandler(context=_SSL),
    )
    return opener, jar


def _request(opener, url, *, data=None, headers=None, method=None):
    h = {"User-Agent": UA, "Referer": PLEDGE_URL, "Accept": "*/*"}
    if headers:
        h.update(headers)
    body = None
    if data is not None:
        body = data.encode("utf-8") if isinstance(data, str) else data
    req = urllib.request.Request(url, data=body, headers=h, method=method)
    try:
        with opener.open(req, timeout=TIMEOUT) as r:
            return r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")[:300]
        raise WbError(f"HTTP {e.code} on {url}: {detail}") from e
    except urllib.error.URLError as e:
        raise WbError(f"network error on {url}: {e}") from e


def get_session():
    """GET pledge 页 → 建立 cookie（Rsi-Token）并取出 csrf token。"""
    opener, _ = build_opener()
    html = _request(opener, PLEDGE_URL)
    m = re.search(r'name="csrf-token"\s+content="([0-9a-f]+)"', html)
    if not m:
        raise WbError("找不到 csrf-token（页面结构可能变了）")
    return opener, m.group(1)


def _gql(opener, url, csrf, query, variables=None):
    payload = {"query": query}
    if variables is not None:
        payload["variables"] = variables
    headers = {"Content-Type": "application/json", "X-CSRF-TOKEN": csrf}
    raw = _request(opener, url, data=json.dumps(payload), headers=headers)
    doc = json.loads(raw)
    if doc.get("errors"):
        raise WbError(f"GraphQL 报错 @ {url}: {json.dumps(doc['errors'], ensure_ascii=False)[:300]}")
    return doc["data"]


def prime_tokens(opener, csrf):
    """拿匿名账户 token + 浏览上下文 token（价格解析的前提）。"""
    headers = {"Content-Type": "application/json", "X-CSRF-TOKEN": csrf}
    _request(opener, AUTH_TOKEN_URL, data="{}", headers=headers)
    _request(opener, CONTEXT_TOKEN_URL, data="{}", headers=headers)


def fetch_warbond_ships(opener, csrf):
    """从 upgrade v2 取全船，筛出含 Warbond sku 的，返回 {id: {name, warbond, standard}}。"""
    data = _gql(opener, UPGRADE_GQL, csrf, UPGRADE_QUERY)
    ships = (data.get("to") or {}).get("ships") or []
    out = {}
    for s in ships:
        skus = s.get("skus") or []
        wb = next((k for k in skus if "warbond" in (k.get("title") or "").lower()), None)
        if not wb:
            continue
        # 原价：非 warbond 的 sku（通常 Standard Edition）。取价格最高者兜底。
        others = [k for k in skus if k is not wb and k.get("price") is not None]
        std = max(others, key=lambda k: k["price"]) if others else None
        out[str(s["id"])] = {
            "nameEn": s.get("name"),
            "warbond_cents": wb.get("price"),
            "standard_cents": std.get("price") if std else None,
        }
    return out


def fetch_browse_index(opener, csrf):
    """分页拉 store.search，返回 {ship_id: {url, thumbnail}}。"""
    index = {}
    page = 1
    while page <= 20:  # 安全上限
        variables = {"query": {"page": page, "limit": 30, "ships": {"all": True}}}
        data = _gql(opener, STORE_GQL, csrf, BROWSE_QUERY, variables)
        resources = ((data.get("store") or {}).get("search") or {}).get("resources") or []
        if not resources:
            break
        for r in resources:
            sid = str(r.get("id"))
            thumb = None
            for ic in (r.get("imageComposer") or []):
                if ic.get("slot") == "thumbnail" and ic.get("url"):
                    thumb = ic["url"]
                    break
            index[sid] = {
                "url": _abs(r.get("url")),
                "thumbnail": _abs(thumb),
            }
        page += 1
    return index


def _abs(path):
    if not path:
        return None
    if path.startswith("http"):
        return path
    return BASE + path


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-o", "--out", default="wb/daily_wb.json", help="输出文件路径")
    args = ap.parse_args()

    opener, csrf = get_session()
    prime_tokens(opener, csrf)

    warbond = fetch_warbond_ships(opener, csrf)
    if not warbond:
        raise WbError("解析出 0 条 warbond 船（疑似 schema 变更）—— 拒绝写出空数据")

    browse = fetch_browse_index(opener, csrf)

    items = []
    for sid, w in warbond.items():
        extra = browse.get(sid, {})
        items.append({
            "nameEn": w["nameEn"],
            "warbondPrice": round(w["warbond_cents"] / 100, 2) if w["warbond_cents"] is not None else None,
            "standardPrice": round(w["standard_cents"] / 100, 2) if w["standard_cents"] is not None else None,
            "currency": "USD",
            "url": extra.get("url"),
            "thumbnail": extra.get("thumbnail"),
        })
    items.sort(key=lambda x: x["nameEn"] or "")

    out = {
        "version": int(time.time()),
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "items": items,
    }
    import os
    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)

    print(f"✅ 写出 {len(items)} 条 warbond → {args.out}")
    for it in items:
        print(f"   {it['nameEn']:22} WB ${it['warbondPrice']}  原价 ${it['standardPrice']}  thumb={'有' if it['thumbnail'] else '无'}")


if __name__ == "__main__":
    try:
        main()
    except WbError as e:
        print(f"❌ {e}", file=sys.stderr)
        sys.exit(1)
