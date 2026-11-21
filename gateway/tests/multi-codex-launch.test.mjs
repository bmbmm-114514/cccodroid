import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import { CFG } from "../gw/config.mjs";
import { codexOptions } from "../gw/multi-codex-launch.mjs";

test("fresh install creates the private profile before starting Codex and preserves it on retry", async t => {
  const home = await fs.mkdtemp(path.join(CFG.home, "codex-fresh-"));
  t.after(() => fs.rm(home, { recursive: true, force: true }));
  const options = { home, verifyBinary: async () => "/fixture/codex-app-server" };
  const profile = path.join(home, ".codex");
  const existing = path.join(profile, "preexisting-file");
  await assert.rejects(fs.stat(profile), {code: "ENOENT"});
  const first = await codexOptions(options);
  assert.equal(first.env.CODEX_HOME, profile);
  assert.equal((await fs.stat(profile)).isDirectory(), true);
  assert.equal((await fs.stat(first.env.TMPDIR)).isDirectory(), true);
  assert.equal((await fs.stat(profile)).mode & 0o077, 0);
  await fs.writeFile(existing, "preserve me");
  await codexOptions(options);
  assert.equal(await fs.readFile(existing, "utf8"), "preserve me");
});

test("Codex startup reports an unusable profile without overwriting it", async t => {
  const home = await fs.mkdtemp(path.join(CFG.home, "codex-conflict-"));
  t.after(() => fs.rm(home, { recursive: true, force: true }));
  const conflict = path.join(home, ".codex");
  await fs.writeFile(conflict, "existing data");
  await assert.rejects(codexOptions({ home, verifyBinary: async () => "/fixture/bin" }), {code: "ENOTDIR"});
  assert.equal(await fs.readFile(conflict, "utf8"), "existing data");
});
