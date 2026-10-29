import type { ArtifactKind } from "@heron/contracts";

import { ARTIFACT_EXT } from "./types";

/**
 * 对象键布局：tenants/<租户>/<年>/<月>/<日>/tasks/<任务>/v<版本>/<kind>.<ext>
 *
 * 三层前缀各有各的用处：
 * - 租户在最外层：按租户导出/删桶仍是一次前缀扫。
 * - 日期在中间：按天配生命周期规则（过期旧成片）、按天清理桶里的孤儿，都不用遍历全桶。
 * - 任务与版本在内层：同一任务的不同版本能同时存在（确认记录指向指纹，不是最新版）。
 *
 * 日期取的是 **UTC**：桶在哪、部署在哪都不影响分桶的口径——本地时区变了，键也不会变。
 */
export function objectKey(input: {
  tenantId: string;
  taskId: string;
  version: number;
  kind: ArtifactKind;
  /** 这次产物落库的那一刻。一次上传的三个产物共用同一个日期。 */
  at: Date;
}): string {
  const tenantId = segment(input.tenantId, "tenantId");
  const taskId = segment(input.taskId, "taskId");
  if (!Number.isInteger(input.version) || input.version <= 0)
    throw new Error(`对象键的版本号必须是正整数：${String(input.version)}`);
  const at = input.at;
  const day = `${String(at.getUTCFullYear())}/${pad2(at.getUTCMonth() + 1)}/${pad2(at.getUTCDate())}`;
  return `tenants/${tenantId}/${day}/tasks/${taskId}/v${String(input.version)}/${input.kind}.${ARTIFACT_EXT[input.kind]}`;
}

/**
 * 段值来自数据库，理论上都是雪花 id。仍然逐段校验：键会被拼进 URL 与 SDK 调用，
 * 一个带 `../` 或反斜杠的段就能把对象写到别人的前缀下。
 */
function segment(value: string, name: string): string {
  if (!value || value.includes("/") || value.includes("\\") || value.includes(".."))
    throw new Error(`对象键的 ${name} 不合法：${JSON.stringify(value)}`);
  return value;
}

/** 月与日补零：10 月与 1 月要分得清，键的形状得定长。 */
function pad2(value: number): string {
  return value < 10 ? `0${String(value)}` : String(value);
}
