import { execFile, spawn } from "node:child_process";
import { existsSync } from "node:fs";
import { join } from "node:path";

import { ZEE_RP_HOME } from "@heron/db";

/**
 * 引擎子进程的唯一边界。runner 可注入：测试用一个假 CLI 就能覆盖非零退出、超时、产物缺失，
 * 不必在测试里装 Python 与 Pillow。
 */
export type EngineRunner = (args: string[], timeoutMs?: number) => Promise<EngineRun>;

export interface EngineRun {
  readonly ok: boolean;
  readonly code: number | null;
  readonly timedOut: boolean;
  readonly stdout: string;
  readonly stderr: string;
}

/** 一次编译在真机上通常几十秒；三分钟的余量给慢机器，超时就杀，不让人对着「生成中」等一晚上。 */
export const ENGINE_TIMEOUT_MS = 180_000;
/** 引擎的诊断文本堆到这份上已经没用了，留尾部即可，别让一句唠叨把进程内存顶爆。 */
const OUTPUT_LIMIT = 1_000_000;

export function makeEngineRunner(home: string = ZEE_RP_HOME): EngineRunner {
  return (args, timeoutMs = ENGINE_TIMEOUT_MS) => runEngine(home, args, timeoutMs);
}

export function runEngine(
  home: string,
  args: string[],
  timeoutMs = ENGINE_TIMEOUT_MS,
): Promise<EngineRun> {
  const cli = join(home, "scripts", "proto.mjs");
  return new Promise((resolve) => {
    if (!home || !existsSync(cli)) {
      resolve({
        ok: false,
        code: null,
        timedOut: false,
        stdout: "",
        stderr: home ? `找不到引擎 CLI：${cli}` : "未配置 ZEE_RP_HOME",
      });
      return;
    }

    let stdout = "";
    let stderr = "";
    let timedOut = false;
    let settled = false;
    let terminating = false;
    let closed = false;
    let exitCode: number | null = null;
    let fallback: ReturnType<typeof setTimeout> | undefined;
    const finish = (code: number | null): void => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      clearTimeout(fallback);
      if (timedOut) {
        child.stdout?.destroy();
        child.stderr?.destroy();
        child.unref();
      }
      resolve({ ok: code === 0 && !timedOut, code, timedOut, stdout, stderr });
    };
    const terminationFailed = (cause: unknown): void => {
      if (settled) return;
      const detail = cause instanceof Error ? cause.message : String(cause);
      stderr = trim(`${stderr}\n终止引擎进程树失败：${detail}；可能仍有子进程运行`);
      finish(null);
    };

    const child = spawn(process.execPath, [cli, ...args], {
      cwd: home,
      env: { ...process.env, AXURE_TARGET_VERSION: process.env.AXURE_TARGET_VERSION ?? "9" },
      stdio: ["ignore", "pipe", "pipe"],
      windowsHide: true,
      // POSIX 的独立进程组包含 CLI 及其 Python 等后代，不把 worker 自己一起杀掉。
      detached: process.platform !== "win32",
    });

    const timer = setTimeout(() => {
      timedOut = true;
      terminating = true;
      fallback = setTimeout(() => terminationFailed("等待终止超过 5s，未能确认退出"), 5_000);
      void terminateProcessTree(child.pid).then(() => {
        terminating = false;
        if (closed) finish(exitCode);
      }, terminationFailed);
    }, timeoutMs);

    child.stdout?.on("data", (chunk: Buffer) => {
      stdout = trim(stdout + chunk.toString("utf8"));
    });
    child.stderr?.on("data", (chunk: Buffer) => {
      stderr = trim(stderr + chunk.toString("utf8"));
    });
    child.on("error", (cause: Error) => {
      stderr = trim(`${stderr}\n${cause.message}`);
      if (!terminating) finish(null);
    });
    child.on("close", (code) => {
      closed = true;
      exitCode = code;
      // Windows 的父进程可能先 close；必须等 taskkill 的整棵树结果，不能抢先报成功。
      if (!terminating) finish(code);
    });
  });
}

async function terminateProcessTree(pid: number | undefined): Promise<void> {
  if (pid === undefined) throw new Error("引擎 PID 不可用");
  if (process.platform !== "win32") {
    try {
      process.kill(-pid, "SIGKILL");
    } catch (cause) {
      // 退出与超时竞争时，整个进程组可能已经消失。
      if ((cause as NodeJS.ErrnoException).code !== "ESRCH") throw cause;
    }
    return;
  }
  await new Promise<void>((resolve, reject) => {
    execFile(
      join(process.env.SystemRoot ?? "C:\\Windows", "System32", "taskkill.exe"),
      ["/PID", String(pid), "/T", "/F"],
      { windowsHide: true, timeout: 5_000 },
      (cause, stdout, stderr) => {
        if (cause) {
          reject(new Error(`taskkill: ${cause.message}\n${stderr || stdout}`));
        } else {
          resolve();
        }
      },
    );
  });
}

function trim(text: string): string {
  return text.length > OUTPUT_LIMIT ? text.slice(text.length - OUTPUT_LIMIT) : text;
}

/** 引擎失败时的原因：先说是超时，再给人看它自己最后说了什么。 */
export function describeRun(run: EngineRun, timeoutMs = ENGINE_TIMEOUT_MS): string {
  const detail = (run.stderr.trim() || run.stdout.trim()).slice(-1_200);
  if (run.timedOut) {
    return `引擎超时（>${String(Math.round(timeoutMs / 1000))}s）${detail ? `：${detail}` : ""}`;
  }
  return `引擎退出码 ${String(run.code ?? "null")}${detail ? `：${detail}` : ""}`;
}
