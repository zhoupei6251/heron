import { mkdtemp, mkdir, rm, symlink, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { randomUUID } from "node:crypto";
import type { INestApplication } from "@nestjs/common";
import { Test } from "@nestjs/testing";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import {
  type Db,
  withTenant,
  tasks,
  renders,
  openDb,
  migrate,
  createTenant,
  createUser,
} from "@heron/db/pg";
import { hashPassword } from "@heron/domain";
import { AppModule } from "../app.module";
import { PG_DB } from "../db/pg";
import { applyTransport } from "../transport";

const SECRET = "artifact-e2e-not-a-real-key-at-least-32";
const PASSWORD = "artifact-e2e-explicit-password";

describe("protected artifact streaming (real socket + PGlite)", () => {
  let app: INestApplication;
  let db: Db;
  let root: string;
  let videos: string;
  let base: string;
  let cookie: string;
  let tenantId: string;
  let outsider: string;
  let normal: string;
  const originalSecret = process.env.HERON_JWT_SECRET;
  async function task(
    path: string,
    tid = tenantId,
    version = 1,
    key: string | null = null,
  ): Promise<string> {
    return withTenant(db, tid, async (t) => {
      const id = randomUUID();
      const at = new Date().toISOString();
      await t.db.insert(tasks).values({
        id,
        tenantId: tid,
        capability: "demo",
        stage: "review",
        createdAt: at,
        updatedAt: at,
      });
      await t.db.insert(renders).values({
        id: randomUUID(),
        taskId: id,
        version,
        pack: "demo",
        fingerprint: "fp",
        mp4Path: path,
        contactSheetPath: path,
        aigcSidecarPath: path,
        mp4Key: key,
        contactSheetKey: key,
        aigcSidecarKey: key,
        createdAt: at,
      });
      return id;
    });
  }
  const get = (id: string, suffix = "mp4?version=1", headers = {}) =>
    fetch(`${base}/api/manage/artifacts/${id}/${suffix}`, { headers: { cookie, ...headers } });
  beforeAll(async () => {
    process.env.HERON_JWT_SECRET = SECRET;
    root = await mkdtemp(join(tmpdir(), "heron-artifacts-test-"));
    videos = join(root, "videos");
    await mkdir(videos);
    await writeFile(join(videos, "sample.mp4"), "0123456789abcdef");
    await writeFile(join(root, "outside.mp4"), "must never be served");
    await mkdir(join(root, "outside"));
    await writeFile(join(root, "outside", "secret.mp4"), "symlink secret");
    await symlink(join(root, "outside"), join(videos, "escape"), "junction");
    db = openDb("memory");
    await migrate(db);
    tenantId = await createTenant(db, { name: "Artifacts", slug: "artifacts" });
    outsider = await createTenant(db, { name: "Other", slug: "other" });
    await withTenant(db, tenantId, (t) =>
      createUser(t, {
        email: "viewer@example.com",
        displayName: "Viewer",
        passwordHash: hashPassword(PASSWORD),
      }),
    );
    normal = await task(join(videos, "sample.mp4"));
    const mod = await Test.createTestingModule({ imports: [AppModule] })
      .overrideProvider(PG_DB)
      .useValue(db)
      .overrideProvider("ARTIFACT_ROOT")
      .useValue(videos)
      // 假的私有桶：只拼签名直链，不打网络。key 原样回显，方便断言拿到的确实是这一份。
      .overrideProvider("OBJECT_STORE")
      .useValue({
        driver: "qiniu",
        put: () => Promise.resolve({ key: "", bytes: 0 }),
        url: (key: string) => `https://cdn.example.com/${key}?sign=test`,
        remove: () => Promise.resolve(),
      })
      .compile();
    app = mod.createNestApplication({ logger: false });
    applyTransport(app);
    await app.listen(0, "127.0.0.1");
    base = await app.getUrl();
    const login = await fetch(`${base}/api/auth/login`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        tenant: "artifacts",
        identifier: "viewer@example.com",
        password: PASSWORD,
      }),
    });
    expect(login.status).toBe(201);
    cookie = login.headers.getSetCookie()[0]!.split(";")[0]!;
  });
  afterAll(async () => {
    await app?.close();
    await rm(root, { recursive: true, force: true });
    if (originalSecret === undefined) delete process.env.HERON_JWT_SECRET;
    else process.env.HERON_JWT_SECRET = originalSecret;
  });

  it("serves exact bytes with native range handling, content type, private/no-store and no raw paths", async () => {
    const res = await get(normal);
    expect(res.status).toBe(200);
    expect(await res.text()).toBe("0123456789abcdef");
    expect(res.headers.get("content-type")).toBe("video/mp4");
    expect(res.headers.get("cache-control")).toBe("private, no-store");
    expect(res.headers.get("content-disposition")).toContain(normal);
    const range = await get(normal, undefined, { range: "bytes=2-5" });
    expect(range.status).toBe(206);
    expect(await range.text()).toBe("2345");
    expect(range.headers.get("content-range")).toBe("bytes 2-5/16");
    const invalidRange = await get(normal, undefined, { range: "bytes=200-300" });
    expect(invalidRange.status).toBe(416);
    expect(await invalidRange.text()).not.toContain(videos);
    const sheet = await get(normal, "contact-sheet?version=1");
    expect(sheet.headers.get("content-type")).toBe("image/png");
    const sidecar = await get(normal, "aigc-sidecar?version=1");
    expect(sidecar.headers.get("content-type")).toContain("application/json");
    const pending = await fetch(`${base}/api/reviews/pending`, { headers: { cookie } });
    const body = await pending.text();
    expect(body).toContain(`/api/manage/artifacts/${normal}/contact-sheet?version=1`);
    expect(body).not.toContain("sample.mp4");
  });

  it("enforces authentication/RLS before file access, required positive version and valid identifiers", async () => {
    expect((await fetch(`${base}/api/manage/artifacts/${normal}/mp4?version=1`)).status).toBe(401);
    const foreign = await task(join(videos, "sample.mp4"), outsider);
    expect((await get(foreign)).status).toBe(404);
    expect((await get(randomUUID())).status).toBe(404);
    for (const suffix of [
      "mp4",
      "mp4?version=0",
      "mp4?version=-1",
      "mp4?version=1.2",
      "mp4?version=oops",
      "unknown?version=1",
    ])
      expect((await get(normal, suffix)).status).toBe(400);
    expect((await get("not-uuid")).status).toBe(400);
    expect((await get(normal, "mp4?version=2")).status).toBe(409);
    const latest = await task(join(videos, "sample.mp4"), tenantId, 2);
    const stale = await get(latest);
    expect(stale.status).toBe(409);
    expect(await stale.text()).toContain("版本");
  });

  it("reports a stale version before missing paths on the latest render", async () => {
    const id = await task(join(videos, "sample.mp4"));
    expect((await get(id)).status).toBe(200);
    await withTenant(db, tenantId, (t) =>
      t.db.insert(renders).values({
        id: randomUUID(),
        taskId: id,
        version: 2,
        pack: "demo",
        fingerprint: "fp-v2",
        createdAt: new Date().toISOString(),
      }),
    );
    for (const kind of ["mp4", "contact-sheet", "aigc-sidecar"]) {
      expect((await get(id, `${kind}?version=1`)).status).toBe(409);
      expect((await get(id, `${kind}?version=2`)).status).toBe(404);
    }
  });

  it("本地那份没了就 302 到私有桶的签名直链；本地还在时优先读本机", async () => {
    const key = `tenants/${tenantId}/tasks/keyed/v1/mp4.mp4`;
    // 本地有：不绕 CDN（发布与诊断就在这台机器上）
    const both = await task(join(videos, "sample.mp4"), tenantId, 1, key);
    const local = await get(both);
    expect(local.status).toBe(200);
    expect(await local.text()).toBe("0123456789abcdef");

    // 本地没了（保留清理删过），只剩桶里那份：302，签名地址只出现在 Location 里
    const cleaned = await task(join(videos, "cleaned-away.mp4"), tenantId, 1, key);
    const res = await fetch(`${base}/api/manage/artifacts/${cleaned}/mp4?version=1`, {
      headers: { cookie },
      redirect: "manual",
    });
    expect(res.status).toBe(302);
    expect(res.headers.get("location")).toBe(`https://cdn.example.com/${key}?sign=test`);
    expect(res.headers.get("cache-control")).toBe("private, no-store");
    expect(await res.text()).not.toContain("cdn.example.com");
  });

  it("rejects traversal, external path, symlink escape and missing/directory paths without disclosure", async () => {
    for (const path of [
      join(root, "outside.mp4"),
      join(videos, "..", "outside.mp4"),
      "../outside.mp4",
      join(videos, "escape", "secret.mp4"),
      join(videos, "missing.mp4"),
      videos,
    ]) {
      const id = await task(path);
      const res = await get(id);
      expect(res.status).toBe(404);
      const text = await res.text();
      expect(text).not.toContain(root);
      expect(text).not.toMatch(/outside|secret.mp4|ENOENT|SQL/);
    }
  });
});
