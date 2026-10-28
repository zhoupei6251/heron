import { createHash, randomUUID } from "node:crypto";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";

import type { INestApplication } from "@nestjs/common";
import { Test } from "@nestjs/testing";
import {
  type Db,
  createProtoProject,
  createProtoRevision,
  createTenant,
  createUser,
  migrate,
  openDb,
  listProtoProjects,
  protoProjects,
  updateProtoRevision,
  withTenant,
} from "@heron/db/pg";
import { protoArtifacts } from "@heron/db";
import { hashPassword } from "@heron/domain";
import { afterAll, beforeAll, describe, expect, it, vi } from "vitest";

import { AppModule } from "../app.module";
import { SESSION_COOKIE } from "../config/runtime";
import { PG_DB } from "../db/pg";
import { QUEUE, TaskQueueService } from "../redis/redis.module";
import { PROTO_ROOT } from "./proto.service";
import { applyTransport } from "../transport";

/**
 * 原型工坊在真 socket 上的四条线：建项目（幂等 + 并发上限）、确认闸、授权下载、能力探针。
 * 状态推进本身由 packages/db 与 worker 的 proto.test.ts 覆盖，这一层验的是
 * 「守卫 + 控制器 + RLS + 文件系统」接在一起之后的行为——单测各自绿了也可能接错线。
 *
 * 每个用例一个新租户：并发上限按租户算，共用会让上一条用例挡住下一条。
 */

const SECRET = "proto-e2e-secret-not-a-real-key-at-least-32";
const PASSWORD = "proto-e2e-explicit-password";
const OPERATOR = "operator@example.com";
const REVIEWER = "reviewer@example.com";
const VIEWER = "viewer@example.com";
const BRIEF = {
  title: "航线查询后台",
  requirement: "做一个能查航线、能订票的后台",
  target: "web",
  theme: "cobalt-pro",
} as const;
const PREVIEW = "preview-bytes";

const originalSecret = process.env.HERON_JWT_SECRET;

function sha(text: string): string {
  return createHash("sha256").update(text).digest("hex");
}

