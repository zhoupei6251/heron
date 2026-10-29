import { describe, expect, it } from "vitest";

import { objectKey } from "./key";

const base = {
  tenantId: "1839203847562000001",
  taskId: "1839203847562000002",
  version: 3,
  kind: "mp4",
  at: new Date("2026-10-29T12:34:56.000Z"),
} as const;

describe("objectKey", () => {
  it("按租户/年/月/日/任务/版本/类型布局，同一输入稳定", () => {
    const key = objectKey(base);
    expect(key).toBe("tenants/1839203847562000001/2026/10/29/tasks/1839203847562000002/v3/mp4.mp4");
    expect(objectKey(base)).toBe(key);
  });

  it("日期层按 UTC 分桶：本地时区变了，键也不变", () => {
    // 本地时区是 UTC+8 时，这一刻的本地日期是 10-29；UTC 也是 10-29。
    // 换成 21:00 之后（本地已是 30 日），UTC 仍是 10-29——键跟着 UTC 走。
    const late = new Date("2026-10-29T21:00:00.000Z");
    expect(objectKey({ ...base, at: late })).toContain("/2026/10/29/");
    // 跨过 UTC 零点，日期层才进位
    const nextDay = new Date("2026-10-30T00:00:00.000Z");
    expect(objectKey({ ...base, at: nextDay })).toContain("/2026/10/30/");
  });

  it("月与日补零：10 月与 1 月在键里分得清", () => {
    expect(objectKey({ ...base, at: new Date("2026-01-05T00:00:00.000Z") })).toContain(
      "/2026/01/05/",
    );
  });

  it("换版本或换类型就换 key（重渲不会覆盖上一版）", () => {
    expect(objectKey({ ...base, version: 4 })).not.toBe(objectKey(base));
    expect(objectKey({ ...base, kind: "contact-sheet" })).toContain("/contact-sheet.png");
    expect(objectKey({ ...base, kind: "aigc-sidecar" })).toContain("/aigc-sidecar.json");
  });

  it("换租户就换前缀", () => {
    expect(objectKey({ ...base, tenantId: "1839203847562000009" })).toContain(
      "tenants/1839203847562000009/",
    );
  });

  it("段里出现越界字符就抛，不拼进键", () => {
    expect(() => objectKey({ ...base, taskId: "../other" })).toThrowError(/taskId/);
    expect(() => objectKey({ ...base, tenantId: "a\\b" })).toThrowError(/tenantId/);
    expect(() => objectKey({ ...base, tenantId: "" })).toThrowError(/tenantId/);
  });

  it("版本号必须是正整数", () => {
    expect(() => objectKey({ ...base, version: 0 })).toThrowError(/版本号/);
    expect(() => objectKey({ ...base, version: 1.5 })).toThrowError(/版本号/);
  });
});
