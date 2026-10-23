import { hostname } from "node:os";

/** 数据在哪、库怎么开、租约多久都在 `@heron/db`。这里只留 worker 自己的身份与重试上限。 */
export const MAX_ATTEMPTS = 3;
/** 租约上的进程身份，不是人。库里 lease_owner 与 advanceTask 的 owner 必须同源。 */
export const OWNER = `worker-${hostname()}`;
