import { createHash, createHmac } from "node:crypto";
import { statSync } from "node:fs";

import * as qiniu from "qiniu";

import type { QiniuConfig, QiniuRegion } from "./config";
import { DEFAULT_URL_TTL_SEC, type ObjectStore } from "./types";

/**
 * 七牛 Kodo 的最小面：四个动作，且都能被替换（测试只验调用形状，不打网络）。
 * 真实的 SDK 调用集中在 qiniuPort 里，createQiniuStore 只负责编排。
 */
export interface QiniuPort {
  /** 只允许写这一个 key 的上传凭证。 */
  uploadToken(key: string): string;
  /** 从磁盘流式上传（不把 mp4 读进内存）。失败抛错。 */
  putFile(token: string, key: string, localPath: string): Promise<void>;
  /** 时效直链；ttlSec 是**剩余秒数**。CDN 模式签 Kodo token，S3 模式签 SigV4 预签名。 */
  downloadUrl(key: string, ttlSec: number): string;
  /** 删除对象；对象本来就不在算成功。 */
  deleteObject(key: string): Promise<void>;
}

/** 区域代号 → SDK 的 zone 常量。默认 z2（华南-广东）。 */
const ZONES: Record<QiniuRegion, qiniu.conf.Zone> = {
  z0: qiniu.zone.Zone_z0,
  "cn-east-2": qiniu.zone.Zone_cn_east_2,
  z1: qiniu.zone.Zone_z1,
  z2: qiniu.zone.Zone_z2,
  na0: qiniu.zone.Zone_na0,
  as0: qiniu.zone.Zone_as0,
};

/** 上传凭证的有效期。它只在一次 put 的生命周期里用，给一小时足够，给一天就没必要。 */
const UPLOAD_TOKEN_TTL_SEC = 3600;
/** Kodo 的「文件不存在」错误码：删除与 stat 都会遇到它，重跑清理时它是正常态。 */
const ERR_NO_SUCH_FILE = 612;
/** SigV4 的 X-Amz-Expires 上限：7 天。 */
const S3_MAX_TTL_SEC = 604_800;

export function qiniuPort(cfg: QiniuConfig, now: () => number = Date.now): QiniuPort {
  const mac = new qiniu.auth.digest.Mac(cfg.accessKey, cfg.secretKey);
  // 上传域名不写死：zone 自带 up-z2.qiniup.com 一族，SDK 自己挑可用的那个
  const config = new qiniu.conf.Config({ zone: ZONES[cfg.region], useHttpsDomain: true });
  const uploader = new qiniu.form_up.FormUploader(config);
  const manager = new qiniu.rs.BucketManager(mac, config);
  const download = cfg.download;

  return {
    uploadToken: (key) =>
      new qiniu.rs.PutPolicy({
        // scope 绑到具体 key：一张凭证只能写这一个对象，泄漏了也换不了别人的文件
        scope: `${cfg.bucket}:${key}`,
        expires: UPLOAD_TOKEN_TTL_SEC,
      }).uploadToken(mac),

    async putFile(token, key, localPath) {
      const result = await uploader.putFile(token, key, localPath, null);
      if (!result.ok()) throw new Error(`七牛上传失败：${describe(result)}`);
    },

    // SDK 的第三个参数是「截止时刻」（直接拼进 ?e=），不是剩余秒数：传 600 会签出一张
    // 1970 年的过期票，拿它去取成片永远是 401。这里把有效期换算成绝对时间戳。
    downloadUrl:
      download.kind === "s3"
        ? (key, ttlSec) => presignS3Url(cfg, key, ttlSec, now())
        : (key, ttlSec) =>
            manager.privateDownloadUrl(download.domain, key, Math.floor(now() / 1000) + ttlSec),

    async deleteObject(key) {
      const result = await manager.delete(cfg.bucket, key);
      if (!result.ok() && statusOf(result) !== ERR_NO_SUCH_FILE)
        throw new Error(`七牛删除失败：${describe(result)}`);
    },
  };
}

/**
 * S3 端点的预签名直链（AWS SigV4，query 签名）。
 *
 * 七牛的 `<bucket>.s3.<region>.qiniucs.com` 只认 SigV4：Kodo 的 `?e=&token=` 在它上面是 400
 * （实测 NotSupportAnonymous / MissingSecurityElement）。换来的是七牛自带的合法证书
 * （不必先绑自有域名）和「不经过 CDN」——删掉对象立刻 404，签名每次都真校验。
 */
