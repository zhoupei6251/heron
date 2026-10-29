import type { ArtifactKind } from "@heron/contracts";

/**
 * 成片的异地副本。本地盘仍然是发布与诊断的现场（renders 的 *_path 列照旧写），
 * 这一层只回答「同一条成片在对象存储里叫什么、怎么取、怎么删」。
 *
 * 为什么不做「注册表 + 运行时切驱动」：一个部署只有一种存储，驱动由 HERON_STORAGE 定死。
 * 多驱动可切换的价值在多供应商并存，而 heron 的同一个部署不该一半文件在本地一半在云上。
 */
export interface ObjectStore {
  readonly driver: "local" | "qiniu";
  /** 上传本地文件（同 key 覆盖），返回对象键与字节数。 */
  put(key: string, localPath: string): Promise<{ key: string; bytes: number }>;
  /** 私有桶的短时效下载地址；本地驱动返回 null，调用方据此回落 sendFile。 */
  url(key: string, ttlSec?: number): string | null;
  /** 删除对象；对象不存在算成功——清理会被重跑，404 不该让整轮失败。 */
  remove(key: string): Promise<void>;
}

/** 私有空间签名链接的有效期。不做长：URL 本身是 bearer 凭据，会留在浏览器历史与日志里。 */
export const DEFAULT_URL_TTL_SEC = 600;

/** 三种产物各用自己的扩展名。这张表是键布局与响应头的唯一来源。 */
export const ARTIFACT_EXT: Record<ArtifactKind, string> = {
  mp4: "mp4",
  "contact-sheet": "png",
  "aigc-sidecar": "json",
};
