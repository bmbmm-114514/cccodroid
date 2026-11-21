import fs from "node:fs/promises";
import path from "node:path";
import { randomUUID } from "node:crypto";
import { activeEngine, compareVersions, engineInstallation, invalidateEnginePins, stableVersion, updateDir, updatedFile } from "./multi-binary.mjs";
import { checkAgyUpdate, stageAgyUpdate } from "./multi-agy-update.mjs";
import { checkCodexUpdate, stageCodexUpdate } from "./multi-codex-update.mjs";
import { nativeRequest } from "./native-request.mjs";
import { holdMultiActivity } from "./runs.mjs";

const providers = new Map([
  ["codex", { check: checkCodexUpdate, stage: stageCodexUpdate }],
  ["agy", { check: checkAgyUpdate, stage: stageAgyUpdate }]
]);
const jobs = new Map();
const pendingFiles = new Set();
const ticketPattern = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/;
const problem = (message, status) => Object.assign(new Error(message), { status });

async function currentVersion(agent) {
  try { return (await activeEngine(agent)).version; }
  catch { return ""; }
}
function jobFor(agent) {
  if (!providers.has(agent)) throw problem("请选择要更新的 Agent", 400);
  if (!jobs.has(agent)) jobs.set(agent, { release: null, state: "idle", message: "" });
  return jobs.get(agent);
}
export async function engineUpdateStatus() {
  const items = await Promise.all([...providers.keys()].map(async agent => {
    const current = await currentVersion(agent);
    const { release, state, message } = jobFor(agent);
    const latest = release?.version || "";
    const checkedState = latest && compareVersions(latest, current || "0.0.0") > 0 ? "available" : "current";
    return { agent, current, latest, state: state === "checked" ? checkedState : state, message };
  }));
  return { items };
}

async function prepareDownloads() {
  await fs.mkdir(updateDir, { recursive: true, mode: 0o700 });
  // On process death a partial archive can be left behind. Only collect stale downloads.
  for (const entry of await fs.readdir(updateDir, { withFileTypes: true })) {
    if (!entry.isFile() || !entry.name.endsWith(".part") || !ticketPattern.test(entry.name.slice(0,-5))) continue;
    const file = path.join(updateDir, entry.name);
    const info = await fs.stat(file).catch(() => null);
    if (info && !pendingFiles.has(file) && Date.now() - info.mtimeMs > 30 * 60_000) await fs.rm(file, { force: true });
  }
}

async function install(agent, job, ticket) {
  const { release } = job;
  const version = release.version;
  const request = { agent, version, ticket };
  const authorization = await nativeRequest({ op: "engine_update_authorized", ...request });
  if (!authorization.ok) throw new Error(authorization.message || "请在设备的更新页重新选择要安装的版本");
  const current = await currentVersion(agent);
  if (compareVersions(version, current || engineInstallation(agent).version) <= 0) {
    if (current) return;
    throw new Error("请先修复 APK 自带的 Agent");
  }
  await prepareDownloads();
  const temp = path.join(updateDir, randomUUID() + ".part");
  pendingFiles.add(temp);
  try {
    const pin = await providers.get(agent).stage(release, temp);
    if (pin.version !== version) throw new Error("下载的版本与所选版本不一致");
    const file = updatedFile(agent, pin);
    const existed = await fs.lstat(file).then(() => true, error => {
      if (error.code === "ENOENT") return false;
      throw error;
    });
    await fs.rename(temp, file);
    const approval = await nativeRequest({ op: "engine_update_activate", ...request, bytes: pin.bytes, sha256: pin.sha256 });
    if (!approval.ok) {
      // A lost reply may follow a successful native commit. Preserve the candidate in that case.
      const records = await nativeRequest({ op: "engine_update_pins" });
      const saved = records.releases?.[agent];
      if (!records.ok || saved?.version !== version || saved?.sha256 !== pin.sha256) {
        if (records.ok && !existed) await fs.rm(file, { force: true });
        throw new Error(approval.message || "保存 Agent 版本失败");
      }
    }
    invalidateEnginePins();
    if ((await activeEngine(agent)).file !== file) throw new Error("已下载的 Agent 未能启用，请重试");
  } finally {
    pendingFiles.delete(temp);
    await fs.rm(temp, { force: true });
  }
}

export function requestEngineUpdate({ action, agent, version, ticket }) {
  const job = jobFor(agent);
  if (action !== "check" && action !== "install") throw problem("无效的更新操作", 400);
  if (action === "install" && (!job.release || version !== job.release.version || !ticketPattern.test(ticket || "")))
    throw problem("请先检查版本，再在设备上确认更新", 409);
  if (job.state === "installing" || job.state === "checking") {
    if ((action === "install") !== (job.state === "installing")) throw problem("此 Agent 正在执行更新操作", 409);
    return { ok: true };
  }
  job.state = action === "check" ? "checking" : "installing";
  job.message = "";
  const releaseActivity = holdMultiActivity();
  (async () => {
    if (action === "install") await install(agent, job, ticket);
    else {
      job.release = null;
      const release = await providers.get(agent).check();
      if (!stableVersion(release.version)) throw new Error("官方版本号无效");
      job.release = release;
    }
    job.state = "checked";
  })().catch(error => {
    job.state = "error";
    job.message = error.message;
  }).finally(releaseActivity);
  return { ok: true };
}
