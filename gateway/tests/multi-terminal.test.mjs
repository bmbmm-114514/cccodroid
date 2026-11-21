import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";
import { execFile } from "node:child_process";
import { CFG } from "../gw/config.mjs";
import { codexRelease } from "../gw/engine-releases.mjs";
import { codexOptions } from "../gw/multi-codex-launch.mjs";
import { unpackCodex } from "../gw/multi-codex-install.mjs";
import { createCodexClient } from "../gw/multi-codex.mjs";
import { personalEnv } from "../gw/multi-agy-launch.mjs";
import { runTerminal } from "../gw/multi-terminal.mjs";

const exec = promisify(execFile);
const root = fileURLToPath(new URL("../../", import.meta.url));
async function fixture(t) {
  const dir = await fs.promises.mkdtemp(path.join(CFG.home, "multi-cli-"));
  t.after(() => fs.promises.rm(dir, { recursive: true, force: true }));
  const project = path.join(dir, "project"), bin = path.join(dir, "app", "bin");
  await fs.promises.mkdir(project);
  await fs.promises.mkdir(bin, { recursive: true });
  await fs.promises.symlink(path.join(root, "gateway", "gw"), path.join(dir, "app", "gw"));
  for (const name of ["codex", "agy"]) {
    await fs.promises.copyFile(path.join(root, "android", "assets", "bin", name), path.join(bin, name));
  }
  const env = { HOME: dir, CCAPP_HOME: dir, CCAPP_TEST: "1", PATH: process.env.PATH, LANG: "C.UTF-8" };
  return { dir, project, bin, env };
}

test("terminal passes argv and current directory through and preserves the native exit status", async t => {
  const { dir, project, env } = await fixture(t);
  const output = path.join(dir, "args.json");
  const status = await runTerminal("codex", ["one two", "", "$(literal)"], async () => ({
    command: process.execPath, cwd: project, env,
    args: ["-e", 'require("fs").writeFileSync(process.argv[1],JSON.stringify([process.cwd(),...process.argv.slice(2)]));process.exit(23)', output]
  }));
  assert.equal(status, 23);
  assert.deepEqual(JSON.parse(await fs.promises.readFile(output)), [project, "one two", "", "$(literal)"]);
  assert.equal(await runTerminal("agy", [], async () => ({
    command: "/bin/sh", args: ["-c", "kill -TERM $$"], env, cwd: project
  })), 143);
});

test("the packaged Claude terminal keeps the App-selected version", async t => {
  const { dir, project, bin, env } = await fixture(t);
  const wrapper = path.join(bin, "claude");
  await fs.promises.copyFile(path.join(root, "android/assets/bin/claude"), wrapper);
  await fs.promises.symlink(path.join(root, "gateway/guard"), path.join(bin, "../guard"));
  const loader = path.join(dir, "version-probe");
  await fs.promises.writeFile(loader, '#!/bin/sh\nprintf "%s" "$DISABLE_AUTOUPDATER"\n', { mode: 0o700 });
  const probe = await exec("/bin/sh", [wrapper, "--version"], {
    cwd: project, env: { ...env, CCAPP_LD: loader, DISABLE_AUTOUPDATER: "0" }
  });
  assert.equal(probe.stdout, "1");
});

test("both packaged shell entries report a missing engine without reading gateway state", async t => {
  const { dir, bin, project, env } = await fixture(t);
  for (const [name, label] of [["codex", "Codex CLI"], ["agy", "Antigravity CLI"]]) {
    await assert.rejects(exec("/bin/sh", [path.join(bin, name), "--version"], { cwd: project, env }), error =>
      error.code === 1 && error.stderr.includes(label));
  }
  assert.equal(fs.existsSync(path.join(dir, ".run-active")), false);
});

test("terminal and app use the same personal profiles with separate CLI and app-server argv", async t => {
  const { dir } = await fixture(t);
  const options = { home: dir, verifyBinary: async () => "/fixture/codex" };
  const term = await codexOptions({ ...options, terminal: true }), app = await codexOptions(options);
  assert.equal(term.cwd, process.cwd());
  assert.deepEqual(app.args, [...term.args, "app-server", "--listen", "stdio://"]);
  assert.equal(app.env.CODEX_HOME, term.env.CODEX_HOME);
  assert.equal(term.env.CCAPP_KEY_FILE, undefined);
  assert.equal(personalEnv().HOME, CFG.home);
  assert.equal(personalEnv().GOOGLE_API_KEY, undefined);
  assert.equal(personalEnv({ AGY_CLI_DISABLE_AUTO_UPDATE: "false" }).AGY_CLI_DISABLE_AUTO_UPDATE, "true");
});

const archive = path.join(root, "devkit", "cache", codexRelease.asset + ".tar.gz");
test("pinned Linux arm64 bundle starts through both the packaged terminal and the GUI launcher", {
  skip: process.arch !== "arm64" || process.platform !== "linux" || !fs.existsSync(archive)
}, async t => {
  const { dir, bin, project, env } = await fixture(t);
  const install = path.join(dir, ".local", "share", "multi-agent");
  await fs.promises.mkdir(install, {recursive: true});
  const binary = path.join(install, "codex-" + codexRelease.version);
  await unpackCodex(fs.createReadStream(archive), binary);
  const cli = path.join(bin, "codex");
  const printed = await exec("/bin/sh", [cli, "--version"], { cwd: project, env });
  assert.match(printed.stdout, /codex-cli 0\.166\.0/);
  const login = await exec("/bin/sh", [cli, "login", "--help"], { cwd: project, env });
  assert.match(login.stdout, /--device-auth/);
  const launched = await codexOptions({ home: dir, verifyBinary: async () => binary });
  const client = createCodexClient({ ...launched, cwd: project, timeoutMs: 7000 });
  try { assert.equal((await client.account()).account, null); }
  finally { client.close(); }
});
