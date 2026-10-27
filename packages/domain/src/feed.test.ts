import { describe, expect, it } from "vitest";

import { fetchFeed, parseFeed } from "./feed";

describe("parseFeed", () => {
  it("RSS：取 item 的标题与摘要，名次按出现顺序", () => {
    const entries = parseFeed(`<?xml version="1.0"?>
      <rss><channel>
        <item><title>某市开通新航线</title><description><![CDATA[<b>今日</b>首航]]></description></item>
        <item><title>A &amp; B 联合发布</title></item>
      </channel></rss>`);
    expect(entries).toEqual([
      { title: "某市开通新航线", summary: "今日首航", rank: 1 },
      { title: "A & B 联合发布", summary: "", rank: 2 },
    ]);
  });

  it("Atom：entry 同样解析，title 带属性也认", () => {
    const entries = parseFeed(`<feed><entry>
      <title type="text">  新能源车销量
      再创新高 </title><summary>九连涨</summary></entry></feed>`);
    expect(entries).toEqual([{ title: "新能源车销量 再创新高", summary: "九连涨", rank: 1 }]);
  });

  it("纯文本：每行一条，空行与 # 注释跳过", () => {
    expect(parseFeed("# 热榜\n第一条\n\n第二条\n")).toEqual([
      { title: "第一条", summary: "", rank: 1 },
      { title: "第二条", summary: "", rank: 2 },
    ]);
  });

  it("是 XML 但没有任何条目：返回空，不能按行拆成假标题", () => {
    expect(parseFeed("<rss><channel></channel></rss>")).toEqual([]);
  });
});

describe("fetchFeed", () => {
  it("非 2xx 抛错并带上状态码", async () => {
    const fetcher = () =>
      Promise.resolve(new Response("nope", { status: 503, statusText: "Busy" }));
    await expect(fetchFeed("https://example.com/feed", { fetcher })).rejects.toThrow(/503/);
  });

  it("抓到了但解析不出条目也算失败", async () => {
    const fetcher = () => Promise.resolve(new Response("<html><body>登录</body></html>"));
    await expect(fetchFeed("https://example.com/feed", { fetcher })).rejects.toThrow(
      /没解析出条目/,
    );
  });
});
