import { describe, expect, it } from "vitest";

import {
  AuthUserSchema,
  ConfirmBodySchema,
  JwtPayloadSchema,
  LoginBodySchema,
  MIN_PASSWORD_LENGTH,
  ReviewItemSchema,
  ROLES,
  TaskSchema,
  USAGE_KINDS,
  USAGE_KIND_UNITS,
  USAGE_UNITS,
  UsageRecordSchema,
} from "./index";

/**
 * contracts 只测「自己带判据」的那几件：规范化、边界长度、配对、uuid 形状。
 * 纯名单（STAGES / GATES / ROLES）不在这里断言——它们的形状由 domain 的 rbac.test.ts
 * 与 db 的 schema.test.ts 各自对着真实消费者测，在这儿再抄一遍只会多一处要维护的副本。
 */

const AT = "2026-10-22T00:00:00.000Z";
const TENANT = "00000000-0000-4000-8000-0000000000aa";
const USER = "11111111-1111-4111-8111-111111111111";
const SLUG = "acme";

describe("LoginBodySchema", () => {
  it("邮箱在边界上就规范化成小写", () => {
    const parsed = LoginBodySchema.parse({
      tenant: SLUG,
      email: "Boss@Example.COM",
      password: "x".repeat(MIN_PASSWORD_LENGTH),
    });
    // 库里只有小写（ck_users_email_lower），登录查询不许再猜大小写
    expect(parsed.email).toBe("boss@example.com");
  });

  it("第二个坐标是必需的，但大小写与空格留给数据层去规范化", () => {
    // 空 slug 进不来：那等于让「找不到租户」和「租户是空串」共用一条查询
    expect(
      LoginBodySchema.safeParse({
        tenant: "",
        email: "boss@example.com",
        password: "x".repeat(MIN_PASSWORD_LENGTH),
      }).success,
    ).toBe(false);
    // ACME / 带空格 都收：slug 的 lower+trim 由 ck_tenants_slug_lower 与 findTenantBySlug 负责，
    // 契约在这儿抢先改写过一遍，界面上回填的输入就会和库里对不上
    expect(
      LoginBodySchema.safeParse({
        tenant: " ACME ",
        email: "boss@example.com",
        password: "x".repeat(MIN_PASSWORD_LENGTH),
      }).success,
    ).toBe(true);
  });

  it("差一个字符的口令被拒：短的直接不进哈希层", () => {
    expect(
      LoginBodySchema.safeParse({
        tenant: SLUG,
        email: "boss@example.com",
        password: "x".repeat(MIN_PASSWORD_LENGTH - 1),
      }).success,
    ).toBe(false);
    expect(
      LoginBodySchema.safeParse({
        tenant: SLUG,
        email: "boss@example.com",
        password: "x".repeat(MIN_PASSWORD_LENGTH),
      }).success,
    ).toBe(true);
  });

  it("不是邮箱的字符串进不来", () => {
    expect(
      LoginBodySchema.safeParse({
        tenant: SLUG,
        email: "boss",
        password: "x".repeat(MIN_PASSWORD_LENGTH),
      }).success,
    ).toBe(false);
  });
});

describe("AuthUserSchema", () => {
  const user = {
    id: USER,
    tenantId: TENANT,
    tenantName: "A 台",
    tenantSlug: "acme",
    email: "boss@example.com",
    displayName: "周佩",
    role: "owner" as const,
  };

  it("会话里的自己是五个角色之一", () => {
    expect(AuthUserSchema.parse(user)).toEqual(user);
  });

  it("退役的 editor 不是角色：名单只有 ROLES 那一份", () => {
    // 与库里的 ck_users_role_known 同一条判据——两边都认旧名字，就等于谁都不认
    expect(AuthUserSchema.safeParse({ ...user, role: "editor" }).success).toBe(false);
    for (const role of ROLES)
      expect(AuthUserSchema.safeParse({ ...user, role }).success).toBe(true);
  });

  it("非 uuid 的 id 进不来：租户边界上的 id 不许是任意字符串", () => {
    expect(AuthUserSchema.safeParse({ ...user, tenantId: "default" }).success).toBe(false);
  });
});

