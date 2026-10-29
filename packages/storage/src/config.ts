/**
 * 存储配置只从环境变量读：AK/SK 是部署方的凭据，不进库、不进镜像、不进 git。
 *
 * 半配的配置必须在**启动时**就炸：能跑起来但缺一项的存储，症状出现在渲染那一刻，
 * 而那时已经付过一次渲染成本（还可能把任务推到 failed）。
 */

/** 七牛 Kodo 的区域代号。华南-广东是 z2，也是本项目的默认值。 */
export const QINIU_REGIONS = ["z0", "cn-east-2", "z1", "z2", "na0", "as0"] as const;
export type QiniuRegion = (typeof QINIU_REGIONS)[number];
export const DEFAULT_QINIU_REGION: QiniuRegion = "z2";

export interface QiniuConfig {
  readonly accessKey: string;
  readonly secretKey: string;
  readonly bucket: string;
  /** 绑定到该空间的下载域名，已归一成 `https://host` 形式（不带结尾斜杠）。 */
  readonly domain: string;
  readonly region: QiniuRegion;
}

export type StorageConfig =
  { readonly driver: "local" } | { readonly driver: "qiniu"; readonly qiniu: QiniuConfig };

/** 缺一项就不许启动的四个键。区域有默认值，所以不在这一组里。 */
export const QINIU_REQUIRED_ENV = [
  "QINIU_ACCESS_KEY",
  "QINIU_SECRET_KEY",
  "QINIU_BUCKET",
  "QINIU_DOMAIN",
] as const;

export function readStorageConfig(env: NodeJS.ProcessEnv = process.env): StorageConfig {
  const driver = (env.HERON_STORAGE ?? "").trim().toLowerCase();
  // 留空 = 本地盘。没配存储的环境行为必须与今天一字不差。
  if (driver === "" || driver === "local") return { driver: "local" };
  if (driver !== "qiniu")
    throw new Error(`HERON_STORAGE 只能是 local 或 qiniu，收到：${JSON.stringify(driver)}`);

  const missing = QINIU_REQUIRED_ENV.filter((name) => !env[name]?.trim());
  if (missing.length > 0)
    throw new Error(
      `HERON_STORAGE=qiniu 但缺少 ${missing.join("、")}。这四项只从环境变量读，不要写进代码、不要提交到仓库`,
    );

  return {
    driver: "qiniu",
    qiniu: {
      accessKey: (env.QINIU_ACCESS_KEY ?? "").trim(),
      secretKey: (env.QINIU_SECRET_KEY ?? "").trim(),
      bucket: (env.QINIU_BUCKET ?? "").trim(),
      domain: normalizeDomain(env.QINIU_DOMAIN ?? ""),
      region: readRegion(env.QINIU_REGION),
    },
  };
}

/** 只给域名或整条 URL 都接受；统一补上 https 与去掉结尾斜杠，签名 URL 才拼得对。 */
function normalizeDomain(raw: string): string {
  const trimmed = raw.trim().replace(/\/+$/, "");
  if (!trimmed) throw new Error("QINIU_DOMAIN 不能为空");
  return /^https?:\/\//i.test(trimmed) ? trimmed : `https://${trimmed}`;
}

function readRegion(raw: string | undefined): QiniuRegion {
  const value = (raw ?? "").trim().toLowerCase();
  if (!value) return DEFAULT_QINIU_REGION;
  const found = QINIU_REGIONS.find((region) => region === value);
  if (!found)
    throw new Error(
      `QINIU_REGION 只能是 ${QINIU_REGIONS.join(" / ")}，收到：${JSON.stringify(raw)}`,
    );
  return found;
}
