// Prepare the offline first-run assets. Every large input is pinned before it enters the APK.
import fs from "node:fs";
import path from "node:path";
import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import { createGzip } from "node:zlib";
import { pipeline } from "node:stream/promises";
import { verifyDetached, ANTHROPIC_KEYS } from "../devkit/pgp.mjs";

import { codexRelease, codexArchive, agyRelease } from "../gateway/gw/engine-releases.mjs";

const [cache, out, pinFile] = process.argv.slice(2);
fs.mkdirSync(cache, { recursive: true });
fs.mkdirSync(out, { recursive: true });
const pin = JSON.parse(fs.readFileSync(pinFile));
async function digest(file, algorithm) {
  const h = createHash(algorithm);
  for await (const data of fs.createReadStream(file)) h.update(data);
  return h.digest("hex");
}
async function obtain(name, url, hash, algorithm = "sha256") {
  const file = path.join(cache, name);
  if (fs.existsSync(file) && (!hash || await digest(file, algorithm) === hash)) return file;
  const part = file + ".part-" + process.pid;
  try {
    execFileSync("curl", ["-fsSL", "--retry", "3", "--connect-timeout", "20", "-o", part, url], { stdio: "inherit" });
    if (hash && await digest(part, algorithm) !== hash) throw Error("Checksum mismatch for " + name);
    fs.renameSync(part, file);
  } finally { fs.rmSync(part, {force:true}); }
  return file;
}
const claudeVersion = "2.1.308", cb = "https://downloads.claude.ai/claude-code-releases/" + claudeVersion + "/";
const manifest = await obtain("claude-" + claudeVersion + ".manifest.json", cb + "manifest.json");
const signature = await obtain("claude-" + claudeVersion + ".manifest.json.sig", cb + "manifest.json.sig");
const raw = fs.readFileSync(manifest);
verifyDetached(raw, fs.readFileSync(signature, "utf8"), ANTHROPIC_KEYS);
const release = JSON.parse(raw), claude = release.platforms["linux-arm64"];
if (release.version !== claudeVersion || claude.checksum !== "fdd22e6cbb734d580f5664c48df4294eeec850218ff07f186f9af30426396ee1") throw Error("Wrong Claude release");
const binary = await obtain("claude-" + claudeVersion + ".bin", cb + "linux-arm64/claude", claude.checksum);
fs.copyFileSync(manifest, path.join(out, "manifest.json"));
fs.copyFileSync(signature, path.join(out, "manifest.json.sig"));
await pipeline(fs.createReadStream(binary), createGzip({level:4}), fs.createWriteStream(path.join(out, "claude.gz")));
const catalog = await obtain("modules-" + pin.tag + ".json", pin.base + pin.modules.file, pin.modules.sha256);
const modules = JSON.parse(fs.readFileSync(catalog));
const core = modules.modules.find(m => m.id === "base").gz;
if (modules.modules.some(m => m.required && m.id !== "base")) throw Error("Bundle is missing a required runtime module");
fs.copyFileSync(catalog, path.join(out, "modules.json"));
const files = [
  [core.file, pin.base + core.file, core.sha256, "sha256", "base.tar.gz"],
  [codexRelease.asset + ".tar.gz", codexArchive, codexRelease.archiveSha256, "sha256", "codex.tar.gz"],
  ["agy-" + agyRelease.version + "-android.tar.gz", agyRelease.archive, agyRelease.archiveSha512, "sha512", "agy.tar.gz"]
];
for (const [name, url, hash, algorithm, asset] of files) {
  fs.copyFileSync(await obtain(name, url, hash, algorithm), path.join(out, asset));
}
fs.writeFileSync(path.join(out,"engines.json"), JSON.stringify({
  claude: {version:claudeVersion, bytes:claude.size, sha256:claude.checksum},
  codex: {version:codexRelease.version, bytes:codexRelease.bytes, sha256:codexRelease.sha256},
  agy: {version:agyRelease.version, bytes:agyRelease.bytes, sha256:agyRelease.sha256}
})+"\n");
