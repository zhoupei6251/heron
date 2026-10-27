import { randomUUID } from "node:crypto";

import type { CandidateStatus } from "@heron/contracts";
import { normKey } from "@heron/domain";
import { and, eq } from "drizzle-orm";

import { nowIso } from "../time";
import type { TenantDb } from "./rls";
import { candidates } from "./schema/index";

/**
 * 建一条候选，返回 id。去重键在这里算——normKey 只有一份实现，
 * 测试里手搓 /\W/g/ 那种「不带 u 标志的」正则会把整段汉字删光，两条不同标题会撞成同一个空键。
 */
export async function createCandidate(
  tdb: TenantDb,
  input: { title: string; summary: string; heat: number },
): Promise<string> {
  const key = normKey(input.title);
  if (key.length === 0) throw new Error(`标题归一化后为空，无法建立候选：${input.title}`);
  const at = nowIso();
  const rows = await tdb.db
    .insert(candidates)
    .values({
      id: randomUUID(),
      tenantId: tdb.tenantId,
      title: input.title,
      summary: input.summary,
      normKey: key,
      heat: input.heat,
      firstSeenAt: at,
      lastSeenAt: at,
      createdAt: at,
      updatedAt: at,
    })
    .returning({ id: candidates.id });
  const id = rows[0]?.id;
  if (!id) throw new Error("候选写入后没有返回 id");
  return id;
}

/** 租户内查同一条热点（u_candidates_tenant_norm）：别的租户撞同一个热点是正常业务，不算重复。 */
export async function findCandidateByNormKey(tdb: TenantDb, key: string): Promise<string | null> {
  const rows = await tdb.db
    .select({ id: candidates.id })
    .from(candidates)
    .where(and(eq(candidates.normKey, key), eq(candidates.tenantId, tdb.tenantId)))
    .limit(1);
  return rows[0]?.id ?? null;
}

/** 写稿要的输入：标题与摘要。 */
export interface CandidateBrief {
  id: string;
  title: string;
  summary: string;
}

export async function getCandidate(tdb: TenantDb, id: string): Promise<CandidateBrief | null> {
  const rows = await tdb.db
    .select({ id: candidates.id, title: candidates.title, summary: candidates.summary })
    .from(candidates)
    .where(and(eq(candidates.id, id), eq(candidates.tenantId, tdb.tenantId)))
    .limit(1);
  return rows[0] ?? null;
}

/**
 * 认领、发布这些动作会推着候选走 status。
 * 时间戳由调用方传进来：一次状态转移里 tasks.updated_at 与 candidates.updated_at 必须是同一个时刻。
 */
export async function setCandidateStatus(
  tdb: TenantDb,
  candidateId: string,
  status: CandidateStatus,
  at: string,
): Promise<void> {
  await tdb.db
    .update(candidates)
    .set({ status, updatedAt: at })
    .where(
      and(
        eq(candidates.id, candidateId),
        eq(candidates.tenantId, tdb.tenantId), // RLS 之外的第二道：FK 不受策略管，显式钉住归属
      ),
    );
}
