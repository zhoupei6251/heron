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
            # 页面请求本来就带 detail_list=1&source=6，响应里有 view_count 与 video_count；
            # 只挑 word/hot_value 会把它丢掉，而这两个字段别的口径拿不到。
            # 注意别取成 discuss_video_count——那个恒为 1，没有信息量。
            "viewCount": item.get("view_count"),
            "videoCount": item.get("video_count"),
            "sentenceId": item.get("sentence_id"),
            "position": item.get("position"),
        })
    return words


def pick_download(url_list):
    """从 download_addr.url_list 里挑一条无水印地址。

    抖音的 url_list 不保证顺序：多数时候 [0] 是 CDN 直链，偶尔会落到
    aweme/v1/play?watermark=1 这种带水印的接口形式。带水印的片子拆解时
    字幕区域被遮挡，所以优先取非 aweme/v1 的；一条都没有才退回 [0]。

    退回时拿到的地址带 watermark=1，下载环节能直接从 URL 上看出来——
    个别作品整条 url_list 里就没有 CDN 直链，这不是挑选失败，是源头没有。
    """
    urls = [u for u in (url_list or []) if u]
    if not urls:
        return None
    for url in urls:
        if "aweme/v1" not in url:
            return url
    return urls[0]


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
        "downloadUrl": pick_download(download),
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


def self_check():
    """不联网校验解析与挑选：这些都是只在真跑时才暴露的形状问题，等它复现太被动。"""
    cdn = "https://v26-web.douyinvod.com/abc/video.mp4"
    api = "https://www.douyin.com/aweme/v1/play/?video_id=x&watermark=1"
    picks = [
        ([cdn, api], cdn, "中间夹着带水印的，取无水印"),
        ([api, cdn], cdn, "[0] 是带水印的，要跳过"),
        ([api], api, "只有带水印的，退回它而不是给 None"),
        ([], None, "空列表给 None"),
        (None, None, "字段缺失不炸"),
        (["", cdn], cdn, "跳过空串"),
    ]
    for url_list, want, why in picks:
        got = pick_download(url_list)
        assert got == want, f"{why}: 期望 {want}，实际 {got}"

    # detail_list=1&source=6 的响应形状：view_count / video_count 必须留下来。
    # 同时锁住不要取成 discuss_video_count——那个恒为 1，取了等于没取。
    payload = {"data": {"word_list": [
        {"word": "话题一", "hot_value": 11587881, "view_count": 63229373,
         "video_count": 14, "discuss_video_count": 1, "sentence_id": "1", "position": 1},
        {"word": "", "hot_value": 1},
        {"hot_value": 2},
    ]}}
    words = parse_word_list(payload)
    assert len(words) == 1, f"空词与缺词应被跳过，实际留下 {len(words)} 条"
    assert words[0]["viewCount"] == 63229373, "viewCount 被丢了"
    assert words[0]["videoCount"] == 14, "videoCount 被丢了"
    assert words[0]["hotValue"] == 11587881, "hotValue 被丢了"
    assert parse_word_list({}) == [], "缺 data 应给空列表"

    print(f"[self-check] pick_download {len(picks)} 例、parse_word_list 3 例通过")
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--scrolls", type=int, default=6)
    ap.add_argument("--timeout-ms", type=int, default=45000)
    ap.add_argument("--out", default=None, help="写文件；缺省打到 stdout")
    ap.add_argument("--self-check", action="store_true",
                    help="只跑不联网的地址挑选校验")
    args = ap.parse_args()

    if args.self_check:
        return self_check()

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
