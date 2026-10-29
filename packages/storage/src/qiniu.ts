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
  /** 私有空间的签名下载地址。 */
  downloadUrl(key: string, deadlineSec: number): string;
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

export function qiniuPort(cfg: QiniuConfig): QiniuPort {
  const mac = new qiniu.auth.digest.Mac(cfg.accessKey, cfg.secretKey);
  // 上传域名不写死：zone 自带 up-z2.qiniup.com 一族，SDK 自己挑可用的那个
  const config = new qiniu.conf.Config({ zone: ZONES[cfg.region], useHttpsDomain: true });
  const uploader = new qiniu.form_up.FormUploader(config);
  const manager = new qiniu.rs.BucketManager(mac, config);

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

    downloadUrl: (key, deadlineSec) => manager.privateDownloadUrl(cfg.domain, key, deadlineSec),

    async deleteObject(key) {
      const result = await manager.delete(cfg.bucket, key);
      if (!result.ok() && statusOf(result) !== ERR_NO_SUCH_FILE)
        throw new Error(`七牛删除失败：${describe(result)}`);
    },
  };
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
