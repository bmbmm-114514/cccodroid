import test from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { gzipSync } from "node:zlib";
import { checkAgyUpdate, stageAgyUpdate } from "../gw/multi-agy-update.mjs";

const url = "https://storage.googleapis.com/antigravity-public/antigravity-cli/1.5.14-5302690847260672/android-arm/cli_android_arm64.tar.gz";
const hex = (algorithm, data) => createHash(algorithm).update(data).digest("hex");
const elf = Buffer.alloc(80);
Buffer.from("7f454c46020101", "hex").copy(elf);
elf.writeUInt16LE(183, 18);

function archive(name = "antigravity", payload = elf) {
  const header = Buffer.alloc(512);
  header.write(name);
  header.write(payload.length.toString(8).padStart(11, "0"), 124);
  header.fill(32, 148, 156);
  header.write("0", 156);
  header.write(header.reduce((n, b) => n + b, 0).toString(8).padStart(6, "0") + "\0 ", 148);
  return gzipSync(Buffer.concat([header, payload, Buffer.alloc(512 - payload.length + 1024)]));
}

const metadata = (pkg, urlValue = url) => ({ version: "1.5.14", url: urlValue, sha512: hex("sha512", pkg) });
const check = data => checkAgyUpdate(async (address, options) => {
  assert.equal(address, "https://antigravity-cli-auto-updater-974169037036.us-central1.run.app/manifests/android_arm64.json");
  assert.equal(options.redirect, "error");
  return Response.json(data);
});

async function target(t) {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), "codroid-agy-update-"));
  t.after(() => fs.rm(dir, { recursive: true, force: true }));
  return path.join(dir, "release.part");
}

test("official Android arm64 manifest yields an exclusively staged and verified executable", async t => {
  const pkg = archive(), dest = await target(t), release = await check(metadata(pkg));
  const get = async (address, options) => {
    assert.equal(address, url);
    assert.equal(options.redirect, "error");
    return new Response(pkg);
  };
  assert.deepEqual(await stageAgyUpdate(release, dest, get), { version: "1.5.14", bytes: elf.length, sha256: hex("sha256", elf) });
  assert.deepEqual(await fs.readFile(dest), elf);
  assert.equal((await fs.stat(dest)).mode & 0o777, 0o700);
  await assert.rejects(stageAgyUpdate(release, dest, get), { code: "EEXIST" });
  assert.deepEqual(await fs.readFile(dest), elf);
});

test("release metadata must identify the exact Google Android arm64 build and SHA-512", async () => {
  const ok = metadata(archive());
  for (const alternative of [url.replace("storage.googleapis.com", "example.org"), url.replace("android-arm/", "linux-arm/"), url + "?alt=media", url.replace("1.5.14-", "1.5.13-")]) {
    await assert.rejects(check({ ...ok, url: alternative }), /发行清单/);
  }
  await assert.rejects(check({ ...ok, sha512: "0" }), /发行清单/);
  await assert.rejects(check({ ...ok, padding: " ".repeat(16 * 1024) }), /发行清单过大/);
});

test("a bad official digest, unexpected tar member or wrong ELF architecture cannot be installed", async t => {
  const pkg = archive(), release = await check(metadata(pkg));
  await assert.rejects(stageAgyUpdate({ ...release, archiveSha512: "1".repeat(128) }, await target(t), async () => new Response(pkg)), /校验失败/);
  const otherArch = Buffer.from(elf);
  otherArch.writeUInt16LE(62, 18);
  for (const invalid of [archive("other-file"), archive("antigravity", otherArch)]) {
    await assert.rejects(stageAgyUpdate(await check(metadata(invalid)), await target(t), async () => new Response(invalid)), /格式异常|校验失败/);
  }
});

