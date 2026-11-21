import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import zlib from "node:zlib";
import { CFG } from "../gw/config.mjs";
import { relocatePrefix } from "../gw/prefix-relocation.mjs";

test("variant rehomes binaries, packed JMOD entries, paths, links and their audited hashes", async t => {
  const temp = fs.mkdtempSync(path.join(CFG.workspace, "multi-devkit-"));
  t.after(() => fs.rmSync(temp, { recursive: true, force: true }));
  const root = path.join(temp, "stage");
  const ppm = "libexec/devkit";
  const prefix = "/data/data/cc.codroid/";
  const expected = "/data/data/cc.agentui/";
  fs.mkdirSync(path.join(root, ppm), { recursive: true });
  const binary = Buffer.alloc(5 << 20);
  binary.write(prefix, (1 << 20) - 3);
  binary.write(prefix, binary.length - prefix.length);
  const zip = Buffer.from("UEsDBBQAAAAIALhldV2Nq1mQIQAAACMAAAAIAAAAZGlyL3Rvb2zTT0ksSdQHE8nJesn5KUX5mSn6aZk5qcX6pcVF+jmZSQBQSwECFAMUAAAACAC4ZXVdjatZkCEAAAAjAAAACAAAAAAAAAAAAAAAgAEAAAAAZGlyL3Rvb2xQSwUGAAAAAAEAAQA2AAAARwAAAAAA","base64");
  const jmod = Buffer.concat([Buffer.from("JM\x01\x00"), zip]);
  const sha = b => crypto.createHash("sha256").update(b).digest("hex");
  fs.writeFileSync(path.join(root,"tool"), binary, { mode: 0o500 });
  fs.linkSync(path.join(root,"tool"), path.join(root,"same-tool"));
  fs.writeFileSync(path.join(root,"sdk.jmod"),jmod);
  fs.symlinkSync(prefix+"files/usr/bin/git",path.join(root,"git"));
  const rel = "share/auto-load/data/data/cc.codroid/symbol";
  fs.mkdirSync(path.dirname(path.join(root, rel)), { recursive: true });
  fs.writeFileSync(path.join(root, rel), "symbol");
  const manifest = path.join(root,ppm,"MANIFEST");
  fs.writeFileSync(manifest, [[binary,"tool"],[binary,"same-tool"],[jmod,"sdk.jmod"],[Buffer.from("symbol"),rel]]
    .map(([raw,name]) => sha(raw)+" "+raw.length+" "+name).join("\n")+"\n");
  await relocatePrefix(root,"cc.codroid","cc.agentui");
  assert.equal(fs.readlinkSync(path.join(root,"git")), expected+"files/usr/bin/git");
  assert.equal(fs.statSync(path.join(root,"tool")).mode & 0o777, 0o500);
  const patched = fs.readFileSync(path.join(root,"tool"));
  assert.equal(patched.indexOf(expected), (1<<20)-3);
  assert.equal(patched.lastIndexOf(expected), patched.length-expected.length);
  const jar = fs.readFileSync(path.join(root,"sdk.jmod"));
  assert.equal(zlib.inflateRawSync(jar.subarray(4+30+8,4+30+8+jar.readUInt32LE(4+18))).toString(),expected+"files/usr/lib");
  for (const line of fs.readFileSync(manifest,"utf8").trim().split("\n")) {
    const [,hash,len,item] = /^(\w+) (\d+) (.+)$/.exec(line);
    const b = fs.readFileSync(path.join(root,item));
    assert.equal(sha(b),hash,item);
    assert.equal(b.length,+len,item);
  }
  assert.equal(fs.readFileSync(path.join(root,rel.replace("cc.codroid","cc.agentui")),"utf8"),"symbol");
});
