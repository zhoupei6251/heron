import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { afterEach, beforeEach, describe, expect, it } from "vitest";

import type { QiniuConfig } from "./config";
import { createQiniuStore, presignS3Url, qiniuPort, type QiniuPort } from "./qiniu";

const base = {
  accessKey: "ak-test",
  secretKey: "sk-test",
  bucket: "heron-videos",
  region: "z2",
} as const;

const cfg: QiniuConfig = { ...base, download: { kind: "cdn", domain: "https://cdn.example.com" } };

const s3cfg: QiniuConfig = {
  ...base,
  download: { kind: "s3", s3Region: "cn-south-1" },
};

/** 假的 port：只记下被怎么调用，不打网络。 */
function fakePort(): QiniuPort & { calls: string[][] } {
  const calls: string[][] = [];
  return {
    calls,
    uploadToken: (key) => {
      calls.push(["uploadToken", key]);
      return `token-for-${key}`;
    },
    putFile: (token, key, localPath) => {
      calls.push(["putFile", token, key, localPath]);
      return Promise.resolve();
    },
    downloadUrl: (key, ttl) => {
      calls.push(["downloadUrl", key, String(ttl)]);
      return `https://cdn.example.com/${key}?ttl=${String(ttl)}`;
    },
    deleteObject: (key) => {
      calls.push(["deleteObject", key]);
      return Promise.resolve();
    },
  };
}

describe("createQiniuStore", () => {
  let dir: string;
  let file: string;

  beforeEach(() => {
    dir = mkdtempSync(join(tmpdir(), "heron-storage-"));
    file = join(dir, "fake.mp4");
    writeFileSync(file, "0123456789", "utf8");
  });

  afterEach(() => {
    rmSync(dir, { recursive: true, force: true });
  });

  it("put 先换这张 key 专用的凭证，再用同一个 key 上传；字节数由本地文件量", async () => {
    const port = fakePort();
    const store = createQiniuStore(cfg, port);
    await expect(store.put("tenants/t/tasks/x/v1/mp4.mp4", file)).resolves.toEqual({
      key: "tenants/t/tasks/x/v1/mp4.mp4",
      bytes: 10,
    });
    expect(port.calls).toEqual([
      ["uploadToken", "tenants/t/tasks/x/v1/mp4.mp4"],
      ["putFile", "token-for-tenants/t/tasks/x/v1/mp4.mp4", "tenants/t/tasks/x/v1/mp4.mp4", file],
    ]);
  });

  it("文件不存在时抛错，不会上传一个空对象", async () => {
    const store = createQiniuStore(cfg, fakePort());
    await expect(store.put("k", join(dir, "missing.mp4"))).rejects.toThrow();
  });

  it("url 默认 10 分钟，可显式覆盖", () => {
    const port = fakePort();
    const store = createQiniuStore(cfg, port);
    expect(store.url("k/a.mp4")).toContain("ttl=600");
    expect(store.url("k/a.mp4", 60)).toContain("ttl=60");
  });

  it("remove 删的是对象键", async () => {
    const port = fakePort();
    const store = createQiniuStore(cfg, port);
    await store.remove("k/a.mp4");
    expect(port.calls).toEqual([["deleteObject", "k/a.mp4"]]);
  });
});

/**
 * 真 SDK 的两个纯函数面：换凭证与签名都不打网络，
 * 所以这两条能真跑，而不是靠 fake 自我确认。
 */
