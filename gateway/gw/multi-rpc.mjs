import { spawn } from "node:child_process";
import { createInterface } from "node:readline";

export function deferred() {
  let resolve, reject;
  const promise = new Promise((a, b) => { resolve = a; reject = b; });
  // A crash/early completion can arrive before the turn/start acknowledgement.
  promise.catch(() => {});
  return { promise, resolve, reject };
}

const live = new Set();
export function shutdownAgentTransports() {
  for (const rpc of live) rpc.kill("SIGKILL");
}
export class AgentTransport {
  constructor(options, dispatch, closed) {
    this.pending = new Map();
    this.seq = 0;
    this.label = options.label;
    this.version = options.jsonrpc;
    this.dead = false;
    this.timeout = options.timeoutMs ?? 30000;
    this.closed = closed;
    const spawnProcess = options.spawnProcess || spawn;
    const command = options.command;
    const args = options.args || [];
    const env = { ...(options.env || process.env) };
    this.group = !options.spawnProcess && process.platform !== "win32";
    this.child = spawnProcess(command, args, { cwd: options.cwd, env, detached: this.group, stdio: ["pipe", "pipe", "pipe"] });
    live.add(this);
    this.child.once("close", () => live.delete(this));
    const lines = createInterface({ input: this.child.stdout, crlfDelay: Infinity });
    this.child.stderr.resume(); // Never forward CLI diagnostics carrying auth/login payloads to web clients.
    this.child.once("error", (e) => this.close(new Error("启动 " + this.label + " 失败：" + e.message)));
    this.child.once("exit", (code, signal) => this.close(new Error(this.label + " 已退出（" + (signal || code) + "）")));
    this.child.stdin.on("error", (e) => this.close(e));
    this.child.stdout.on("error", (e) => this.close(e));
    lines.on("error", (e) => this.close(e));
    lines.once("close", () => this.close(new Error(this.label + " 协议连接已关闭")));
    lines.on("line", (line) => {
      if (this.dead || !line.trim()) return;
      try {
        const msg = JSON.parse(line);
        if (msg.method) return dispatch(this, msg);
        const waiter = this.pending.get(msg.id);
        if (!waiter) return;
        this.pending.delete(msg.id);
        clearTimeout(waiter.timer);
        if (msg.error) waiter.reject(new Error(String(msg.error.message || this.label + " 请求失败")));
        else waiter.resolve(msg.result);
      } catch (e) { this.close(e); }
    });
  }

  send(msg) {
    if (this.dead) throw new Error(this.label + " 连接已关闭");
    this.child.stdin.write(JSON.stringify(this.version ? { jsonrpc: this.version, ...msg } : msg) + "\n");
  }

  request(method, params, timeout = this.timeout) {
    if (this.dead) return Promise.reject(new Error(this.label + " 连接已关闭"));
    const id = ++this.seq;
    const waiter = deferred();
    waiter.timer = setTimeout(() => this.close(new Error(this.label + " 请求超时：" + method)), timeout);
    this.pending.set(id, waiter);
    try { this.send({ id, method, params }); } catch (e) { this.close(e); }
    return waiter.promise;
  }

  kill(signal) {
    if (this.group && this.child.pid) {
      try { process.kill(-this.child.pid, signal); return; } catch {}
    }
    this.child.kill(signal);
  }

  close(error = new Error(this.label + " 连接已关闭")) {
    if (this.dead) return;
    this.dead = true;
    for (const waiter of this.pending.values()) {
      clearTimeout(waiter.timer);
      waiter.reject(error);
    }
    this.pending.clear();
    this.kill("SIGTERM");
    // An unresponsive server may still own an executing turn. Ensure it leaves.
    const kill = setTimeout(() => {
      if (this.child.exitCode == null && !this.child.signalCode) this.kill("SIGKILL");
    }, 1500);
    kill.unref();
    this.closed(this, error);
  }
}
