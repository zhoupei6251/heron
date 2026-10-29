import { BadRequestException, type PipeTransform } from "@nestjs/common";
import { snowflakeIdSchema } from "@heron/contracts";

/**
 * 路径参数里的 id：必须是雪花 id。
 *
 * 为什么不用 Nest 自带的 ParseUUIDPipe：主键已经是 numeric 的雪花 id，不是 uuid。
 * 判据直接取 contracts 的 snowflakeIdSchema——参数层与契约层只认一种 id，
 * 各写一份正则，迟早会有一处先漂。
 */
export class SnowflakeIdPipe implements PipeTransform<string, string> {
  transform(value: unknown): string {
    const parsed = snowflakeIdSchema.safeParse(value);
    if (!parsed.success) throw new BadRequestException(`路径参数不是合法的 id：${String(value)}`);
    return parsed.data;
  }
}
