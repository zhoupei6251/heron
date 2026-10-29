import type { ArtifactKind } from "@heron/contracts";

import { ARTIFACT_EXT } from "./types";

/**
 * 对象键布局：tenants/<tenantId>/tasks/<taskId>/v<version>/<kind>.<ext>
 *
 * 带租户前缀是为了「按租户导出/删除」能按前缀做，也让桶的生命周期规则能分段；
 * 带版本号是因为重渲会换片，同一条任务的不同版本必须能同时存在（确认记录指向指纹，不是最新版）。
 */
export function objectKey(input: {
  tenantId: string;
  taskId: string;
  version: number;
  kind: ArtifactKind;
}): string {
  const tenantId = segment(input.tenantId, "tenantId");
  const taskId = segment(input.taskId, "taskId");
  if (!Number.isInteger(input.version) || input.version <= 0)
    throw new Error(`对象键的版本号必须是正整数：${String(input.version)}`);
  return `tenants/${tenantId}/tasks/${taskId}/v${String(input.version)}/${input.kind}.${ARTIFACT_EXT[input.kind]}`;
}

/**
 * 段值来自数据库，理论上都是 uuid。仍然逐段校验：键会被拼进 URL 与 SDK 调用，
 * 一个带 `../` 或反斜杠的段就能把对象写到别人的前缀下。
 */
function segment(value: string, name: string): string {
  if (!value || value.includes("/") || value.includes("\\") || value.includes(".."))
    throw new Error(`对象键的 ${name} 不合法：${JSON.stringify(value)}`);
  return value;
}
