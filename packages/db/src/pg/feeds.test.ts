import { and, eq } from "drizzle-orm";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import { snowflakeId } from "../id";
import { nowIso } from "../time";
import {
  candidates,
  claimDueFeeds,
  createTenant,
  feedSources,
  mergeFeedEntries,
  migrate,
  openDb,
  recordFeedAttempt,
  withTenant,
  type Db,
} from "./index";

const AT = "2026-10-27T12:00:00.000Z";

describe("热榜采集", () => {
  let db: Db;
  let tenantId: string;

  beforeEach(async () => {
    db = openDb("memory");
    await migrate(db);
    tenantId = snowflakeId();
    await createTenant(db, { id: tenantId, name: "采集台", slug: "feeds-test" });
  });

  afterEach(async () => {
    await db.close();
  });

  async function addFeed(input: {
    name: string;
    url: string;
    frequencySec?: number;
    enabled?: boolean;
  }): Promise<string> {
    const id = snowflakeId();
    await withTenant(db, tenantId, (t) =>
      t.db.insert(feedSources).values({
        id,
        tenantId,
        name: input.name,
        url: input.url,
        frequencySec: input.frequencySec ?? 900,
        enabled: input.enabled ?? true,
        createTime: nowIso(),
      }),
    );
    return id;
  }

  it("同标题不同来源合并成一条：hits=2、名次取最好、来源记最后一次", async () => {
    const first = await addFeed({ name: "甲", url: "https://a.example/rss" });
    const second = await addFeed({ name: "乙", url: "https://b.example/rss" });

    await withTenant(db, tenantId, (t) =>
      mergeFeedEntries(t, first, [{ title: "某市开通新航线", summary: "首航", rank: 3 }], AT),
    );
    await withTenant(db, tenantId, (t) =>
      mergeFeedEntries(
        t,
        second,
        [{ title: "某市开通新航线！", summary: "", rank: 1 }],
        "2026-10-27T12:05:00.000Z",
      ),
    );

    const rows = await withTenant(db, tenantId, (t) => t.db.select().from(candidates));
    expect(rows).toHaveLength(1);
    expect(rows[0]).toMatchObject({
      hits: 2,
      rankPeak: 1,
      summary: "首航",
      sourceId: second,
      lastSeenAt: "2026-10-27T12:05:00.000Z",
    });
  });

  it("同一批里重复的标题并成一行，不会撞 ON CONFLICT 两次命中", async () => {
    const feed = await addFeed({ name: "丙", url: "https://c.example/rss" });
    await withTenant(db, tenantId, (t) =>
      mergeFeedEntries(
        t,
        feed,
        [
          { title: "同一个标题", summary: "", rank: 2 },
          { title: "同一个标题", summary: "后来补的摘要", rank: 5 },
        ],
        AT,
      ),
    );
    const rows = await withTenant(db, tenantId, (t) => t.db.select().from(candidates));
    expect(rows).toHaveLength(1);
    expect(rows[0]).toMatchObject({ hits: 1, rankPeak: 2, summary: "后来补的摘要" });
  });

  it("claimDueFeeds：没抓过的立刻到期，抓过一次后按间隔才再到期", async () => {
    const feed = await addFeed({ name: "丁", url: "https://d.example/rss", frequencySec: 900 });

    expect((await claimDueFeeds(db, AT)).map((row) => row.id)).toEqual([feed]);
    expect(await claimDueFeeds(db, AT)).toEqual([]);

    const atNext = "2026-10-27T12:15:00.000Z";
    expect((await claimDueFeeds(db, atNext)).map((row) => row.id)).toEqual([feed]);
  });

  it("停用的源不会被领", async () => {
    await addFeed({ name: "戊", url: "https://e.example/rss", enabled: false });
    expect(await claimDueFeeds(db, AT)).toEqual([]);
  });

  it("失败只写原因、不推进 last_fetch_at；成功才推进并清掉原因", async () => {
    const feed = await addFeed({ name: "己", url: "https://f.example/rss" });

    await withTenant(db, tenantId, (t) => recordFeedAttempt(t, feed, AT, "HTTP 503"));
    let row = await withTenant(db, tenantId, (t) =>
      t.db
        .select()
        .from(feedSources)
        .where(and(eq(feedSources.id, feed), eq(feedSources.tenantId, tenantId)))
        .then((rows) => rows[0]),
    );
    expect(row).toMatchObject({ lastAttemptAt: AT, lastFetchAt: null, lastError: "HTTP 503" });

    const atOk = "2026-10-27T12:20:00.000Z";
    await withTenant(db, tenantId, (t) => recordFeedAttempt(t, feed, atOk, null));
    row = await withTenant(db, tenantId, (t) =>
      t.db
        .select()
        .from(feedSources)
        .where(and(eq(feedSources.id, feed), eq(feedSources.tenantId, tenantId)))
        .then((rows) => rows[0]),
    );
    expect(row).toMatchObject({ lastAttemptAt: atOk, lastFetchAt: atOk, lastError: null });
  });
});
