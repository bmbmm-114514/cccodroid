import { createHash } from "node:crypto";
import { createWriteStream } from "node:fs";
import { pipeline } from "node:stream/promises";
import { createGunzip } from "node:zlib";

// Published by Google's installer at https://antigravity.google/cli.
const manifest = "https://antigravity-cli-auto-updater-974169037036.us-central1.run.app/manifests/android_arm64.json";
const archivePattern = /^https:\/\/storage\.googleapis\.com\/antigravity-public\/antigravity-cli\/(\d+\.\d+\.\d+)-\d+\/android-arm\/cli_android_arm64\.tar\.gz$/;
const maxArchive = 256 * 1024 * 1024;
const maxExecutable = 768 * 1024 * 1024;

function validateRelease(version, archive, hash) {
  if (typeof version !== "string" || typeof archive !== "string" || archive.match(archivePattern)?.[1] !== version
      || typeof hash !== "string" || !/^[\da-f]{128}$/i.test(hash)) throw new Error("Antigravity 官方 Android 发行清单格式异常");
  return { version, archive, archiveSha512: hash.toLowerCase() };
}

async function fetchBody(url, timeout, fetcher) {
  const response = await fetcher(url, { redirect: "error", signal: AbortSignal.timeout(timeout) });
  if (!response.ok || !response.body) throw new Error(`获取 Antigravity 官方发行文件失败，HTTP ${response.status}`);
  return response.body;
}

export async function checkAgyUpdate(fetcher = fetch) {
  const body = await fetchBody(manifest, 30_000, fetcher);
  const chunks = [];
  let bytes = 0;
  for await (const chunk of body) {
    bytes += chunk.byteLength;
    if (bytes > 16 * 1024) throw new Error("Antigravity 官方发行清单过大");
    chunks.push(chunk);
  }
  const { version, url, sha512 } = JSON.parse(Buffer.concat(chunks).toString("utf8"));
  return validateRelease(version, url, sha512);
}

function executableSize(header) {
  const text = (start, end) => header.toString("ascii", start, end).replace(/[\0 ]+$/g, "");
  const sizeField = text(124, 136);
  const size = Number.parseInt(sizeField, 8);
  let checksum = 0;
  for (let i = 0; i < 512; i++) checksum += i >= 148 && i < 156 ? 32 : header[i];
  if (text(0, 100) !== "antigravity" || ![0, 48].includes(header[156]) || text(345, 500) !== ""
      || !/^[0-7]+$/.test(sizeField) || size < 64 || size > maxExecutable
      || Number.parseInt(text(148, 156), 8) !== checksum) throw new Error("Antigravity 压缩包格式异常");
  return size;
}

// Both bundled-version repair and a manual update use the same tar and checksum validation.
export async function unpackAgyArchive(source, temp, release) {
  const packed = createHash("sha512"), unpacked = createHash("sha256");
  const header = Buffer.alloc(512), elf = Buffer.alloc(20);
  let headerBytes = 0, archiveBytes = 0, bytes = 0, size = 0, padding = 0;
  await pipeline(source, async function* (input) {
    for await (const chunk of input) {
      archiveBytes += chunk.length;
      if (archiveBytes > (release.archiveBytes ?? maxArchive)) throw new Error("Antigravity 下载大小超过限额");
      packed.update(chunk);
      yield chunk;
    }
  }, createGunzip(), async function* (input) {
    for await (let chunk of input) {
      if (headerBytes < 512) {
        const n = chunk.copy(header, headerBytes, 0, 512 - headerBytes);
        headerBytes += n;
        chunk = chunk.subarray(n);
        if (headerBytes < 512) continue;
        size = executableSize(header);
        if (release.bytes && size !== release.bytes) throw new Error("Antigravity 文件大小与发行清单不符");
      }
      const payload = chunk.subarray(0, size - bytes);
      if (payload.length) {
        if (bytes < elf.length) payload.copy(elf, bytes, 0, elf.length - bytes);
        bytes += payload.length;
        unpacked.update(payload);
        yield payload;
      }
      const tail = chunk.subarray(payload.length);
      padding += tail.length;
      if (padding > 1024 * 1024 || tail.some(b => b !== 0)) throw new Error("Antigravity 压缩包格式异常");
    }
  }, createWriteStream(temp, { flags: "wx", mode: 0o700 }));
  const sha256 = unpacked.digest("hex");
  if (headerBytes !== 512 || bytes !== size || padding < (512 - size % 512) % 512 + 1024
      || elf.readUInt32BE(0) !== 0x7f454c46 || elf[4] !== 2 || elf[5] !== 1 || elf.readUInt16LE(18) !== 183
      || (release.archiveBytes && archiveBytes !== release.archiveBytes) || packed.digest("hex") !== release.archiveSha512
      || (release.sha256 && sha256 !== release.sha256)) throw new Error("Antigravity 官方发布校验失败");
  return { version: release.version, bytes, sha256 };
}

export async function stageAgyUpdate(release, tempPath, fetcher = fetch) {
  const official = validateRelease(release.version, release.archive, release.archiveSha512);
  const body = await fetchBody(official.archive, 15 * 60_000, fetcher);
  return unpackAgyArchive(body, tempPath, official);
}
