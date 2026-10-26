import { ApiError, request } from "./http";

export interface HealthReport {
  ok: boolean;
  at: string;
  node: string;
  db: { ok: boolean; detail: string };
  webDist: { ok: boolean; detail: string };
  publish: { open: boolean; blockers: string[] };
}

export async function loadHealth(): Promise<HealthReport> {
  try {
    return await request<HealthReport>("/platform/health");
  } catch (cause) {
    if (cause instanceof ApiError && cause.status === 503) {
      try {
        const body = JSON.parse(cause.body) as HealthReport;
        if (
          body.ok === false &&
          typeof body.at === "string" &&
          typeof body.node === "string" &&
          typeof body.db?.ok === "boolean" &&
          typeof body.db.detail === "string" &&
          typeof body.webDist?.ok === "boolean" &&
          typeof body.webDist.detail === "string" &&
          typeof body.publish?.open === "boolean" &&
          Array.isArray(body.publish.blockers) &&
          body.publish.blockers.every((item) => typeof item === "string")
        )
          return body;
      } catch {
        // A gateway's 503 page is not an application health report.
      }
    }
    throw cause;
  }
}
