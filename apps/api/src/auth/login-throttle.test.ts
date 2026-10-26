import { HttpException } from "@nestjs/common";
import { describe, expect, it } from "vitest";

import {
  LoginThrottleService,
  MAX_FAILURES,
  type LoginThrottleStore,
} from "./login-throttle.service";

const REALM = "acme";
const EMAIL = "owner@example.com";

/** 只实现限流用到的那四条命令，语义按真实 Redis 的最小面来定。 */
/* eslint-disable @typescript-eslint/require-await -- 替身按接口返回 Promise，体内没有真异步 */
class FakeStore implements LoginThrottleStore {
  readonly seenKeys: string[] = [];
  private readonly data = new Map<string, { count: number; expiresAt: number }>();
  broken = false;

  private live(key: string): { count: number; expiresAt: number } | undefined {
    const entry = this.data.get(key);
    if (!entry) return undefined;
    if (entry.expiresAt <= Date.now()) {
      this.data.delete(key);
      return undefined;
    }
    return entry;
  }

  async get(key: string): Promise<string | null> {
    if (this.broken) throw new Error("connection is closed");
    return this.live(key)?.count.toString() ?? null;
  }

  async set(key: string, value: string, _token: "PX", ttl: number, _nx: "NX"): Promise<unknown> {
    if (this.broken) throw new Error("connection is closed");
    if (this.live(key)) return null;
    this.seenKeys.push(key);
    this.data.set(key, { count: Number(value), expiresAt: Date.now() + ttl });
    return "OK";
  }

  async incr(key: string): Promise<number> {
    if (this.broken) throw new Error("connection is closed");
    const entry = this.live(key) ?? { count: 0, expiresAt: Date.now() + 15 * 60_000 };
    entry.count += 1;
    this.data.set(key, entry);
    return entry.count;
  }

  async del(key: string): Promise<number> {
    if (this.broken) throw new Error("connection is closed");
    return this.data.delete(key) ? 1 : 0;
  }
}
/* eslint-enable @typescript-eslint/require-await */

async function fail(times: number, service: LoginThrottleService): Promise<void> {
  for (let i = 0; i < times; i += 1) {
    await service.assertAllowed(REALM, EMAIL);
    await service.settle(REALM, EMAIL, false);
  }
}

describe("登录失败限流", () => {
  it("到阈值才拦：前 5 次失败照常回 401，第 6 次进 429", async () => {
    const service = new LoginThrottleService(new FakeStore());
    await fail(MAX_FAILURES, service);
    await expect(service.assertAllowed(REALM, EMAIL)).rejects.toMatchObject({
      status: 429,
    });
  });

  it("成功登录清零：失败不会跨一次成功继续累计", async () => {
    const service = new LoginThrottleService(new FakeStore());
    await fail(MAX_FAILURES - 1, service);
    await service.settle(REALM, EMAIL, true);
    await fail(MAX_FAILURES, service);
    await expect(service.assertAllowed(REALM, EMAIL)).rejects.toBeInstanceOf(HttpException);
  });

  it("按工作空间 + 邮箱分桶：别的邮箱、别的租户都不受影响", async () => {
    const service = new LoginThrottleService(new FakeStore());
    await fail(MAX_FAILURES, service);
    await expect(service.assertAllowed(REALM, "other@example.com")).resolves.toBeUndefined();
    await expect(service.assertAllowed("other", EMAIL)).resolves.toBeUndefined();
    // 大小写与首尾空白不是新桶：客户端不用为这个再猜一次。
    await expect(service.assertAllowed(" ACME ", " OWNER@example.com ")).rejects.toBeInstanceOf(
      HttpException,
    );
  });

  it("没配 Redis 就进程内计数，阈值一样生效", async () => {
    const service = new LoginThrottleService(null);
    await fail(MAX_FAILURES, service);
    await expect(service.assertAllowed(REALM, EMAIL)).rejects.toMatchObject({ status: 429 });
  });

  it("Redis 断线退回进程内计数：接口不许跟着挂，也不许一下子全放行", async () => {
    const store = new FakeStore();
    const service = new LoginThrottleService(store);
    await fail(MAX_FAILURES - 1, service);
    store.broken = true;
    await service.settle(REALM, EMAIL, false);
    await expect(service.assertAllowed(REALM, EMAIL)).rejects.toMatchObject({ status: 429 });
    await expect(service.assertAllowed(REALM, "other@example.com")).resolves.toBeUndefined();
  });

  it("键里不放邮箱原文：Redis 的 MONITOR 与慢日志会把原始键打出来", async () => {
    const store = new FakeStore();
    const service = new LoginThrottleService(store);
    await service.settle(REALM, EMAIL, false);
    expect(store.seenKeys).toHaveLength(1);
    expect(store.seenKeys[0]).not.toContain("owner");
    expect(store.seenKeys[0]).not.toContain("acme");
  });
});
