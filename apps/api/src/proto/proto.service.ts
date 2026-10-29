import { createHash } from "node:crypto";
import { readFile, realpath, stat } from "node:fs/promises";
import { isAbsolute, relative, sep } from "node:path";

import {
  ConflictException,
  Inject,
  Injectable,
  NotFoundException,
  PayloadTooLargeException,
} from "@nestjs/common";
import {
  type AuthUser,
  type CreateProtoBody,
  type Page,
  type PageQuery,
  type ProtoArtifactKind,
  type ProtoConfirmBody,
  type ProtoEngineStatus,
  type ProtoProjectDetail,
  type ProtoProjectSummary,
} from "@heron/contracts";
import { protoArtifacts, probeEngine, probeTemplate } from "@heron/db";
import {
  type Db,
  confirmProtoRevision,
  countProtoInflight,
  createProtoProject,
  getProtoRevision,
  listProtoProjects,
  listProtoRevisions,
  requireActiveActor,
  requireProtoProject,
  requestProtoGeneration,
  withTenant,
} from "@heron/db/pg";
import { protoVisualVerdict } from "@heron/domain";
import type { Response } from "express";

import { protoMaxInflight } from "../config/runtime";
import { PG_DB } from "../db/pg";
import { TaskQueueService } from "../redis/redis.module";

/** 产物根目录由模块注入（生产是 HERON_HOME/protos），测试只换这一个值。 */
export const PROTO_ROOT = "PROTO_ROOT";

/** 单文件上限（需求 P5）：超限宁可拒绝，也不把磁盘塞进内存。 */
const MAX_ARTIFACT_BYTES = 50 * 1024 * 1024;

const ARTIFACT_MEDIA: Record<ProtoArtifactKind, { type: string; ext: string }> = {
  rp: { type: "application/octet-stream", ext: "rp" },
  preview: { type: "image/png", ext: "png" },
  audit: { type: "application/json", ext: "json" },
  source: { type: "application/json", ext: "json" },
};

/** 列表与详情共用同一条投影：只挑契约里的字段，worker 的调度坐标不出进程。 */
type SummarySource = Pick<
  ProtoProjectSummary,
  "id" | "title" | "target" | "theme" | "stage" | "errorReason" | "createTime" | "updateTime"
>;

function summaryOf(row: SummarySource, latestVersion: number | null): ProtoProjectSummary {
  return {
    id: row.id,
    title: row.title,
    target: row.target,
    theme: row.theme,
    stage: row.stage,
    errorReason: row.errorReason,
    createTime: row.createTime,
    updateTime: row.updateTime,
    latestVersion,
  };
}

/**
 * 原型工坊的读写面。判据不在这层复述（状态、闸、并发上限全在仓储），
 * 产物路径只由 protoArtifacts 一处拼——worker 写、api 读，两边必须指向同一个目录。
 */
@Injectable()
export class ProtoService {
  // @Inject 不能省成裸类型：tsx 不执行 emitDecoratorMetadata，隐式注入在启动期才炸。
  constructor(
    @Inject(PG_DB) private readonly db: Db,
    @Inject(PROTO_ROOT) private readonly root: string,
    @Inject(TaskQueueService) private readonly queue: TaskQueueService,
  ) {}

  list(user: AuthUser, query: PageQuery): Promise<Page<ProtoProjectSummary>> {
    return withTenant(this.db, user.tenantId, async (t) => {
      const page = await listProtoProjects(t, query);
      return { ...page, items: page.items.map((row) => summaryOf(row, row.latestVersion)) };
    });
  }

  async detail(user: AuthUser, projectId: string): Promise<ProtoProjectDetail> {
    return withTenant(this.db, user.tenantId, async (t) => {
      const project = await requireProtoProject(t, projectId);
      const revisions = await listProtoRevisions(t, projectId);
      const latest = revisions[0];
      // 闸结论读最新那一版的审计原文：界面回答「这一版过没过」，不是这个项目历史上最好看的那次
      const stored = latest ? await getProtoRevision(t, latest.id) : null;
      const verdict = stored?.audit == null ? null : protoVisualVerdict(stored.audit);
      return {
        ...summaryOf(project, latest?.version ?? null),
        requirement: project.requirement,
        revisions,
        gates: (verdict?.checks ?? []).map(({ key, label, passed }) => ({ key, label, passed })),
      };
    });
  }

  /** 建项目即排一次生成：账先落定再推队列，反过来的话 worker 可能领到一条还没落库的活。 */
  async create(user: AuthUser, body: CreateProtoBody): Promise<ProtoProjectDetail> {
    const project = await withTenant(this.db, user.tenantId, (t) =>
      createProtoProject(t, user.id, body, protoMaxInflight()),
    );
    await this.queue.push(project.id, "proto");
    return this.detail(user, project.id);
  }

