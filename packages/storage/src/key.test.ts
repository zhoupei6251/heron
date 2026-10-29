import { describe, expect, it } from "vitest";

import { objectKey } from "./key";

const base = {
  tenantId: "11111111-1111-4111-8111-111111111111",
  taskId: "22222222-2222-4222-8222-222222222222",
  version: 3,
  kind: "mp4",
} as const;

describe("objectKey", () => {
  it("按租户/任务/版本/类型布局，同一输入稳定", () => {
    const key = objectKey(base);
    expect(key).toBe(
      "tenants/11111111-1111-4111-8111-111111111111/tasks/22222222-2222-4222-8222-222222222222/v3/mp4.mp4",
    );
    expect(objectKey(base)).toBe(key);
  });

  it("换版本或换类型就换 key（重渲不会覆盖上一版）", () => {
    expect(objectKey({ ...base, version: 4 })).not.toBe(objectKey(base));
    expect(objectKey({ ...base, kind: "contact-sheet" })).toContain("/contact-sheet.png");
    expect(objectKey({ ...base, kind: "aigc-sidecar" })).toContain("/aigc-sidecar.json");
  });

  it("换租户就换前缀", () => {
    expect(objectKey({ ...base, tenantId: "33333333-3333-4333-8333-333333333333" })).toContain(
      "tenants/33333333-3333-4333-8333-333333333333/",
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