describe("原型工坊 API（真 socket + PGlite）", () => {
  let db: Db;
  let app: INestApplication;
  let base: string;
  let root: string;
  let tenantSeq = 0;

  async function json(res: Response): Promise<Record<string, unknown>> {
    return (await res.json()) as Record<string, unknown>;
  }

  function get(path: string, token?: string): Promise<Response> {
    return fetch(`${base}/api${path}`, {
      headers: token ? { cookie: `${SESSION_COOKIE}=${token}` } : {},
    });
  }

  function post(path: string, token: string, body: unknown): Promise<Response> {
    return fetch(`${base}/api${path}`, {
      method: "POST",
      headers: { "content-type": "application/json", cookie: `${SESSION_COOKIE}=${token}` },
      body: JSON.stringify(body),
    });
  }

  async function login(slug: string, email: string): Promise<string> {
    const res = await fetch(`${base}/api/auth/login`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ tenant: slug, identifier: email, password: PASSWORD }),
    });
    if (!res.ok) throw new Error(`登录失败：${res.status} ${await res.text()}`);
    const pair = (res.headers.getSetCookie()[0] ?? "").split(";")[0] ?? "";
    if (!pair.startsWith(`${SESSION_COOKIE}=`)) throw new Error("登录成功但没拿到会话 cookie");
    return pair.slice(SESSION_COOKIE.length + 1);
  }

  async function newTenant(): Promise<{ slug: string; tenantId: string; operatorId: string }> {
    tenantSeq += 1;
    const slug = `proto-${String(tenantSeq)}`;
    const tenantId = await createTenant(db, { name: `原型租户 ${String(tenantSeq)}`, slug });
    const operatorId = await withTenant(db, tenantId, async (t) => {
      const operator = await createUser(t, {
        email: OPERATOR,
        displayName: "操作员",
        passwordHash: hashPassword(PASSWORD),
        role: "operator",
      });
      await createUser(t, {
        email: REVIEWER,
        displayName: "审核人",
        passwordHash: hashPassword(PASSWORD),
        role: "reviewer",
      });
      await createUser(t, {
        email: VIEWER,
        displayName: "旁观者",
        passwordHash: hashPassword(PASSWORD),
      });
      return operator;
    });
    return { slug, tenantId, operatorId };
  }

  /** 一版停在 review 的稿子：四份产物写在盘上，预览图与 .rp 的指纹记在版本上。 */
  async function seedReviewProject(
    tenantId: string,
    operatorId: string,
    brief: { title: string; requirement: string } = { ...BRIEF },
    rpBytes = "rp-bytes",
  ): Promise<string> {
    const projectId = await withTenant(db, tenantId, async (t) => {
      const project = await createProtoProject(
        t,
        operatorId,
        { ...brief, target: "web", theme: "cobalt-pro" },
        10,
      );
      const revision = await createProtoRevision(t, project.id);
      await updateProtoRevision(t, revision.id, {
        rp1Source: '{"language":"rp/1"}',
        audit: {
          gates: {
            color_ok: true,
            font_ok: true,
            semantic_naming_ok: true,
            group_coverage_ok: true,
          },
        },
        previewSha256: sha(PREVIEW),
        rpSha256: sha(rpBytes),
        rpBytes: rpBytes.length,
      });
      // 夹具把状态摆到 review：这一层不验推进（worker 与仓储的单验了），只验接线。
      // 不带 WHERE 的 UPDATE 靠「新租户只有这一条项目」成立，前提没了就宁可红在夹具上。
      const { total } = await listProtoProjects(t);
      if (total !== 1) {
        throw new Error(`夹具前提不成立：新租户里应有 1 个项目，实际 ${String(total)} 个`);
      }
      await t.db.update(protoProjects).set({ stage: "review" });
      return project.id;
    });
    const art = protoArtifacts(projectId, 1, root);
    await mkdir(art.engineDir, { recursive: true });
    await writeFile(art.source, '{"language":"rp/1"}');
    await writeFile(art.audit, "{}");
    await writeFile(art.preview, PREVIEW);
    await writeFile(art.rp, rpBytes);
    return projectId;
  }

  beforeAll(async () => {
    process.env.HERON_JWT_SECRET = SECRET;
    delete process.env.HERON_COOKIE_SECURE;
    delete process.env.PROTO_MAX_INFLIGHT;
    db = openDb("memory");
    await migrate(db);
    root = await mkdtemp(join(tmpdir(), "heron-proto-api-"));

    const moduleRef = await Test.createTestingModule({ imports: [AppModule] })
      .overrideProvider(PG_DB)
      .useValue(db)
      .overrideProvider(PROTO_ROOT)
      .useValue(root)
      // 队列恒为 null：投没投由 spy 判；真连开发机上的 Redis 是外溢副作用
      .overrideProvider(QUEUE)
      .useValue(null)
      .compile();
    app = moduleRef.createNestApplication({ logger: false });
    applyTransport(app);
    await app.listen(0, "127.0.0.1");
    base = await app.getUrl();
  });

  afterAll(async () => {
    delete process.env.PROTO_MAX_INFLIGHT;
    if (originalSecret === undefined) delete process.env.HERON_JWT_SECRET;
    else process.env.HERON_JWT_SECRET = originalSecret;
    await app.close();
    await rm(root, { recursive: true, force: true });
  });

  it("建项目即排在途并投递，同标题同需求重复提交只有一条", async () => {
    const { slug } = await newTenant();
    const operator = await login(slug, OPERATOR);
    const push = vi.spyOn(app.get(TaskQueueService), "push");

    const created = await post("/proto", operator, BRIEF);
    expect(created.status).toBe(201);
    const detail = await json(created);
    expect(detail).toMatchObject({
      title: BRIEF.title,
      stage: "draft",
      requirement: BRIEF.requirement,
    });
    expect(push.mock.calls.map(([id, kind]) => [id, kind])).toEqual([[detail.id, "proto"]]);

    const again = await post("/proto", operator, BRIEF);
    expect(await json(again)).toMatchObject({ id: detail.id });

    const listed = await json(await get("/proto", operator));
    expect(listed).toMatchObject({ total: 1, items: [{ stage: "draft", latestVersion: null }] });
    // 列表投影里没有 worker 的调度坐标，也没有需求原文
    expect(JSON.stringify(listed)).not.toMatch(/leaseOwner|heartbeat|attempts|requirement/);
  });

  it("并发上限按租户算，拒的时候说得出是哪条限制", async () => {
    const { slug } = await newTenant();
    const operator = await login(slug, OPERATOR);
    process.env.PROTO_MAX_INFLIGHT = "1";
    try {
      expect((await post("/proto", operator, BRIEF)).status).toBe(201);
      const second = await post("/proto", operator, {
        ...BRIEF,
        requirement: "换一个需求，算第二个在途",
      });
      expect(second.status).toBe(409);
      expect(await second.text()).toContain("同时最多 1 个");
    } finally {
      delete process.env.PROTO_MAX_INFLIGHT;
    }
  });

  it("生成要 operator、确认要 reviewer；确认要指纹对上当前那一版并留下谁与何时", async () => {
    const { slug, tenantId, operatorId } = await newTenant();
    const reviewer = await login(slug, REVIEWER);
    const viewer = await login(slug, VIEWER);
    const push = vi.spyOn(app.get(TaskQueueService), "push");
    const projectId = await seedReviewProject(tenantId, operatorId);

    expect((await post("/proto", viewer, BRIEF)).status).toBe(403);
    const detail = await json(await get(`/proto/${projectId}`, reviewer));
    const revisions = detail.revisions as Record<string, unknown>[];
    const gates = detail.gates as { key: string; passed: boolean }[];
    expect(detail).toMatchObject({ stage: "review" });
    // 四项视觉闸都从最新那一版的审计原文里读出来
    expect(gates.map((gate) => gate.key)).toEqual(
      expect.arrayContaining(["color_ok", "font_ok", "semantic_naming_ok", "group_coverage_ok"]),
    );
    expect(gates.every((gate) => gate.passed)).toBe(true);
    const revisionId = String(revisions[0]?.id);

    expect(
      (
        await post(`/proto/${projectId}/confirm`, viewer, {
          revisionId,
          previewSha256: sha(PREVIEW),
          axureVerified: false,
        })
      ).status,
    ).toBe(403);

    const stale = await post(`/proto/${projectId}/confirm`, reviewer, {
      revisionId,
      previewSha256: sha("另一版的预览图"),
      axureVerified: true,
    });
    expect(stale.status).toBe(409);
    expect(await stale.text()).toContain("预览图指纹对不上");

    const confirmed = await post(`/proto/${projectId}/confirm`, reviewer, {
      revisionId,
      previewSha256: sha(PREVIEW),
      axureVerified: true,
    });
    expect(confirmed.status).toBe(201);
    const after = await json(confirmed);
    expect(after).toMatchObject({ stage: "approved" });
    const row = (after.revisions as Record<string, unknown>[])[0];
    expect(row?.axureVerified).toBe(true);
    expect(row?.confirmedBy).toBeTruthy();
    expect(row?.axureVerifiedAt).toBeTruthy();
    // 确认之后仍投队列：approved 的下一步是交付落库
    expect(push.mock.calls.some(([id, kind]) => id === projectId && kind === "proto")).toBe(true);
  });

  it("下载先过 RLS 再碰盘，指纹对不上就不发", async () => {
    const { slug, tenantId, operatorId } = await newTenant();
    const operator = await login(slug, OPERATOR);
    const projectId = await seedReviewProject(tenantId, operatorId);
    const url = `/proto/${projectId}/revisions/1/artifacts/`;

    const rp = await get(`${url}rp`, operator);
    expect(rp.status).toBe(200);
    expect(await rp.text()).toBe("rp-bytes");
    expect(rp.headers.get("x-heron-sha256")).toBe(sha("rp-bytes"));
    expect(rp.headers.get("content-type")).toBe("application/octet-stream");
    expect(rp.headers.get("content-disposition")).toBe(
      `attachment; filename="proto-${projectId}-v1.rp"`,
    );
    expect((await get(`${url}preview`, operator)).headers.get("content-type")).toBe("image/png");
    expect((await get(`${url}source`, operator)).status).toBe(200);

    // 盘上的字节被换掉：库里记的才是审过的那一份，拒绝发出
    await writeFile(protoArtifacts(projectId, 1, root).rp, "tampered");
    const tampered = await get(`${url}rp`, operator);
    expect(tampered.status).toBe(409);
    expect(await tampered.text()).toContain("指纹不一致");

    // 没跑到那一步的产物：404，且不说出目录在哪
    await rm(join(root, projectId, "v1", "engine", "audit-report.json"));
    const gone = await get(`${url}audit`, operator);
    expect(gone.status).toBe(404);
    expect(await gone.text()).not.toContain(root);
    expect((await get(`/proto/${randomUUID()}/revisions/1/artifacts/rp`, operator)).status).toBe(
      404,
    );

    const other = await newTenant();
    const outsider = await login(other.slug, OPERATOR);
    expect((await get(`${url}rp`, outsider)).status).toBe(404);
    expect((await get(`/proto/${projectId}`, outsider)).status).toBe(404);

    expect((await get(`${url}rp`)).status).toBe(401);
    expect((await get(`${url}nope`, operator)).status).toBe(400);
    expect((await get(`/proto/${projectId}/revisions/0/artifacts/rp`, operator)).status).toBe(400);
    expect((await get(`/proto/${projectId}/revisions/abc/artifacts/rp`, operator)).status).toBe(
      400,
    );
    expect((await get("/proto/not-a-uuid", operator)).status).toBe(400);
  });

  it("能力探针如实报告，在途计数不含等人确认的那一版", async () => {
    const { slug, tenantId, operatorId } = await newTenant();
    const operator = await login(slug, OPERATOR);
    // 先摆一条停在 review 的，再排一条 draft：夹具那条 UPDATE 不带 WHERE，顺序就是它的前提
    await seedReviewProject(tenantId, operatorId, {
      title: "待审的那一版",
      requirement: "停在 review，不占并发名额",
    });
    await post("/proto", operator, BRIEF);

    const status = await json(await get("/proto/capability", operator));
    expect(status).toMatchObject({ inflight: 1, maxInflight: 3 });
    for (const key of ["engine", "template"]) {
      const probe = status[key] as Record<string, unknown>;
      expect(typeof probe.ok).toBe("boolean");
      expect(String(probe.detail).length).toBeGreaterThan(0);
    }
  });
});
