import { statSync } from "node:fs";

import type { ObjectStore } from "./types";

/**
 * 本地驱动：文件本来就在 VIDEOS_DIR 下，这一层不搬任何东西。
 *
 * `put` 仍然 stat 一次：文件不在时要在这里炸，而不是等到界面取产物那一刻才发现渲染只写了一半。
 * `url` 恒返回 null，调用方据此回落 sendFile —— 没配对象存储的环境行为与今天一字不差。
 */
export function createLocalStore(): ObjectStore {
  return {
    driver: "local",
    put(key, localPath) {
      return Promise.resolve({ key, bytes: statSync(localPath).size });
    },
    url: () => null,
    remove: () => Promise.resolve(),
  };
}