test("the update page activates only a checked, approved download and keeps the working version on failure", async t => {
  const { createServer } = await import("node:net");
  const { randomUUID } = await import("node:crypto");
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), "cc-engine-update-"));
  const previousHome = process.env.CCAPP_HOME, previousSocket = process.env.CCAPP_PRIV_SOCK;
  process.env.CCAPP_HOME = dir;
  process.env.CCAPP_PRIV_SOCK = path.join(dir, "native.sock");
  const { agyRelease } = await import("../gw/engine-releases.mjs");
  const original = { ...agyRelease }, base = Buffer.alloc(80, 32), ticket = randomUUID();
  Object.assign(agyRelease, { bytes: base.length, sha256: hex("sha256", base) });
  const { activeEngine, agyBinary, updateDir } = await import("../gw/multi-binary.mjs");
  const { engineUpdateStatus, requestEngineUpdate } = await import("../gw/multi-engine-updates.mjs");
  await fs.mkdir(path.dirname(agyBinary), { recursive: true });
  await fs.writeFile(agyBinary, base, { mode: 0o700 });
  const releases = {};
  let allowCommit = false;
  const bridge = createServer(socket => socket.once("data", buffer => {
    const req = JSON.parse(buffer.toString());
    let ok = req.op === "engine_update_pins";
    if (req.op === "engine_update_authorized") ok = req.ticket === ticket;
    if (req.op === "engine_update_activate" && allowCommit && req.ticket === ticket) {
      releases.agy = { version: req.version, bytes: req.bytes, sha256: req.sha256 };
      ok = true;
    }
    socket.end(JSON.stringify({ ok, releases }));
  }));
  await new Promise(resolve => bridge.listen(process.env.CCAPP_PRIV_SOCK, resolve));
  t.after(async () => {
    await new Promise(resolve => bridge.close(resolve));
    Object.assign(agyRelease, original);
    process.env.CCAPP_HOME = previousHome;
    if (previousSocket === undefined) delete process.env.CCAPP_PRIV_SOCK;
    else process.env.CCAPP_PRIV_SOCK = previousSocket;
    await fs.rm(dir, { recursive: true, force: true });
  });

  let downloads = 0, searches = 0, corrupt = false;
  const packed = archive();
  t.mock.method(globalThis, "fetch", async address => {
    if (address === url) { downloads++; return new Response(corrupt ? packed.subarray(0, 15) : packed); }
    searches++;
    return Response.json(metadata(packed));
  });
  async function status() { return (await engineUpdateStatus()).items.find(i => i.agent === "agy"); }
  async function settled() {
    const until = Date.now() + 4000;
    while (Date.now() < until) {
      const state = await status();
      if (!["installing", "checking"].includes(state.state)) return state;
      await new Promise(resolve => setTimeout(resolve, 10));
    }
    assert.fail("update did not finish");
  }
  const check = { agent: "agy", action: "check" };
  const install = { agent: "agy", action: "install", version: "1.5.14", ticket };
  assert.equal((await status()).current, original.version);
  assert.equal(searches, 0);
  assert.throws(() => requestEngineUpdate(install), /先检查版本/);
  requestEngineUpdate(check);
  requestEngineUpdate(check);
  assert.equal((await settled()).state, "available");
  assert.equal(searches, 1);
  assert.throws(() => requestEngineUpdate({ ...install, version: "1.9.0" }), /先检查版本/);

  requestEngineUpdate({ ...install, ticket: randomUUID() });
  assert.equal((await settled()).state, "error");
  assert.equal(downloads, 0);
  requestEngineUpdate(install);
  assert.equal((await settled()).state, "error");
  assert.deepEqual(await fs.readdir(updateDir), []);
  assert.equal((await activeEngine("agy")).file, agyBinary);
  corrupt = true;
  requestEngineUpdate(install);
  assert.equal((await settled()).state, "error");
  assert.deepEqual(await fs.readdir(updateDir), []);

  allowCommit = true;
  corrupt = false;
  requestEngineUpdate(install);
  requestEngineUpdate(install);
  assert.equal((await settled()).current, install.version);
  assert.equal(downloads, 3);
  const active = await activeEngine("agy");
  assert.equal(active.sha256, hex("sha256", elf));
  assert.deepEqual(await fs.readFile(active.file), elf);
  assert.deepEqual(await fs.readFile(agyBinary), base);
  await fs.writeFile(active.file, base);
  assert.equal((await status()).state, "available");
  assert.equal((await activeEngine("agy")).file, agyBinary);
});
