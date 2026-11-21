import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { createHash } from "node:crypto";
import { gzipSync } from "node:zlib";
import { codexRelease } from "../gw/engine-releases.mjs";
import { checkCodexUpdate, codexUpdateSource, stageCodexUpdate } from "../gw/multi-codex-update.mjs";

const version = "0.171.0", name = codexRelease.asset;
const url = `https://github.com/openai/codex/releases/download/rust-v${version}/${name}.tar.gz`;
const payload = Buffer.alloc(64);
payload.writeUInt32BE(0x7f454c46);
payload[4] = 2; // ELF64
payload[5] = 1; // little endian
payload.writeUInt16LE(183, 18); // AArch64
const hash = bytes => createHash("sha256").update(bytes).digest("hex");

function archive(member = name, type = "0", extra = Buffer.alloc(0), executable = payload) {
  const header = Buffer.alloc(512);
  header.write(member);
  header.write(executable.length.toString(8).padStart(11, "0"), 124);
  header.write(type, 156);
  header.fill(32, 148, 156);
  const checksum = header.reduce((sum, byte) => sum + byte, 0);
  header.write(checksum.toString(8).padStart(6, "0") + "\0 ", 148);
  return gzipSync(Buffer.concat([header, executable, Buffer.alloc(512 - executable.length + 1024), extra]));
}
function release(archive) {
  return { version, archiveBytes: archive.length, archiveSha256: hash(archive) };
}
function metadata(packed) {
  return { tag_name: "rust-v" + version, draft: false, prerelease: false,
    assets: [{ name: name + ".tar.gz", browser_download_url: url, size: packed.length, digest: "sha256:" + hash(packed) }] };
}
async function destination(t) {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), "cc-codex-stage-"));
  t.after(() => fs.rm(dir, { recursive: true, force: true }));
  return path.join(dir, "staged");
}

test("selects the GitHub stable ARM64 musl release, verifies the download and stages a private executable", async t => {
  const data = archive(), target = await destination(t), requests = [];
  t.mock.method(globalThis, "fetch", async input => {
    requests.push(input);
    if (input === codexUpdateSource) return Response.json(metadata(data));
    if (input === url) return Response.redirect("https://release-assets.githubusercontent.com/github-production-release-asset/test", 302);
    return new Response(data);
  });
  const current = await checkCodexUpdate();
  assert.deepEqual(current, release(data));
  assert.deepEqual(await stageCodexUpdate(current, target), { version, bytes: payload.length, sha256: hash(payload) });
  assert.deepEqual(requests, [codexUpdateSource, url, "https://release-assets.githubusercontent.com/github-production-release-asset/test"]);
  assert.deepEqual(await fs.readFile(target), payload);
  assert.equal((await fs.stat(target)).mode & 0o777, 0o700);
});

test("rejects previews, nonofficial URLs and incomplete GitHub metadata", async t => {
  let response;
  t.mock.method(globalThis, "fetch", async () => Response.json(response));
  for (const change of [
    data => { data.prerelease = true; },
    data => { data.draft = true; },
    data => { data.tag_name = "rust-v0.172.0-alpha.1"; },
    data => { data.assets[0].name = "wrong-architecture.tar.gz"; },
    data => { data.assets[0].browser_download_url = "https://example.com/archive"; },
    data => { delete data.assets[0].digest; },
    data => { data.assets[0].size = 2 ** 32; }
  ]) {
    response = metadata(archive());
    change(response);
    await assert.rejects(checkCodexUpdate(), /官方/);
  }
});

test("rejects damaged digests, truncated archives and unsafe tar members without leaving a partial executable", async t => {
  const target = await destination(t);
  let data = archive(), current = release(data);
  t.mock.method(globalThis, "fetch", async () => new Response(data));
  const failed = async () => {
    await assert.rejects(stageCodexUpdate(current, target));
    await assert.rejects(fs.stat(target), { code: "ENOENT" });
  };
  current.archiveSha256 = "0".repeat(64);
  await failed();
  data = data.subarray(0, data.length - 5);
  current = release(data);
  await failed();
  for (const bad of [archive("../escaped"), archive(name, "2"), archive(name, "0", Buffer.from("extra member")), archive(name, "0", Buffer.alloc(0), Buffer.alloc(64))]) {
    data = bad;
    current = release(data);
    await failed();
  }
  await fs.writeFile(target, "existing");
  await assert.rejects(stageCodexUpdate(current, target), { code: "EEXIST" });
  assert.equal(await fs.readFile(target, "utf8"), "existing");
});

test("repeated manual installs leave a verified Codex executable untouched and make zero downloads", async t => {
  const home = await fs.mkdtemp(path.join(os.tmpdir(), "cc-codex-install-"));
  const previous = { ...codexRelease }, originalHome = process.env.CCAPP_HOME;
  process.env.CCAPP_HOME = home;
  Object.assign(codexRelease, { bytes: payload.length, sha256: hash(payload) });
  t.after(async () => {
    Object.assign(codexRelease, previous);
    if (originalHome === undefined) delete process.env.CCAPP_HOME;
    else process.env.CCAPP_HOME = originalHome;
    await fs.rm(home, { recursive: true, force: true });
  });
  const { codexBinary, startInstall } = await import("../gw/multi-codex-install.mjs");
  await fs.mkdir(path.dirname(codexBinary), { recursive: true });
  await fs.writeFile(codexBinary, payload, { mode: 0o700 });
  const fetch = t.mock.method(globalThis, "fetch", () => { throw new Error("unexpected download"); });
  const before = await fs.stat(codexBinary, { bigint: true });
  for (const state of await Promise.all([startInstall(), startInstall()])) {
    assert.equal(state.installed, true);
    assert.equal(state.installing, false);
    assert.equal(state.error, "");
  }
  const after = await fs.stat(codexBinary, { bigint: true });
  assert.equal(after.ino, before.ino);
  assert.equal(after.ctimeNs, before.ctimeNs);
  assert.equal(fetch.mock.callCount(), 0);
});


test("downloads only follow official HTTPS GitHub release redirects", async t => {
  const target = await destination(t);
  for (const destination of ["http://release-assets.githubusercontent.com/object", "https://example.com/object"]) {
    let attempts = 0;
    await assert.rejects(stageCodexUpdate(release(archive()), target, async () => {
      attempts++;
      return Response.redirect(destination, 302);
    }), /非官方/);
    assert.equal(attempts, 1);
    await assert.rejects(fs.stat(target), { code: "ENOENT" });
  }
});

test("GitHub rate limiting reports a retry time without starting a download", async () => {
  await assert.rejects(checkCodexUpdate(async () => new Response(null, {
    status: 403, headers: { "x-ratelimit-remaining": "0", "x-ratelimit-reset": String(Math.ceil(Date.now() / 1000) + 500) }
  })), /GitHub.*9 分钟/);
});
