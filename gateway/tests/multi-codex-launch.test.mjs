import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import { CFG } from "../gw/config.mjs";
import { codexOptions, managedCodexClient } from "../gw/multi-codex-launch.mjs";

test("fresh install creates the private profile before starting Codex and preserves it on retry", async t => {
  const home = await fs.mkdtemp(path.join(CFG.home, "codex-fresh-"));
  t.after(() => fs.rm(home, { recursive: true, force: true }));
  const options = { home, verifyBinary: async () => "/fixture/codex-app-server" };
  const profile = path.join(home, ".codex");
  const existing = path.join(profile, "preexisting-file");
  await assert.rejects(fs.stat(profile), {code: "ENOENT"});
  const first = await codexOptions(options);
  assert.deepEqual(first.args.slice(-5), ["-c", "check_for_update_on_startup=false", "app-server", "--listen", "stdio://"]);
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

test("Codex switches to the active binary once in-flight requests and device login have finished", async () => {
  let active = "bundled", waitingForLogin = false;
  const closed = [], opened = [];
  const use = managedCodexClient(async () => active, async file => {
    opened.push(file);
    return { file, close: () => closed.push(file) };
  }, () => waitingForLogin);
  const started = Promise.withResolvers(), done = Promise.withResolvers();
  const run = use(async client => {
    started.resolve(client.file);
    await done.promise;
  });
  assert.equal(await started.promise, "bundled");
  active = "updated";
  assert.equal(await use(client => client.file), "bundled");
  waitingForLogin = true;
  done.resolve();
  await run;
  assert.equal(await use(client => client.file), "bundled");
  assert.deepEqual(closed, []);
  waitingForLogin = false;
  assert.deepEqual(await Promise.all([use(client => client.file), use(client => client.file)]), ["updated", "updated"]);
  assert.deepEqual(closed, ["bundled"]);
  assert.deepEqual(opened, ["bundled", "updated"]);
});
