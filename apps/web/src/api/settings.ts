import { request } from "./http";

/** 只回答「配没配密钥」，密钥本身不回传。 */
export interface ModelSettingsView {
  baseUrl: string;
  model: string;
  hasApiKey: boolean;
}

export const settingsApi = {
  get: () => request<{ retentionDays: number; model: ModelSettingsView }>("/settings"),
  setRetention: (days: number) =>
    request<{ retentionDays: number }>("/settings/retention", {
      method: "PUT",
      body: JSON.stringify({ days }),
    }),
  setModel: (body: { baseUrl: string; model: string; apiKey?: string }) =>
    request<ModelSettingsView>("/settings/model", {
      method: "PUT",
      body: JSON.stringify(body),
    }),
};
