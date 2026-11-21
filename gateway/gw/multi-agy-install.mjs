// Google publishes the SHA-512 of each Android archive in its official platform manifest.
// This pin was verified against .../manifests/android_arm64.json (1.5.13).
import fs from "node:fs";
import { holdMultiActivity } from "./runs.mjs";
import path from "node:path";
import { createHash, randomUUID } from "node:crypto";
import { pipeline } from "node:stream/promises";
import { createGunzip } from "node:zlib";
import { CFG } from "./config.mjs";

import { agyRelease } from "./engine-releases.mjs";
export const agyVersion = agyRelease.version;
const { archive: url, archiveBytes: size, archiveSha512: sha512, sha256: executableSha, bytes: binaryBytes } = agyRelease;
const dir = path.join(CFG.home, ".local", "share", "multi-agent");
const binary = path.join(dir, "agy-" + agyVersion);
let verification = "", installing = null, error = "";

export function agyInstallation() {
  return { installed: fs.existsSync(binary), installing: !!installing, version: agyVersion, error };
}

export async function verifiedAgy() {
  const info = await fs.promises.lstat(binary).catch(() => null);
  if (!info?.isFile() || info.size !== binaryBytes) throw new Error("请安装或重新安装 Antigravity CLI");
  const fingerprint = [info.ino, info.size, info.ctimeMs].join("/");
  if (fingerprint !== verification) {
    const hash = createHash("sha256");
    for await (const part of fs.createReadStream(binary)) hash.update(part);
    if (hash.digest("hex") !== executableSha) throw new Error("Antigravity CLI 校验失败，请重新安装");
    verification = fingerprint;
  }
  return binary;
}

export async function unpackAgy(source, temp) {
  const packed = createHash("sha512"), unpacked = createHash("sha256");
  let bytes = 0, remaining = binaryBytes, header = Buffer.alloc(0), parsed = false;
  await pipeline(source, async function* (input) {
    for await (const part of input) {
      bytes += part.length;
      if (bytes > size) throw new Error("Antigravity 下载大小超过发行清单");
      packed.update(part);
      yield part;
    }
  }, createGunzip(), async function* (input) {
    for await (let part of input) {
      if (!parsed) {
        header = Buffer.concat([header, part]);
        if (header.length < 512) continue;
        const name = header.toString("utf8", 0, 100).split("\0")[0];
        const declared = parseInt(header.toString("ascii", 124, 136).trim(), 8);
        if (name !== "antigravity" || header[156] !== 48 || declared !== binaryBytes) throw new Error("Antigravity 压缩包格式异常");
        part = header.subarray(512);
        header = Buffer.alloc(0);
        parsed = true;
      }
      const payload = part.subarray(0, remaining);
      remaining -= payload.length;
      if (payload.length) { unpacked.update(payload); yield payload; }
    }
  }, fs.createWriteStream(temp, { flags: "wx", mode: 0o700 }));
  if (remaining || bytes !== size || packed.digest("hex") !== sha512 || unpacked.digest("hex") !== executableSha) {
    throw new Error("Antigravity 官方发布校验失败");
  }
}

async function download() {
  if (process.arch !== "arm64") throw new Error("此 Antigravity 安装包仅支持 Android arm64");
  await fs.promises.mkdir(dir, { recursive: true, mode: 0o700 });
  const temp = path.join(dir, randomUUID() + ".part");
  try {
    const response = await fetch(url, { signal: AbortSignal.timeout(15 * 60_000) });
    if (!response.ok || !response.body) throw new Error("下载 Antigravity 失败，HTTP " + response.status);
    await unpackAgy(response.body, temp);
    await fs.promises.rename(temp, binary);
    verification = "";
  } finally { await fs.promises.rm(temp, { force: true }); }
}

export function startAgyInstall() {
  if (!installing) {
    const release = holdMultiActivity();
    error = "";
    installing = download().catch(e => { error = e.message; }).finally(() => { installing = null; release(); });
  }
  return agyInstallation();
}
