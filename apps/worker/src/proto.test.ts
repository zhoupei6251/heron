import { createHash } from "node:crypto";
import { existsSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { protoArtifacts } from "@heron/db";
import {
  confirmProtoRevision,
  createProtoProject,
  createTenant,
  createUser,
  getProtoProject,
  getProtoRevision,
  latestProtoRevision,
  listUsage,
  migrate,
  openDb,
  protoProjects,
  usageRecords,
  withTenant,
  type Db,
} from "@heron/db/pg";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { OWNER } from "./config";
import { chat } from "./model";
import { walkProto, type ProtoDeps } from "./proto";
import { runEngine, type EngineRun, type EngineRunner } from "./proto-engine";
import { claimNextProto, claimProtoById } from "./scheduler";
import type { ChatFn } from "./scripting";
import { snowflakeId } from "@heron/db";

/**
 * 原型流水线的红线：
 * 1. 模型 → 契约闸 → 编译 → 视觉闸 → 装配，任何一关不过都要带着原因落到 failed；
 * 2. 引擎/模板没装就不烧模型 token，错误直说缺什么；
 * 3. 人确认后的交付先核对盘上的 .rp 指纹，对不上就停，不把别人的稿子当这一版交付。
 *
 * 引擎都是假 CLI：真引擎跑一次要 Python + Pillow + 模板，不适合当单测前提。
 */

const PASSING_AUDIT = {
  ok: true,
  structural_ok: true,
  aesthetic_ok: true,
  gates: {
    color_ok: true,
    font_ok: true,
    semantic_naming_ok: true,
    group_coverage_ok: true,
  },
  coverage: {
    font: { status: "pass", passed: 5, applicable: 5 },
    semanticNaming: { status: "pass", passed: 12, applicable: 12 },
    groupCoverage: { status: "pass", passed: 9, applicable: 9 },
  },
};

const CHECK_OK = JSON.stringify({ ok: true, structural_ok: true, diagnostics: [] });
const PNG = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0]);
const RP = Buffer.from([0xac, 0xef, 0x09, 0x00, 1, 2, 3, 4]);

const SPEC = {
  language: "rp/1",
  target: 9,
  name: "订票后台",
  theme: "cobalt-pro",
  domain: "web",
  pages: [
    {
      id: "orders",
      name: "订单",
      canvas: { w: 1280, h: 800 },
      root: {
        type: "box",
        id: "root",
        layout: "column",
        padding: 24,
        gap: 16,
        children: [{ type: "label", id: "heading", text: "订单" }],
      },
    },
  ],
};

function sha256(data: Buffer): string {
  return createHash("sha256").update(data).digest("hex");
}

function okRun(stdout: string): EngineRun {
  return { ok: true, code: 0, timedOut: false, stdout, stderr: "" };
}

function argValue(args: string[], flag: string): string {
  const index = args.indexOf(flag);
  const value = index === -1 ? undefined : args[index + 1];
  if (value === undefined) throw new Error(`假引擎没拿到 ${flag}`);
  return value;
}

interface EngineOptions {
  check?: () => EngineRun;
  /** 返回 void 表示「按默认成功路径写产物」；返回 run 表示这一关的真实结果。 */
  compile?: (outDir: string) => EngineRun | void;
  build?: (out: string) => EngineRun | void;
  audit?: unknown;
  skipAudit?: boolean;
}

function fakeEngine(options: EngineOptions = {}): EngineRunner {
  return (args) => {
    const command = args[0];
    if (command === "check") {
      return Promise.resolve(options.check?.() ?? okRun(CHECK_OK));
    }
    if (command === "compile") {
      const outDir = argValue(args, "--out-dir");
      const custom = options.compile?.(outDir);
      if (custom) return Promise.resolve(custom);
      mkdirSync(outDir, { recursive: true });
      writeFileSync(join(outDir, "compile-plan.json"), "{}");
      if (options.skipAudit !== true) {
        writeFileSync(
          join(outDir, "audit-report.json"),
          JSON.stringify(options.audit ?? PASSING_AUDIT),
        );
      }
      writeFileSync(join(outDir, "pages-sheet.png"), PNG);
      return Promise.resolve(okRun('{"ok":true}'));
    }
    if (command === "build-rp") {
      const out = argValue(args, "--out");
      const custom = options.build?.(out);
      if (custom) return Promise.resolve(custom);
      writeFileSync(out, RP);
      return Promise.resolve(okRun('{"ok":true}'));
    }
    return Promise.resolve({
      ok: false,
      code: 2,
      timedOut: false,
      stdout: "",
      stderr: `假引擎不认识 ${String(command)}`,
    });
  };
}

