import fs from "node:fs/promises";
import { createHash } from "node:crypto";
import { pipeline } from "node:stream/promises";
import { createGunzip } from "node:zlib";
import { codexRelease } from "./engine-releases.mjs";

export const codexUpdateSource = "https://api.github.com/repos/openai/codex/releases/latest";
const asset = codexRelease.asset;
const stableVersion = /^(?:0|[1-9]\d{0,8})(?:\.(?:0|[1-9]\d{0,8})){2}$/;
const maxArchiveBytes = 512 * 1024 * 1024;
const maxExecutableBytes = 1024 * 1024 * 1024;
const archiveUrl = version => `https://github.com/openai/codex/releases/download/rust-v${version}/${asset}.tar.gz`;

function validateRelease(release) {
  if (typeof release?.version !== "string" || !stableVersion.test(release.version) || !Number.isSafeInteger(release.archiveBytes) ||
      release.archiveBytes < 1 || release.archiveBytes > maxArchiveBytes ||
      !/^[a-f0-9]{64}$/.test(release.archiveSha256)) {
    throw new Error("Codex 官方发布缺少有效的版本、大小或 SHA-256");
  }
}

export async function checkCodexUpdate(fetcher = fetch) {
  const response = await fetcher(codexUpdateSource, {
    headers: { Accept: "application/vnd.github+json", "User-Agent": "cccodroid" },
    signal: AbortSignal.timeout(30_000), redirect: "error"
  });
  if (!response.ok || !response.body) {
    await response.body?.cancel();
    if (response.status === 429 || (response.status === 403 && response.headers.get("x-ratelimit-remaining") === "0")) {
      const wait = Math.ceil((Number(response.headers.get("x-ratelimit-reset")) * 1000 - Date.now()) / 60_000);
      throw new Error("GitHub 版本查询次数已达上限，" + (wait > 0 && wait <= 1440 ? "请在 " + wait + " 分钟后重试" : "请稍后重试"));
    }
    throw new Error("查询 Codex 官方版本失败，HTTP " + response.status);
  }
  const chunks = [];
  let size = 0;
  for await (const chunk of response.body) {
    size += chunk.length;
    if (size > 4 * 1024 * 1024) throw new Error("Codex 官方发布信息过大");
    chunks.push(chunk);
  }
  const data = JSON.parse(Buffer.concat(chunks).toString("utf8"));
  const tag = data.tag_name;
  const version = typeof tag === "string" && tag.startsWith("rust-v") ? tag.slice(6) : "";
  const archive = Array.isArray(data.assets) && data.assets.find(item => item.name === asset + ".tar.gz");
  if (data.draft !== false || data.prerelease !== false || !archive ||
      archive.browser_download_url !== archiveUrl(version) || typeof archive.digest !== "string" || !archive.digest.startsWith("sha256:")) {
    throw new Error("Codex 官方稳定版缺少可验证的 ARM64 发布包");
  }
  const release = { version, archiveBytes: archive.size, archiveSha256: archive.digest.slice(7) };
  validateRelease(release);
  return release;
}

// The official musl archive is one regular file. Never use archive paths as destinations.
export async function unpackCodex(source, target, release = codexRelease) {
  validateRelease(release);
  const packed = createHash("sha256"), executable = createHash("sha256");
  const header = Buffer.alloc(512), elf = Buffer.alloc(20);
  let size = 0, headerUsed = 0, bytes = 0, remaining = 0, padding = 0;
  const output = await fs.open(target, "wx", 0o700);
  try {
    await pipeline(source, async function* (input) {
      for await (const chunk of input) {
        size += chunk.length;
        if (size > release.archiveBytes) throw new Error("Codex 下载超出预期大小");
        packed.update(chunk);
        yield chunk;
      }
    }, createGunzip(), async function* (input) {
      for await (let chunk of input) {
        if (headerUsed < 512) {
          const take = Math.min(chunk.length, 512 - headerUsed);
          header.set(chunk.subarray(0, take), headerUsed);
          headerUsed += take;
          chunk = chunk.subarray(take);
          if (headerUsed < 512) continue;
          const name = header.toString("ascii", 0, 100).replace(/\0+$/, "");
          const octal = header.toString("ascii", 124, 136).replace(/[\0 ]+$/, "");
          let checksum = 0;
          for (let i = 0; i < header.length; ++i) checksum += i >= 148 && i < 156 ? 32 : header[i];
          if (name !== asset || header[156] !== 48 || header[345] !== 0 || !/^[0-7]+$/.test(octal) ||
              parseInt(header.toString("ascii", 148, 156), 8) !== checksum) {
            throw new Error("Codex 归档格式不匹配");
          }
          bytes = remaining = parseInt(octal, 8);
          if (bytes < 64 || bytes > maxExecutableBytes || (release.bytes !== undefined && bytes !== release.bytes)) {
            throw new Error("Codex 文件大小不匹配");
          }
        }
        const payload = chunk.subarray(0, remaining);
        if (payload.length) {
          const offset = bytes - remaining;
          if (offset < elf.length) elf.set(payload.subarray(0, elf.length - offset), offset);
          remaining -= payload.length;
          executable.update(payload);
          yield payload;
        }
        const tail = chunk.subarray(payload.length);
        padding += tail.length;
        if (padding > 1024 * 1024 || tail.some(byte => byte !== 0)) throw new Error("Codex 归档有额外内容");
      }
    }, output.createWriteStream());
    const sha256 = executable.digest("hex");
    if (!bytes || remaining || padding < (512 - bytes % 512) % 512 + 1024 ||
        elf.readUInt32BE(0) !== 0x7f454c46 || elf[4] !== 2 || elf[5] !== 1 || elf.readUInt16LE(18) !== 183 ||
        size !== release.archiveBytes || packed.digest("hex") !== release.archiveSha256 ||
        (release.sha256 !== undefined && sha256 !== release.sha256)) {
      throw new Error("Codex 官方发布文件校验失败");
    }
    return { version: release.version, bytes, sha256 };
  } catch (error) {
    await output.close();
    await fs.rm(target, { force: true });
    throw error;
  }
}

export async function stageCodexUpdate(release, tempPath, fetcher = fetch) {
  validateRelease(release);
  let url = archiveUrl(release.version);
  const signal = AbortSignal.timeout(15 * 60_000);
  for (let count = 0; count < 5; count++) {
    const response = await fetcher(url, { signal, redirect: "manual" });
    if (response.ok && response.body) return unpackCodex(response.body, tempPath, release);
    const location = response.headers.get("location");
    await response.body?.cancel();
    if (![301, 302, 303, 307, 308].includes(response.status) || !location) {
      throw new Error("下载 Codex 失败，HTTP " + response.status);
    }
    const next = new URL(location, url);
    if (next.protocol !== "https:" || next.username || next.password || next.port ||
        (next.hostname !== "github.com" && !next.hostname.endsWith(".githubusercontent.com"))) {
      throw new Error("Codex 下载跳转到了非官方地址");
    }
    url = next.href;
  }
  throw new Error("Codex 官方下载重定向次数过多");
}
