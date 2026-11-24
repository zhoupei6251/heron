"""抖音热点采集：用浏览器加载抖音热点榜，拦截页面自己发的接口，输出结构化 JSON。

不逆向签名：页面加载时抖音自己的 JS 会给请求签名，这里只把响应读下来。
纯 HTTP 取不到数据（接口返回 200 但正文为空），所以必须走浏览器渲染。

用法：
    python trend.py --scrolls 6 --out trend.json

输出：
    {"collectedAt": ..., "hotWords": [...], "videos": [...]}

依赖 patchright（social-auto-upload 的虚拟环境里已有）。
"""

import argparse
import json
import sys
import time
from datetime import datetime, timezone

from patchright.sync_api import sync_playwright

HOT_URL = "https://www.douyin.com/hot"
HOT_SEARCH_API = "/aweme/v1/web/hot/search/list/"
HOTSPOT_API = "/aweme/v1/web/channel/hotspot"


def parse_word_list(payload):
    data = payload.get("data") or {}
    words = []
    for item in (data.get("word_list") or []):
        word = item.get("word")
        if not word:
            continue
        words.append({
            "word": word,
            "hotValue": item.get("hot_value"),
            "sentenceId": item.get("sentence_id"),
            "position": item.get("position"),
        })
    return words


def parse_aweme(item):
    stats = item.get("statistics") or {}
    video = item.get("video") or {}
    author = item.get("author") or {}
    download = ((video.get("download_addr") or {}).get("url_list") or [])
    cover = ((video.get("cover") or {}).get("url_list") or [])
    return {
        "awemeId": str(item.get("aweme_id") or ""),
        "desc": (item.get("desc") or "").strip(),
        "durationMs": video.get("duration") or item.get("duration"),
        "createTime": item.get("create_time"),
        "downloadUrl": download[0] if download else None,
        "coverUrl": cover[0] if cover else None,
        "author": {
            "nickname": author.get("nickname"),
            "uniqueId": author.get("unique_id"),
            "followerCount": author.get("follower_count"),
        },
        "stats": {
            "digg": stats.get("digg_count"),
            "comment": stats.get("comment_count"),
            "share": stats.get("share_count"),
            "collect": stats.get("collect_count"),
            "recommend": stats.get("recommend_count"),
            "play": stats.get("play_count"),
        },
    }


def run(scrolls, timeout_ms):
    hotspot = {}
    order = []
    words = []

    def keep(item):
        aweme = parse_aweme(item)
        aweme_id = aweme["awemeId"]
        if not aweme_id or aweme_id in hotspot:
            return
        # 位次取首次出现的顺序：接口给的是热榜顺序，自己再排一次就把这个信息丢了。
        aweme["rankNo"] = len(order) + 1
        hotspot[aweme_id] = aweme
        order.append(aweme_id)

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True, channel="chromium",
                                    args=["--no-sandbox",
                                          "--disable-blink-features=AutomationControlled"])
        page = browser.new_page(viewport={"width": 1280, "height": 900})

        def on_response(resp):
            url = resp.url
            if resp.status != 200:
                return
            if HOT_SEARCH_API in url:
                try:
                    payload = json.loads(resp.text())
                except Exception:
                    return
                got = parse_word_list(payload)
                if got and not words:
                    words.extend(got)
            elif HOTSPOT_API in url:
                try:
                    payload = json.loads(resp.text())
                except Exception:
                    return
                for item in (payload.get("aweme_list") or []):
                    keep(item)

        page.on("response", on_response)
        page.goto(HOT_URL, wait_until="domcontentloaded", timeout=timeout_ms)
        page.wait_for_timeout(5000)
        for _ in range(scrolls):
            page.mouse.wheel(0, 1200)
            page.wait_for_timeout(1500)
        try:
            page.inner_text("body")
        except Exception:
            pass
        browser.close()

    return {
        "collectedAt": datetime.now(timezone.utc).isoformat(),
        "hotWords": words,
        "videos": [hotspot[aweme_id] for aweme_id in order],
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--scrolls", type=int, default=6)
    ap.add_argument("--timeout-ms", type=int, default=45000)
    ap.add_argument("--out", default=None, help="写文件；缺省打到 stdout")
    args = ap.parse_args()

    started = time.time()
    result = run(args.scrolls, args.timeout_ms)
    result["elapsedSeconds"] = round(time.time() - started, 1)

    text = json.dumps(result, ensure_ascii=False, indent=2)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as fh:
            fh.write(text)
    else:
        sys.stdout.write(text)

    if not result["videos"]:
        print("\n[WARN] 没有抓到任何视频：页面可能被风控或结构已变", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