  async regenerate(user: AuthUser, projectId: string): Promise<ProtoProjectDetail> {
    await withTenant(this.db, user.tenantId, async (t) => {
      await requireActiveActor(t, user.id, "task.claim");
      await requestProtoGeneration(t, projectId, protoMaxInflight(), user.id);
    });
    await this.queue.push(projectId, "proto");
    return this.detail(user, projectId);
  }

  async confirm(
    user: AuthUser,
    projectId: string,
    body: ProtoConfirmBody,
  ): Promise<ProtoProjectDetail> {
    await withTenant(this.db, user.tenantId, (t) =>
      confirmProtoRevision(t, user.id, {
        projectId,
        revisionId: body.revisionId,
        previewSha256: body.previewSha256,
        axureVerified: body.axureVerified,
      }),
    );
    // approved 是可领状态（下一步只做交付落库），确认落定后照样推队列
    await this.queue.push(projectId, "proto");
    return this.detail(user, projectId);
  }

  /** 生成按钮能不能点取决于机器上装没装：引擎与模板各判一次，原因原文交给界面。 */
  capability(user: AuthUser): Promise<ProtoEngineStatus> {
    const engine = probeEngine();
    const template = probeTemplate();
    return withTenant(this.db, user.tenantId, async (t) => ({
      engine,
      template,
      inflight: await countProtoInflight(t),
      maxInflight: protoMaxInflight(),
    }));
  }

  /**
   * 下载：先按会话把项目与版本查出来（RLS 挡跨租户，查不到就是 404），再碰文件系统。
   * 库里的指纹与盘上的字节对不上时拒绝发出——那正是「人审 A、系统发 B」被拦下的时刻。
   */
  async serve(
    user: AuthUser,
    projectId: string,
    version: number,
    kind: ProtoArtifactKind,
    res: Response,
  ): Promise<void> {
    const { revision } = await withTenant(this.db, user.tenantId, async (t) => {
      // 这一句就是授权：查不到（跨租户被 RLS 挡掉、或根本不存在）由 requireProtoProject 抛 not_found
      await requireProtoProject(t, projectId);
      const revisions = await listProtoRevisions(t, projectId);
      return { revision: revisions.find((row) => row.version === version) };
    });
    if (!revision) throw new NotFoundException("这一版原型不存在");

    const file = await this.contained(protoArtifacts(projectId, version, this.root)[kind]);
    const info = await stat(file).catch(() => null);
    if (!info?.isFile()) throw new NotFoundException("产物文件不在盘上：这一版可能没跑到那一步");
    if (info.size > MAX_ARTIFACT_BYTES) {
      throw new PayloadTooLargeException(
        `产物 ${String(Math.trunc(info.size / 1024 / 1024))}MB 超过 ${String(Math.trunc(MAX_ARTIFACT_BYTES / 1024 / 1024))}MB 上限`,
      );
    }
    // shortcut: 整个读进内存才能算指纹比对（原型产物是 MB 级）；出现 GB 级产物时改成流式哈希
    const bytes = await readFile(file);
    const sha256 = createHash("sha256").update(bytes).digest("hex");
    const expected =
      kind === "rp" ? revision.rpSha256 : kind === "preview" ? revision.previewSha256 : null;
    if (expected !== null && expected !== sha256) {
      throw new ConflictException(
        `产物与库里的指纹不一致（期望 ${expected.slice(0, 12)}…，实际 ${sha256.slice(0, 12)}…）：这一版可能已被重跑或被改动`,
      );
    }
    const media = ARTIFACT_MEDIA[kind];
    res.setHeader("Content-Type", media.type);
    res.setHeader("Cache-Control", "private, no-store");
    // 文件名用 id + 版本，不用项目名：标题是人写的，塞进响应头等于开了注入的口子
    res.setHeader(
      "Content-Disposition",
      `attachment; filename="proto-${projectId}-v${String(version)}.${media.ext}"`,
    );
    res.setHeader("X-Heron-Sha256", sha256);
    res.status(200).send(bytes);
  }

  /** 路径只来自 protoArtifacts，仍逐次确认它没跑出根目录（符号链接也算跑出）。 */
  private async contained(target: string): Promise<string> {
    const root = await realpath(this.root);
    const resolved = await realpath(target).catch(() => null);
    const within = resolved === null ? null : relative(root, resolved);
    if (
      resolved === null ||
      !within ||
      within === ".." ||
      within.startsWith(`..${sep}`) ||
      isAbsolute(within)
    ) {
      throw new NotFoundException("产物不存在或不可读取");
    }
    return resolved;
  }
}
