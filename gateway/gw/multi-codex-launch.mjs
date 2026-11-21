import fs from "node:fs";
import path from "node:path";
import net from "node:net";
import http from "node:http";
import { randomBytes } from "node:crypto";
import { CFG, PREFIX, glibcEnv } from "./config.mjs";
import { enosysRun } from "./claude.mjs";
import { verifiedCodex } from "./multi-codex-install.mjs";

let tunnel;
// Android's resolver lives in bionic. Let the gateway resolve CONNECT destinations for
// the static musl client. The local tunnel requires a fresh per-process credential.
function androidTunnel() {
  if (tunnel) return tunnel;
  const secret = randomBytes(32).toString("hex");
  const auth = "Basic " + Buffer.from("codex:" + secret).toString("base64");
  const server = http.createServer((_req, res) => { res.writeHead(405); res.end(); });
  server.on("connect", (req, socket, first) => {
    if (req.headers["proxy-authorization"] !== auth) return socket.end("HTTP/1.1 407 Proxy Authentication Required\r\n\r\n");
    const match = /^([A-Za-z0-9.-]+|\[[\da-fA-F:]+\]):([0-9]{1,5})$/.exec(req.url || "");
    if (!match || +match[2] < 1 || +match[2] > 65535) return socket.destroy();
    const peer = net.connect(+match[2], match[1].replace(/^\[|\]$/g, ""), () => {
      socket.write("HTTP/1.1 200 Connection Established\r\n\r\n");
      if (first.length) peer.write(first);
      socket.pipe(peer).pipe(socket);
    });
    socket.on("error", () => peer.destroy());
    peer.on("error", () => socket.destroy());
    socket.on("close", () => peer.destroy());
    peer.on("close", () => socket.destroy());
  });
  tunnel = new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => { server.unref(); resolve("http://codex:" + secret + "@127.0.0.1:" + server.address().port); });
  }).catch(e => { tunnel = null; throw e; });
  return tunnel;
}

export async function codexOptions({ home = CFG.home, verifyBinary = verifiedCodex } = {}) {
  const binary = await verifyBinary();
  // An explicit CODEX_HOME must already exist when the official app-server starts.
  const profile = path.join(home, ".codex");
  const temp = path.join(profile, "tmp");
  await fs.promises.mkdir(temp, { recursive: true, mode: 0o700 });
  const env = glibcEnv();
  for (const key of ["LD_LIBRARY_PATH", "NODE_OPTIONS", "NODE_PATH", "OPENAI_API_KEY", "OPENAI_BASE_URL", "CODEX_API_KEY"]) delete env[key];
  env.HOME = home;
  env.CODEX_HOME = profile;
  env.TMPDIR = temp;
  if (PREFIX) {
    env.PATH = [path.join(PREFIX, "bin"), "/system/bin", env.PATH].filter(Boolean).join(":");
    env.SHELL = path.join(PREFIX, "bin", "bash");
    const ca = path.join(PREFIX, "etc", "tls", "cert.pem");
    if (fs.existsSync(ca)) env.SSL_CERT_FILE = ca;
    if (!env.HTTPS_PROXY && !env.https_proxy) env.HTTPS_PROXY = await androidTunnel();
    env.NO_PROXY = [env.NO_PROXY, "127.0.0.1", "localhost", "::1"].filter(Boolean).join(",");
  }
  const wrapper = enosysRun();
  return { command: wrapper || binary, args: wrapper ? ["-f", binary] : [], cwd: CFG.workspace, env };
}