export function presignS3Url(
  cfg: QiniuConfig,
  key: string,
  ttlSec: number,
  nowMs: number = Date.now(),
): string {
  if (cfg.download.kind !== "s3") throw new Error("presignS3Url 只用于 s3 下载模式");
  const { s3Region } = cfg.download;
  const host = `${cfg.bucket}.s3.${s3Region}.qiniucs.com`;
  // X-Amz-Expires 上限 7 天，超过它签名会被判非法；截断而不是抛，免得渲染完才发现
  const expires = Math.min(Math.max(1, Math.floor(ttlSec)), S3_MAX_TTL_SEC);
  const amzDate = new Date(nowMs)
    .toISOString()
    .replace(/\.\d{3}/, "")
    .replace(/[-:]/g, "");
  const dateStamp = amzDate.slice(0, 8);
  const scope = `${dateStamp}/${s3Region}/s3/aws4_request`;
  const canonicalUri = `/${key.split("/").map(uriEncode).join("/")}`;
  const params: Array<[string, string]> = [
    ["X-Amz-Algorithm", "AWS4-HMAC-SHA256"],
    ["X-Amz-Credential", `${cfg.accessKey}/${scope}`],
    ["X-Amz-Date", amzDate],
    ["X-Amz-Expires", String(expires)],
    ["X-Amz-SignedHeaders", "host"],
  ];
  // 已按参数名排好序，SigV4 要求的就是字典序
  const query = params.map(([name, value]) => `${uriEncode(name)}=${uriEncode(value)}`).join("&");
  const canonicalRequest = [
    "GET",
    canonicalUri,
    query,
    `host:${host}\n`,
    "host",
    "UNSIGNED-PAYLOAD",
  ].join("\n");
  const stringToSign = ["AWS4-HMAC-SHA256", amzDate, scope, sha256Hex(canonicalRequest)].join("\n");

  let signingKey = hmac(`AWS4${cfg.secretKey}`, dateStamp);
  signingKey = hmac(signingKey, s3Region);
  signingKey = hmac(signingKey, "s3");
  signingKey = hmac(signingKey, "aws4_request");
  const signature = createHmac("sha256", signingKey).update(stringToSign).digest("hex");
  return `https://${host}${canonicalUri}?${query}&X-Amz-Signature=${signature}`;
}

/** AWS SigV4 的 uriEncode：只留 A-Za-z0-9-_.~，其余按大写 %XX。 */
function uriEncode(value: string): string {
  return encodeURIComponent(value).replace(
    /[!'()*]/g,
    (c) => `%${c.charCodeAt(0).toString(16).toUpperCase()}`,
  );
}

function sha256Hex(data: string): string {
  return createHash("sha256").update(data).digest("hex");
}

function hmac(key: string | Buffer, data: string): Buffer {
  return createHmac("sha256", key).update(data).digest();
}

/**
 * 私有空间 + 独立副本。
 * bytes 由本地文件量出来，不从上传响应里取：Kodo 的回执是 {hash,key}，没有大小。
 */
export function createQiniuStore(cfg: QiniuConfig, port: QiniuPort = qiniuPort(cfg)): ObjectStore {
  return {
    driver: "qiniu",
    async put(key, localPath) {
      const bytes = statSync(localPath).size;
      await port.putFile(port.uploadToken(key), key, localPath);
      return { key, bytes };
    },
    url: (key, ttlSec = DEFAULT_URL_TTL_SEC) => port.downloadUrl(key, ttlSec),
    remove: (key) => port.deleteObject(key),
  };
}

interface ResponseLike {
  resp?: { statusCode?: number } | undefined;
  data?: unknown;
}

function statusOf(result: ResponseLike): number {
  return result.resp?.statusCode ?? 0;
}

/** 错误文案只带状态码与供应商给的短原因：不回显上传凭证，也不回显 SK。 */
function describe(result: ResponseLike): string {
  const data = result.data;
  const reason =
    typeof data === "object" &&
    data !== null &&
    typeof (data as { error?: unknown }).error === "string"
      ? (data as { error: string }).error
      : "";
  const status = statusOf(result);
  return reason ? `${String(status)} ${reason}` : String(status || "无状态码");
}
