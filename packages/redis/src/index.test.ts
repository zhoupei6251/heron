import { createServer, type AddressInfo } from "node:net";

import { describe, expect, it } from "vitest";

import {
  enqueueTask,
  openRedis,
  openTaskQueue,
  parseRedisUrl,
  probeRedis,
  TASK_QUEUE,
} from "./index";

describe("parseRedisUrl", () => {
  it("没配就是 null：功能关闭，不许退到一个默认地址", () => {
    expect(parseRedisUrl(undefined)).toBeNull();
    expect(parseRedisUrl("   ")).toBeNull();
  });

  it("只认 redis / rediss：写错的协议要在启动时报，不是连上去才发现", () => {
    expect(() => parseRedisUrl("http://127.0.0.1:6379")).toThrow(/rediss/);
  });

  it("原串返回：口令里的百分号编码解一次就成了分隔符", () => {
    expect(parseRedisUrl("redis://:p%40ss@127.0.0.1:6379")).toBe("redis://:p%40ss@127.0.0.1:6379");
  });
});

describe("probeRedis", () => {
  it("未配置时探针算通过，但必须标明 configured=false", async () => {
    const result = await probeRedis(null);
    expect(result.configured).toBe(false);
    expect(result.ok).toBe(true);
    expect(result.detail).toContain("REDIS_URL");
  });

  it("PONG 才算通", async () => {
    const result = await probeRedis({ ping: () => Promise.resolve("PONG") });
    expect(result).toMatchObject({ configured: true, ok: true });
  });

  it("连不上要带出原因，不能只报一个 false", async () => {
    const result = await probeRedis({ ping: () => Promise.reject(new Error("ECONNREFUSED")) });
    expect(result.configured).toBe(true);
    expect(result.ok).toBe(false);
    expect(result.detail).toContain("ECONNREFUSED");
  });

  it("卡住的连接按超时判失败：探针不许把健康检查挂住", async () => {
    const result = await probeRedis({ ping: () => new Promise<string>(() => undefined) }, 10);
    expect(result.ok).toBe(false);
    expect(result.detail).toContain("超时");
  });

  it("连不上时报连接错误本身，不是 ioredis 的内部措辞", async () => {
    const port = await closedPort();
    const errors: Error[] = [];
    const redis = openRedis(`redis://127.0.0.1:${port}`, (cause) => errors.push(cause));
    await new Promise<void>((resolve) => redis.once("error", () => resolve()));
    const result = await probeRedis(redis);
    await redis.quit().catch(() => redis.disconnect());

    expect(result).toMatchObject({ configured: true, ok: false });
    expect(result.detail).toContain("ECONNREFUSED");
    expect(errors.length).toBeGreaterThan(0);
  });
});

describe("任务派发队列", () => {
  it("没配 Redis 就没有队列：投递整个关闭，worker 退回轮询而不是启动失败", () => {
    expect(openTaskQueue(null, () => undefined)).toBeNull();
  });

  it("没有队列时投递返回 false，不抛：人点确认不该被 Redis 挡住", async () => {
    const result = await enqueueTask(null, "task-1");
    expect(result.ok).toBe(false);
    expect(result.detail).toContain("队列未配置");
  });

  it("Redis 没起来时投递在超时内给出原因，不许把请求挂住", async () => {
    const port = await closedPort();
    const queue = openTaskQueue(`redis://127.0.0.1:${port}`, () => undefined);
    const started = Date.now();
    const result = await enqueueTask(queue, "task-2");
    const elapsed = Date.now() - started;
    // 只 close 不 obliterate：对着一个从没连上的连接删队列，BullMQ 会另开一条没人监听错误的连接
    await queue?.close().catch(() => undefined);

    expect(result.ok).toBe(false);
    expect(result.detail).not.toBe("");
    expect(elapsed).toBeLessThan(4_000);
  });
});

describe("投递到真 Redis", () => {
  // 没有 REDIS_URL 就整组跳过：单测不许因为本机没起服务而变红。
  const url = parseRedisUrl(process.env.REDIS_URL);
  const live = it.skipIf(!url);

  live("job 只带 taskId，账本仍在 Postgres", async () => {
    const queue = openTaskQueue(url, () => undefined);
    expect(await enqueueTask(queue, "task-live-1")).toMatchObject({ ok: true });

    const waiting = await queue!.getWaiting(0, -1);
    expect(waiting.map((job) => job.data.taskId)).toContain("task-live-1");
    expect(waiting[0]?.name).toBe(TASK_QUEUE);

    await queue!.obliterate({ force: true });
    await queue!.close();
  });
});

/** 先占一个端口再放掉：这个地址保证没人监听，ECONNREFUSED 不会等也不看机器状态。 */
async function closedPort(): Promise<number> {
  const server = createServer();
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address() as AddressInfo;
  await new Promise<void>((resolve) => server.close(() => resolve()));
  return port;
}
