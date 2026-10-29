import { readStorageConfig } from "./config";
import { createLocalStore } from "./local";
import { createQiniuStore } from "./qiniu";
import type { ObjectStore } from "./types";

export * from "./config";
export * from "./key";
export * from "./local";
export * from "./qiniu";
export * from "./types";

/**
 * 唯一入口。配置不合法就在这里抛——调用方是 Nest provider 或 worker 启动，抛在启动期。
 * 等到第一次渲染才发现 AK/SK 少一项，代价是一条已经渲染完的任务。
 */
export function createObjectStore(env: NodeJS.ProcessEnv = process.env): ObjectStore {
  const config = readStorageConfig(env);
  return config.driver === "local" ? createLocalStore() : createQiniuStore(config.qiniu);
}
