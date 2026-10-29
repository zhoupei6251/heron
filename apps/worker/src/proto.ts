import { createHash } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";

import type { ProtoFailureKind, ProtoStage, ProtoTarget, ProtoTheme } from "@heron/contracts";
import { LEASE_TTL_SEC, protoArtifacts, probeEngine, probeTemplate } from "@heron/db";
import {
  advanceProtoProject,
  createProtoRevision,
  getModelSettings,
  getProtoProject,
  latestProtoRevision,
  markProtoDelivered,
  markProtoFailed,
  platformDefaults,
  recordUsage,
  touchProtoLease,
  updateProtoRevision,
  withTenant,
  type ClaimedProtoProject,
  type Db,
  type PlatformDefaults,
} from "@heron/db/pg";
import { protoContractVerdict, protoVisualVerdict } from "@heron/domain";

import { OWNER } from "./config";
import {
  chat,
  EmptyModelReplyError,
  resolveModelConfig,
  type ChatReply,
  type ModelConfig,
} from "./model";
import { describeRun, makeEngineRunner, type EngineRunner } from "./proto-engine";
import { PROTO_SYSTEM_PROMPT, buildProtoPrompt } from "./proto-prompt";
import { claimNextProto, claimProtoById } from "./scheduler";
import type { ChatFn } from "./scripting";

/**
 * 原型线的推进：需求 → RP/1 源码 → 契约闸 → 编译 → 视觉闸 → 装配 .rp → 停在 review；
 * 人确认后 approved，worker 再核对交付物指纹并落 delivered。
 *
 * 事务切法与新闻线不同，原因只有一个：一次编译可能比租约 TTL 长。
 * 模型与引擎都在事务外跑，中途用心跳续租；每次落库都是一次短事务。
 * 因此「失败原因 + stage=failed」必须在同一个短事务里写完，否则回滚会把审计痕抹掉。
 */

export interface ProtoDeps {
  chat?: ChatFn;
  modelConfig?: ModelConfig;
  /** 平台级默认值。与新闻线同一条解析链：租户设置 → 平台设置 → 环境变量。 */
  platform?: PlatformDefaults | null;
  engine?: EngineRunner;
  engineHome?: string;
  templatePath?: string;
  /** 产物根目录。生产走 HERON_HOME/protos，测试指到临时目录。 */
  artifactsRoot?: string;
}

export type ProtoOutcome =
  | { readonly ok: true; readonly stage: ProtoStage | "skipped"; readonly steps: string[] }
  | { readonly ok: false; readonly kind: ProtoFailureKind; readonly reason: string };

class ProtoFailure extends Error {
  constructor(
    readonly kind: ProtoFailureKind,
    message: string,
    readonly patch: { audit?: unknown } = {},
  ) {
    super(message);
    this.name = "ProtoFailure";
  }
}

function messageOf(cause: unknown): string {
  return cause instanceof Error ? cause.message : String(cause);
}

