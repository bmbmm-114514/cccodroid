import { personalEnv } from "./multi-agy-launch.mjs";
// Native Google Antigravity CLI (ACP v1). Personal OAuth is completed by agy itself.
import fs from "node:fs";
import path from "node:path";
import { randomUUID } from "node:crypto";
import { spawn } from "node:child_process";
import { CFG } from "./config.mjs";
import { AgentTransport } from "./multi-rpc.mjs";
import { holdMultiActivity } from "./runs.mjs";
import { agyInstallation, startAgyInstall, verifiedAgy } from "./multi-agy-install.mjs";

// ACP and the official /usage command must resolve the same private Google profile.
const profile = path.join(CFG.home, ".gemini");
const busy = new Set();
const approvals = new Map();
let currentLogin, confirmed = null, accountRevision = 0, usageRevision = 0;

function authUrl(text) {
  for (const candidate of text.match(/https:\/\/[^\s<>"'\x1b]+/g) || []) {
    try {
      const url = new URL(candidate.replace(/[)\].,;]+$/, ""));
      if (!url.username && !url.password && !url.port && (url.hostname === "accounts.google.com" ||
          (url.hostname === "antigravity.google" && /^\/(auth|login|signin|callback)(\/|$)/.test(url.pathname)))) return url.href;
    } catch {}
  }
  return "";
}

function loginStatus() {
  const l = currentLogin;
  if (!l) return { status: "idle" };
  if (!l.verificationUri && l.dir && l.status === "waiting") {
    try { l.verificationUri = authUrl(fs.readFileSync(path.join(l.dir, "url"), "utf8")); } catch {}
  }
  return {
    status: l.status, needsCode: l.status === "waiting" && !!l.verificationUri && !l.codeSent,
    verificationUri: l.verificationUri || "", verificationUrl: l.verificationUri || "", message: l.message,
  };
}

export function agyState() {
  const install = agyInstallation(), login = loginStatus();
  return { ...install, loggedIn: confirmed, accountRevision, usageRevision, login: login.status === "waiting" ? login : null,
    error: install.error || (login.status === "failed" ? login.message : "") };
}
export async function agyUsageOptions() {
  return { command: await verifiedAgy(), cwd: CFG.home, env: personalEnv() };
}
export function installAgy() { startAgyInstall(); return agyState(); }

export async function loginAgy(input = {}, options = {}) {
  const code = typeof input === "string" ? input : input?.code;
  if (input?.cancel) {
    if (currentLogin?.status === "waiting") currentLogin.stop("登录已取消");
    return loginStatus();
  }
  if (code != null && code !== "") {
    const l = currentLogin;
    if (l?.status !== "waiting" || !l.child || l.codeSent) throw new Error("请重新发起 Google 登录");
    if (typeof code !== "string" || code.length > 4096 || /[\r\n\0]/.test(code) || !code.trim()) throw new Error("授权码格式无效");
    l.codeSent = true;
    await new Promise((r, j) => l.child.stdin.write(code.trim() + "\n", e => e ? j(e) : r()));
    return loginStatus();
  }
  if (currentLogin?.status === "waiting") return loginStatus();
  accountRevision++;
  confirmed = null;
  const l = currentLogin = { status: "waiting", verificationUri: "", message: "等待 Google 授权地址" };
  const release = holdMultiActivity();
  let timer;
  function finish(status, message) {
    if (l.status !== "waiting") return;
    l.status = status;
    l.message = message;
    l.verificationUri = "";
    clearTimeout(timer);
    release();
    if (l.dir) { try { fs.rmSync(l.dir, { recursive: true, force: true }); } catch {} }
  }
  l.stop = message => { finish("failed", message); l.child?.kill(); };
  try {
    const bin = options.command || await verifiedAgy();
    if (l.status !== "waiting") return loginStatus();
    fs.mkdirSync(profile, { recursive: true, mode: 0o700 });
    l.dir = fs.mkdtempSync(path.join(profile, "login-"));
    const browser = path.join(l.dir, "browser");
    const shell = fs.existsSync("/system/bin/sh") ? "/system/bin/sh" : "/bin/sh";
    fs.writeFileSync(browser, "#!" + shell + '\numask 077\nprintf "%s\\n" "$1" > "$CCAPP_AGY_URL_FILE"\n', { mode: 0o700 });
    const child = l.child = (options.spawnProcess || spawn)(bin, ["acp", "--login"], {
      env: { ...personalEnv(), BROWSER: browser, CCAPP_AGY_URL_FILE: path.join(l.dir, "url") },
      cwd: CFG.home, stdio: ["pipe", "pipe", "pipe"],
    });
    let tail = "";
    const receive = data => {
      if (l.status !== "waiting" || l.verificationUri) return;
      tail = (tail + data).slice(-24000);
      l.verificationUri = authUrl(tail);
      if (l.verificationUri) { tail = ""; l.message = "在 Google 页面授权，然后提交页面提供的授权码"; }
    };
    child.stdout.on("data", receive);
    child.stderr.on("data", receive);
    child.stdin.on("error", () => l.stop("Antigravity 登录进程已停止接收授权码"));
    child.once("error", () => l.stop("Antigravity 登录进程无法启动"));
    child.once("close", c => {
      if (l.status !== "waiting") return;
      if (c === 0) confirmed = true;
      finish(c === 0 ? "complete" : "failed", c === 0 ? "Google 登录流程已完成" : "Google 登录未完成，请重试");
    });
    timer = setTimeout(() => l.stop("Google 登录已超时"), options.timeoutMs ?? 12 * 60_000);
    timer.unref();
  } catch (e) { l.stop(e.message); }
  return loginStatus();
}

