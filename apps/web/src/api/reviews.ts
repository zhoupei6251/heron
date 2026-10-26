import type { ArtifactKind, ReviewItem, Task } from "@heron/contracts";

import { request } from "./http";

export const reviewsApi = {
  pending: () => request<{ reviews: ReviewItem[] }>("/reviews/pending"),
  confirm: (taskId: string, contactSheetFingerprint: string) =>
    request<Task>(`/reviews/${encodeURIComponent(taskId)}/confirm`, {
      method: "POST",
      body: JSON.stringify({ contactSheetFingerprint }),
    }),
};

export function artifactUrl(taskId: string, kind: ArtifactKind, version: number): string {
  return `/api/manage/artifacts/${encodeURIComponent(taskId)}/${kind}?version=${version}`;
}
