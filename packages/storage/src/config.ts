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
  readonly region: QiniuRegion;
  /**
   * 取下载链接走哪条路，二选一：
   * - `cdn`：绑到空间的下载域名 + Kodo 私有 token（`?e=&token=`）。有 CDN 加速，
   *   但域名得自带证书，而且域名一旦开着「忽略查询参数」，被签名取过的路径会被边缘缓存成匿名可读。
   * - `s3`：`<bucket>.s3.<s3Region>.qiniucs.com` + AWS SigV4 预签名。七牛自带合法证书、
   *   不经过 CDN 缓存（删掉立刻 404）；代价是没有 CDN 加速。
   *
   * 配了 `QINIU_S3_REGION` 就走 s3，否则必须给 `QINIU_DOMAIN`。
   */
  readonly download:
    | { readonly kind: "cdn"; readonly domain: string }
    | { readonly kind: "s3"; readonly s3Region: string };
}

export type StorageConfig =
  { readonly driver: "local" } | { readonly driver: "qiniu"; readonly qiniu: QiniuConfig };

/** 缺一项就不许启动的三个键。区域与下载域名/端点见下面的二选一。 */
export const QINIU_REQUIRED_ENV = ["QINIU_ACCESS_KEY", "QINIU_SECRET_KEY", "QINIU_BUCKET"] as const;

export function readStorageConfig(env: NodeJS.ProcessEnv = process.env): StorageConfig {
  const driver = (env.HERON_STORAGE ?? "").trim().toLowerCase();
  // 留空 = 本地盘。没配存储的环境行为必须与今天一字不差。
  if (driver === "" || driver === "local") return { driver: "local" };
  if (driver !== "qiniu")
    throw new Error(`HERON_STORAGE 只能是 local 或 qiniu，收到：${JSON.stringify(driver)}`);

  const missing = QINIU_REQUIRED_ENV.filter((name) => !env[name]?.trim());
  if (missing.length > 0)
    throw new Error(
      `HERON_STORAGE=qiniu 但缺少 ${missing.join("、")}。这三项只从环境变量读，不要写进代码、不要提交到仓库`,
    );

  return {
    driver: "qiniu",
    qiniu: {
      accessKey: (env.QINIU_ACCESS_KEY ?? "").trim(),
      secretKey: (env.QINIU_SECRET_KEY ?? "").trim(),
      bucket: (env.QINIU_BUCKET ?? "").trim(),
      region: readRegion(env.QINIU_REGION),
      download: readDownload(env),
    },
  };
}

/** 下载域名与 S3 区域二选一：都没给就没法签发地址，与其运行到渲染后才发现，不如启动就炸。 */
function readDownload(env: NodeJS.ProcessEnv): QiniuConfig["download"] {
  const s3Region = (env.QINIU_S3_REGION ?? "").trim().toLowerCase();
  if (s3Region) {
    if (!/^[a-z]+(-[a-z]+)*-\d+$/.test(s3Region))
      throw new Error(
        `QINIU_S3_REGION 只写区域本身（例：cn-south-1），收到：${JSON.stringify(env.QINIU_S3_REGION)}`,
      );
    // 配了 S3 就以 S3 为准：留着一个不生效的 QINIU_DOMAIN 只会让「为什么改了没反应」变成谜
    return { kind: "s3", s3Region };
  }
  const domain = (env.QINIU_DOMAIN ?? "").trim();
  if (!domain)
    throw new Error(
      "HERON_STORAGE=qiniu 需要 QINIU_S3_REGION（走 S3 端点）或 QINIU_DOMAIN（走 CDN 直链），二选一",
    );
  return { kind: "cdn", domain: normalizeDomain(domain) };
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