export function stopAgyLogin() {
  if (currentLogin?.status === "waiting") currentLogin.stop("登录已取消");
}

export function respondAgy(id, allow) {
  const row = approvals.get(id);
  if (!row || typeof allow !== "boolean") return false;
  approvals.delete(id);
  const kind = allow ? "allow_once" : "reject_once";
  const selected = row.options.find(option => option.kind === kind && typeof option.optionId === "string");
  const outcome = selected ? { outcome: "selected", optionId: selected.optionId } : { outcome: "cancelled" };
  row.rpc.send({ id: row.requestId, result: { outcome } });
  return true;
}

const acpInit = {
  protocolVersion: 1, clientInfo: { name: "cccodroid", version: "1.0.0" },
  clientCapabilities: { fs: { readTextFile: false, writeTextFile: false }, terminal: false },
};

function acpProcess(command, cwd, options, dispatch) {
  return new AgentTransport({ ...options, cwd, command, args: ["acp", "--stdio"],
    jsonrpc: "2.0", label: "Antigravity", env: options.env || personalEnv() }, dispatch, () => {});
}
async function initializePersonal(rpc) {
  const revision = accountRevision;
  const initialized = await rpc.request("initialize", acpInit);
  if (!initialized.authMethods?.some(m => m.id === "oauth-personal")) throw new Error("Antigravity 未提供 Google 个人账号登录");
  try { await rpc.request("authenticate", { methodId: "oauth-personal" }); }
  catch (e) {
    if (!/already (authenticated|authorized|logged in|signed in)/i.test(e.message)) {
      if (revision === accountRevision) confirmed = false;
      throw new Error("Google 个人账号验证失败：" + e.message);
    }
  }
  if (revision !== accountRevision) throw new Error("Google 账号已切换，请重试");
  confirmed = true;
  return initialized;
}

function modelSelector(session) {
  const selector = session?.configOptions?.find(o => o.category === "model" || o.id === "model");
  const catalog = new Map();
  function addOptions(options) {
    for (const o of Array.isArray(options) ? options : []) {
      if (Array.isArray(o?.options)) addOptions(o.options);
      else if (typeof o?.value === "string" && o.value) catalog.set(o.value, { id: o.value, label: o.name || o.value });
    }
  }
  addOptions(selector?.options);
  if (selector) return { models: [...catalog.values()], defaultModel: selector.currentValue || "", configId: selector.id };
  for (const m of session?.models?.availableModels || []) {
    if (typeof m?.modelId === "string" && m.modelId) catalog.set(m.modelId, { id: m.modelId, label: m.name || m.modelId });
  }
  return { models: [...catalog.values()], defaultModel: session?.models?.currentModelId || "", configId: null };
}

