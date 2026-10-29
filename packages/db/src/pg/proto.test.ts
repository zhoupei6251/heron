import { hashPassword } from "@heron/domain";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import { snowflakeId } from "../id";
import { withAdmin } from "./admin";
import {
  type Db,
  advanceProtoProject,
  claimNextProtoProject,
  confirmProtoRevision,
  createProtoProject,
  createProtoRevision,
  createTenant,
  createUser,
  getProtoProject,
  latestProtoRevision,
  listProtoProjects,
  listProtoRevisions,
  markProtoDelivered,
  markProtoFailed,
  migrate,
  openDb,
  recoverExpiredProtoLeases,
  requestProtoGeneration,
  updateProtoRevision,
  withTenant,
} from "./index";

/**
 * 原型工坊仓储的几条红线：
 * 1. 并发上限在租户行上排队（不是各读各的再一起插）；
 * 2. 重新生成只从终态出发——在途/待审时再排一份会撞在同一条版本上；
 * 3. 确认要同时对上「当前版本」与「人看到的预览图指纹」，且人证勾选带是谁、何时；
 * 4. proto_revisions 不带 tenant_id，隔离靠父表 EXISTS 策略，跨租户读不到。
 */

const TENANT_A = "101";
const TENANT_B = "102";
const OWNER = "proto-worker-test";
const PASSWORD_HASH = hashPassword("test-password-1234");
const PREVIEW_V1 = "a".repeat(64);
const PREVIEW_V2 = "b".repeat(64);

interface Seeded {
  tenantId: string;
  userId: string;
  projectId: string;
}

async function newUser(db: Db, tenantId: string): Promise<string> {
  return withTenant(db, tenantId, (t) =>
    createUser(t, {
      email: `proto-${snowflakeId()}@example.com`,
      displayName: "周佩",
      passwordHash: PASSWORD_HASH,
      role: "reviewer",
    }),
  );
}

async function newProject(
  db: Db,
  tenantId = TENANT_A,
  maxInflight = 3,
  title = "开通新航线的后台",
): Promise<Seeded> {
  const userId = await newUser(db, tenantId);
  const project = await withTenant(db, tenantId, (t) =>
    createProtoProject(
      t,
      userId,
      { title, requirement: "做一个能查航线、能订票的后台", target: "web", theme: "cobalt-pro" },
      maxInflight,
    ),
  );
  return { tenantId, userId, projectId: project.id };
}

/** worker 领走并推到 review，带一版带预览图的稿子。 */
async function toReview(db: Db, seeded: Seeded, previewSha256 = PREVIEW_V1): Promise<string> {
  await withAdmin(db, (a) => claimNextProtoProject(a, OWNER, 600));
  return withTenant(db, seeded.tenantId, async (t) => {
    const revision = await createProtoRevision(t, seeded.projectId);
    await updateProtoRevision(t, revision.id, { previewSha256, rpSha256: "c".repeat(64) }, "1");
    for (const [from, to] of [
      ["draft", "scripting"],
      ["scripting", "compiling"],
      ["compiling", "review"],
    ] as const) {
      await advanceProtoProject(t, {
        projectId: seeded.projectId,
        from,
        to,
        owner: OWNER,
        clearLease: to === "review",
      });
    }
    return revision.id;
  });
}

