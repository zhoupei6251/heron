import { realpath, stat } from "node:fs/promises";
import { isAbsolute, relative, sep } from "node:path";
import { ConflictException, Inject, Injectable, NotFoundException } from "@nestjs/common";
import type { ArtifactKind, AuthUser } from "@heron/contracts";
import { getArtifactForTask, ManagementError, withTenant, type Db } from "@heron/db/pg";
import type { Response } from "express";
import { PG_DB } from "./db/pg";

/** Production provider is VIDEOS_DIR from @heron/db; tests override only this root. */
export const ARTIFACT_ROOT = "ARTIFACT_ROOT";

@Injectable()
export class ArtifactService {
  constructor(
    @Inject(PG_DB) private readonly db: Db,
    @Inject(ARTIFACT_ROOT) private readonly root: string,
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
    let file: string;
    try {
      if (!isAbsolute(artifact.path) || artifact.path.split(/[\\/]/).includes(".."))
        throw new Error("invalid artifact path");
      const root = await realpath(this.root);
      file = await realpath(artifact.path);
      const within = relative(root, file);
      if (
        !within ||
        within === ".." ||
        within.startsWith(`..${sep}`) ||
        isAbsolute(within) ||
        !(await stat(file)).isFile()
      )
        throw new Error("outside artifact root");
    } catch {
      throw new NotFoundException("产物不存在或不可读取");
    }
    const ext = kind === "mp4" ? "mp4" : kind === "contact-sheet" ? "png" : "json";
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
  }
}