function sha256(data: Buffer): string {
  return createHash("sha256").update(data).digest("hex");
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

/** 模型常见两种包装：```json 围栏，或前后带一句解释。没有 JSON 就是模型的错，不替它补。 */
export function parseProtoJson(text: string): unknown {
  const fenced = /```(?:json)?\s*([\s\S]*?)```/i.exec(text);
  const body = (fenced?.[1] ?? text).trim();
  const start = body.indexOf("{");
  const end = body.lastIndexOf("}");
  if (start === -1 || end <= start) {
    throw new ProtoFailure("model", `模型没有输出 RP/1 JSON：${body.slice(0, 200)}`);
  }
  const json = body.slice(start, end + 1);
  try {
    return JSON.parse(json);
  } catch {
    throw new ProtoFailure("model", `模型输出的 JSON 解析失败：${json.slice(0, 200)}`);
  }
}

/**
 * 只校正「人选的配置」：语言、目标版本、主题、端与工程名。
 * 页面内容一律交给契约闸判——这里替模型补内容，等于闸门自证。
 */
function normalizeSpec(
  raw: unknown,
  brief: { title: string; target: ProtoTarget; theme: ProtoTheme },
): Record<string, unknown> {
  if (!isRecord(raw) || !Array.isArray(raw.pages) || raw.pages.length === 0) {
    throw new ProtoFailure("model", "模型输出的 RP/1 里没有 pages");
  }
  const pending: { value: unknown; pointer: string }[] = [{ value: raw, pointer: "" }];
  while (pending.length) {
    const item = pending.pop()!;
    if (!isRecord(item.value)) continue;
    if (item.value.type === "image" || "src" in item.value) {
      throw new ProtoFailure(
        "contract",
        `不允许模型指定素材引用：${item.pointer || "/"}；请用文字和容器表达，不能读取本地文件或外部地址`,
      );
    }
    for (const [key, value] of Object.entries(item.value)) {
      pending.push({
        value,
        pointer: `${item.pointer}/${key.replaceAll("~", "~0").replaceAll("/", "~1")}`,
      });
    }
  }
  return {
    ...raw,
    language: "rp/1",
    target: 9,
    name: brief.title,
    theme: brief.theme,
    domain: brief.target === "mobile" ? "mobile" : "web",
  };
}

function parseEngineJson(run: { stdout: string }): unknown {
  const text = run.stdout.trim();
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

/**
 * 续租：脚本与引擎可能跑几分钟，租约 TTL 只有 60s。
 * 心跳失败只记日志——真的失去租约时，后续 advance 会拒绝转移，那才是判据。
 */
function startHeartbeat(db: Db, tenantId: string, projectId: string) {
  let pending: Promise<void> = Promise.resolve();
  const timer = setInterval(
    () => {
      pending = withTenant(db, tenantId, (t) => touchProtoLease(t, projectId, OWNER))
        .then(() => undefined)
        .catch((cause: unknown) => {
          console.error(`原型租约续期失败：${messageOf(cause)}`);
        });
    },
    Math.max(5_000, Math.floor((LEASE_TTL_SEC * 1_000) / 3)),
  );
  timer.unref();
  return {
    stop: async (): Promise<void> => {
      clearInterval(timer);
      await pending;
    },
  };
}

function isLeaseLoss(cause: unknown): boolean {
  const message = messageOf(cause);
  return message.includes("拒绝转移") || message.includes("租约") || message.includes("待交付状态");
}

async function markFailure(
  db: Db,
  tenantId: string,
  projectId: string,
  revisionId: string | null,
  kind: ProtoFailureKind,
  reason: string,
  patch: { audit?: unknown } = {},
): Promise<ProtoOutcome> {
  try {
    await withTenant(db, tenantId, async (t) => {
      await markProtoFailed(t, projectId, reason, OWNER);
      if (revisionId) {
        await updateProtoRevision(t, revisionId, {
          failureKind: kind,
          failureReason: reason,
          ...(patch.audit === undefined ? {} : { audit: patch.audit }),
        });
      }
    });
  } catch (cause) {
    if (isLeaseLoss(cause)) return { ok: true, stage: "skipped", steps: ["租约已易手，跳过"] };
    const detail = messageOf(cause);
    return {
      ok: false,
      kind,
      reason: detail.includes("已在终态") ? reason : `${reason}（落库失败：${detail}）`,
    };
  }
  return { ok: false, kind, reason };
}

async function generate(
  db: Db,
  claimed: ClaimedProtoProject,
  deps: ProtoDeps,
): Promise<ProtoOutcome> {
  const { project, tenantId } = claimed;

  const engineStatus = probeEngine(deps.engineHome);
  const templateStatus = probeTemplate(deps.templatePath);
  if (!engineStatus.ok || !templateStatus.ok) {
    const reason = [
      engineStatus.ok ? null : engineStatus.detail,
      templateStatus.ok ? null : templateStatus.detail,
    ]
      .filter((line): line is string => line !== null)
      .join("；");
    return markFailure(db, tenantId, project.id, null, "engine", `原型引擎未就绪：${reason}`);
  }

  // 第一段：认领阶段 → scripting，定下这一版读的需求原文
  const start = await withTenant(db, tenantId, async (t) => {
    const current = await getProtoProject(t, project.id);
    if (!current || current.stage !== "draft" || current.leaseOwner !== OWNER) return null;
    await advanceProtoProject(t, {
      projectId: project.id,
      from: "draft",
      to: "scripting",
      owner: OWNER,
    });
    const revision = await createProtoRevision(t, project.id);
    return {
      revisionId: revision.id,
      version: revision.version,
      title: current.title,
      requirement: current.requirement,
      target: current.target,
      theme: current.theme,
      settings: await getModelSettings(t),
    };
  });
  if (!start) return { ok: true, stage: "skipped", steps: ["租约不在手，跳过"] };

  const brief = {
    title: start.title,
    requirement: start.requirement,
    target: start.target,
    theme: start.theme,
  };
  const art = protoArtifacts(project.id, start.version, deps.artifactsRoot);
  const engineRun = deps.engine ?? makeEngineRunner(deps.engineHome);
  const beat = startHeartbeat(db, tenantId, project.id);

  try {
    let config: ModelConfig;
    try {
      config =
        deps.modelConfig ??
        resolveModelConfig(start.settings, process.env, {
          baseUrl: deps.platform?.modelBaseUrl ?? null,
          model: deps.platform?.modelName ?? null,
        });
    } catch (cause) {
      throw new ProtoFailure("model", messageOf(cause));
    }

    const call = deps.chat ?? chat;
    let reply: ChatReply;
    try {
      reply = await call(config, [
        { role: "system", content: PROTO_SYSTEM_PROMPT },
        { role: "user", content: buildProtoPrompt(brief) },
      ]);
    } catch (cause) {
      if (!(cause instanceof EmptyModelReplyError)) {
        throw new ProtoFailure("model", `原型模型调用失败：${messageOf(cause)}`);
      }
      reply = cause.reply;
    }

    // 费用在模型返回时已发生，后续校验失败不能回滚这笔账。
    await withTenant(db, tenantId, async (t) => {
      await updateProtoRevision(t, start.revisionId, {
        rp1Source: reply.content,
        model: reply.model,
        engineVersion: engineStatus.version,
        tokensIn: reply.tokensIn,
        tokensOut: reply.tokensOut,
      });
      if (reply.tokensIn !== null) {
        await recordUsage(t, {
          kind: "llm_tokens_in",
          quantity: String(reply.tokensIn),
          unit: "tokens",
          detail: { model: reply.model, protoProject: project.id, protoRevision: start.revisionId },
        });
      }
      if (reply.tokensOut !== null) {
        await recordUsage(t, {
          kind: "llm_tokens_out",
          quantity: String(reply.tokensOut),
          unit: "tokens",
          detail: { model: reply.model, protoProject: project.id, protoRevision: start.revisionId },
        });
      }
    });
    mkdirSync(art.engineDir, { recursive: true });
    writeFileSync(art.source, reply.content);
    if (!reply.content.trim()) throw new ProtoFailure("model", "模型返回了空内容");
    const spec = normalizeSpec(parseProtoJson(reply.content), brief);
    const source = `${JSON.stringify(spec, null, 2)}\n`;
    writeFileSync(art.source, source);

    // 第二段：源码已落盘 → compiling
    await withTenant(db, tenantId, async (t) => {
      await advanceProtoProject(t, {
        projectId: project.id,
        from: "scripting",
        to: "compiling",
        owner: OWNER,
      });
    });

    // 契约闸：check 的输出按 JSON Pointer 报哪一条不过
    const checkRun = await engineRun(["check", "--spec", art.source]);
    const checked = parseEngineJson(checkRun);
    if (!checked) {
      throw new ProtoFailure(
        "engine",
        checkRun.timedOut
          ? describeRun(checkRun)
          : `契约检查没有输出 JSON：${describeRun(checkRun)}`,
      );
    }
    const contract = protoContractVerdict(checked);
    if (!contract.ok) throw new ProtoFailure("contract", contract.reason ?? "契约闸未通过");
    if (!checkRun.ok) throw new ProtoFailure("engine", describeRun(checkRun));

    // 编译：预览图与两级审计报告都在这里产出；审计不达标不返回非零，必须读报告
    const compileRun = await engineRun([
      "compile",
      "--out-dir",
      art.engineDir,
      "--spec",
      art.source,
    ]);
    if (!compileRun.ok) throw new ProtoFailure("engine", describeRun(compileRun));
    if (!existsSync(art.audit)) {
      throw new ProtoFailure("engine", `引擎没有产出审计报告：${art.audit}`);
    }
    let audit: unknown;
    try {
      audit = JSON.parse(readFileSync(art.audit, "utf8"));
    } catch (cause) {
      throw new ProtoFailure("engine", `审计报告不是 JSON：${messageOf(cause)}`);
    }
    const visual = protoVisualVerdict(audit);
    if (!visual.ok) throw new ProtoFailure("visual", visual.reason ?? "视觉闸未通过", { audit });

    // 装配 .rp：模板缺失在这里暴露，错误里带着引擎自己的话
    if (!existsSync(art.plan)) {
      throw new ProtoFailure("engine", `引擎没有产出装配计划：${art.plan}`);
    }
    const buildRun = await engineRun(["build-rp", "--plan", art.plan, "--out", art.rp]);
    if (!buildRun.ok) throw new ProtoFailure("engine", describeRun(buildRun));
    const built = parseEngineJson(buildRun);
    if (isRecord(built) && built.ok === false) {
      throw new ProtoFailure("engine", `装配 .rp 未通过：${buildRun.stdout.trim().slice(-800)}`);
    }
    if (!existsSync(art.rp)) {
      throw new ProtoFailure("engine", `装配结束但没有 .rp 产物：${art.rp}`);
    }
    if (!existsSync(art.preview)) {
      throw new ProtoFailure("engine", `引擎没有产出预览图：${art.preview}`);
    }
    const rp = readFileSync(art.rp);
    const preview = readFileSync(art.preview);
    if (rp.length === 0) throw new ProtoFailure("engine", `.rp 产物是空文件：${art.rp}`);
    if (preview.length < 8 || preview[0] !== 0x89 || preview[1] !== 0x50) {
      throw new ProtoFailure("engine", `预览图不是 PNG：${art.preview}`);
    }

    await withTenant(db, tenantId, async (t) => {
      await updateProtoRevision(t, start.revisionId, {
        audit,
        rpSha256: sha256(rp),
        rpBytes: rp.length,
        previewSha256: sha256(preview),
      });
      await advanceProtoProject(t, {
        projectId: project.id,
        from: "compiling",
        to: "review",
        owner: OWNER,
        clearLease: true,
      });
    });

    return {
      ok: true,
      stage: "review",
      steps: [
        `v${String(start.version)}`,
        "模型输出 RP/1",
        "契约闸通过",
        "编译通过",
        "视觉闸通过",
        "已装配 .rp",
        "等人确认",
      ],
    };
  } catch (cause) {
    if (cause instanceof ProtoFailure) {
      return markFailure(
        db,
        tenantId,
        project.id,
        start.revisionId,
        cause.kind,
        cause.message,
        cause.patch,
      );
    }
    if (isLeaseLoss(cause)) return { ok: true, stage: "skipped", steps: ["租约已易手，跳过"] };
    return markFailure(
      db,
      tenantId,
      project.id,
      start.revisionId,
      "engine",
      `worker 异常：${messageOf(cause)}`,
    );
  } finally {
    await beat.stop();
  }
}

/** 交付只是一个落库动作，但先核对盘上的 .rp 确实是那一版——不是就把项目停在失败等人看。 */
async function deliver(
  db: Db,
  claimed: ClaimedProtoProject,
  deps: ProtoDeps,
): Promise<ProtoOutcome> {
  const { project, tenantId } = claimed;
  try {
    return await withTenant(db, tenantId, async (t): Promise<ProtoOutcome> => {
      const current = await getProtoProject(t, project.id);
      if (!current || current.stage !== "approved" || current.leaseOwner !== OWNER) {
        return { ok: true, stage: "skipped", steps: ["不在待交付状态，跳过"] };
      }
      const latest = await latestProtoRevision(t, project.id);
      if (!latest) throw new ProtoFailure("engine", "待交付项目没有版本记录");
      if (!latest.rpSha256) {
        throw new ProtoFailure(
          "engine",
          `第 ${String(latest.version)} 版没有 .rp 指纹，无法确认交付物`,
        );
      }
      const art = protoArtifacts(project.id, latest.version, deps.artifactsRoot);
      if (!existsSync(art.rp)) {
        throw new ProtoFailure("engine", `交付物不在盘上：${art.rp}（可能被清理）`);
      }
      if (sha256(readFileSync(art.rp)) !== latest.rpSha256) {
        throw new ProtoFailure(
          "engine",
          `交付物指纹对不上：盘上的 .rp 不是人确认的那一版（${art.rp}）`,
        );
      }
      await markProtoDelivered(t, {
        projectId: project.id,
        revisionId: latest.id,
        owner: OWNER,
      });
      return {
        ok: true,
        stage: "delivered",
        steps: [`v${String(latest.version)}`, "交付物指纹核对通过", "delivered"],
      };
    });
  } catch (cause) {
    if (cause instanceof ProtoFailure) {
      return markFailure(db, tenantId, project.id, null, cause.kind, cause.message);
    }
    if (isLeaseLoss(cause)) return { ok: true, stage: "skipped", steps: ["租约已易手，跳过"] };
    return markFailure(db, tenantId, project.id, null, "engine", `交付异常：${messageOf(cause)}`);
  }
}

export async function walkProto(
  db: Db,
  claimed: ClaimedProtoProject,
  deps: ProtoDeps = {},
): Promise<ProtoOutcome> {
  // 与新闻线同一条解析链：每条任务读一次平台默认值（不缓存），读不到就退到租户设置与环境变量
  const platform =
    deps.platform ??
    (await platformDefaults(db).catch((cause: unknown) => {
      console.error(`读平台默认值失败，本条只用租户设置与环境变量：${messageOf(cause)}`);
      return null;
    }));
  const merged = { ...deps, platform };
  return claimed.project.stage === "approved"
    ? deliver(db, claimed, merged)
    : generate(db, claimed, merged);
}

function describe(projectId: string, outcome: ProtoOutcome): string {
  const label = projectId.slice(0, 8);
  if (!outcome.ok) return `proto ${label} failed[${outcome.kind}] · ${outcome.reason}`;
  const trail = outcome.steps.join(" → ");
  if (outcome.stage === "review") return `proto ${label} review · ${trail}，租约已释放，等人确认`;
  if (outcome.stage === "delivered") return `proto ${label} delivered · ${trail}`;
  return `proto ${label} ${outcome.stage} · ${trail}`;
}

/** 兜底轮询：领最早一条待生成/待交付的原型项目。null 表示池子里没有。 */
export async function tickProto(db: Db, deps: ProtoDeps = {}): Promise<string | null> {
  const claimed = await claimNextProto(db);
  if (!claimed) return null;
  return describe(claimed.project.id, await walkProto(db, claimed, deps));
}

/** 队列投来的 projectId：按 id 领，领不到就什么都不做。 */
export async function runProto(
  db: Db,
  projectId: string,
  deps: ProtoDeps = {},
): Promise<string | null> {
  const claimed = await claimProtoById(db, projectId);
  if (!claimed) return null;
  return describe(claimed.project.id, await walkProto(db, claimed, deps));
}
