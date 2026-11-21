import net from "node:net";
import { tz } from "./config.mjs";

// Also used by the standalone terminal launchers, which do not load the gateway services.
export function nativeRequest(req, timeoutMs = 20000) {
  return new Promise(resolve => {
    const socket = net.connect({ path: process.env.CCAPP_PRIV_SOCK || "/data/data/cc.agentui/files/.priv.sock" },
      () => socket.write(JSON.stringify(req) + "\n"));
    let buf = "";
    const timer = setTimeout(() => { socket.destroy(); resolve({ ok: false, message: tz("超时") }); }, timeoutMs);
    socket.on("data", part => { buf += part; });
    socket.on("end", () => {
      try { resolve(JSON.parse(buf)); }
      catch { resolve({ ok: false, message: buf.slice(0, 200) }); }
    });
    socket.on("error", error => resolve({ ok: false, state: "unreachable", message: error.message }));
    socket.on("close", () => clearTimeout(timer));
  });
}