describe("qiniuPort（真 SDK，无网络）", () => {
  function decodePolicy(token: string): Record<string, unknown> {
    const parts = token.split(":");
    expect(parts).toHaveLength(3);
    expect(parts[0]).toBe("ak-test");
    return JSON.parse(Buffer.from(parts[2] ?? "", "base64url").toString("utf8")) as Record<
      string,
      unknown
    >;
  }

  it("上传凭证的 scope 绑到具体 key（换成别的 key 就写不了）", () => {
    const port = qiniuPort(cfg);
    const now = Math.floor(Date.now() / 1000);
    const policy = decodePolicy(port.uploadToken("tenants/t/tasks/x/v1/mp4.mp4"));
    expect(policy.scope).toBe("heron-videos:tenants/t/tasks/x/v1/mp4.mp4");
    // PutPolicy 把 expires（相对秒）换成 deadline（绝对秒）写进凭证
    expect(Number(policy.deadline)).toBeGreaterThanOrEqual(now + 3590);
    expect(Number(policy.deadline)).toBeLessThanOrEqual(now + 3610);
  });

  it("私有空间的下载地址带过期时间与签名，且 e= 是绝对截止时刻而不是剩余秒数", () => {
    const port = qiniuPort(cfg);
    const now = Math.floor(Date.now() / 1000);
    const url = port.downloadUrl("tenants/t/tasks/x/v1/mp4.mp4", 600);
    expect(url.startsWith("https://cdn.example.com/")).toBe(true);
    expect(url).toContain("mp4.mp4");
    expect(url).toMatch(/token=ak-test:/);
    // 直接传 600 会签出一张 1970 年的票（真跑时 401），所以这里卡住换算
    const deadline = Number(/[?&]e=(\d+)/.exec(url)?.[1]);
    expect(deadline).toBeGreaterThanOrEqual(now + 599);
    expect(deadline).toBeLessThanOrEqual(now + 601);
  });
});

describe("presignS3Url（SigV4，纯函数）", () => {
  const at = Date.UTC(2026, 9, 29, 10, 30, 0); // 2026-10-29T10:30:00Z

  it("签的是 S3 端点，带全套 X-Amz-* 与签名；同一时刻结果稳定", () => {
    const url = presignS3Url(s3cfg, "tenants/t/tasks/x/v1/mp4.mp4", 600, at);
    expect(presignS3Url(s3cfg, "tenants/t/tasks/x/v1/mp4.mp4", 600, at)).toBe(url);

    const parsed = new URL(url);
    expect(parsed.origin).toBe("https://heron-videos.s3.cn-south-1.qiniucs.com");
    expect(parsed.pathname).toBe("/tenants/t/tasks/x/v1/mp4.mp4");
    expect(parsed.searchParams.get("X-Amz-Algorithm")).toBe("AWS4-HMAC-SHA256");
    expect(parsed.searchParams.get("X-Amz-Credential")).toBe(
      "ak-test/20261029/cn-south-1/s3/aws4_request",
    );
    expect(parsed.searchParams.get("X-Amz-Date")).toBe("20261029T103000Z");
    expect(parsed.searchParams.get("X-Amz-Expires")).toBe("600");
    expect(parsed.searchParams.get("X-Amz-SignedHeaders")).toBe("host");
    expect(parsed.searchParams.get("X-Amz-Signature")).toMatch(/^[0-9a-f]{64}$/);
    // Kodo 的私有 token 在 S3 端点上不认（实测 400），别混着用
    expect(parsed.search).not.toContain("token=");
  });

  it("不会签出超过 7 天的有效期（SigV4 上限）", () => {
    const parsed = new URL(presignS3Url(s3cfg, "k.mp4", 30 * 24 * 3600, at));
    expect(parsed.searchParams.get("X-Amz-Expires")).toBe("604800");
  });

  it("cdn 配置下不允许走 S3 签名", () => {
    expect(() => presignS3Url(cfg, "k.mp4", 600, at)).toThrowError(/s3 下载模式/);
  });

  it("qiniuPort 配了 s3Region 时取链自动走 SigV4", () => {
    const url = qiniuPort(s3cfg, () => at).downloadUrl("tenants/t/tasks/x/v1/mp4.mp4", 600);
    expect(url.startsWith("https://heron-videos.s3.cn-south-1.qiniucs.com/")).toBe(true);
    expect(url).toContain("X-Amz-Signature=");
  });
});
