import type { GateState } from "@heron/contracts";

import { request } from "./http";

export const gatesApi = {
  state: () => request<GateState>("/gates"),
};
