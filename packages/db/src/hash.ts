import { createHash } from "node:crypto";

/**
 * 联络表 / 成片 / 登录态文件的指纹算法。人审确认与发布前断言都对齐这一个函数，
 * 换算法等于把历史指纹全部作废，所以 api 与 worker 共用它，不许各写一份。
 */
export function sha256(text: string): string {
  return createHash("sha256").update(text).digest("hex");
}
