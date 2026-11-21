import fs from "node:fs";
import path from "node:path";
import { createHash } from "node:crypto";
import { CFG } from "./config.mjs";
import { codexRelease, agyRelease } from "./engine-releases.mjs";

const dir = path.join(CFG.home, ".local", "share", "multi-agent");
export const codexBinary = path.join(dir, "codex-" + codexRelease.version);
export const agyBinary = path.join(dir, "agy-" + agyRelease.version);
const checked = new Map();

async function verified(file, release, label) {
  const info = await fs.promises.lstat(file).catch(() => null);
  if (!info?.isFile() || info.size !== release.bytes) throw new Error("请安装或重新安装 " + label);
  const fingerprint = [info.ino, info.size, info.ctimeMs].join("/");
  if (checked.get(file) !== fingerprint) {
    const hash = createHash("sha256");
    for await (const part of fs.createReadStream(file)) hash.update(part);
    if (hash.digest("hex") !== release.sha256) throw new Error(label + " 校验失败，请重新安装");
    checked.set(file, fingerprint);
  }
  return file;
}

export const verifiedCodex = () => verified(codexBinary, codexRelease, "Codex CLI");
export const verifiedAgy = () => verified(agyBinary, agyRelease, "Antigravity CLI");
