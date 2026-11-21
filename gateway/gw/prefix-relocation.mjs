import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import zlib from "node:zlib";

// ── 压缩归档内部也要改写 ──────────────────────────────────────────────
// 上面的 swap 是等长字节替换，对 flat 文件有效：ELF 的 RUNPATH、未压缩的
// jimage（JDK 的 lib/modules）都是这么改对的。
// 但 zip 类归档（本工具包里就是 JDK 的 jmods/*.jmod）条目是 deflate 压缩的，
// 字节流里找不到那个模式，于是被整个跳过 —— 归档里仍留着 /data/data/com.termux/。
//
// 后果（实测，红魔 9 Pro / Android 14）：jlink 从 jmod 里拷出启动器和本地库，
// 把未改写的 RUNPATH 带进产出，镜像一启动就
//     CANNOT LINK EXECUTABLE: library "libz.so.1" not found
// 而 jlink 自己退出码是 0。68 个 jmod 里 31 个中招，jpackage 直接不可用。
//
// 装回时必须严格保持条目顺序与压缩方式：实测 jlink 会拒绝重排过的 jmod
// （报 "Resources tree, invalid data structure"），所以这里全程按中央目录的顺序重建。
const SIG_LOC = 0x04034b50, SIG_CEN = 0x02014b50, SIG_EOCD = 0x06054b50;
const ZIP_EXT = [".jmod", ".jar", ".zip", ".whl", ".egg", ".aar", ".apk"];
export const looksLikeZip = (name) => ZIP_EXT.some((e) => name.toLowerCase().endsWith(e));

const CRC_TABLE = (() => {
  const t = new Int32Array(256);
  for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; t[n] = c; }
  return t;
})();
function crc32(buf) {
  let c = -1;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ -1) >>> 0;
}

/** 有的归档（jmod）在 zip 数据前带自定义头，探测 zip 实际起点 */
function zipBase(buf) {
  if (buf.length >= 4 && buf.readUInt32LE(0) === SIG_LOC) return 0;
  for (let o = 4; o <= 8 && o + 4 <= buf.length; o += 4)
    if (buf.readUInt32LE(o) === SIG_LOC) return o;
  return -1;
}

