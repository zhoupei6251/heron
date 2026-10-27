import { BadRequestException, Inject, Injectable, NotFoundException } from "@nestjs/common";
import type { AuthUser } from "@heron/contracts";
import {
  type Db,
  getFeedSource,
  mergeFeedEntries,
  recordFeedAttempt,
  requireActiveActor,
  withTenant,
} from "@heron/db/pg";
import { nowIso } from "@heron/db";
import { fetchFeed, type FeedEntry } from "@heron/domain";

import { PG_DB } from "../db/pg";

/** 手动「立即采集」。与 worker 的定时采集共用同一套解析与合并，差别只是不经过 claimDueFeeds。 */
@Injectable()
export class FeedService {
  constructor(@Inject(PG_DB) private readonly db: Db) {}

  async fetchNow(user: AuthUser, feedId: string): Promise<{ imported: number }> {
    const feed = await withTenant(this.db, user.tenantId, async (t) => {
      await requireActiveActor(t, user.id, "settings.write");
      const row = await getFeedSource(t, feedId);
      if (!row) throw new NotFoundException("数据源不存在或不在当前工作区");
      return row;
    });
    const at = nowIso();
    let entries: FeedEntry[];
    try {
      entries = await fetchFeed(feed.url);
    } catch (cause) {
      // 失败原因必须落库再抛：界面刷新后还能看到「上次为什么失败」，不是一闪而过的 toast
      const reason = cause instanceof Error ? cause.message : String(cause);
      await withTenant(this.db, user.tenantId, (t) => recordFeedAttempt(t, feed.id, at, reason));
      throw new BadRequestException(`采集失败：${reason}`);
    }
    const imported = await withTenant(this.db, user.tenantId, async (t) => {
      const count = await mergeFeedEntries(t, feed.id, entries, at);
      await recordFeedAttempt(t, feed.id, at, null);
      return count;
    });
    return { imported };
  }
}
