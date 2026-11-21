import fs from "node:fs";
import path from "node:path";
import { randomUUID } from "node:crypto";
import { holdMultiActivity } from "./runs.mjs";
import { engineInstallation, codexBinary, verifiedCodex } from "./multi-binary.mjs";
import { codexRelease } from "./engine-releases.mjs";
import { stageCodexUpdate } from "./multi-codex-update.mjs";
export { codexBinary, verifiedCodex };
export { unpackCodex } from "./multi-codex-update.mjs";

const dir = path.dirname(codexBinary);
let checking = null, task = null, error = "";

export function installation() {
  return { ...engineInstallation("codex"), installing: !!task, error };
}

async function install() {
  await fs.promises.mkdir(dir, { mode: 0o700, recursive: true });
  const target = path.join(dir, randomUUID() + ".part");
  try {
    await stageCodexUpdate(codexRelease, target);
    await fs.promises.rename(target, codexBinary);
  } finally { await fs.promises.rm(target, { force: true }); }
}

async function checkBeforeDownload() {
  error = "";
  try {
    await fs.promises.access(await verifiedCodex(), fs.constants.X_OK);
    return;
  } catch {
    // Repair the bundled version only when no usable version is installed.
  }
  const release = holdMultiActivity();
  task = install().catch(e => { error = e.message; }).finally(() => { task = null; release(); });
}

export async function startInstall() {
  if (!task) {
    checking ??= checkBeforeDownload().finally(() => { checking = null; });
    await checking;
  }
  return installation();
}
