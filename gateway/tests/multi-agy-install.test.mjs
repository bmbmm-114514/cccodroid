import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { createHash } from "node:crypto";
import { agyRelease } from "../gw/engine-releases.mjs";

test("repeated install requests leave the verified executable untouched and report ready", async t => {
  const home = await fs.mkdtemp(path.join(os.tmpdir(), "cc-agy-install-"));
  const originalHome = process.env.CCAPP_HOME;
  process.env.CCAPP_HOME = home;
  const content = Buffer.from("pinned Antigravity test executable");
  const previous = { ...agyRelease };
  Object.assign(agyRelease, { bytes: content.length, sha256: createHash("sha256").update(content).digest("hex") });
  t.after(async () => {
    Object.assign(agyRelease, previous);
    if (originalHome === undefined) delete process.env.CCAPP_HOME;
    else process.env.CCAPP_HOME = originalHome;
    await fs.rm(home, { recursive: true, force: true });
  });
  const { agyBinary } = await import("../gw/multi-binary.mjs");
  const { startAgyInstall } = await import("../gw/multi-agy-install.mjs");
  await fs.mkdir(path.dirname(agyBinary), { recursive: true });
  await fs.writeFile(agyBinary, content, { mode: 0o700 });
  const fetch = t.mock.method(globalThis, "fetch", () => { throw new Error("unexpected download"); });
  const before = await fs.stat(agyBinary, { bigint: true });
  for (const state of await Promise.all([startAgyInstall(), startAgyInstall()])) {
    assert.equal(state.installed, true);
    assert.equal(state.installing, false);
    assert.equal(state.error, "");
  }
  const after = await fs.stat(agyBinary, { bigint: true });
  assert.equal(after.ino, before.ino);
  assert.equal(after.ctimeNs, before.ctimeNs);
  assert.equal(after.mtimeNs, before.mtimeNs);
  assert.equal(fetch.mock.callCount(), 0);
});
