/**
 * 库里所有 `*_at` 列的写入口径：UTC ISO-8601 带 Z。
 * 迁移文件的 DEFAULT 用 strftime 生成同样形状；两边不一致时前端 `new Date()` 会差一个时区。
 */
export function nowIso(now: Date = new Date()): string {
  return now.toISOString();
}
