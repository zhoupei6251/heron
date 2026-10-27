import { describe, expect, it } from "vitest";

import { parseScriptJson } from "./scripting";

describe("解析模型稿子", () => {
  it("```json 围栏能剥掉", () => {
    expect(parseScriptJson('```json\n{"title":"标题"}\n```')).toEqual({ title: "标题" });
  });

  it("前后带一句解释时只取最外层 JSON", () => {
    expect(parseScriptJson('好的，稿子如下：\n{"title":"标题","scenes":[]}\n以上。')).toEqual({
      title: "标题",
      scenes: [],
    });
  });

  it("完全没有 JSON 就报错，并带原文片段", () => {
    expect(() => parseScriptJson("抱歉，这个问题我不能回答。")).toThrow(/没有输出 JSON/);
    expect(() => parseScriptJson("抱歉，这个问题我不能回答。")).toThrow(/抱歉/);
  });

  it("JSON 残缺同样报错，不把半个稿子当稿子", () => {
    expect(() => parseScriptJson('{"title":"标题",}')).toThrow(/解析失败/);
  });
});
