import { describe, expect, it } from "vitest";

import { readStorageConfig } from "./config";

const FULL = {
  HERON_STORAGE: "qiniu",
  QINIU_ACCESS_KEY: "ak-test",
  QINIU_SECRET_KEY: "sk-test",
  QINIU_BUCKET: "heron-videos",
  QINIU_DOMAIN: "cdn.example.com",
} as const;

describe("readStorageConfig", () => {
  it("没配 HERON_STORAGE 时就是本地盘", () => {
    expect(readStorageConfig({})).toEqual({ driver: "local" });
  });

  it("显式 local 也一样", () => {
    expect(readStorageConfig({ HERON_STORAGE: "local" })).toEqual({ driver: "local" });
  });

  it("qiniu 缺项立刻抛，并点名缺哪几个", () => {
    expect(() => readStorageConfig({ HERON_STORAGE: "qiniu" })).toThrowError(
      /QINIU_ACCESS_KEY、QINIU_SECRET_KEY、QINIU_BUCKET/,
    );
  });

  it("只缺一项时只点名那一项", () => {
    expect(() => readStorageConfig({ ...FULL, QINIU_BUCKET: "  " })).toThrowError(
      /缺少 QINIU_BUCKET/,
    );
  });

  it("缺省下载域名与 S3 区域时启动即失败，不等到渲染完", () => {
    expect(() => readStorageConfig({ ...FULL, QINIU_DOMAIN: "" })).toThrowError(
      /QINIU_S3_REGION.*QINIU_DOMAIN/,
    );
  });

  it("配了域名的三项齐全时默认华南 z2，域名补成 https", () => {
    expect(readStorageConfig(FULL)).toEqual({
      driver: "qiniu",
      qiniu: {
        accessKey: "ak-test",
        secretKey: "sk-test",
        bucket: "heron-videos",
        region: "z2",
        download: { kind: "cdn", domain: "https://cdn.example.com" },
      },
    });
  });

  it("域名已经带 scheme 时不再叠一层，结尾斜杠去掉", () => {
    const config = readStorageConfig({ ...FULL, QINIU_DOMAIN: "http://cdn.example.com//" });
    expect(config).toMatchObject({
      qiniu: { download: { kind: "cdn", domain: "http://cdn.example.com" } },
    });
  });

  it("给了 S3 区域就只走 S3 端点，域名不再必需（也不再生效）", () => {
    const config = readStorageConfig({
      ...FULL,
      QINIU_DOMAIN: "",
      QINIU_S3_REGION: "cn-south-1",
    });
    expect(config).toMatchObject({ qiniu: { download: { kind: "s3", s3Region: "cn-south-1" } } });
  });

  it("S3 区域写了整条地址就抛错，只收区域代号", () => {
    expect(() =>
      readStorageConfig({
        ...FULL,
        QINIU_S3_REGION: "https://oss-heron.s3.cn-south-1.qiniucs.com",
      }),
    ).toThrowError(/只写区域本身/);
  });

  it("区域可改，非法区域抛错", () => {
    expect(readStorageConfig({ ...FULL, QINIU_REGION: "z0" })).toMatchObject({
      qiniu: { region: "z0" },
    });
    expect(() => readStorageConfig({ ...FULL, QINIU_REGION: "z9" })).toThrowError(/QINIU_REGION/);
  });

  it("驱动名写错时抛错，不静默回落本地盘", () => {
    expect(() => readStorageConfig({ HERON_STORAGE: "oss" })).toThrowError(/local 或 qiniu/);
  });
});
