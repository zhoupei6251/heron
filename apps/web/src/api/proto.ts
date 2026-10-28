import type {
  CreateProtoBody,
  ProtoArtifactKind,
  ProtoConfirmBody,
  ProtoEngineStatus,
  ProtoProjectDetail,
} from "@heron/contracts";

import { request } from "./http";

export const protoApi = {
  capability: () => request<ProtoEngineStatus>("/proto/capability"),
  detail: (projectId: string) =>
    request<ProtoProjectDetail>(`/proto/${encodeURIComponent(projectId)}`),
  create: (input: CreateProtoBody) =>
    request<ProtoProjectDetail>("/proto", { method: "POST", body: JSON.stringify(input) }),
  /** 重跑永不覆盖已交付版本：接口只开一个新的生成请求。 */
  generate: (projectId: string) =>
    request<ProtoProjectDetail>(`/proto/${encodeURIComponent(projectId)}/generate`, {
      method: "POST",
    }),
  confirm: (projectId: string, body: ProtoConfirmBody) =>
    request<ProtoProjectDetail>(`/proto/${encodeURIComponent(projectId)}/confirm`, {
      method: "POST",
      body: JSON.stringify(body),
    }),
};

/** 下载是普通链接：cookie 就是凭证，浏览器自己处理附件与进度，不占内存。 */
export function protoArtifactUrl(
  projectId: string,
  version: number,
  kind: ProtoArtifactKind,
): string {
  return `/api/proto/${encodeURIComponent(projectId)}/revisions/${String(version)}/artifacts/${kind}`;
}
