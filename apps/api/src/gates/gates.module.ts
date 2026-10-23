import { Module } from "@nestjs/common";

import { GatesController } from "./gates.controller";

@Module({ controllers: [GatesController] })
export class GatesModule {}
