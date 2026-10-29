import { readFile } from "node:fs/promises";
import { join } from "node:path";

import { snowflakeId } from "../id";
import { generateDrizzleJson, generateMigration } from "drizzle-kit/api";
import { sql } from "drizzle-orm";
import * as schema from "./schema/index";
import { afterEach, describe, expect, it } from "vitest";
import type { Role } from "@heron/contracts";

import {
  TABLES_PLATFORM,
  TABLES_TENANT_ROOT,
  TABLES_WITH_TENANT,
  TABLES_WITHOUT_TENANT,
  aigcPosture,
  candidates,
  confirmations,
  DEFAULT_TENANT_ID,
  migrate,
  openDb,
  settings,
  taskEvents,
  tasks,
  tenants,
  usageRecords,
  users,
  type Db,
} from "./index";

const AT = "2026-10-22T00:00:00.000Z";
const TENANT_B = "102";

describe("schema v3（多租户）", () => {
  let db: Db | null = null;

  afterEach(async () => {
    if (db) await db.close();
    db = null;
  });

  async function freshDb(): Promise<Db> {
    const opened = openDb("memory");
    db = opened;
    await migrate(opened);
    await opened.insert(tenants).values([
      {
        id: DEFAULT_TENANT_ID,
        name: "默认租户",
        slug: "default",
        suspendedAt: null,
        createTime: AT,
      },
      { id: TENANT_B, name: "第二租户", slug: "second", suspendedAt: null, createTime: AT },
    ]);
    return opened;
  }

  /** drizzle 把驱动错误包成 Failed query，真正的 SQLSTATE 文案在 cause 上。 */
  async function rejection(run: () => Promise<unknown>): Promise<string> {
    try {
      await run();
    } catch (e) {
      const err = e as { message: string; cause?: { message?: string } };
      return `${err.message} ${err.cause?.message ?? ""}`;
    }
    throw new Error("期望被数据库拒掉，结果写进去了");
  }

  async function realTables(d: Db): Promise<string[]> {
    const raw = (await d.execute(
      sql`SELECT table_name FROM information_schema.tables
          WHERE table_schema = 'public' AND table_type = 'BASE TABLE'`,
    )) as unknown as { rows: { table_name: string }[] };
    // migrator 自己建的那张记账表不属于 heron，比对时排除，不然「覆盖率」这条断言永远红
    return raw.rows.map((r) => r.table_name).filter((n) => n !== "__drizzle_migrations");
  }

  it("四张互斥清单正好覆盖库里的表，平台表不混入租户子表", async () => {
    const d = await freshDb();
    const listed = [
      ...TABLES_WITH_TENANT,
      ...TABLES_WITHOUT_TENANT,
      ...TABLES_TENANT_ROOT,
      ...TABLES_PLATFORM,
    ];
    expect(new Set(listed).size).toBe(listed.length);
    expect((await realTables(d)).sort()).toEqual([...listed].sort());
  });

  it("最新快照与当前 schema 一致，下一次生成不会重复建表", async () => {
    // 读 journal 的最后一条而不是写死 0001：每加一个迁移都得回来改一次，改不动的那天就成了摆设
    const journal = JSON.parse(
      await readFile(join(__dirname, "../../drizzle/meta/_journal.json"), "utf8"),
    ) as { entries: { idx: number }[] };
    const latest = journal.entries.at(-1);
    if (!latest) throw new Error("drizzle journal 是空的，没有快照可比");
    // 快照按序号命名（0002_snapshot.json），不是按 journal 的 tag
    const snapshot: unknown = JSON.parse(
      await readFile(
        join(__dirname, `../../drizzle/meta/${String(latest.idx).padStart(4, "0")}_snapshot.json`),
        "utf8",
      ),
    );
    expect(
      await generateMigration(
        snapshot as Parameters<typeof generateMigration>[0],
        generateDrizzleJson(schema),
      ),
    ).toEqual([]);
  });

  it("列了租户的表真的带 tenant_id 这一列", async () => {
    const d = await freshDb();
    for (const table of TABLES_WITH_TENANT) {
      const raw = (await d.execute(
        sql`SELECT column_name FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = ${table}`,
      )) as unknown as { rows: { column_name: string }[] };
      expect(
        raw.rows.map((c) => c.column_name),
        `${table} 缺 tenant_id`,
      ).toContain("tenant_id");
    }
  });

  it("租户 slug 也只留一种规范形：登录第一个查询就按它找人", async () => {
    const d = await freshDb();
    // 与 ck_sys_user_email_lower 成对：写入方（createTenant）会先归一，
    // 这条断言验的是**归一没做也不会写进去**——否则 B 台可以自称 "Acme"，登录就分不出是谁。
    expect(
      await rejection(() =>
        d.insert(tenants).values({
          id: snowflakeId(),
          name: "大写 slug",
          slug: "Acme",
          suspendedAt: null,
          createTime: AT,
        }),
      ),
    ).toMatch(/ck_sys_tenant_slug_lower/);
  });

  it("去重键是租户内的事实：同 norm_key 跨租户可并存，同租户不可", async () => {
    const d = await freshDb();
    const row = (tenantId: string) => ({
      id: snowflakeId(),
      tenantId,
      title: "同一条热点",
      summary: "",
      normKey: "同一条热点",
      sourceId: null,
      heat: 10,
      rankPeak: null,
      hits: 1,
      status: "candidate" as const,
      firstSeenAt: AT,
      lastSeenAt: AT,
      createTime: AT,
      updateTime: AT,
    });
    await d.insert(candidates).values(row(DEFAULT_TENANT_ID));
    await d.insert(candidates).values(row(TENANT_B));
    expect(await rejection(() => d.insert(candidates).values(row(DEFAULT_TENANT_ID)))).toMatch(
      /u_news_candidate_tenant_norm/,
    );
  });

  it("settings 主键是 (tenant_id, key)：同 key 跨租户各存一份", async () => {
    const d = await freshDb();
    await d.insert(settings).values([
      { tenantId: DEFAULT_TENANT_ID, key: "retention_days", value: "30", updateTime: AT },
      { tenantId: TENANT_B, key: "retention_days", value: "7", updateTime: AT },
    ]);
    expect(
      await rejection(() =>
        d.insert(settings).values({
          tenantId: DEFAULT_TENANT_ID,
          key: "retention_days",
          value: "99",
          updateTime: AT,
        }),
      ),
    ).toMatch(/news_setting_tenant_id_key_pk/);
  });

  /** 建一个库里的人，返回 id。set_by / confirmed_by 都是 uuid 外键，自由文本的「谁」回不来了。 */
  async function seedUser(d: Db, tenantId: string, email: string, role: Role): Promise<string> {
    const id = snowflakeId();
    await d.insert(users).values({
      id,
      tenantId,
      email,
      displayName: "测试用户",
      passwordHash: "!synthetic-not-loginable",
      role,
      disabledAt: null,
      createTime: AT,
      updateTime: AT,
    });
    return id;
  }

  it("每个租户最多一条现行 AIGC 姿态（partial unique 取代 SQLite 的 open_flag 补丁）", async () => {
    const d = await freshDb();
    const ownerA = await seedUser(d, DEFAULT_TENANT_ID, "a@example.com", "admin");
    const ownerB = await seedUser(d, TENANT_B, "b@example.com", "admin");
    const row = (tenantId: string, setBy: string) => ({
      id: snowflakeId(),
      tenantId,
      renderMode: "full",
      declaration: "required",
      note: "",
      setBy,
      sinceAt: AT,
      revertedAt: null,
    });
    await d.insert(aigcPosture).values(row(DEFAULT_TENANT_ID, ownerA));
    await d.insert(aigcPosture).values(row(TENANT_B, ownerB));
    expect(
      await rejection(() => d.insert(aigcPosture).values(row(DEFAULT_TENANT_ID, ownerA))),
    ).toMatch(/u_news_posture_single_current/);

    // 回退旧的之后可以再立一条现行：reverted_at 一填，那行就退出唯一约束的范围
    await d
      .update(aigcPosture)
      .set({ revertedAt: AT })
      .where(sql`${aigcPosture.tenantId} = ${DEFAULT_TENANT_ID}`);
    await d.insert(aigcPosture).values(row(DEFAULT_TENANT_ID, ownerA));
  });

  it("确认人必须是库里的人：自由文本的 confirmer 回不来了", async () => {
    const d = await freshDb();
    const confirmer = await seedUser(d, DEFAULT_TENANT_ID, "zhoupei@example.com", "reviewer");
    await d.insert(tasks).values({
      id: snowflakeId(),
      tenantId: DEFAULT_TENANT_ID,
      capability: "news",
      candidateId: null,
      stage: "review",
      leaseOwner: null,
      heartbeat: null,
      attempts: 0,
      claimedBy: null,
      claimedAt: null,
      errorReason: null,
      createTime: AT,
      updateTime: AT,
    });

    const base = {
      id: snowflakeId(),
      tenantId: DEFAULT_TENANT_ID,
      taskId: (await d.select({ id: tasks.id }).from(tasks))[0]!.id,
      renderVersion: 1,
      contactSheetFingerprint: "sha256:abc",
      confirmedAt: AT,
    };
    expect(
      await rejection(() =>
        d.insert(confirmations).values({ ...base, confirmedBy: snowflakeId() }),
      ),
    ).toMatch(/news_confirmation_confirmed_by_sys_user_id_fk/);

    // 旧库那种自由文本（"p0"）连列都进不去：uuid 列拒掉它，不靠口头约定
    expect(
      await rejection(() => d.insert(confirmations).values({ ...base, confirmedBy: "p0" })),
      // 列类型已经是 numeric(19,0)：自由文本塞进来报的是数字解析错，不是 uuid 解析错
    ).toMatch(/invalid input syntax for type numeric/);

    // 对上库里那个人才写得进去——确认人这一列现在只能是真实的人
    await d.insert(confirmations).values({ ...base, confirmedBy: confirmer });
    expect(await d.select({ id: confirmations.id }).from(confirmations)).toHaveLength(1);

    // 大写邮箱进不了库：规范形只留一种，登录查询不许猜大小写
    expect(
      await rejection(() =>
        d.insert(users).values({
          id: snowflakeId(),
          tenantId: DEFAULT_TENANT_ID,
          email: "Boss@Example.com",
          displayName: "坏形状",
          passwordHash: "!synthetic",
          role: "viewer",
          disabledAt: null,
          createTime: AT,
          updateTime: AT,
        }),
      ),
    ).toMatch(/ck_sys_user_email_lower/);

    // 退役的角色名同样进不了库：名单只有 contracts 那一份，存储层不认旧写法
    const staleRole: string = "editor";
    expect(
      await rejection(() =>
        d.insert(users).values({
          id: snowflakeId(),
          tenantId: DEFAULT_TENANT_ID,
          email: "stale@example.com",
          displayName: "旧角色",
          passwordHash: "!synthetic",
          role: staleRole as Role,
          disabledAt: null,
          createTime: AT,
          updateTime: AT,
        }),
      ),
    ).toMatch(/ck_sys_user_role_known/);
  });

  it("用量台账的 kind 与 unit 必须配对：配错是记账事故，不靠 code review 拦", async () => {
    const d = await freshDb();
    await d.insert(usageRecords).values({
      id: snowflakeId(),
      tenantId: DEFAULT_TENANT_ID,
      taskId: null,
      kind: "render_seconds",
      quantity: "61.4",
      unit: "seconds",
      detail: {},
      recordedAt: AT,
    });
    expect(
      await rejection(() =>
        d.insert(usageRecords).values({
          id: snowflakeId(),
          tenantId: DEFAULT_TENANT_ID,
          taskId: null,
          kind: "render_seconds",
          quantity: "61.4",
          unit: "count",
          detail: {},
          recordedAt: AT,
        }),
      ),
    ).toMatch(/ck_usage_record_kind_unit_pair/);
  });

  it("task_events 靠父表 FK 继承租户，自己不存 tenant_id；删任务时台账跟着走", async () => {
    const d = await freshDb();
    const taskId = snowflakeId();
    await d.insert(tasks).values({
      id: taskId,
      tenantId: DEFAULT_TENANT_ID,
      capability: "news",
      candidateId: null,
      stage: "claimed",
      leaseOwner: null,
      heartbeat: null,
      attempts: 0,
      claimedBy: null,
      claimedAt: null,
      errorReason: null,
      createTime: AT,
      updateTime: AT,
    });
    await d.insert(taskEvents).values({
      id: snowflakeId(),
      taskId,
      fromStage: "candidate",
      toStage: "claimed",
      reason: "认领人 p0-script",
      createTime: AT,
    });
    await d.delete(tasks).where(sql`${tasks.id} = ${taskId}`);
    expect(await d.select({ id: taskEvents.id }).from(taskEvents)).toEqual([]);
  });
});
