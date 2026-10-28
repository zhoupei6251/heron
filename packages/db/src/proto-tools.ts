import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";

/**
 * 原型引擎与 Axure 模板的「装没装」探针。api 拿它置灰「生成」按钮，worker 拿它判断这次能不能跑；
 * 判断只读环境与文件头，不启动子进程——健康探针不该为一次检查等三分钟。
 */

/** zee-rp 安装根（scripts/proto.mjs 所在目录）。没有它整条原型线不可用，但不影响新闻线启动。 */
export const ZEE_RP_HOME = process.env.ZEE_RP_HOME?.trim() ?? "";

/** 装配 .rp 用的 Axure RP9 空白工程。模板不随代码分发，只能由部署者提供。 */
export const BLANK_TEMPLATE_RP9 = process.env.AXURE_BLANK_TEMPLATE_RP9?.trim() ?? "";

export interface EngineStatus {
  readonly ok: boolean;
  readonly detail: string;
  readonly version: string | null;
}

export interface TemplateStatus {
  readonly ok: boolean;
  readonly detail: string;
}

function messageOf(cause: unknown): string {
  return cause instanceof Error ? cause.message : String(cause);
}

export function probeEngine(home: string = ZEE_RP_HOME): EngineStatus {
  if (!home) {
    return { ok: false, detail: "未配置 ZEE_RP_HOME：原型生成需要 zee-rp 引擎", version: null };
  }
  if (!existsSync(join(home, "scripts", "proto.mjs"))) {
    return {
      ok: false,
      detail: `ZEE_RP_HOME 下找不到 scripts/proto.mjs：${home}`,
      version: null,
    };
  }
  let version: string | null = null;
  try {
    const manifest: unknown = JSON.parse(readFileSync(join(home, "release-manifest.json"), "utf8"));
    if (typeof manifest === "object" && manifest !== null && "version" in manifest) {
      const raw = (manifest as { version?: unknown }).version;
      version = typeof raw === "string" ? raw.trim() || null : null;
    }
  } catch (cause) {
    return { ok: false, detail: `引擎版本清单读不出来：${messageOf(cause)}`, version: null };
  }
  if (!version)
    return {
      ok: false,
      detail: "引擎 release-manifest.json 缺少有效 version，请安装完整固定版本",
      version: null,
    };
  return { ok: true, detail: `zee-rp ${version} 已就位`, version };
}

/**
 * RP9 文件头是 ac ef 09 00。只认文件本身，不认扩展名——
 * 把 RP11 的空白件喂给 RP9 装配出来的工程打不开，而那要到人手里才发现。
 */
export function probeTemplate(path: string = BLANK_TEMPLATE_RP9): TemplateStatus {
  if (!path) {
    return {
      ok: false,
      detail:
        "未配置 AXURE_BLANK_TEMPLATE_RP9：请在 Axure RP9 新建单页空白工程，保存为 .rp，并将绝对路径填入该变量",
    };
  }
  if (!existsSync(path)) {
    return { ok: false, detail: `AXURE_BLANK_TEMPLATE_RP9 指向的文件不存在：${path}` };
  }
  try {
    const head = readFileSync(path).subarray(0, 4);
    const version = head.length === 4 ? head.readUInt16LE(2) : NaN;
    if (head[0] !== 0xac || head[1] !== 0xef || version !== 9) {
      return {
        ok: false,
        detail: `模板不是 Axure RP9 工程（文件头应为 ac ef 09 00）：${path}`,
      };
    }
  } catch (cause) {
    return { ok: false, detail: `模板读不出来：${messageOf(cause)}` };
  }
  return { ok: true, detail: `RP9 空白模板已就位：${path}` };
}
