import { hostname } from "node:os";

import { snowflakeId } from "@heron/db";
import { parseRedisUrl } from "@heron/redis";

/** 数据在哪、库怎么开、租约多久都在 `@heron/db`。这里只留 worker 自己的身份、重试上限与派发通道。 */
export const MAX_ATTEMPTS = 3;
/** 租约上的进程身份，不是人。库里 lease_owner 与 advanceTask 的 owner 必须同源。 */
export const OWNER = `worker-${hostname()}-${snowflakeId()}`;
/**
 * 派发队列的地址。没配就没有队列，worker 退回纯轮询——开源自托管只靠 Postgres 也要能跑完流水线。
 * 这里不建连接：连接归 BullMQ 的 Worker 管，worker 起来时才要。
 */
export const REDIS_URL = parseRedisUrl(process.env.REDIS_URL);
