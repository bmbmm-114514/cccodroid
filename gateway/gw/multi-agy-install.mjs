// Google publishes the SHA-512 of each Android archive in its official platform manifest.
// This pin was verified against .../manifests/android_arm64.json (1.5.13).
import fs from "node:fs";
import { holdMultiActivity } from "./runs.mjs";
import path from "node:path";
import { randomUUID } from "node:crypto";
import { unpackAgyArchive } from "./multi-agy-update.mjs";
import { engineInstallation, agyBinary as binary, verifiedAgy } from "./multi-binary.mjs";
export { verifiedAgy };

import { agyRelease } from "./engine-releases.mjs";
export const agyVersion = agyRelease.version;
const url = agyRelease.archive;
const dir = path.dirname(binary);
let checking = null, installing = null, error = "";

export function agyInstallation() {
  return { ...engineInstallation("agy"), installing: !!installing, error };
}

export function unpackAgy(source, temp) {
  return unpackAgyArchive(source, temp, agyRelease);
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
  } finally { await fs.promises.rm(temp, { force: true }); }
}

async function checkBeforeDownload() {
  error = "";
  try {
    await fs.promises.access(await verifiedAgy(), fs.constants.X_OK);
    return;
  } catch {
    // A missing, damaged or unusable executable can be repaired from the pinned archive.
  }
  const release = holdMultiActivity();
  installing = download().catch(e => { error = e.message; }).finally(() => {
    installing = null;
    release();
  });
}

export async function startAgyInstall() {
  if (!installing) {
    checking ??= checkBeforeDownload().finally(() => { checking = null; });
    await checking;
  }
  return agyInstallation();
}
