import { randomUUID } from "node:crypto";
import { eq, sql } from "drizzle-orm";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import * as repo from "./index";
import type { Db, TenantDb } from "./index";

const AT = "2026-10-22T00:00:00.000Z";
const HASH = "sensitive-password-hash-never-audit";
const feed = { name: "News", url: "https://example.com/feed", frequencySec: 900, enabled: true };

describe("tenant console repositories (real PGlite)", () => {
  let db: Db;
  let a: string;
  let b: string;
  let owner: string;
  let outsider: string;
  const inA = <T>(fn: (tdb: TenantDb) => Promise<T>) => repo.withTenant(db, a, fn);
  beforeEach(async () => {
    db = repo.openDb("memory");
    await repo.migrate(db);
    a = await repo.createTenant(db, { name: "Alpha", slug: "alpha" });
    b = await repo.createTenant(db, { name: "Beta", slug: "beta" });
    owner = await inA((t) =>
      repo.createUser(t, {
        email: "owner@a.com",
        displayName: "Owner",
        passwordHash: HASH,
        role: "owner",
      }),
    );
    outsider = await repo.withTenant(db, b, (t) =>
      repo.createUser(t, {
        email: "owner@b.com",
        displayName: "Outsider",
        passwordHash: HASH,
        role: "owner",
      }),
    );
  });
  afterEach(async () => {
    await db.close();
  });

  it("adds a default session version to auth reads", async () => {
    expect((await inA((t) => repo.getUserById(t, owner)))?.sessionVersion).toBe(0);
  });

  it("paginates safe members using actual counts and search; refuses foreign IDs", async () => {
    expect(repo.listMembers).toBeTypeOf("function");
    const member = await inA((t) =>
      repo.createManagedMember(t, owner, {
        email: " NEW@A.COM ",
        displayName: "New",
        passwordHash: HASH,
        role: "viewer",
      }),
    );
    expect(member.email).toBe("new@a.com");
    expect(member).not.toHaveProperty("passwordHash");
    const page = await inA((t) => repo.listMembers(t, { page: 2, pageSize: 1, q: "" }));
    expect(page).toMatchObject({ total: 2, page: 2, pageSize: 1 });
    expect(page.items).toHaveLength(1);
    expect((await inA((t) => repo.listMembers(t, { q: "new@" }))).items).toEqual([member]);
    expect((await inA((t) => repo.listMembers(t, { page: 9 }))).total).toBe(2);
    await expect(inA((t) => repo.getMember(t, outsider))).rejects.toMatchObject({
      code: "not_found",
    });
    await expect(
      inA((t) => repo.updateMember(t, owner, outsider, { disabled: true })),
    ).rejects.toMatchObject({ code: "not_found" });
    await expect(
      inA((t) =>
        repo.createManagedMember(t, outsider, {
          email: "bad@a.com",
          displayName: "Bad",
          passwordHash: HASH,
          role: "owner",
        }),
      ),
    ).rejects.toMatchObject({ code: "forbidden" });
  });

  it("forbids self-disable/self-demote and rechecks the actor after serialized owner changes", async () => {
    expect(repo.updateMember).toBeTypeOf("function");
    for (const patch of [{ disabled: true }, { role: "admin" as const }]) {
      await expect(inA((t) => repo.updateMember(t, owner, owner, patch))).rejects.toMatchObject({
        code: "forbidden",
      });
    }
    const second = await inA((t) =>
      repo.createManagedMember(t, owner, {
        email: "second@a.com",
        displayName: "Second",
        passwordHash: HASH,
        role: "owner",
      }),
    );
    const outcomes = await Promise.allSettled([
      inA((t) => repo.updateMember(t, owner, second.id, { role: "viewer" })),
      inA((t) => repo.updateMember(t, second.id, owner, { disabled: true })),
    ]);
    expect(outcomes.filter((r) => r.status === "fulfilled")).toHaveLength(1);
    const members = await inA((t) => repo.listMembers(t));
    expect(members.items.filter((m) => m.role === "owner" && m.disabledAt === null)).toHaveLength(
      1,
    );
  });

  it("updates members and records safe actor audit in the same transaction", async () => {
    expect(repo.createManagedMember).toBeTypeOf("function");
    const member = await inA((t) =>
      repo.createManagedMember(t, owner, {
        email: "new@a.com",
        displayName: "New",
        passwordHash: HASH,
        role: "viewer",
      }),
    );
    await inA((t) =>
      repo.updateMember(t, owner, member.id, { displayName: "Changed", disabled: true }),
    );
    expect((await inA((t) => repo.getUserById(t, member.id)))?.sessionVersion).toBe(1);
    const audit = await inA((t) => repo.listAudit(t));
    expect(audit.total).toBe(2);
    expect(audit.items.every((row) => row.actorId === owner && row.actorName === "Owner")).toBe(
      true,
    );
    expect(JSON.stringify(audit)).not.toMatch(/password|sensitive-password-hash/);
    expect((await repo.withTenant(db, b, (t) => repo.listAudit(t))).total).toBe(0);
    await expect(
      inA(async (t) => {
        await repo.updateMember(t, owner, member.id, { displayName: "Rolled back" });
        throw new Error("rollback");
      }),
    ).rejects.toThrow("rollback");
    expect((await inA((t) => repo.getMember(t, member.id))).displayName).toBe("Changed");
    expect((await inA((t) => repo.listAudit(t))).total).toBe(2);
    await expect(
      inA((t) =>
        repo.createManagedMember(t, owner, {
          email: "NEW@a.com",
          displayName: "Duplicate",
          passwordHash: HASH,
          role: "viewer",
        }),
      ),
    ).rejects.toMatchObject({ code: "conflict" });
  });

  it("isolates feeds/workspace and requires an active owner for management", async () => {
    expect(repo.createFeedSource).toBeTypeOf("function");
    const row = await inA((t) => repo.createFeedSource(t, owner, feed));
    expect(row.enabled).toBe(true);
    const updated = await inA((t) =>
      repo.updateFeedSource(t, owner, row.id, { ...feed, enabled: false }),
    );
    expect(updated.enabled).toBe(false);
    expect((await inA((t) => repo.listFeedSources(t, { q: "News" }))).total).toBe(1);
    expect((await repo.withTenant(db, b, (t) => repo.listFeedSources(t))).total).toBe(0);
    await expect(
      repo.withTenant(db, b, (t) => repo.updateFeedSource(t, outsider, row.id, feed)),
    ).rejects.toMatchObject({ code: "not_found" });
    await expect(
      inA((t) => repo.updateFeedSource(t, owner, randomUUID(), feed)),
    ).rejects.toMatchObject({ code: "not_found" });
    await expect(inA((t) => repo.createFeedSource(t, owner, feed))).rejects.toMatchObject({
      code: "conflict",
    });
    await inA((t) => repo.updateWorkspace(t, owner, { name: "Renamed" }));
    expect((await inA(repo.getWorkspace)).name).toBe("Renamed");
    expect((await repo.withTenant(db, b, repo.getWorkspace)).name).toBe("Beta");
    const viewer = await inA((t) =>
      repo.createUser(t, { email: "v@a.com", displayName: "V", passwordHash: HASH }),
    );
    await expect(
      inA((t) => repo.createFeedSource(t, viewer, { ...feed, url: "https://other.com" })),
    ).rejects.toMatchObject({ code: "forbidden" });
  });

  it("lets admins configure but not manage members, and rejects suspended workspace writes", async () => {
    const admin = await inA((t) =>
      repo.createUser(t, {
        email: "admin@a.com",
        displayName: "Admin",
        role: "admin",
        passwordHash: HASH,
      }),
    );
    await inA((t) => repo.updateWorkspace(t, admin, { name: "Admin configured" }));
    const source = await inA((t) => repo.createFeedSource(t, admin, feed));
    await inA((t) => repo.updateFeedSource(t, admin, source.id, { ...feed, enabled: false }));
    await expect(
      inA((t) => repo.updateMember(t, admin, owner, { displayName: "No" })),
    ).rejects.toMatchObject({ code: "forbidden" });
    await expect(
      inA((t) =>
        repo.createManagedMember(t, admin, {
          email: "no@a.com",
          displayName: "No",
          role: "viewer",
          passwordHash: HASH,
        }),
      ),
    ).rejects.toMatchObject({ code: "forbidden" });
    await repo.setTenantSuspended(db, a, AT);
    await expect(inA((t) => repo.updateWorkspace(t, admin, { name: "No" }))).rejects.toMatchObject({
      code: "forbidden",
    });
    await expect(
      inA((t) => repo.updateProfile(t, owner, { displayName: "No" })),
    ).rejects.toMatchObject({ code: "forbidden" });
    await expect(inA((t) => repo.setAuditedRetention(t, admin, 30))).rejects.toMatchObject({
      code: "forbidden",
    });
    await expect(
      inA((t) => repo.updateFeedSource(t, admin, source.id, feed)),
    ).rejects.toMatchObject({ code: "forbidden" });
    await expect(inA((t) => repo.revokeUserSessions(t, owner))).rejects.toMatchObject({
      code: "forbidden",
    });
  });

  it("CAS changes passwords and revokes sessions without auditing any hash", async () => {
    expect(repo.changePassword).toBeTypeOf("function");
    await inA((t) => repo.updateProfile(t, owner, { displayName: "Renamed" }));
    expect((await inA((t) => repo.getMember(t, owner))).displayName).toBe("Renamed");
    await expect(
      inA((t) => repo.changePassword(t, owner, "wrong", "new-secret-hash")),
    ).rejects.toMatchObject({ code: "conflict" });
    expect(await inA((t) => repo.changePassword(t, owner, HASH, "new-secret-hash"))).toBe(1);
    await expect(inA((t) => repo.changePassword(t, owner, HASH, "stale"))).rejects.toMatchObject({
      code: "conflict",
    });
    expect(await inA((t) => repo.revokeUserSessions(t, owner))).toBe(2);
    expect((await inA((t) => repo.getUserById(t, owner)))?.passwordHash).toBe("new-secret-hash");
    await expect(inA((t) => repo.revokeUserSessions(t, outsider))).rejects.toMatchObject({
      code: "forbidden",
    });
    const audit = await inA((t) => repo.listAudit(t));
    expect(audit.total).toBe(3);
    expect(JSON.stringify(audit)).not.toMatch(
      /sensitive-password-hash|new-secret-hash|expectedHash|passwordHash/,
    );
    await db.update(repo.users).set({ disabledAt: AT }).where(eq(repo.users.id, owner));
    await expect(
      inA((t) => repo.updateProfile(t, owner, { displayName: "Disabled" })),
    ).rejects.toMatchObject({ code: "forbidden" });
    await expect(inA((t) => repo.revokeUserSessions(t, owner))).rejects.toMatchObject({
      code: "forbidden",
    });
  });

  it("lists actual candidates, joined tasks, safe assets/publishes and exact usage totals", async () => {
    expect(repo.listCandidates).toBeTypeOf("function");
    const ids = await inA(async (t) => {
      const candidateId = await repo.createCandidate(t, {
        title: "Real news",
        summary: "Summary",
        heat: 42,
      });
      await repo.createCandidate(t, { title: "Unclaimed news", summary: "", heat: 1 });
      const task = await repo.createTask(t, { capability: "news", candidateId });
      const renderId = randomUUID();
      await t.db.insert(repo.renders).values({
        id: renderId,
        taskId: task.id,
        version: 1,
        pack: "news",
        fingerprint: "fp",
        mp4Path: "/private/video.mp4",
        mp4Sha256: "sha",
        mp4Bytes: 123,
        contactSheetPath: "/private/contact.png",
        aigcSidecarPath: "/private/aigc.json",
        createdAt: AT,
      });
      await t.db.insert(repo.publishes).values({
        id: randomUUID(),
        tenantId: a,
        renderId,
        idempotencyKey: "key",
        declaration: "AI",
        status: "sent",
        receipt: "secret-receipt",
        credentialPath: "/private/cookie",
        credentialSha256: "secret-sha",
        createdAt: AT,
        updatedAt: AT,
      });
      await repo.recordUsage(t, { kind: "render_seconds", unit: "seconds", quantity: "0.1" });
      await repo.recordUsage(t, { kind: "render_seconds", unit: "seconds", quantity: "0.2" });
      return { taskId: task.id, renderId };
    });
    const candidates = await inA((t) => repo.listCandidates(t));
    expect(candidates.total).toBe(2);
    expect((await inA((t) => repo.listCandidates(t, { q: "Unclaimed" }))).items[0]?.title).toBe(
      "Unclaimed news",
    );
    const tasks = await inA((t) => repo.listTaskSummaries(t));
    expect(tasks.items[0]).toMatchObject({ id: ids.taskId, title: "Real news" });
    const assets = await inA((t) => repo.listAssets(t));
    expect(assets.items[0]).toMatchObject({
      id: ids.renderId,
      title: "Real news",
      hasMp4: true,
      mp4Bytes: 123,
    });
    const publishes = await inA((t) => repo.listPublishes(t));
    expect(publishes.items[0]).toMatchObject({
      title: "Real news",
      taskId: ids.taskId,
      status: "sent",
    });
    expect(JSON.stringify([assets, publishes])).not.toMatch(/private|receipt|credential|Path/);
    expect(await inA((t) => repo.getArtifactForTask(t, ids.taskId, "mp4"))).toMatchObject({
      path: "/private/video.mp4",
      renderId: ids.renderId,
      sha256: "sha",
      bytes: 123,
    });
    await expect(
      repo.withTenant(db, b, (t) => repo.getArtifactForTask(t, ids.taskId, "mp4")),
    ).rejects.toMatchObject({ code: "not_found" });
    expect(await inA(repo.listUsage)).toEqual([
      { kind: "render_seconds", unit: "seconds", quantity: "0.3" },
    ]);
    await repo.withTenant(db, b, async (t) => {
      expect((await repo.listCandidates(t)).total).toBe(0);
      expect((await repo.listTaskSummaries(t)).total).toBe(0);
      expect((await repo.listAssets(t)).total).toBe(0);
      expect((await repo.listPublishes(t)).total).toBe(0);
      expect(await repo.listUsage(t)).toEqual([]);
    });
  });

  it("applies 0001 once and preserves rows created before the new columns", async () => {
    const result = await db.execute(sql`SELECT session_version FROM users WHERE id = ${owner}`);
    expect(result.rows[0]).toEqual({ session_version: 0 });
    await repo.migrate(db);
    expect((await inA((t) => repo.getUserById(t, owner)))?.sessionVersion).toBe(0);
  });
});
