import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, expect, it } from "vitest";

import { probeEngine, probeTemplate } from "./proto-tools";

const roots: string[] = [];
function engineHome(): string {
  const root = mkdtempSync(join(tmpdir(), "heron-probe-"));
  roots.push(root);
  mkdirSync(join(root, "scripts"));
  writeFileSync(join(root, "scripts", "proto.mjs"), "");
  return root;
}
afterEach(() => roots.splice(0).forEach((root) => rmSync(root, { recursive: true, force: true })));

it("requires a readable engine version before reporting readiness", () => {
  const root = engineHome();
  expect(probeEngine(root).ok).toBe(false);
  for (const manifest of ["invalid json", "{}", '{"version":9}', '{"version":" "}']) {
    writeFileSync(join(root, "release-manifest.json"), manifest);
    expect(probeEngine(root)).toMatchObject({ ok: false, version: null });
  }
  writeFileSync(join(root, "release-manifest.json"), '{"version":"0.2.1"}');
  expect(probeEngine(root)).toMatchObject({ ok: true, version: "0.2.1" });
});

it("rejects a missing CLI even when the release version exists", () => {
  const root = engineHome();
  rmSync(join(root, "scripts", "proto.mjs"));
  writeFileSync(join(root, "release-manifest.json"), '{"version":"0.2.1"}');
  expect(probeEngine(root).ok).toBe(false);
});

it("explains how to obtain a missing RP9 template and rejects other headers", () => {
  expect(probeTemplate("").detail).toContain("新建单页空白工程");
  const file = join(engineHome(), "blank.rp");
  expect(probeTemplate(file).ok).toBe(false);
  writeFileSync(file, Buffer.from([0xac, 0xef, 11, 0]));
  expect(probeTemplate(file).ok).toBe(false);
  writeFileSync(file, Buffer.from([0xac, 0xef, 9, 0]));
  expect(probeTemplate(file).ok).toBe(true);
});
