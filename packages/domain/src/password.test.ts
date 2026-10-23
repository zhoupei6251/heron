import { describe, expect, it } from "vitest";

import { hashPassword, UNUSABLE_PREFIX, verifyPassword } from "./password";

describe("口令哈希（node:crypto scrypt）", () => {
  it("同一口令两次哈希不同，但都能验过", () => {
    const h1 = hashPassword("correct horse battery staple");
    const h2 = hashPassword("correct horse battery staple");
    expect(h1).not.toBe(h2);
    expect(verifyPassword("correct horse battery staple", h1)).toBe(true);
    expect(verifyPassword("correct horse battery staplf", h1)).toBe(false);
  });

  it("存储形状是 scrypt$盐$哈希，且不含口令明文", () => {
    const password = "correct horse battery staple";
    const stored = hashPassword(password);
    const [scheme, salt, hash] = stored.split("$");
    expect(scheme).toBe("scrypt");
    expect(salt).toHaveLength(32);
    expect(hash).toHaveLength(128);
    expect(stored).not.toContain(password);
  });

  it("! 前缀的合成 actor 永远登不进来", () => {
    // 审计列要写「是谁干的」，但这个人不能变成一条能登录的后门——UNUSABLE_PREFIX 就是这两件事的分界
    expect(verifyPassword("anything", `${UNUSABLE_PREFIX}synthetic-p0`)).toBe(false);
    expect(verifyPassword("", UNUSABLE_PREFIX)).toBe(false);
    expect(UNUSABLE_PREFIX).toBe("!");
  });

  it("形状不对的存储值一律验不过，而且不许抛", () => {
    // password_hash 是 text 列，历史值/手填值/别的方案都在同一个列里，验不出就返回 false，不炸进程
    for (const stored of [
      "",
      "x",
      "scrypt$",
      "scrypt$salt",
      "bcrypt$a$b",
      "scrypt$deadbeef$not-hex",
      "scrypt$deadbeef$00",
    ]) {
      expect(verifyPassword("whatever", stored), stored).toBe(false);
    }
  });

  it("整串比对，不做大小写或空白宽松", () => {
    // 宽松匹配是「哈希层偷偷改判据」的那种 bug：这里只认整串，规范化归 LoginBodySchema 管
    const stored = hashPassword("Zhou2026!heron");
    expect(verifyPassword("zhou2026!heron", stored)).toBe(false);
    expect(verifyPassword("Zhou2026!heron ", stored)).toBe(false);
    expect(verifyPassword("Zhou2026!heron", stored)).toBe(true);
  });
});
