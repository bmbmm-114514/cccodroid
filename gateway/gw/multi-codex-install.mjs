import fs from "node:fs";
import { holdMultiActivity } from "./runs.mjs";
import path from "node:path";
import crypto from "node:crypto";
import { createGunzip } from "node:zlib";
import { pipeline } from "node:stream/promises";
import { CFG } from "./config.mjs";

import { codexRelease, codexArchive as url } from "./engine-releases.mjs";
const { asset, archiveBytes: archiveSize, archiveSha256: archiveHash, sha256: executableHash } = codexRelease;
const dir = path.join(CFG.home, ".local", "share", "multi-agent");
export const codexBinary = path.join(dir, "codex-app-server-" + codexRelease.version);

let task = null, error = "", checked = "";
export function installation() {
  return { installed: fs.existsSync(codexBinary), installing: !!task, error };
}
export async function verifiedCodex() {
  const st = await fs.promises.lstat(codexBinary);
  const key = [st.ino, st.size, st.ctimeMs].join("/");
  if (key !== checked) {
    const hash = crypto.createHash("sha256");
    if (!st.isFile()) throw new Error("Codex 引擎不是普通文件");
    for await (const chunk of fs.createReadStream(codexBinary)) hash.update(chunk);
    if (hash.digest("hex") !== executableHash) throw new Error("Codex 引擎校验失败，请重新安装");
    checked = key;
  }
  return codexBinary;
}

// The pinned OpenAI archive has a single regular file. Extract only its contents;
// names and paths from the archive are never used as filesystem destinations.
export async function unpackCodex(source, target) {
  const packed = crypto.createHash("sha256"), executable = crypto.createHash("sha256");
  let size = 0, remaining = null, header = Buffer.alloc(0);
  await pipeline(source, async function* (input) {
    for await (const chunk of input) {
      size += chunk.length;
      if (size > archiveSize) throw new Error("Codex 下载超出预期大小");
      packed.update(chunk);
      yield chunk;
    }
  }, createGunzip(), async function* (input) {
    for await (let chunk of input) {
      if (remaining === null) {
        header = Buffer.concat([header, chunk]);
        if (header.length < 512) continue;
        if (header.toString("ascii", 0, asset.length + 1) !== asset + "\0" || header[156] !== 48) throw new Error("Codex 归档格式不匹配");
        remaining = parseInt(header.toString("ascii", 124, 136), 8);
        if (!Number.isSafeInteger(remaining) || remaining < 1 || remaining > 400 * 1024 * 1024) throw new Error("Codex 文件大小不匹配");
        chunk = header.subarray(512);
        header = Buffer.alloc(0);
      }
      if (!remaining) continue;
      const content = chunk.subarray(0, remaining);
      remaining -= content.length;
      executable.update(content);
      yield content;
    }
  }, fs.createWriteStream(target, { flags: "wx", mode: 0o700 }));
  if (size !== archiveSize || remaining !== 0 || packed.digest("hex") !== archiveHash || executable.digest("hex") !== executableHash) {
    throw new Error("Codex 官方发布文件校验失败");
  }
}
async function install() {
  await fs.promises.mkdir(dir, { mode: 0o700, recursive: true });
  const target = path.join(dir, crypto.randomUUID() + ".part");
  try {
    const response = await fetch(url, { signal: AbortSignal.timeout(15 * 60_000) });
    if (!response.ok || !response.body) throw new Error("下载 Codex 失败，HTTP " + response.status);
    await unpackCodex(response.body, target);
    await fs.promises.rename(target, codexBinary);
    checked = "";
  } finally { await fs.promises.rm(target, { force: true }); }
}
export function startInstall() {
  if (!task) {
    const release = holdMultiActivity();
    error = "";
    task = install().catch(e => { error = e.message; }).finally(() => { task = null; release(); });
  }
}
