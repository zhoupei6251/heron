import { createHash } from "node:crypto";
import { hostname } from "node:os";

import { SNOWFLAKE_ID_PATTERN } from "@heron/contracts";

/**
 * 标准 64 位雪花 id：41 bit 毫秒 + 10 bit 节点 + 12 bit 序列。
 *
 * 为什么自己写而不用现成库：这个包要的是「一个进程内单调、跨进程不撞」的 id，
 * 而现成的实现大多把机器号写死在配置里，或者依赖 clock 同步。heron 的部署形态是
 * 单机跑 api + worker 两个进程，节点号用 hostname + pid 兜底就够了。
 *
 * 布局照 Twitter 那套，epoch 换成 2024-01-01：41 bit 毫秒能用到 2093 年，
 * 换算出来的时间序也让「按 id 排序 ≈ 按时间排序」成立（新行才会，存量映射行不成立）。
 */

/** 2024-01-01T00:00:00Z。 */
const EPOCH_MS = 1_704_067_200_000n;
const NODE_BITS = 10n;
const SEQUENCE_BITS = 12n;
const MAX_NODE = (1n << NODE_BITS) - 1n;
const MAX_SEQUENCE = (1n << SEQUENCE_BITS) - 1n;

export interface SnowflakeOptions {
  /** 节点号 0..1023；不传则按 `HERON_NODE_ID`，再兜底到 hostname + pid。 */
  readonly nodeId?: number;
  /** 时钟来源。测试用它把时间钉住，让生成结果可复现。 */
  readonly now?: () => number;
}

export interface Snowflake {
  /** 下一个 id，十进制字符串。 */
  next(): string;
}

/**
 * 节点号：显式配置优先，没配就按 hostname 哈希 + pid 兜底。
 *
 * 只用 hostname 不够——同一台机器上的 api 与 worker 共享 hostname，会拿到同一个节点号，
 * 两个进程同毫秒生成的 id 就会撞。pid 补上这一层：同机并存的两个进程 pid 必不同。
 * 两副本部署 hostname 也不同，但显式配 `HERON_NODE_ID` 更稳（见部署文档）。
 */
function resolveNodeId(): number {
  const configured = process.env.HERON_NODE_ID?.trim();
  if (configured) {
    const parsed = Number(configured);
    if (!Number.isInteger(parsed) || parsed < 0 || parsed > Number(MAX_NODE))
      throw new Error(
        `HERON_NODE_ID 必须是 0..${MAX_NODE.toString()} 的整数，收到：${JSON.stringify(configured)}`,
      );
    return parsed;
  }
  const digest = createHash("sha1").update(hostname()).digest();
  const fromHost = ((digest[0] ?? 0) << 8) | (digest[1] ?? 0);
  return (fromHost + process.pid) % (Number(MAX_NODE) + 1);
}

/** 模块加载即解析：配错了要在启动时炸，而不是等到第一条 insert 才发现。 */
const NODE_ID = resolveNodeId();

export function createSnowflake(options: SnowflakeOptions = {}): Snowflake {
  const nodeId = BigInt(options.nodeId ?? NODE_ID);
  if (nodeId < 0n || nodeId > MAX_NODE)
    throw new Error(`雪花节点号必须是 0..${MAX_NODE.toString()}，收到：${nodeId.toString()}`);
  const now = options.now ?? Date.now;

  let lastTimestamp = -1n;
  let sequence = 0n;

  return {
    next(): string {
      let timestamp = BigInt(now());
      // 时钟回拨：不把时间退回去，宁可与上一条同毫秒（序列位还能分），
      // 也不要给出一串比上一条更小的 id——那会让「按 id 排序 = 按时间序」这条性质破掉。
      if (timestamp < lastTimestamp) timestamp = lastTimestamp;

      if (timestamp === lastTimestamp) {
        sequence = (sequence + 1n) & MAX_SEQUENCE;
        // 同一毫秒里用完了 4096 个序列号：推到下一毫秒去，别让 id 重复。
        if (sequence === 0n) timestamp = lastTimestamp + 1n;
      } else {
        sequence = 0n;
      }
      lastTimestamp = timestamp;

      const id =
        ((timestamp - EPOCH_MS) << (NODE_BITS + SEQUENCE_BITS)) |
        (nodeId << SEQUENCE_BITS) |
        sequence;
      const text = id.toString();
      // 与 contracts 共用同一份格式规则：两边各写一遍正则，迟早会飘。
      if (!SNOWFLAKE_ID_PATTERN.test(text)) throw new Error(`生成的 id 不符合雪花格式：${text}`);
      return text;
    },
  };
}

let shared: Snowflake | undefined;

/**
 * 进程级单例。单调状态必须活在模块里：每次新建一个生成器，
 * 同毫秒的两个调用会拿到同一个序列号，id 就重复了。
 */
export function snowflakeId(): string {
  shared ??= createSnowflake();
  return shared.next();
}

/**
 * 旧 uuid → 新的 numeric id：与迁移 0012 里那段 SQL 表达式**逐位一致**。
 *
 * 为什么需要它：老 SQLite 库里的 id 是 uuid，而新库的列已经是 numeric(19,0)。
 * 导入时必须把 uuid 映射成数，而且外键（task_id、render_id…）要指向同一个数——
 * 所以映射必须是 uuid 的纯函数，而且必须和迁移里的算法一致：
 * 同一份数据，走「升级老库」和「导入老 SQLite」两条路要得到同一个 id。
 *
 * 算法：取 uuid 十六进制的前 13 位（52 bit），与 SQL 里的
 * `('x' || substr(replace(id::text,'-',''),1,13))::bit(52)::bigint` 等价。
 */
export function legacyUuidToId(uuid: string | null | undefined): string {
  if (typeof uuid !== "string")
    throw new Error(`旧 id 是空的，映射不了：${JSON.stringify(uuid ?? null)}`);
  const hex = uuid.replaceAll("-", "").slice(0, 13);
  if (!/^[0-9a-f]{1,13}$/i.test(hex))
    throw new Error(`旧 id 不是 uuid，映射不了：${JSON.stringify(uuid)}`);
  return BigInt(`0x${hex}`).toString();
}
