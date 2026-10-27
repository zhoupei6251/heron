import { randomUUID } from "node:crypto";

import { normKey, type FeedEntry } from "@heron/domain";
import { and, eq, isNull, lte, or, sql } from "drizzle-orm";

import { nowIso } from "../time";
import { withAdmin } from "./admin";
import type { Db } from "./driver";
import type { TenantDb } from "./rls";
import { candidates, feedSources } from "./schema/index";

/**
 * 采集的读写。跨租户「谁该抓了」必须走特权通道——调度方不知道下一条属于哪个客户，
 * 与 claimNextTask 同一条理由；抓回来的合并则在租户上下文里做。
 * 本文件在 packages/db 内部，所以 withAdmin 不违反 eslint 的特权通道规则。
 */

export interface DueFeed {
  id: string;
  tenantId: string;
  name: string;
  url: string;
}

/**
 * 领到期的源。先按 last_attempt_at + frequency_sec 筛，再用同一条判据 CAS 一次——
 * 两个 worker 同时来领时，只有一个的 UPDATE 会命中，另一个直接跳过，同一条热点不会被抓两遍、
 * hits 也就不会一次涨两格。last_attempt_at 为 NULL 或解析不出来都算「从没抓过」。
 */
export async function claimDueFeeds(db: Db, at: string = nowIso(), limit = 5): Promise<DueFeed[]> {
  const now = Date.parse(at);
  return withAdmin(db, async (admin) => {
    const rows = await admin.db
      .select({
        id: feedSources.id,
        tenantId: feedSources.tenantId,
        name: feedSources.name,
        url: feedSources.url,
        frequencySec: feedSources.frequencySec,
        lastAttemptAt: feedSources.lastAttemptAt,
      })
      .from(feedSources)
      .where(eq(feedSources.enabled, true));

    const due = rows
      .filter((row) => {
        if (!row.lastAttemptAt) return true;
        const last = Date.parse(row.lastAttemptAt);
        return !Number.isFinite(last) || last + row.frequencySec * 1000 <= now;
      })
      .slice(0, limit);

    const claimed: DueFeed[] = [];
    for (const row of due) {
      const cutoff = nowIso(new Date(now - row.frequencySec * 1000));
      const touched = await admin.db
        .update(feedSources)
        .set({ lastAttemptAt: at })
        .where(
          and(
            eq(feedSources.id, row.id),
            or(isNull(feedSources.lastAttemptAt), lte(feedSources.lastAttemptAt, cutoff)),
          ),
        )
        .returning({ id: feedSources.id });
      if (touched.length > 0) {
        claimed.push({ id: row.id, tenantId: row.tenantId, name: row.name, url: row.url });
      }
    }
    return claimed;
  });
}

/** 抓取结果落库：失败写原因但不动 last_fetch_at，成功才把它推到当前，并清掉旧错误。 */
export async function recordFeedAttempt(
  tdb: TenantDb,
  feedId: string,
  at: string,
  error: string | null,
): Promise<void> {
  const touched = await tdb.db
    .update(feedSources)
    .set({
      lastAttemptAt: at,
      lastFetchAt: error === null ? at : undefined,
      lastError: error === null ? null : error.slice(0, 500),
    })
    .where(and(eq(feedSources.id, feedId), eq(feedSources.tenantId, tdb.tenantId)))
    .returning({ id: feedSources.id });
  if (touched.length === 0) throw new Error(`记录抓取结果失败：没有这个数据源 ${feedId}`);
}

export interface MergedEntry {
  title: string;
  summary: string;
  rank: number;
}

/**
 * 一次抓取的条目 → 候选。同一 norm_key 合并：hits + 1、最近出现时间前推、
 * rank_peak 取历史最好（数字更小）、source_id 记最后一次看到的来源。
 *
 * 同一批里重复的标题先在内存里并掉：PostgreSQL 的 ON CONFLICT DO UPDATE
 * 一条语句里不能两次命中同一行，否则整次抓取报错、一条都进不去。
 */
export async function mergeFeedEntries(
  tdb: TenantDb,
  feedId: string,
  entries: readonly FeedEntry[],
  at: string = nowIso(),
): Promise<number> {
  const byKey = new Map<string, MergedEntry>();
  for (const entry of entries) {
    const key = normKey(entry.title);
    if (key.length === 0) continue;
    const existing = byKey.get(key);
    if (existing) {
      existing.rank = Math.min(existing.rank, entry.rank);
      existing.summary = existing.summary || entry.summary;
      continue;
    }
    byKey.set(key, { title: entry.title, summary: entry.summary, rank: entry.rank });
  }
  if (byKey.size === 0) return 0;

  const rows = [...byKey].map(([key, entry]) => ({
    id: randomUUID(),
    tenantId: tdb.tenantId,
    title: entry.title,
    summary: entry.summary,
    normKey: key,
    sourceId: feedId,
    heat: 0,
    rankPeak: entry.rank,
    firstSeenAt: at,
    lastSeenAt: at,
    createdAt: at,
    updatedAt: at,
  }));

  await tdb.db
    .insert(candidates)
    .values(rows)
    .onConflictDoUpdate({
      target: [candidates.tenantId, candidates.normKey],
      set: {
        hits: sql`${candidates.hits} + 1`,
        lastSeenAt: sql`excluded.last_seen_at`,
        updatedAt: sql`excluded.updated_at`,
        sourceId: sql`excluded.source_id`,
        summary: sql`CASE WHEN excluded.summary <> '' THEN excluded.summary ELSE ${candidates.summary} END`,
        // PostgreSQL 的 least/greatest 忽略 NULL：历史上没名次时直接取新的那条
        rankPeak: sql`least(${candidates.rankPeak}, excluded.rank_peak)`,
        heat: sql`greatest(${candidates.heat}, excluded.heat)`,
      },
    });
  return rows.length;
}

/** 手动触发用：手动抓取不经过 claimDueFeeds，得先拿到地址。 */
export async function getFeedSource(
  tdb: TenantDb,
  id: string,
): Promise<{ id: string; url: string } | null> {
  const rows = await tdb.db
    .select({ id: feedSources.id, url: feedSources.url })
    .from(feedSources)
    .where(and(eq(feedSources.id, id), eq(feedSources.tenantId, tdb.tenantId)))
    .limit(1);
  return rows[0] ?? null;
}