/** 对 zip 类归档做前缀改写：条目名、以及解压后的内容各跑一遍 swap。非 zip / zip64 / 结构异常的原样返回 */
export function rewriteZip(buf, swap) {
  const base = zipBase(buf);
  if (base < 0) return [buf, 0];

  let eocd = -1;                                            // 从尾部找 EOCD（注释最长 65535）
  for (let i = buf.length - 22; i >= base && i >= buf.length - 22 - 65535; i--)
    if (buf.readUInt32LE(i) === SIG_EOCD) { eocd = i; break; }
  if (eocd < 0) return [buf, 0];

  const nent = buf.readUInt16LE(eocd + 10);
  const cdOff = buf.readUInt32LE(eocd + 16) + base;
  if (nent === 0xffff || cdOff === 0xffffffff) return [buf, 0];   // zip64：本工具包用不到

  let hits = 0, pos = base, p = cdOff;
  const locals = [], cens = [];
  for (let i = 0; i < nent; i++) {
    if (p + 46 > buf.length || buf.readUInt32LE(p) !== SIG_CEN) return [buf, 0];
    const cen = Buffer.from(buf.subarray(p, p + 46));
    const nlen = cen.readUInt16LE(28), elen = cen.readUInt16LE(30), clen = cen.readUInt16LE(32);
    const name = Buffer.from(buf.subarray(p + 46, p + 46 + nlen));
    const extra = Buffer.from(buf.subarray(p + 46 + nlen, p + 46 + nlen + elen));
    const comment = Buffer.from(buf.subarray(p + 46 + nlen + elen, p + 46 + nlen + elen + clen));
    const method = cen.readUInt16LE(10), csize = cen.readUInt32LE(20);
    const lo = cen.readUInt32LE(42) + base;
    p += 46 + nlen + elen + clen;

    if (lo + 30 > buf.length || buf.readUInt32LE(lo) !== SIG_LOC) return [buf, 0];
    const lnlen = buf.readUInt16LE(lo + 26), lelen = buf.readUInt16LE(lo + 28);
    const lhOut = Buffer.from(buf.subarray(lo, lo + 30));
    // 本地头有自己的名字与 extra（jar 首条常带 0xCAFE extra），长度未必与中央目录相同，必须原样带上
    const lname = Buffer.from(buf.subarray(lo + 30, lo + 30 + lnlen));
    const lextra = buf.subarray(lo + 30 + lnlen, lo + 30 + lnlen + lelen);

    const [nameOut, kn] = swap(name);                       // 条目名（等长，长度不变）
    const [lnameOut] = swap(lname);
    hits += kn;

    let data = Buffer.from(buf.subarray(lo + 30 + lnlen + lelen, lo + 30 + lnlen + lelen + csize));
    let usize = cen.readUInt32LE(24), crc = cen.readUInt32LE(16);
    if (method === 8) {                                     // deflate：解开 → 改 → 压回
      let raw;
      try { raw = zlib.inflateRawSync(data); } catch { return [buf, 0]; }
      const [dec, k] = swap(raw);
      if (k) {
        hits += k;
        data = zlib.deflateRawSync(dec, { level: 9 });
        usize = dec.length; crc = crc32(dec);
        cen.writeUInt32LE(data.length, 20); cen.writeUInt32LE(usize, 24); cen.writeUInt32LE(crc, 16);
      }
    } else if (method === 0) {                              // stored：明文，直接换
      const [d, k] = swap(data);
      if (k) { hits += k; data = d; crc = crc32(d); cen.writeUInt32LE(crc, 16); }
    } else {
      return [buf, 0];                                      // 不认识的压缩方式，别乱动
    }

    // 大小与 CRC 已写进本地头，数据描述符不再搬运 —— 清掉标志位 3，否则流式读取会去找它
    lhOut.writeUInt16LE(lhOut.readUInt16LE(6) & ~8, 6); cen.writeUInt16LE(cen.readUInt16LE(8) & ~8, 8);
    cen.writeUInt32LE(pos - base, 42);                      // 新的本地头偏移（与原档一样相对 zip 起点）
    lhOut.writeUInt32LE(data.length, 18); lhOut.writeUInt32LE(usize, 22); lhOut.writeUInt32LE(crc, 14);
    locals.push(lhOut, lnameOut, lextra, data);
    cens.push(cen, nameOut, extra, comment);
    pos += 30 + lnlen + lelen + data.length;
  }

  // 一处没命中就原档返回：重建会丢掉数据描述符等细节，签名过的 jar / 校验和也就不再逐字节一致
  if (!hits) return [buf, 0];
  const cdBuf = Buffer.concat(cens);
  const eocdOut = Buffer.from(buf.subarray(eocd, eocd + 22));
  eocdOut.writeUInt32LE(cdBuf.length, 12);
  eocdOut.writeUInt32LE(pos - base, 16);
  return [Buffer.concat([buf.subarray(0, base), ...locals, cdBuf, eocdOut, buf.subarray(eocd + 22)]), hits];
}

