import { nowIso } from "@heron/db";
import {
  claimDueFeeds,
  mergeFeedEntries,
  recordFeedAttempt,
  withTenant,
  type Db,
} from "@heron/db/pg";
import { fetchFeed } from "@heron/domain";

export interface CollectResult {
  readonly ok: number;
  readonly failed: number;
  /** 本轮写入或合并的候选条数（按标题去重后）。 */
  readonly imported: number;
}

function describe(cause: unknown): string {
  return cause instanceof Error ? cause.message : String(cause);
}

/**
 * 抓一轮到期的源。失败不抛：一个源坏了不该让其它源停摆，
 * 但必须把原因写回 feed_sources.last_error——「不静默」是这一层的验收标准。
 *
 * fetcher 可注入是为了离线测试：真实现只走 domain 的 fetchFeed（含 20s 超时与解析）。
 */
export async function collectFeeds(
  db: Db,
  options: { fetcher?: typeof fetch; signal?: AbortSignal; limit?: number } = {},
): Promise<CollectResult> {
  const feeds = await claimDueFeeds(db, undefined, options.limit ?? 5);
  let ok = 0;
  let failed = 0;
  let imported = 0;

  for (const feed of feeds) {
    const at = nowIso();
    try {
      const entries = await fetchFeed(feed.url, {
        fetcher: options.fetcher,
        signal: options.signal,
      });
      const merged = await withTenant(db, feed.tenantId, async (t) => {
        const count = await mergeFeedEntries(t, feed.id, entries, at);
        await recordFeedAttempt(t, feed.id, at, null);
        return count;
      });
      ok += 1;
      imported += merged;
    } catch (cause) {
      failed += 1;
      // 停机中止不是源的错：写进 last_error 会把「进程退出」记成「源坏了」
      if (options.signal?.aborted) continue;
      const reason = describe(cause);
      console.error(`采集失败 ${feed.name}（${feed.url}）：${reason}`);
      try {
        await withTenant(db, feed.tenantId, (t) => recordFeedAttempt(t, feed.id, at, reason));
      } catch (recordCause) {
        // 连原因都写不回去时，两段都得出现在输出里
        console.error(
          `采集失败原因未落库 ${feed.name}：${describe(recordCause)}（原始原因：${reason}）`,
        );
      }
    }
  }

  return { ok, failed, imported };
}
