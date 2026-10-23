import { BadRequestException, Injectable, type PipeTransform } from "@nestjs/common";
import type { ZodType } from "zod";

@Injectable()
export class ZodValidationPipe<T> implements PipeTransform {
  constructor(private readonly schema: ZodType<T>) {}

  transform(value: unknown): T {
    const parsed = this.schema.safeParse(value);
    if (!parsed.success) {
      const detail = parsed.error.issues
        .map((issue) => `  - ${issue.path.join(".") || "(根)"}: ${issue.message}`)
        .join("\n");
      throw new BadRequestException(`请求体不符合契约：\n${detail}`);
    }
    return parsed.data;
  }
}
