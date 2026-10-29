import * as childProcess from "node:child_process";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { setTimeout as delay } from "node:timers/promises";

import { describe, expect, it, vi } from "vitest";

// The real barrel loads .env at import time; this boundary needs neither it nor a database.
vi.mock("@heron/db", () => ({ ZEE_RP_HOME: "" }));
vi.mock("node:child_process", { spy: true });

import { describeRun, runEngine } from "./proto-engine";

const roles = ["root", "middle", "leaf"] as const;

function fakeHome(): string {
  const home = mkdtempSync(join(tmpdir(), "heron-engine-tree-"));
  mkdirSync(join(home, "scripts"));
  writeFileSync(
    join(home, "scripts", "proto.mjs"),
    `import { spawn } from 'node:child_process';
import { writeFileSync } from 'node:fs';
const role = process.argv[2] || 'root';
const stdio = process.argv[3] || 'inherit';
writeFileSync(role + '.pid', String(process.pid));
// Self-expire even if the test runner crashes before its finally block.
setTimeout(() => process.exit(0), 20_000);
const next = role === 'root' ? 'middle' : role === 'middle' ? 'leaf' : null;
if (next) {
  // Avoid libuv's Windows parent-lifetime job, like a non-Node descendant.
  // POSIX descendants must stay in the engine's process group.
  const child = spawn(process.execPath, [process.argv[1], next, stdio], {
    stdio, detached: process.platform === 'win32', windowsHide: true,
  });
  writeFileSync(next + '.pid', String(child.pid));
} else {
  // Past the runner's 1s timeout + 5s termination bound, even on a busy host.
  writeFileSync('ready', String(Date.now() + 7_000));
  setTimeout(() => writeFileSync('late-write', 'descendant survived'), 7_000);
}
`,
  );
  return home;
}

function pidOf(home: string, role: string): number {
  const pid = Number(readFileSync(join(home, `${role}.pid`), "utf8"));
  if (!Number.isSafeInteger(pid) || pid <= 0) throw new Error(`Invalid test PID: ${pid}`);
  return pid;
}

function running(pid: number): boolean {
  try {
    process.kill(pid, 0);
    // Linux orphan zombies cannot execute the delayed write but may await PID 1's reap.
    if (process.platform === "linux") {
      return !/\) Z /.test(readFileSync(`/proc/${pid}/stat`, "utf8"));
    }
    return true;
  } catch (cause) {
    if (["ESRCH", "ENOENT"].includes((cause as NodeJS.ErrnoException).code ?? "")) return false;
    throw cause;
  }
}

async function cleanup(home: string): Promise<void> {
  vi.restoreAllMocks();
  const errors: unknown[] = [];
  // Stop ancestors first so no new descendants can be started during cleanup.
  for (const role of roles) {
    if (!existsSync(join(home, `${role}.pid`))) continue;
    try {
      const pid = pidOf(home, role);
      if (running(pid)) {
        if (process.platform === "win32") {
          const result = childProcess.spawnSync("taskkill.exe", ["/PID", String(pid), "/T", "/F"], {
            windowsHide: true,
            timeout: 5_000,
          });
          if (result.error) throw result.error;
          if (result.status !== 0 && running(pid)) {
            throw new Error(`Test cleanup failed: ${result.stderr.toString()}`);
          }
        } else {
          try {
            process.kill(pid, "SIGKILL");
          } catch (cause) {
            if ((cause as NodeJS.ErrnoException).code !== "ESRCH") throw cause;
          }
        }
      }
      await expect.poll(() => running(pid), { timeout: 3_000 }).toBe(false);
    } catch (cause) {
      errors.push(cause);
    }
  }
  // Windows 上刚被 taskkill 掉的进程要过一两百毫秒才放开目录句柄，而 rmSync 默认一次不成就抛：
  // 一次 EPERM 会让「清理失败」冒充「被测行为失败」（这条用例本来就故意留了一个活着的子进程）。
  rmSync(home, { recursive: true, force: true, maxRetries: 30, retryDelay: 100 });
  if (errors.length) throw new AggregateError(errors, "Failed to clean up test processes");
}

