import { randomUUID } from "node:crypto";

import { nowIso } from "@heron/db";
import {
  candidates,
  createTenant,
  feedSources,
  migrate,
  openDb,
  withTenant,
  type Db,
} from "@heron/db/pg";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import { collectFeeds } from "./collect";

function rss(...titles: string[]): string {
  return `<rss><channel>${titles.map((title) => `<item><title>${title}</title></item>`).join("")}</channel></rss>`;
}

describe("worker 采集", () => {
  let db: Db;
  let tenantId: string;

  beforeEach(async () => {
    db = openDb("memory");
    await migrate(db);
    tenantId = randomUUID();
    await createTenant(db, { id: tenantId, name: "采集台", slug: "worker-collect" });
  });

  afterEach(async () => {
    await db.close();
  });

  async function addFeed(name: string, url: string): Promise<string> {
    const id = randomUUID();
    await withTenant(db, tenantId, (t) =>
      t.db.insert(feedSources).values({
        id,
        tenantId,
        name,
        url,
        frequencySec: 900,
        enabled: true,
        createdAt: nowIso(),
      }),
    );
    return id;
  }

  it("两个源抓到同一标题：候选只有一条、hits=2，两个源都记了成功", async () => {
    const first = await addFeed("甲", "https://a.example/rss");
    const second = await addFeed("乙", "https://b.example/rss");
    const fetcher = () => Promise.resolve(new Response(rss("某市开通新航线")));

    const result = await collectFeeds(db, { fetcher });
    expect(result).toEqual({ ok: 2, failed: 0, imported: 2 });

    const rows = await withTenant(db, tenantId, (t) => t.db.select().from(candidates));
    expect(rows).toHaveLength(1);
    expect(rows[0]?.hits).toBe(2);

    const feeds = await withTenant(db, tenantId, (t) => t.db.select().from(feedSources));
    for (const feed of feeds) {
      expect(feed.lastFetchAt).not.toBeNull();
      expect(feed.lastError).toBeNull();
    }
    expect(feeds.map((feed) => feed.id).sort()).toEqual([first, second].sort());
  });

  it("坏源留下失败原因与最近尝试时间，好源照常入库", async () => {
    const bad = await addFeed("坏", "https://bad.example/rss");
    await addFeed("好", "https://good.example/rss");
    const fetcher = (input: string | URL | Request) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
      return Promise.resolve(
        url.includes("bad")
          ? new Response("down", { status: 502, statusText: "Bad Gateway" })
          : new Response(rss("新能源车销量再创新高")),
      );
    };

    const result = await collectFeeds(db, { fetcher });
    expect(result).toEqual({ ok: 1, failed: 1, imported: 1 });

    const feeds = await withTenant(db, tenantId, (t) => t.db.select().from(feedSources));
    const broken = feeds.find((feed) => feed.id === bad);
    expect(broken?.lastError).toContain("502");
    expect(broken?.lastFetchAt).toBeNull();
    expect(broken?.lastAttemptAt).not.toBeNull();
  });
});
