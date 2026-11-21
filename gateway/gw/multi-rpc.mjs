import { spawn } from "node:child_process";
import { createInterface } from "node:readline";

export function deferred() {
  let resolve, reject;
  const promise = new Promise((a, b) => { resolve = a; reject = b; });
  // A crash/early completion can arrive before the turn/start acknowledgement.
  promise.catch(() => {});
  return { promise, resolve, reject };
}

// stderr can include device codes, proxy credentials, prompts and full OAuth URLs.
// Only publish recognized causes, never the captured line or private filesystem paths.
const startupHints = [
  [/CODEX_HOME points to[^\r\n]*but that path does not exist/i, "CODEX_HOME 指向的目录不存在"],
  [/CODEX_HOME points to[^\r\n]*not (?:a )?directory/i, "CODEX_HOME 指向的路径不是目录"],
  [/read.only file system/i, "引擎写入配置或临时目录时遇到只读文件系统"],
  [/no space left on device/i, "引擎可用存储空间不足"],

  [/bad system call|seccomp.*(?:denied|failed)/i, "系统调用受到限制"],
  [/ptrace[^\n]*(?:operation not permitted|permission denied)/i, "系统拒绝启动兼容运行器"],
  [/permission denied/i, "系统拒绝了引擎的文件访问或执行权限"],
  [/cannot link executable|cannot locate symbol|exec format error|wrong elf class/i, "可执行文件或运行库与当前系统不兼容"],
  [/failed to (?:load|parse) (?:the )?(?:config|configuration)/i, "引擎配置无法加载"],
];

// Some RPC errors quote an upstream response body, including its sign-in data.
export function publicAgentError(value, fallback) {
  if (typeof value !== "string" || !value.trim()) return fallback;
  const secret = /(?:bearer|basic)\s+[a-z0-9+/._=-]+|(?:authorization|proxy-authorization|cookie|password|api[_-]?key|user[_-]?code|device[_-]?code|access[_-]?token|id[_-]?token|refresh[_-]?token|code_verifier|client_secret)["']?\s*[:=]|\bsk-[a-z0-9_-]{12,}|\beyJ[a-z0-9_-]+\.[a-z0-9_-]+\.|https?:\/\/[^\s?#]+[?#]|https?:\/\/[^\s/@]+@/i;
  if (secret.test(value)) return fallback + "（错误详情含认证数据，已隐藏）";
  return value.replace(/[\x00-\x08\x0b-\x1f\x7f]/g, "").slice(0, 1000);
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
    this.exitGrace = options.exitGraceMs ?? 750;
    this.stderrHint = "";
    this.stderrTail = "";
    const spawnProcess = options.spawnProcess || spawn;
    const env = { ...(options.env || process.env) };
    this.group = !options.spawnProcess && process.platform !== "win32";
    this.child = spawnProcess(options.command, options.args || [], {
      cwd: options.cwd, env, detached: this.group, stdio: ["pipe", "pipe", "pipe"],
    });
    live.add(this);
    const lines = createInterface({ input: this.child.stdout, crlfDelay: Infinity });
    this.child.stderr.setEncoding("utf8");
    this.child.stderr.on("data", (data) => {
      if (this.dead) return;
      const sample = this.stderrTail + data;
      this.stderrTail = sample.slice(-1024);
      const matched = startupHints.find(([pattern]) => pattern.test(sample));
      if (matched) this.stderrHint = matched[1];
    });
    this.child.stderr.on("error", () => {}); // stderr is optional diagnostic output.
    this.child.once("error", (e) => {
      const code = /^[A-Z][A-Z0-9_]{1,39}$/.test(e.code || "") ? e.code : "系统错误";
      this.close(new Error("启动 " + this.label + " 失败（" + code + "）"), false);
    });
    this.child.once("exit", (code, signal) => {
      this.exitCode = code;
      this.exitSignal = signal;
      this.waitForClose();
    });
    this.child.once("close", (code, signal) => {
      live.delete(this);
      this.endProcess(code ?? this.exitCode, signal || this.exitSignal, false);
    });
    const pipeEnded = () => this.waitForClose();
    this.child.stdin.on("error", pipeEnded);
    this.child.stdout.on("error", pipeEnded);
    lines.on("error", pipeEnded);
    lines.once("close", pipeEnded);
    lines.on("line", (line) => {
      if (this.dead || !line.trim()) return;
      let msg;
      try { msg = JSON.parse(line); } catch {
        // The parser's own error can include the offending payload verbatim.
        this.close(new Error(this.label + " 返回了无效的 JSON 协议消息"));
        return;
      }
      try {
        if (msg.method) return dispatch(this, msg);
        const waiter = this.pending.get(msg.id);
        if (!waiter) return;
        this.pending.delete(msg.id);
        clearTimeout(waiter.timer);
        if (msg.error) waiter.reject(new Error(publicAgentError(msg.error.message, this.label + " 请求失败")));
        else waiter.resolve(msg.result);
      } catch (e) { this.close(e); }
    });
  }

  // A pipe EOF or EPIPE can precede the process exit event (including a fatal startup
  // error). Node's child 'close' is after both exit and stdio drainage. Keep a bounded
  // fallback for processes that close stdout and keep running, or leave inherited pipes open.
  waitForClose() {
    if (this.dead || this.exitTimer) return;
    this.exitTimer = setTimeout(() => {
      const code = this.exitCode ?? this.child.exitCode;
      const signal = this.exitSignal || this.child.signalCode;
      this.endProcess(code, signal, code == null && !signal);
    }, this.exitGrace);
  }

  endProcess(code, signal, terminate) {
    let reason = this.label;
    if (signal) reason += " 进程退出（信号 " + signal + "）";
    else if (Number.isInteger(code)) reason += " 进程退出（退出码 " + code + "）";
    else reason += " 标准通信管道已关闭，引擎未报告退出状态";
    if (this.stderrHint) reason += "：" + this.stderrHint;
    this.close(new Error(reason), terminate);
  }

  send(msg) {
    if (this.dead) throw this.closeError;
    this.child.stdin.write(JSON.stringify(this.version ? { jsonrpc: this.version, ...msg } : msg) + "\n");
  }

  request(method, params, timeout = this.timeout) {
    if (this.dead) return Promise.reject(this.closeError);
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

  close(error = new Error(this.label + " 连接已关闭"), terminate = true) {
    if (this.dead) return;
    this.dead = true;
    this.closeError = error;
    this.stderrTail = "";
    clearTimeout(this.exitTimer);
    for (const waiter of this.pending.values()) {
      clearTimeout(waiter.timer);
      waiter.reject(error);
    }
    this.pending.clear();
    if (terminate) {
      this.kill("SIGTERM");
      const kill = setTimeout(() => {
        if (this.child.exitCode == null && !this.child.signalCode) this.kill("SIGKILL");
      }, 1500);
      kill.unref();
    }
    this.closed(this, error);
  }
}
