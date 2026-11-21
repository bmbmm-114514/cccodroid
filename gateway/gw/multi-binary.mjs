import fs from "node:fs";
import path from "node:path";
import { createHash } from "node:crypto";
import { CFG, PREFIX } from "./config.mjs";
import { codexRelease, agyRelease } from "./engine-releases.mjs";
import { nativeRequest } from "./native-request.mjs";

const dir = path.join(CFG.home, ".local", "share", "multi-agent");
export const updateDir = path.join(dir, "updates");
export const codexBinary = path.join(dir, "codex-" + codexRelease.version);
export const agyBinary = path.join(dir, "agy-" + agyRelease.version);
const bundled = new Map([["codex", [codexBinary, codexRelease]], ["agy", [agyBinary, agyRelease]]]);
const checked = new Map(), resolved = new Map();
let receipts, confirmed = {}, readAt = 0;

export const stableVersion = value => typeof value === "string" && /^(?:0|[1-9]\d{0,8})(?:\.(?:0|[1-9]\d{0,8})){2}$/.test(value);
export function compareVersions(a, b) {
  const x = a.split(".").map(Number), y = b.split(".").map(Number);
  return x[0] - y[0] || x[1] - y[1] || x[2] - y[2];
}

export function updatedFile(agent, release) {
  if (!bundled.has(agent) || !stableVersion(release?.version) || !Number.isSafeInteger(release.bytes)
      || release.bytes < 64 || release.bytes > 2 ** 30 || !/^[a-f0-9]{64}$/.test(release.sha256)) throw new Error("无效的 Agent 版本记录");
  return path.join(updateDir, agent + "-" + release.version + "-" + release.sha256);
}

export function invalidateEnginePins() { receipts = null; }
async function pins() {
  if (!PREFIX || !fs.existsSync(updateDir)) return {};
  if (!receipts || Date.now() - readAt > 10000) {
    readAt = Date.now();
    receipts = nativeRequest({ op: "engine_update_pins" }, 2000).then(result => {
      if (result.ok) confirmed = result.releases || {};
      else readAt = 0;
      return confirmed;
    });
  }
  return receipts;
}

const fingerprint = s => [s.dev, s.ino, s.size, s.mtimeNs, s.ctimeNs].join("/");
async function verified(file, release) {
  const stat = await fs.promises.lstat(file, { bigint: true });
  if (!stat.isFile() || stat.size !== BigInt(release.bytes) || !(stat.mode & 0o100n)) throw new Error("Agent 文件缺失或无法执行");
  const key = fingerprint(stat) + "/" + release.sha256;
  if (checked.get(file)?.key !== key) {
    const promise = (async () => {
      const handle = await fs.promises.open(file, fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW);
      try {
        if (fingerprint(await handle.stat({ bigint: true })) !== fingerprint(stat)) throw new Error("Agent 文件已变更");
        const hash = createHash("sha256");
        for await (const chunk of handle.createReadStream({ autoClose: false })) hash.update(chunk);
        if (hash.digest("hex") !== release.sha256 || fingerprint(await fs.promises.lstat(file, { bigint: true })) !== fingerprint(stat))
          throw new Error("Agent 文件校验失败");
      } finally { await handle.close(); }
    })();
    checked.set(file, { key, promise });
  }
  await checked.get(file).promise;
  return { file, version: release.version, bytes: release.bytes, sha256: release.sha256 };
}

export function engineInstallation(agent) {
  const [base, release] = bundled.get(agent);
  const seen = resolved.get(agent);
  if (seen && fs.existsSync(seen.file)) return { installed: true, version: seen.version };
  return { installed: fs.existsSync(base), version: release.version };
}

export async function activeEngine(agent) {
  const [base, release] = bundled.get(agent);
  const pin = (await pins())?.[agent];
  let active;
  if (stableVersion(pin?.version) && compareVersions(pin.version, release.version) > 0) {
    try { active = await verified(updatedFile(agent, pin), pin); }
    catch { /* A damaged download can be repaired from Settings. The signed APK provides a fallback. */ }
  }
  if (!active) {
    try { active = await verified(base, release); }
    catch {
      resolved.delete(agent);
      throw new Error("请安装或修复 " + (agent === "codex" ? "Codex CLI" : "Antigravity CLI"));
    }
  }
  resolved.set(agent, active);
  return active;
}

export async function refreshInstalledEngines() {
  if (fs.existsSync(updateDir)) await Promise.allSettled([...bundled.keys()].map(activeEngine));
}

export const verifiedCodex = async () => (await activeEngine("codex")).file;
export const verifiedAgy = async () => (await activeEngine("agy")).file;
