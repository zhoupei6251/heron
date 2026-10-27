/**
 * 热榜抓取的最小实现：RSS / Atom，或「每行一条标题」的纯文本。
 * 解析放在 domain 是因为它是纯函数，api 的手动触发与 worker 的定时抓取共用同一份——
 * 两份解析迟早会在「这个标签算不算标题」上各走一边。
 */

export interface FeedEntry {
  /** 已去标签、压空白的标题。 */
  title: string;
  /** RSS 的 description / Atom 的 summary 或 content；没有就是空串。 */
  summary: string;
  /** 本次抓取里的名次，从 1 起。候选的 rankPeak 取历史最好那名。 */
  rank: number;
}

const FETCH_TIMEOUT_MS = 20_000;

const NAMED_ENTITIES: Record<string, string> = {
  amp: "&",
  lt: "<",
  gt: ">",
  quot: '"',
  apos: "'",
};

function decode(text: string): string {
  return text
    .replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, "$1")
    .replace(/<[^>]*>/g, "")
    .replace(/&(#x?[0-9a-f]+|amp|lt|gt|quot|apos);/gi, (_, entity: string) => {
      if (entity.startsWith("#")) {
        const code =
          entity[1]?.toLowerCase() === "x"
            ? Number.parseInt(entity.slice(2), 16)
            : Number.parseInt(entity.slice(1), 10);
        return Number.isFinite(code) ? String.fromCodePoint(code) : "";
      }
      return NAMED_ENTITIES[entity.toLowerCase()] ?? "";
    })
    .replace(/\s+/g, " ")
    .trim();
}

function tag(block: string, names: readonly string[]): string {
  for (const name of names) {
    const match = new RegExp(`<${name}\\b[^>]*>([\\s\\S]*?)</${name}\\s*>`, "i").exec(block);
    if (match) return decode(match[1] ?? "");
  }
  return "";
}

export function parseFeed(body: string): FeedEntry[] {
  const blocks = [...body.matchAll(/<(item|entry)\b[^>]*>([\s\S]*?)<\/\1\s*>/gi)];
  if (blocks.length > 0) {
    return blocks
      .map((match, index) => {
        const block = match[2] ?? "";
        return {
          title: tag(block, ["title"]),
          summary: tag(block, ["description", "summary", "content"]),
          rank: index + 1,
        };
      })
      .filter((entry) => entry.title.length > 0);
  }
  // XML 但一条 item/entry 都没有：交给调用方报「格式不支持」，
  // 别把整份 XML 按行拆成几千个字典型标题。
  if (/^\s*</.test(body)) return [];
  return body
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line.length > 0 && !line.startsWith("#"))
    .map((title, index) => ({ title, summary: "", rank: index + 1 }));
}

/**
 * 抓取并解析。非 2xx 与「连一条都没解析出来」都当失败抛出去——
 * 静默返回空数组会让界面显示「抓取成功、0 条」，而实际是源坏了。
 *
 * shortcut: 不校验目标是内网还是云元数据地址（SSRF），租户管理员可以配任意 HTTP 地址；
 * 公开托管多租户前补目标地址白名单/解析后 IP 校验。
 */
export async function fetchFeed(
  url: string,
  options: { fetcher?: typeof fetch; signal?: AbortSignal } = {},
): Promise<FeedEntry[]> {
  const fetcher = options.fetcher ?? fetch;
  const timeout = AbortSignal.timeout(FETCH_TIMEOUT_MS);
  const signal = options.signal ? AbortSignal.any([options.signal, timeout]) : timeout;
  const response = await fetcher(url, {
    signal,
    headers: {
      accept: "application/rss+xml, application/atom+xml, application/xml, text/xml, text/plain",
    },
  });
  if (!response.ok) {
    throw new Error(`抓取失败：HTTP ${response.status} ${response.statusText}`.trim());
  }
  const entries = parseFeed(await response.text());
  if (entries.length === 0) {
    throw new Error("抓取成功但没解析出条目：支持 RSS/Atom，或每行一条标题的纯文本");
  }
  return entries;
}
