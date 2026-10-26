import type { Page, PageQuery } from "@heron/contracts";

export class ManagementError extends Error {
  constructor(
    public readonly code: "not_found" | "conflict" | "forbidden",
    message: string,
  ) {
    super(message);
    this.name = "ManagementError";
  }
}

/** No SQL/parameters in conflict errors: those may contain authentication hashes. */
export function rethrowConflict(error: unknown): never {
  const cause = error as { code?: string; cause?: { code?: string } };
  if (cause.code === "23505" || cause.cause?.code === "23505") {
    throw new ManagementError("conflict", "A record with this identity already exists");
  }
  throw error;
}

export function searchPattern(q: string): string {
  return `%${q.replace(/[\\%_]/g, "\\$&")}%`;
}

export async function readPage<T>(
  query: PageQuery,
  items: PromiseLike<T[]>,
  count: PromiseLike<{ total: number }[]>,
): Promise<Page<T>> {
  const [rows, totals] = await Promise.all([items, count]);
  return { items: rows, total: totals[0]?.total ?? 0, page: query.page, pageSize: query.pageSize };
}