let lastCatalog, catalogTask;
async function readAgyCatalog(options) {
  const command = options.command || await verifiedAgy();
  const cwd = path.join(CFG.home, ".cache", "multi-agent", "model-catalog");
  fs.mkdirSync(cwd, { recursive: true, mode: 0o700 });
  let reported;
  const rpc = acpProcess(command, cwd, { timeoutMs: 15000, ...options }, (transport, msg) => {
    if (msg.id != null) {
      if (msg.method === "session/request_permission") transport.send({ id: msg.id, result: { outcome: { outcome: "cancelled" } } });
      else transport.send({ id: msg.id, error: { code: -32601, message: "Client capability not available" } });
    } else if (msg.method === "session/update" && msg.params?.update?.sessionUpdate === "config_option_update") {
      reported = msg.params.update;
    }
  });
  try {
    const init = await initializePersonal(rpc);
    // The official selector is scoped to a client/session. A metadata-only session needs no model prompt.
    const session = await rpc.request("session/new", { cwd, mcpServers: [] });
    try {
      const { models, defaultModel } = modelSelector(reported || session);
      if (!models.length) throw new Error("Antigravity 未返回可选模型，请确认 Google 账号已登录");
      return { models, defaultModel };
    } finally {
      if (session?.sessionId && init.agentCapabilities?.sessionCapabilities?.close) {
        await rpc.request("session/close", { sessionId: session.sessionId });
      }
    }
  } finally { rpc.close(); }
}

export async function agyModels(options = {}) {
  const revision = accountRevision;
  if (lastCatalog?.revision === revision && Date.now() - lastCatalog.at < 60000) return lastCatalog.result;
  if (catalogTask?.revision === revision) return catalogTask.promise;
  const promise = readAgyCatalog(options).then(result => {
    if (revision !== accountRevision) throw new Error("Google 账号已切换，请刷新模型清单");
    lastCatalog = { revision, result, at: Date.now() };
    return result;
  }).finally(() => { if (catalogTask?.promise === promise) catalogTask = null; });
  catalogTask = { revision, promise };
  return promise;
}

async function selectAgyModel(rpc, id, session, model) {
  const selector = modelSelector(session);
  if (!selector.models.some(m => m.id === model)) throw new Error("Antigravity 当前账号未提供所选模型：" + model);
  if (selector.defaultModel === model) return;
  const changed = selector.configId
    ? await rpc.request("session/set_config_option", { sessionId: id, configId: selector.configId, value: model })
    : await rpc.request("session/set_model", { sessionId: id, modelId: model });
  const actual = modelSelector(changed).defaultModel;
  if (actual && actual !== model) throw new Error("Antigravity 未采用所选模型：" + model);
}