describe("engine timeout process tree", () => {
  it.each(["inherit", "ignore"])(
    "terminates actual grandchildren with %s stdio before they can write later",
    async (stdio) => {
      const home = fakeHome();
      try {
        const result = await runEngine(home, ["root", stdio], 1_000);
        expect(result).toMatchObject({ ok: false, timedOut: true });
        expect(result.stderr).not.toContain("终止引擎进程树失败");
        expect(existsSync(join(home, "ready")), "grandchild must have started").toBe(true);
        const writeAt = Number(readFileSync(join(home, "ready"), "utf8"));
        await delay(Math.max(0, writeAt - Date.now() + 200));
        expect(
          existsSync(join(home, "late-write")),
          `grandchild survived the timeout: ${JSON.stringify(result)}`,
        ).toBe(false);
        for (const role of roles) {
          expect(running(pidOf(home, role)), `${role} is still running`).toBe(false);
        }
      } finally {
        await cleanup(home);
      }
    },
    15_000,
  );

  it("returns a diagnostic instead of claiming termination when native tree kill fails", async () => {
    const home = fakeHome();
    try {
      if (process.platform === "win32") {
        const native = await vi.importActual<typeof childProcess>("node:child_process");
        vi.spyOn(childProcess, "execFile").mockImplementationOnce(
          (_file, args, options, callback) => {
            // Partial failure: the root closes before taskkill reports a descendant failure.
            process.kill(Number(args?.[1]), "SIGKILL");
            return native.execFile(
              process.execPath,
              ["-e", 'process.stderr.write("synthetic tree kill denied"); process.exit(1);'],
              options,
              callback,
            );
          },
        );
      } else {
        const kill = process.kill.bind(process);
        vi.spyOn(process, "kill").mockImplementation((pid, signal) => {
          if (pid < 0 && signal === "SIGKILL") throw new Error("synthetic tree kill denied");
          return kill(pid, signal);
        });
      }
      const result = await runEngine(home, ["root", "ignore"], 1_000);
      expect(result).toMatchObject({ ok: false, timedOut: true });
      expect(result.stderr).toContain("synthetic tree kill denied");
      expect(describeRun(result, 1_000)).toContain("synthetic tree kill denied");
      expect(describeRun(result, 1_000)).not.toContain("已终止");
      expect(running(pidOf(home, "leaf"))).toBe(true);
    } finally {
      await cleanup(home);
    }
  });

  it("bounds an unconfirmed termination and reports that descendants may still be running", async () => {
    const home = fakeHome();
    try {
      if (process.platform === "win32") {
        vi.spyOn(childProcess, "execFile").mockImplementationOnce(
          () => new childProcess.ChildProcess(),
        );
      } else {
        const kill = process.kill.bind(process);
        vi.spyOn(process, "kill").mockImplementation((pid, signal) => {
          if (pid < 0 && signal === "SIGKILL") return true;
          return kill(pid, signal);
        });
      }
      const result = await runEngine(home, ["root", "inherit"], 1_000);
      expect(result).toMatchObject({ ok: false, code: null, timedOut: true });
      expect(result.stderr).toContain("未能确认退出");
      expect(describeRun(result)).toContain("可能仍有子进程运行");
      expect(describeRun(result)).not.toContain("已终止");
      expect(running(pidOf(home, "leaf"))).toBe(true);
    } finally {
      await cleanup(home);
    }
  }, 12_000);

  it.each([0, 3])("preserves normal exit code %s and output", async (code) => {
    const home = fakeHome();
    try {
      writeFileSync(
        join(home, "scripts", "proto.mjs"),
        `process.stdout.write(process.argv[2]); process.stderr.write('diagnostic'); process.exit(${code});`,
      );
      const result = await runEngine(home, ["literal argument & not a shell command"], 5_000);
      expect(result).toEqual({
        ok: code === 0,
        code,
        timedOut: false,
        stdout: "literal argument & not a shell command",
        stderr: "diagnostic",
      });
    } finally {
      await cleanup(home);
    }
  });

  it("does not infer termination from the timedOut flag alone", () => {
    const message = describeRun({
      ok: false,
      code: null,
      timedOut: true,
      stdout: "",
      stderr: "native termination failed: access denied",
    });
    expect(message).toContain("超时");
    expect(message).toContain("access denied");
    expect(message).not.toContain("已终止");
  });
});
