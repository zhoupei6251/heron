import { Module } from "@nestjs/common";
import { PROTOS_DIR } from "@heron/db";

import { PgModule } from "../db/pg";
import { ProtoController } from "./proto.controller";
import { PROTO_ROOT, ProtoService } from "./proto.service";

/** 原型工坊的读写面：传输在控制器，判据在仓储，产物路径在 @heron/db 的 protoArtifacts。 */
@Module({
  imports: [PgModule],
  controllers: [ProtoController],
  providers: [ProtoService, { provide: PROTO_ROOT, useValue: PROTOS_DIR }],
})
export class ProtoModule {}