export async function runAgy({ sessionId, cwd, text, model, signal, onEvent }, options = {}) {
  if (!path.isAbsolute(cwd || "") || typeof text !== "string" || !text.trim()) throw new Error("Antigravity 需要项目目录和消息");
  const stopped = () => Object.assign(new Error("Antigravity 回合已停止"), { name: "AbortError" });
  if (signal?.aborted) throw stopped();
  if (sessionId && busy.has(sessionId)) throw new Error("此 Antigravity 会话正在运行");
  if (sessionId) busy.add(sessionId);
  let id = sessionId, rpc, prompting = false, abortTimer, reportedSelector;
  const chunks = new Map(), full = new Set(), replayed = new Set(), tools = new Map();
  let anonymousFull = "";
  let seenText = "";
  const abort = () => {
    if (id && prompting && rpc && !rpc.dead) rpc.send({ method: "session/cancel", params: { sessionId: id } });
    else { rpc?.close(stopped()); return; }
    abortTimer = setTimeout(() => rpc.close(stopped()), options.interruptTimeoutMs ?? 4000);
  };
  function dispatch(transport, msg) {
    const p = msg.params || {};
    if (msg.id != null) {
      if (msg.method !== "session/request_permission") {
        transport.send({ id: msg.id, error: { code: -32601, message: "Client capability not available" } });
      } else if (!prompting || p.sessionId !== id || signal?.aborted || !onEvent) {
        transport.send({ id: msg.id, result: { outcome: { outcome: "cancelled" } } });
      } else {
        const key = randomUUID();
        approvals.set(key, { rpc: transport, requestId: msg.id, options: Array.isArray(p.options) ? p.options : [] });
        onEvent({ type: "approval", id: key, method: msg.method, params: p, sessionId: id, threadId: id,
          title: p.toolCall?.title || "Antigravity 工具授权" });
      }
      return;
    }
    if (msg.method !== "session/update" || (id && p.sessionId !== id)) return;
    const u = p.update || {};
    if (signal?.aborted) return;
    if (u.sessionUpdate === "config_option_update") { reportedSelector = u; return; }
    const mid = u.messageId || "";
    if (!prompting) { if (mid) replayed.add(mid); return; }
    if (mid && replayed.has(mid)) return;
    if (u.sessionUpdate === "tool_call" || u.sessionUpdate === "tool_call_update") {
      if (!u.toolCallId) return;
      const tool = tools.get(u.toolCallId) || {};
      if (u.title) tool.title = u.title;
      if (Array.isArray(u.content)) tool.output = u.content.flatMap(part => {
        if (part.type === "content" && part.content?.type === "text") return [part.content.text];
        if (part.type === "diff" && part.path) return [part.path];
        return [];
      }).join("\n").slice(0, 4100);
      tools.set(u.toolCallId, tool);
      if (["completed", "failed", "cancelled"].includes(u.status) && !tool.done) {
        tool.done = true;
        onEvent?.({ type: "tool", label: tool.title || u.toolCallId, text: tool.output || u.status });
      }
      return;
    }
    const whole = u.sessionUpdate === "agent_message";
    if (!whole && u.sessionUpdate !== "agent_message_chunk") return;
    if (full.has(mid)) return;
    const parts = Array.isArray(u.content) ? u.content : [u.content];
    const body = parts.filter(part => part?.type === "text").map(part => part.text || "").join("");
    const previous = chunks.get(mid) || "";
    const delta = whole && body.startsWith(previous) ? body.slice(previous.length) : body;
    if (!mid && !whole && body === anonymousFull) { anonymousFull = ""; return; }
    if (whole && mid) full.add(mid);
    if (!mid && whole) { anonymousFull = body; chunks.delete(mid); }
    else chunks.set(mid, previous + delta);
    if (delta) {
      seenText += delta;
      onEvent?.({ type: "delta", text: delta, sessionId: id, threadId: id });
    }
  }
  try {
    const command = options.command || await verifiedAgy();
    if (signal?.aborted) throw stopped();
    fs.mkdirSync(profile, { recursive: true, mode: 0o700 });
    rpc = acpProcess(command, cwd, options, dispatch);
    signal?.addEventListener("abort", abort, { once: true });
    const initialized = await initializePersonal(rpc);
    const context = { cwd, mcpServers: [] };
    let session;
    if (id) {
      // 1.5.13 advertises resume (without replay). Never replace an existing thread with a new one.
      if (!initialized.agentCapabilities?.sessionCapabilities?.resume) throw new Error("当前 Antigravity 不支持无重放恢复原会话");
      session = await rpc.request("session/resume", { ...context, sessionId: id });
    } else {
      session = await rpc.request("session/new", context);
      id = session.sessionId;
      if (typeof id !== "string" || !id) throw new Error("Antigravity 未返回会话 ID");
      busy.add(id);
    }
    onEvent?.({ type: "thread", threadId: id, sessionId: id });
    const catalog = reportedSelector || session;
    const nativeModels = modelSelector(catalog);
    if (!sessionId && nativeModels.models.length) {
      const { models, defaultModel } = nativeModels;
      lastCatalog = { revision: accountRevision, at: Date.now(), result: { models, defaultModel } };
    }
    // session/resume retains a previous explicit model. An explicit reset uses the
    // model a new session with the same account/client gets, not that old override.
    let selected = model;
    if (sessionId && model === "" && nativeModels.models.length) {
      const baseline = lastCatalog?.revision === accountRevision ? lastCatalog.result : await agyModels(options);
      selected = baseline.defaultModel;
    }
    if (signal?.aborted) throw stopped();
    if (selected) await selectAgyModel(rpc, id, catalog, selected);
    if (signal?.aborted) throw stopped();
    prompting = true;
    const result = await rpc.request("session/prompt", { sessionId: id, prompt: [{ type: "text", text }] }, 6 * 60 * 60_000);
    if (signal?.aborted || result.stopReason === "cancelled") throw stopped();
    if (result.stopReason && result.stopReason !== "end_turn") throw new Error("Antigravity 回合未完成：" + result.stopReason);
    return { sessionId: id, text: seenText };
  } finally {
    if (prompting) usageRevision++;
    prompting = false;
    signal?.removeEventListener("abort", abort);
    clearTimeout(abortTimer);
    for (const [key, row] of approvals) if (row.rpc === rpc) approvals.delete(key);
    rpc?.close();
    if (id) busy.delete(id);
  }
}

export async function handoffAgy() {
  throw new Error("Antigravity 个人会话暂不支持独立分支整理，请选择手动交接");
}
