import { realpath, stat } from "node:fs/promises";
import { isAbsolute, relative, sep } from "node:path";
import { ConflictException, Inject, Injectable, NotFoundException } from "@nestjs/common";
import type { ArtifactKind, AuthUser } from "@heron/contracts";
import { getArtifactForTask, ManagementError, withTenant, type Db } from "@heron/db/pg";
import { createObjectStore, type ObjectStore } from "@heron/storage";
import type { Response } from "express";
import { PG_DB } from "../db/pg";

/** Production provider is VIDEOS_DIR from @heron/db; tests override only this root. */
export const ARTIFACT_ROOT = "ARTIFACT_ROOT";
/**
 * 成片桶。默认 local 驱动：没配 HERON_STORAGE 时 url() 恒为 null，
 * 整条取址链路与接对象存储之前逐字节一致。
 */
export const OBJECT_STORE = "OBJECT_STORE";

@Injectable()
export class ArtifactService {
  constructor(
    @Inject(PG_DB) private readonly db: Db,
    @Inject(ARTIFACT_ROOT) private readonly root: string,
    @Inject(OBJECT_STORE) private readonly store: ObjectStore = createObjectStore(),
  ) {}

  async serve(
    user: AuthUser,
    taskId: string,
    kind: ArtifactKind,
    version: number,
    res: Response,
  ): Promise<void> {
    // RLS completes before any filesystem access. Never accept a path from the client.
    const artifact = await withTenant(this.db, user.tenantId, (t) =>
      getArtifactForTask(t, taskId, kind, version),
    ).catch((error: unknown) => {
      if (error instanceof ManagementError && error.code === "conflict")
        throw new ConflictException("产物版本已变化，请刷新后查看当前版本");
      throw error;
    });
    const ext = kind === "mp4" ? "mp4" : kind === "contact-sheet" ? "png" : "json";

    // 本地盘优先：发布与诊断的现场在这台机器上，能直接读就不必绕一圈 CDN。
    const file = artifact.path ? await this.resolve(artifact.path) : null;
    if (file) {
      res.setHeader("Content-Type", artifact.contentType);
      res.setHeader("Cache-Control", "private, no-store");
      res.setHeader(
        "Content-Disposition",
        `inline; filename="${taskId}-v${String(version)}-${kind}.${ext}"`,
      );
      await new Promise<void>((resolve) => {
        res.sendFile(
          file,
          { cacheControl: false, lastModified: false, etag: false, dotfiles: "deny" },
          (error: unknown) => {
            if (error) {
              if (res.headersSent) res.destroy();
              else {
                const status = (error as { status?: number }).status === 416 ? 416 : 404;
                res.removeHeader("Content-Disposition");
                res.removeHeader("Content-Type");
                res.status(status).json({
                  statusCode: status,
                  message: status === 416 ? "请求的字节范围无效" : "产物不存在或不可读取",
                });
              }
            }
            resolve();
          },
        );
      });
      return;
    }

    // 本机那份没了（保留清理删过，或这台机器不是渲染机）：桶里有就给一个短时效签名地址。
    // 只 302、不把 URL 写进 JSON，是为了让 key 与凭证都不进浏览器；私有空间的签名本身有时效。
    // res.redirect 会在正文里再回显一遍地址，这里手写 Location 让正文为空。
    const url = artifact.key ? this.store.url(artifact.key) : null;
    if (!url) throw new NotFoundException("产物不存在或不可读取");
    res.status(302).setHeader("Location", url);
    res.setHeader("Cache-Control", "private, no-store");
    res.end();
  }

  /** 路径必须是 ARTIFACT_ROOT 内的绝对路径且指向普通文件；任何一条不成立都当「没有」。 */
  private async resolve(path: string): Promise<string | null> {
    try {
      if (!isAbsolute(path) || path.split(/[\\/]/).includes("..")) return null;
      const root = await realpath(this.root);
      const file = await realpath(path);
      const within = relative(root, file);
      if (
        !within ||
        within === ".." ||
        within.startsWith(`..${sep}`) ||
        isAbsolute(within) ||
        !(await stat(file)).isFile()
      )
        return null;
      return file;
    } catch {
      return null;
    }
  }
}