describe("UsageRecordSchema", () => {
  const record = (kind: string, unit: string) => ({
    tenantId: TENANT,
    taskId: null,
    kind,
    quantity: "61.4",
    unit,
    recordedAt: AT,
  });

  it("每个 kind 配它的 unit 都过得去", () => {
    for (const kind of USAGE_KINDS) {
      expect(UsageRecordSchema.safeParse(record(kind, USAGE_KIND_UNITS[kind])).success).toBe(true);
    }
  });

  it("错配被拒，且报错落在 unit 上", () => {
    for (const kind of USAGE_KINDS) {
      for (const unit of USAGE_UNITS.filter((u) => u !== USAGE_KIND_UNITS[kind])) {
        const result = UsageRecordSchema.safeParse(record(kind, unit));
        expect(result.success).toBe(false);
        if (!result.success) {
          expect(result.error.issues[0]?.path).toEqual(["unit"]);
          expect(result.error.issues[0]?.message).toContain("kind 与 unit 不配对");
        }
      }
    }
  });

  it("quantity 是十进制字符串：浮点与负数都进不了账本", () => {
    for (const quantity of ["-1", "1e5", "61,4", "", "6.1.4"]) {
      expect(
        UsageRecordSchema.safeParse({
          tenantId: TENANT,
          taskId: null,
          kind: "render_seconds",
          quantity,
          unit: "seconds",
          recordedAt: AT,
        }).success,
        quantity,
      ).toBe(false);
    }
  });

  it("detail 缺省补空对象：记账方不必每次想起它", () => {
    const parsed = UsageRecordSchema.parse(record("publish", "count"));
    expect(parsed.detail).toEqual({});
  });
});

describe("JwtPayloadSchema", () => {
  const payload = { sub: USER, tid: TENANT, role: "reviewer", ver: 0 };

  it("身份与会话版本都是必需的：旧 token 必须重新登录", () => {
    expect(JwtPayloadSchema.parse(payload)).toEqual(payload);
    for (const key of ["sub", "tid", "role", "ver"] as const) {
      const missing = { ...payload };
      delete missing[key];
      expect(JwtPayloadSchema.safeParse(missing).success, `少了 ${key} 也过`).toBe(false);
    }
  });

  it("版本必须是非负整数，不能缺省或从字符串强转", () => {
    for (const ver of [-1, 1.5, "0", null, undefined]) {
      expect(JwtPayloadSchema.safeParse({ ...payload, ver }).success).toBe(false);
    }
  });

  it("tid 必须是 uuid：非 uuid 进 set_config 是在 SQL 里炸 22024，不是安静地 401", () => {
    expect(JwtPayloadSchema.safeParse({ ...payload, tid: "acme" }).success).toBe(false);
    expect(JwtPayloadSchema.safeParse({ ...payload, sub: "周佩" }).success).toBe(false);
  });

  it("角色名单外的值不过（旧 token 里的退役角色不许被当成新角色放行）", () => {
    expect(JwtPayloadSchema.safeParse({ ...payload, role: "editor" }).success).toBe(false);
    for (const role of ROLES) {
      expect(JwtPayloadSchema.parse({ ...payload, role }).role).toBe(role);
    }
  });

  it("多带的字段被剥掉而不是报错：老 token 里捎了 email 也解得出身份，但 email 不会流到下游", () => {
    const parsed = JwtPayloadSchema.parse({ ...payload, email: "zhoupei@example.com" });
    expect(Object.keys(parsed).sort()).toEqual(["role", "sub", "tid", "ver"]);
  });
});

describe("TaskSchema.claimedBy", () => {
  const task = {
    id: USER,
    capability: "news",
    candidateId: null,
    stage: "claimed" as const,
    leaseOwner: null,
    heartbeat: null,
    attempts: 0,
    claimedBy: USER,
    claimedAt: AT,
    errorReason: null,
    createdAt: AT,
    updatedAt: AT,
  };

  it("认领人是 users.id：自由文本过不了契约", () => {
    expect(TaskSchema.parse(task).claimedBy).toBe(USER);
    // 旧 SQLite 层写的是「周佩」这种名字，它会在 T8 被删掉——这条断言就是那条路的围栏
    expect(TaskSchema.safeParse({ ...task, claimedBy: "p0-script" }).success).toBe(false);
    expect(TaskSchema.parse({ ...task, claimedBy: null }).claimedBy).toBeNull();
  });
});

describe("ReviewItemSchema / ConfirmBodySchema", () => {
  const item = {
    taskId: USER,
    title: "某市开通新航线",
    renderVersion: 1,
    contactSheetFingerprint: "a".repeat(64),
    contactSheetPath: null,
    mp4Path: "data/videos/demo/out.mp4",
  };

  it("没有渲染版本的行也过得了：字段是 null 而不是少一行", () => {
    expect(
      ReviewItemSchema.parse({
        taskId: USER,
        title: null,
        renderVersion: null,
        contactSheetFingerprint: null,
        contactSheetPath: null,
        mp4Path: null,
      }).renderVersion,
    ).toBeNull();
  });

  it("renderVersion 是整数版本号，不是任意数字", () => {
    expect(ReviewItemSchema.safeParse({ ...item, renderVersion: 1.5 }).success).toBe(false);
    expect(ReviewItemSchema.safeParse({ ...item, renderVersion: 0 }).success).toBe(false);
  });

  it("空指纹过不了确认：确认动作必须绑着一版具体的联络表", () => {
    expect(ConfirmBodySchema.parse(item).contactSheetFingerprint).toBe("a".repeat(64));
    expect(ConfirmBodySchema.safeParse({ contactSheetFingerprint: "" }).success).toBe(false);
  });
});
