import { request } from "./http";

export const settingsApi = {
  get: () => request<{ retentionDays: number }>("/settings"),
  setRetention: (days: number) =>
    request<{ retentionDays: number }>("/settings/retention", {
      method: "PUT",
      body: JSON.stringify({ days }),
    }),
};