// Run only on an already verified, private staging tree before its installation.
// lstat for every entry: a package symlink may legitimately point outside the tree.
export async function relocatePrefix(root, from, to) {
  if (from === to) return;
  if (![from, to].every(id => /^[a-z][a-z0-9.]{9}$/.test(id))) throw new Error("invalid devkit package identity");
  const old = Buffer.from("/data/data/" + from + "/");
  const next = Buffer.from("/data/data/" + to + "/");
  const changed = new Map(), inodes = new Map(), manifests = [];
  function replacePath(name) {
    const dir = "data/data/" + from;
    return name.split("/").map((part, i, parts) => part === from && parts.slice(Math.max(0, i - 2), i + 1).join("/") === dir ? to : part).join("/");
  }
  function swap(bytes) {
    let count = 0;
    for (let i = bytes.indexOf(old); i !== -1; i = bytes.indexOf(old, i + old.length)) {
      next.copy(bytes, i);
      count++;
    }
    return [bytes, count];
  }
  const digest = bytes => crypto.createHash("sha256").update(bytes).digest("hex");
  // Large ELF files (LLVM, Node) exceed some Android heaps. Only keep one block
  // and its prefix overlap in memory, writing at the same offsets.
  async function relocateLarge(file, length) {
    const handle = await fs.promises.open(file, "r+");
    const hash = crypto.createHash("sha256");
    const chunk = Buffer.allocUnsafe((1 << 20) + old.length - 1);
    let pos = 0, changedBytes = false;
    try {
      while (pos < length) {
        const size = Math.min(chunk.length, length - pos);
        let n = 0;
        while (n < size) {
          const part = await handle.read(chunk, n, size - n, pos + n);
          if (!part.bytesRead) throw new Error("Truncated devkit file: " + file);
          n += part.bytesRead;
        }
        const buf = chunk.subarray(0, size);
        let advance = pos + size === length ? size : size - old.length + 1;
        for (let i = buf.indexOf(old); i !== -1 && i < advance; i = buf.indexOf(old, i + old.length)) {
          next.copy(buf, i);
          let written = 0;
          while (written < next.length) {
            const result = await handle.write(next, written, next.length - written, pos + i + written);
            if (!result.bytesWritten) throw new Error("Cannot relocate " + file);
            written += result.bytesWritten;
          }
          advance = Math.max(advance, i + old.length);
          changedBytes = true;
        }
        hash.update(buf.subarray(0, advance));
        pos += advance;
      }
    } finally { await handle.close(); }
    return changedBytes ? [hash.digest("hex"), length] : null;
  }
  async function walk(rel) {
    const here = path.join(root, rel);
    for (const ent of await fs.promises.readdir(here, { withFileTypes: true })) {
      const before = path.join(rel, ent.name), name = replacePath(before);
      let file = path.join(root, before);
      if (name !== before) {
        const target = path.join(root, name);
        await fs.promises.mkdir(path.dirname(target), { recursive: true });
        await fs.promises.rename(file, target);
        file = target;
      }
      if (ent.isSymbolicLink()) {
        const value = await fs.promises.readlink(file), valueNew = replacePath(value);
        if (value !== valueNew) { await fs.promises.unlink(file); await fs.promises.symlink(valueNew, file); }
      } else if (ent.isDirectory()) {
        await walk(name);
      } else if (ent.isFile()) {
        if (/^libexec\/devkit\/(?:.*\/)?MANIFEST$/.test(name)) { manifests.push(file); continue; }
        const st = await fs.promises.stat(file);
        const inode = st.dev + ":" + st.ino;
        if (inodes.has(inode)) { changed.set(name, inodes.get(inode)); continue; }
        const mode = st.mode & 0o7777;
        if (!(mode & 0o200)) await fs.promises.chmod(file, mode | 0o200);
        let entry;
        try {
          if (st.size > (4 << 20) && !looksLikeZip(name)) entry = await relocateLarge(file, st.size);
          else {
            const bytes = await fs.promises.readFile(file);
            const [updated, count] = looksLikeZip(name) ? rewriteZip(bytes, swap) : swap(bytes);
            if (count) {
              await fs.promises.writeFile(file, updated);
              entry = [digest(updated), updated.length];
            }
          }
          if (entry) {
            changed.set(name, entry);
            if (st.nlink > 1) inodes.set(inode, entry);
            await fs.promises.utimes(file, st.atime, st.mtime);
          }
        } finally { if (!(mode & 0o200)) await fs.promises.chmod(file, mode); }
      }
    }
  }
  await walk("");
  for (const file of manifests) {
    const lines = (await fs.promises.readFile(file, "utf8")).split("\n");
    const mapped = lines.map(line => {
      if (!line) return line;
      const m = /^([a-f0-9]{64}) (\d+) (.+)$/.exec(line);
      if (!m) throw new Error("invalid devkit manifest: " + file);
      const rel = replacePath(m[3]), entry = changed.get(rel);
      return (entry ? entry[0] + " " + entry[1] : m[1] + " " + m[2]) + " " + rel;
    });
    if (mapped.some((line, n) => lines[n] !== line)) {
      const st = await fs.promises.stat(file), mode = st.mode & 0o7777;
      if (!(mode & 0o200)) await fs.promises.chmod(file, mode | 0o200);
      try { await fs.promises.writeFile(file, mapped.join("\n")); }
      finally { if (!(mode & 0o200)) await fs.promises.chmod(file, mode); }
    }
  }
}

// The Android host supplies the source package from its pinned devkit manifest.
export function relocateAddon(root) {
  const from = process.env.CCAPP_DEVKIT_SOURCE_PACKAGE;
  const to = process.env.CCAPP_ANDROID_PACKAGE;
  return from && to && from !== to ? relocatePrefix(root, from, to) : Promise.resolve();
}
