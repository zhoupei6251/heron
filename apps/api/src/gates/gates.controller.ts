import { Controller, Get } from "@nestjs/common";
import type { GateState } from "@heron/contracts";
import { gateLocations, publishBlockers, publishGateOpen } from "@heron/domain";

@Controller("gates")
export class GatesController {
  /** 前端「发布」「确认」按钮置灰只看这一个接口，所以闸位必须整份报出去。 */
  @Get()
  state(): GateState {
    return {
      open: publishGateOpen(),
      blockers: publishBlockers(),
      locations: gateLocations(),
    };
  }
}