it("同机的不同 worker 实例具有不同租约身份", async () => {
  vi.resetModules();
  const next = await import("./config.js");
  expect(next.OWNER).not.toBe(OWNER);
});

describe("原型流水线", () => {
  let db: Db;
  let tenantId: string;
  let userId: string;
  let root: string;
  let engineHome: string;
  let templatePath: string;
  let artifactsRoot: string;
  let chatCalls: number;

  beforeEach(async () => {
    db = openDb("memory");
    await migrate(db);
    tenantId = snowflakeId();
    await createTenant(db, {
      id: tenantId,
      name: "原型测试台",
      slug: `proto-${tenantId.slice(0, 8)}`,
    });
    userId = await withTenant(db, tenantId, (t) =>
      createUser(t, {
        email: "proto@example.com",
        displayName: "原型制作者",
        passwordHash: "!synthetic-test",
        role: "reviewer",
      }),
    );

    root = mkdtempSync(join(tmpdir(), "heron-proto-"));
    engineHome = join(root, "zee-rp");
    mkdirSync(join(engineHome, "scripts"), { recursive: true });
    writeFileSync(join(engineHome, "scripts", "proto.mjs"), "// fake\n");
    writeFileSync(join(engineHome, "release-manifest.json"), '{"version":"0.2.1"}');
    templatePath = join(root, "blank.rp");
    writeFileSync(templatePath, Buffer.from([0xac, 0xef, 0x09, 0x00]));
    artifactsRoot = join(root, "protos");
    chatCalls = 0;
  });

  afterEach(async () => {
    await db.close();
    rmSync(root, { recursive: true, force: true });
  });

  async function seedProject(): Promise<string> {
    const project = await withTenant(db, tenantId, (t) =>
      createProtoProject(
        t,
        userId,
        {
          title: "订票后台",
          requirement: "能查航线、能下单，下单后能看到结果",
          target: "web",
          theme: "cobalt-pro",
        },
        3,
      ),
    );
    return project.id;
  }

  function deps(options: EngineOptions = {}): ProtoDeps {
    const chatStub: ChatFn = () => {
      chatCalls += 1;
      return Promise.resolve({
        content: JSON.stringify(SPEC),
        tokensIn: 100,
        tokensOut: 200,
        model: "stub-model",
      });
    };
    return {
      chat: chatStub,
      modelConfig: { baseUrl: "http://127.0.0.1:9", apiKey: "", model: "stub-model" },
      engine: fakeEngine(options),
      engineHome,
      templatePath,
      artifactsRoot,
    };
  }

  async function claim() {
    const claimed = await claimNextProto(db);
    if (!claimed) throw new Error("池子是空的：预期能领到一条原型项目");
    return claimed;
  }

  async function state(projectId: string) {
    return withTenant(db, tenantId, (t) => getProtoProject(t, projectId));
  }

  it("一路推进到 review：源码、审计、.rp 与预览图指纹落库，租约释放", async () => {
    const projectId = await seedProject();
    const outcome = await walkProto(db, await claim(), deps());

    expect(outcome).toMatchObject({ ok: true, stage: "review" });
    expect(await state(projectId)).toMatchObject({
      stage: "review",
      leaseOwner: null,
      errorReason: null,
    });

    const revision = await withTenant(db, tenantId, (t) => latestProtoRevision(t, projectId));
    expect(revision).toMatchObject({
      version: 1,
      model: "stub-model",
      tokensIn: 100,
      tokensOut: 200,
      engineVersion: "0.2.1",
      failureKind: null,
      rpSha256: sha256(RP),
      rpBytes: RP.length,
      previewSha256: sha256(PNG),
      hasSource: true,
      hasAudit: true,
      hasRp: true,
    });
    const art = protoArtifacts(projectId, 1, artifactsRoot);
    expect(existsSync(art.source)).toBe(true);
    expect(existsSync(art.rp)).toBe(true);
    expect(art.rp.startsWith(root)).toBe(true);
    // review 不在可领集合里：等人期间谁都不许把它再领走
    expect(await claimNextProto(db)).toBeNull();
  });

  it.each(["absolute", "relative", "nested"])(
    "拒绝模型的 %s 素材引用，不交给引擎读取",
    async (mode) => {
      const projectId = await seedProject();
      const otherTenantImage = join(root, "other-tenant-preview.png");
      writeFileSync(otherTenantImage, PNG);
      const image = {
        type: "image",
        id: "private-image",
        src: mode === "relative" ? "../../other-tenant-preview.png" : otherTenantImage,
      };
      const spec = {
        ...SPEC,
        pages: [
          {
            ...SPEC.pages[0],
            root:
              mode === "nested"
                ? {
                    type: "box",
                    children: [{ type: "dynamic-panel", states: [{ id: "one", root: image }] }],
                  }
                : { type: "box", children: [image] },
          },
        ],
      };
      const normal = deps();
      const result = await walkProto(db, await claim(), {
        ...normal,
        chat: () =>
          Promise.resolve({
            content: JSON.stringify(spec),
            model: "stub-model",
            tokensIn: 100,
            tokensOut: 200,
          }),
      });
      expect(result).toMatchObject({ ok: false, kind: "contract" });
      if (!result.ok) expect(result.reason).toMatch(/素材引用.*\/pages\/0\/root/);
      const art = protoArtifacts(projectId, 1, artifactsRoot);
      expect(existsSync(art.rp)).toBe(false);
      expect(existsSync(art.audit)).toBe(false);
      expect((await state(projectId))?.stage).toBe("failed");
    },
  );

  it("契约闸拒绝：原因带 JSON Pointer，版本记 failureKind=contract", async () => {
    const projectId = await seedProject();
    const outcome = await walkProto(
      db,
      await claim(),
      deps({
        check: () =>
          ({
            ok: false,
            code: 1,
            timedOut: false,
            stdout: JSON.stringify({
              ok: false,
              structural_ok: false,
              diagnostics: [
                {
                  severity: "error",
                  code: "E_PROPERTY",
                  pointer: "/pages/0/root/type",
                  message: "未知组件类型 nonsense-widget",
                },
              ],
            }),
            stderr: "",
          }) satisfies EngineRun,
      }),
    );

    expect(outcome).toMatchObject({ ok: false, kind: "contract" });
    if (!outcome.ok) expect(outcome.reason).toContain("/pages/0/root/type");
    const project = await state(projectId);
    expect(project?.stage).toBe("failed");
    expect(project?.errorReason).toContain("nonsense-widget");
    const revision = await withTenant(db, tenantId, (t) => latestProtoRevision(t, projectId));
    expect(revision?.failureKind).toBe("contract");
  });

  it.each(["model", "contract", "engine", "success"])(
    "%s 结果都记录一次已消耗的 token 和模型原稿",
    async (mode) => {
      const projectId = await seedProject();
      const fail = { ok: false, code: 1, timedOut: false, stdout: "", stderr: "compile failed" };
      const normal = deps({
        check:
          mode === "contract"
            ? () => ({
                ...fail,
                stdout:
                  '{"structural_ok":false,"diagnostics":[{"severity":"error","pointer":"/pages","message":"invalid"}]}',
              })
            : undefined,
        compile: mode === "engine" ? () => fail : undefined,
      });
      const content = mode === "model" ? "not JSON" : JSON.stringify(SPEC);
      await walkProto(db, await claim(), {
        ...normal,
        chat: () =>
          Promise.resolve({ content, model: "stub-model", tokensIn: 100, tokensOut: 200 }),
      });
      const revision = await withTenant(db, tenantId, (t) => latestProtoRevision(t, projectId));
      expect(revision).toMatchObject({
        model: "stub-model",
        tokensIn: 100,
        tokensOut: 200,
        engineVersion: "0.2.1",
        hasSource: true,
      });
      const rows = await withTenant(db, tenantId, (t) => t.db.select().from(usageRecords));
      expect(rows).toHaveLength(2);
      expect(
        rows.every(
          (row) =>
            (row.detail as { protoProject: string; protoRevision: string }).protoProject ===
              projectId && (row.detail as { protoRevision: string }).protoRevision === revision?.id,
        ),
      ).toBe(true);
      expect(await withTenant(db, tenantId, (t) => listUsage(t))).toEqual([
        { kind: "llm_tokens_in", quantity: "100", unit: "tokens" },
        { kind: "llm_tokens_out", quantity: "200", unit: "tokens" },
      ]);
      if (revision)
        expect(
          (await withTenant(db, tenantId, (t) => getProtoRevision(t, revision.id)))?.rp1Source,
        ).toBe(content);
      expect(existsSync(protoArtifacts(projectId, 1, artifactsRoot).source)).toBe(true);
    },
  );

  it.each(["", "  ", null])("真实客户端解析空内容 %j 后仍记录用量并失败", async (content) => {
    const projectId = await seedProject();
    const response = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(
        JSON.stringify({
          choices: [{ message: { content } }],
          usage: { prompt_tokens: 11, completion_tokens: 7 },
        }),
      ),
    );
    try {
      const outcome = await walkProto(db, await claim(), { ...deps(), chat });
      expect(outcome).toMatchObject({ ok: false, kind: "model" });
      if (!outcome.ok) expect(outcome.reason).toContain("空内容");
      const revision = await withTenant(db, tenantId, (t) => latestProtoRevision(t, projectId));
      expect(revision).toMatchObject({
        tokensIn: 11,
        tokensOut: 7,
        model: "stub-model",
        failureKind: "model",
      });
      expect(await withTenant(db, tenantId, (t) => listUsage(t))).toEqual([
        { kind: "llm_tokens_in", quantity: "11", unit: "tokens" },
        { kind: "llm_tokens_out", quantity: "7", unit: "tokens" },
      ]);
      expect(await state(projectId)).toMatchObject({ stage: "failed" });
      expect(existsSync(protoArtifacts(projectId, 1, artifactsRoot).rp)).toBe(false);
    } finally {
      response.mockRestore();
    }
  });

  it("视觉闸拒绝：审计报告仍留在版本上供诊断", async () => {
    const projectId = await seedProject();
    const failingAudit = {
      ...PASSING_AUDIT,
      gates: { ...PASSING_AUDIT.gates, font_ok: false },
      coverage: { ...PASSING_AUDIT.coverage, font: { status: "fail", passed: 3, applicable: 5 } },
    };
    const outcome = await walkProto(db, await claim(), deps({ audit: failingAudit }));

    expect(outcome).toMatchObject({ ok: false, kind: "visual" });
    if (!outcome.ok) expect(outcome.reason).toContain("3/5");
    const revision = await withTenant(db, tenantId, (t) => latestProtoRevision(t, projectId));
    expect(revision?.failureKind).toBe("visual");
    const stored = revision
      ? await withTenant(db, tenantId, (t) => getProtoRevision(t, revision.id))
      : null;
    expect(stored?.audit).toMatchObject({ gates: { font_ok: false } });
    expect((await state(projectId))?.stage).toBe("failed");
  });

  it("引擎非零退出与超时都归到 engine，原因里带引擎自己的话", async () => {
    await seedProject();
    const compileFails = await walkProto(
      db,
      await claim(),
      deps({
        compile: () => ({
          ok: false,
          code: 3,
          timedOut: false,
          stdout: "",
          stderr: "[ERR_COMPILE] proto.py boom",
        }),
      }),
    );
    expect(compileFails).toMatchObject({ ok: false, kind: "engine" });
    if (!compileFails.ok) expect(compileFails.reason).toContain("[ERR_COMPILE]");

    const second = await seedProject();
    const timedOut = await walkProto(
      db,
      await claim(),
      deps({
        compile: () => ({ ok: false, code: null, timedOut: true, stdout: "", stderr: "" }),
      }),
    );
    expect(timedOut).toMatchObject({ ok: false, kind: "engine" });
    if (!timedOut.ok) expect(timedOut.reason).toContain("超时");
    expect((await state(second))?.stage).toBe("failed");
  });

  it("引擎产出缺审计报告也算引擎失败，不把没有审计的稿子放去等人", async () => {
    const projectId = await seedProject();
    const outcome = await walkProto(db, await claim(), deps({ skipAudit: true }));
    expect(outcome).toMatchObject({ ok: false, kind: "engine" });
    if (!outcome.ok) expect(outcome.reason).toContain("审计报告");
    expect((await state(projectId))?.stage).toBe("failed");
  });

  it("旧 worker 编译失败不能改写新租约的项目与版本", async () => {
    const projectId = await seedProject();
    const normal = deps();
    const result = await walkProto(db, await claim(), {
      ...normal,
      engine: async (args) => {
        if (args[0] !== "compile") return normal.engine!(args);
        await withTenant(db, tenantId, (t) =>
          t.db.update(protoProjects).set({ leaseOwner: `${OWNER}-replacement` }),
        );
        return { ok: false, code: 3, timedOut: false, stdout: "", stderr: "old process failed" };
      },
    });
    expect(result).toMatchObject({ ok: true, stage: "skipped" });
    expect(await state(projectId)).toMatchObject({
      stage: "compiling",
      leaseOwner: `${OWNER}-replacement`,
      errorReason: null,
    });
    expect(await withTenant(db, tenantId, (t) => latestProtoRevision(t, projectId))).toMatchObject({
      failureKind: null,
      failureReason: null,
    });
  });

  it("模板没装：不烧模型 token，直接以引擎未就绪失败并说缺什么", async () => {
    const projectId = await seedProject();
    const outcome = await walkProto(db, await claim(), {
      ...deps(),
      templatePath: join(root, "missing-blank.rp"),
    });

    expect(outcome).toMatchObject({ ok: false, kind: "engine" });
    if (!outcome.ok) expect(outcome.reason).toContain("AXURE_BLANK_TEMPLATE_RP9");
    expect(chatCalls).toBe(0);
    expect((await state(projectId))?.stage).toBe("failed");
    expect(await withTenant(db, tenantId, (t) => latestProtoRevision(t, projectId))).toBeNull();
  });

  it("人确认后交付：核过指纹才落 delivered", async () => {
    const projectId = await seedProject();
    await walkProto(db, await claim(), deps());
    const revision = await withTenant(db, tenantId, (t) => latestProtoRevision(t, projectId));
    if (!revision?.previewSha256) throw new Error("第一版没有预览图指纹");

    await withTenant(db, tenantId, (t) =>
      confirmProtoRevision(t, userId, {
        projectId,
        revisionId: revision.id,
        previewSha256: revision.previewSha256 ?? "",
        axureVerified: true,
      }),
    );
    const claimed = await claimProtoById(db, projectId);
    if (!claimed) throw new Error("确认后应该能被领去交付");
    const outcome = await walkProto(db, claimed, deps());

    expect(outcome).toMatchObject({ ok: true, stage: "delivered" });
    expect(await state(projectId)).toMatchObject({ stage: "delivered", leaseOwner: null });
    const delivered = await withTenant(db, tenantId, (t) => latestProtoRevision(t, projectId));
    expect(delivered?.deliveredAt).not.toBeNull();
  });

  it("盘上的 .rp 被换过就拒绝交付：不把别人的稿子当这一版发出去", async () => {
    const projectId = await seedProject();
    await walkProto(db, await claim(), deps());
    const revision = await withTenant(db, tenantId, (t) => latestProtoRevision(t, projectId));
    if (!revision?.previewSha256) throw new Error("第一版没有预览图指纹");
    await withTenant(db, tenantId, (t) =>
      confirmProtoRevision(t, userId, {
        projectId,
        revisionId: revision.id,
        previewSha256: revision.previewSha256 ?? "",
        axureVerified: false,
      }),
    );

    writeFileSync(
      protoArtifacts(projectId, revision.version, artifactsRoot).rp,
      Buffer.from([1, 2]),
    );
    const claimed = await claimProtoById(db, projectId);
    if (!claimed) throw new Error("确认后应该能被领去交付");
    const outcome = await walkProto(db, claimed, deps());

    expect(outcome).toMatchObject({ ok: false, kind: "engine" });
    if (!outcome.ok) expect(outcome.reason).toContain("指纹对不上");
    expect((await state(projectId))?.stage).toBe("failed");
  });

  it("引擎子进程：非零退出照样带回 stderr，超时会被杀掉", async () => {
    const fakeHome = join(root, "fake-cli");
    mkdirSync(join(fakeHome, "scripts"), { recursive: true });
    writeFileSync(
      join(fakeHome, "scripts", "proto.mjs"),
      'process.stderr.write("bad thing"); process.exit(3);',
    );
    const failed = await runEngine(fakeHome, ["check"], 5_000);
    expect(failed).toMatchObject({ ok: false, code: 3, timedOut: false });
    expect(failed.stderr).toContain("bad thing");

    writeFileSync(join(fakeHome, "scripts", "proto.mjs"), "setTimeout(() => {}, 60_000);");
    const slow = await runEngine(fakeHome, ["check"], 100);
    expect(slow).toMatchObject({ ok: false, timedOut: true });
  });
});