describe("原型项目仓储", () => {
  let db: Db;

  beforeEach(async () => {
    db = openDb("memory");
    await migrate(db);
    await createTenant(db, { id: TENANT_A, name: "A 台", slug: "a" });
    await createTenant(db, { id: TENANT_B, name: "B 台", slug: "b" });
  });

  afterEach(async () => {
    await db.close();
  });

  it("新建即排一次生成：draft、无版本，列表不带需求原文", async () => {
    const seeded = await newProject(db);
    const page = await withTenant(db, TENANT_A, (t) => listProtoProjects(t));
    expect(page.total).toBe(1);
    expect(page.items[0]).toMatchObject({
      id: seeded.projectId,
      title: "开通新航线的后台",
      stage: "draft",
      latestVersion: null,
    });
    expect(page.items[0]).not.toHaveProperty("requirement");
  });

  it("并发上限按租户算：占满之后新建与重新生成都被拒，并说清占用了几个", async () => {
    await newProject(db, TENANT_A, 1);
    await expect(newProject(db, TENANT_A, 1, "第二条")).rejects.toThrow(/最多 1 个.*已占用 1 个/);
    // 别的租户不受影响：上限是租户内的
    await expect(newProject(db, TENANT_B, 1)).resolves.toBeDefined();
  });

  it("重新生成只从终态出发：在途的项目再排一次是冲突，失败后可以重排", async () => {
    const seeded = await newProject(db);
    await withAdmin(db, (a) => claimNextProtoProject(a, OWNER, 600));
    await expect(
      withTenant(db, TENANT_A, (t) => requestProtoGeneration(t, seeded.projectId, 3, "1")),
    ).rejects.toThrow(/当前停在/);

    await withTenant(db, TENANT_A, (t) => markProtoFailed(t, seeded.projectId, "模型超时", OWNER));
    await withTenant(db, TENANT_A, (t) => requestProtoGeneration(t, seeded.projectId, 3, "1"));
    const project = await withTenant(db, TENANT_A, (t) => getProtoProject(t, seeded.projectId));
    expect(project).toMatchObject({ stage: "draft", errorReason: null, attempts: 0 });
  });

  it("推进要带对的租约持有者与来源阶段：错一个就拒绝", async () => {
    const seeded = await newProject(db);
    await withAdmin(db, (a) => claimNextProtoProject(a, OWNER, 600));
    await expect(
      withTenant(db, TENANT_A, (t) =>
        advanceProtoProject(t, {
          projectId: seeded.projectId,
          from: "draft",
          to: "scripting",
          owner: "someone-else",
        }),
      ),
    ).rejects.toThrow(/拒绝转移/);
    await expect(
      withTenant(db, TENANT_A, (t) =>
        advanceProtoProject(t, {
          projectId: seeded.projectId,
          from: "compiling",
          to: "review",
          owner: OWNER,
        }),
      ),
    ).rejects.toThrow(/拒绝转移/);
  });

  it("版本号由仓储递增，列表按新到旧，产物只发 has* 标志", async () => {
    const seeded = await newProject(db);
    await withTenant(db, TENANT_A, async (t) => {
      const v1 = await createProtoRevision(t, seeded.projectId);
      await updateProtoRevision(t, v1.id, { previewSha256: PREVIEW_V1 }, "1");
      const v2 = await createProtoRevision(t, seeded.projectId);
      await updateProtoRevision(
        t,
        v2.id,
        { previewSha256: PREVIEW_V2, rpSha256: "c".repeat(64) },
        "1",
      );
      expect(v2.version).toBe(2);

      const revisions = await listProtoRevisions(t, seeded.projectId);
      expect(revisions.map((r) => r.version)).toEqual([2, 1]);
      expect(revisions[0]).toMatchObject({ hasPreview: true, hasRp: true, hasSource: false });
      expect(revisions[1]).toMatchObject({ hasPreview: true, hasRp: false });
      expect((await latestProtoRevision(t, seeded.projectId))?.version).toBe(2);

      // 列表里的「最新版本」是一条相关子查询：RLS 能不能看见子表，只有真跑一次才知道
      const page = await listProtoProjects(t);
      expect(page.items[0]?.latestVersion).toBe(2);
    });
  });

  it("确认要同时对上当前版本与预览指纹；指纹对不上就 409，勾了 Axure 必须记下是谁何时", async () => {
    const seeded = await newProject(db);
    const revisionId = await toReview(db, seeded);

    await expect(
      withTenant(db, TENANT_A, (t) =>
        confirmProtoRevision(t, seeded.userId, {
          projectId: seeded.projectId,
          revisionId,
          previewSha256: PREVIEW_V2,
          axureVerified: false,
        }),
      ),
    ).rejects.toThrow(/指纹对不上/);

    await withTenant(db, TENANT_A, (t) =>
      confirmProtoRevision(t, seeded.userId, {
        projectId: seeded.projectId,
        revisionId,
        previewSha256: PREVIEW_V1,
        axureVerified: true,
      }),
    );
    const project = await withTenant(db, TENANT_A, (t) => getProtoProject(t, seeded.projectId));
    expect(project?.stage).toBe("approved");
    const revision = await withTenant(db, TENANT_A, (t) =>
      latestProtoRevision(t, seeded.projectId),
    );
    expect(revision).toMatchObject({
      confirmedBy: seeded.userId,
      confirmedByName: "周佩",
      axureVerified: true,
    });
    const revisions = await withTenant(db, TENANT_A, (t) =>
      listProtoRevisions(t, seeded.projectId),
    );
    expect(revisions[0]?.confirmedByName).toBe("周佩");
    expect(revision?.confirmedAt).not.toBeNull();
    expect(revision?.axureVerifiedAt).not.toBeNull();
  });

  it("没勾 Axure 也能交付，但这一版永远没有验证人；交付后再标失败不动它", async () => {
    const seeded = await newProject(db);
    const revisionId = await toReview(db, seeded);
    await withTenant(db, TENANT_A, (t) =>
      confirmProtoRevision(t, seeded.userId, {
        projectId: seeded.projectId,
        revisionId,
        previewSha256: PREVIEW_V1,
        axureVerified: false,
      }),
    );
    await withAdmin(db, (a) => claimNextProtoProject(a, OWNER, 600));
    await withTenant(db, TENANT_A, (t) =>
      markProtoDelivered(t, { projectId: seeded.projectId, revisionId, owner: OWNER }),
    );
    const project = await withTenant(db, TENANT_A, (t) => getProtoProject(t, seeded.projectId));
    expect(project).toMatchObject({ stage: "delivered", leaseOwner: null });
    const revision = await withTenant(db, TENANT_A, (t) =>
      latestProtoRevision(t, seeded.projectId),
    );
    expect(revision).toMatchObject({ axureVerified: false, axureVerifiedAt: null });
    expect(revision?.deliveredAt).not.toBeNull();

    await expect(
      withTenant(db, TENANT_A, (t) => markProtoFailed(t, seeded.projectId, "迟到的异常", OWNER)),
    ).rejects.toThrow(/已在终态/);
  });

  it("子表按父表继承租户：别的租户读不到我的版本，也挂不进我的项目", async () => {
    const seeded = await newProject(db);
    const revisionId = await toReview(db, seeded);
    await expect(
      withTenant(db, TENANT_B, (t) => listProtoRevisions(t, seeded.projectId)),
    ).resolves.toEqual([]);
    await expect(
      withTenant(db, TENANT_B, (t) => latestProtoRevision(t, seeded.projectId)),
    ).resolves.toBeNull();
    // B 往 A 的项目下插版本：父表 EXISTS 判假，WITH CHECK 直接拒
    await expect(
      withTenant(db, TENANT_B, (t) => createProtoRevision(t, seeded.projectId)),
    ).rejects.toThrow();
    const revision = await withTenant(db, TENANT_A, (t) =>
      latestProtoRevision(t, seeded.projectId),
    );
    expect(revision?.id).toBe(revisionId);
  });

  it("租约过期放回池子，并退回可领阶段；到上限就停下等人，原因写进项目", async () => {
    const seeded = await newProject(db);
    await withAdmin(db, (a) => claimNextProtoProject(a, OWNER, 600));
    // 心跳断了时它可能停在中间态：光清租约会让这条项目永远没人能领
    await withTenant(db, TENANT_A, (t) =>
      advanceProtoProject(t, {
        projectId: seeded.projectId,
        from: "draft",
        to: "scripting",
        owner: OWNER,
      }),
    );
    const first = await withAdmin(db, (a) =>
      recoverExpiredProtoLeases(a, { leaseTtlSec: -1, maxAttempts: 2 }),
    );
    expect(first.returned).toContain(seeded.projectId);
    expect(
      (await withTenant(db, TENANT_A, (t) => getProtoProject(t, seeded.projectId)))?.stage,
    ).toBe("draft");

    await withAdmin(db, (a) => claimNextProtoProject(a, OWNER, 600));
    const second = await withAdmin(db, (a) =>
      recoverExpiredProtoLeases(a, { leaseTtlSec: -1, maxAttempts: 2 }),
    );
    expect(second.failed).toContain(seeded.projectId);
    const project = await withTenant(db, TENANT_A, (t) => getProtoProject(t, seeded.projectId));
    expect(project?.stage).toBe("failed");
    expect(project?.errorReason).toMatch(/租约连续过期 2 次/);
  });

  it("待交付（approved）被回收时保持 approved：它的下一步是落库交付，不是重新生成", async () => {
    const seeded = await newProject(db);
    const revisionId = await toReview(db, seeded);
    await withTenant(db, TENANT_A, (t) =>
      confirmProtoRevision(t, seeded.userId, {
        projectId: seeded.projectId,
        revisionId,
        previewSha256: PREVIEW_V1,
        axureVerified: false,
      }),
    );
    await withAdmin(db, (a) => claimNextProtoProject(a, OWNER, 600));
    await withAdmin(db, (a) => recoverExpiredProtoLeases(a, { leaseTtlSec: -1, maxAttempts: 5 }));
    expect(
      (await withTenant(db, TENANT_A, (t) => getProtoProject(t, seeded.projectId)))?.stage,
    ).toBe("approved");
  });
});
