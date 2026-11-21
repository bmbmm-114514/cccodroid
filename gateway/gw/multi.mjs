import path from "node:path";
import fs from "node:fs";
import { randomUUID } from "node:crypto";
import { holdMultiActivity } from "./runs.mjs";
import { agyState, installAgy, loginAgy, respondAgy, runAgy, stopAgyLogin } from "./multi-agy.mjs";
import { agyQuota } from "./multi-agy-quota.mjs";
import { shutdownAgentTransports } from "./multi-rpc.mjs";
import { CFG } from "./config.mjs";
import { NEXT, readBodyBuf, sendJson } from "./http.mjs";
import { PROJECTS, projectById, projectOfDir } from "./projects.mjs";
import { findSessionFile, peekSession } from "./sessions.mjs";
import { readSessionForward } from "./transcript.mjs";
import { providerById, keyOf } from "./providers.mjs";
import { conversationStore, problem, recordTool } from "./multi-conversations.mjs";
import { runClaude, handoffClaude, claudeProviders, claudeState } from "./multi-claude.mjs";
import { createCodexClient } from "./multi-codex.mjs";
import { codexOptions } from "./multi-codex-launch.mjs";
import { installation, startInstall } from "./multi-codex-install.mjs";

const agyAdapter = {
  async ready() {
    if (!agyState().installed) throw problem("请先安装 Antigravity", 409);
  },
  async run({ onEvent, ...options }) {
    return runAgy({ ...options, onEvent(e) {
      if (e.type === "approval") onEvent({ ...e, label: e.label || e.params?.toolCall?.title || e.method,
        respond: allow => respondAgy(e.id, allow) });
      else onEvent(e);
    } });
  }
};
let clientPromise, repository, login = null, account = null, codexError = "", quota = [], refreshTask, refreshed = 0, loginTask;
function applyLimits(snapshot) {
  const limits = snapshot?.rateLimits || snapshot?.rateLimitsByLimitId?.codex;
  if (!limits) return;
  quota = [];
  for (const [key, fallback] of [["primary", "短期"], ["secondary", "长期"]]) {
    const w = limits[key];
    if (!Number.isFinite(w?.usedPercent)) continue;
    const resetsAt = w.resetsAt ? w.resetsAt * 1000 : null;
    const label = { 300: "5 小时", 10080: "每周" }[w.windowDurationMins] || fallback;
    quota.push({ name: label, remaining: Math.max(0, 100 - w.usedPercent), resetsAt });
  }
}
function setAccount(a) {
  if (account?.email !== a?.email || !a) quota = [];
  account = a ? { type: a.type, email: a.email || null } : null;
}
async function client() {
  if (!clientPromise) clientPromise = codexOptions().then(options => createCodexClient({ ...options, onEvent(e) {
    if (e.type === "disconnect") {
      endLogin();
      setAccount(null);
      codexError = e.error;
      // An explicit login can retry immediately. Background polling must not respawn a broken executable on every refresh.
      refreshed = Date.now();
    }
    if (e.type === "quota") applyLimits(e);
    if (e.type === "account") { setAccount(e.account); refreshed = 0; }
    if (e.type === "login") { endLogin(); refreshed = 0; codexError = e.success ? "" : String(e.error || "Codex 登录失败"); }
  } })).catch(e => { clientPromise = null; throw e; });
  return clientPromise;
}
function refreshAccount() {
  if (refreshTask || !installation().installed || Date.now()-refreshed < 60000) return;
  refreshed = Date.now();
  refreshTask = (async () => {
    const api = await client();
    setAccount((await api.account()).account);
    if (account?.type === "chatgpt") {
      applyLimits(await api.limits());
      codexError = "";
    }
  })().catch(e => { codexError = e.message; }).finally(() => { refreshTask = null; });
}
let loginRelease, loginTimeout;
function endLogin() {
  login = null;
  clearTimeout(loginTimeout);
  loginRelease?.();
  loginRelease = null;
}
function deviceLogin() {
  if (login) return;
  if (!loginTask) loginTask = (async () => {
    const api = await client();
    const result = await api.login();
    loginRelease = holdMultiActivity();
    loginTimeout = setTimeout(endLogin, 15 * 60_000);
    login = { verificationUri: result.verificationUrl || result.verificationUri, userCode: result.userCode };
    codexError = "";
  })().finally(() => { loginTask = null; });
  return loginTask;
}
function availableProvider(id) {
  if (typeof id !== "string" || !claudeProviders().some(p => p.id === id)) throw problem("交接接口未配置");
  const p = providerById(id);
  return { id, baseUrl: p.baseUrl, apiKey: keyOf(p) };
}
const codexAdapter = {
  async ready() {
    if (!installation().installed) throw problem("请先安装 Codex", 409);
    if ((await (await client()).account()).account?.type !== "chatgpt") throw problem("请先使用 ChatGPT 登录 Codex", 409);
  },
  async run({ sessionId, onEvent, ...options }) {
    const api = await client();
    const result = await api.run({ ...options, threadId: sessionId, onEvent(e) {
      if (e.type === "approval") {
        const p = e.params || {};
        onEvent({ ...e, label: [e.method, p.command, p.cwd, p.reason, p.grantRoot, p.permissions && JSON.stringify(p.permissions)].filter(Boolean).join("\n").slice(0, 4800),
          respond: allow => api.respond(e.id, allow) });
      } else onEvent(e);
    } });
    return { sessionId: result.threadId, text: result.text };
  },
  async handoff({ sessionId, provider, ...options }) {
    const api = await client();
    return api.handoff({ ...options, threadId: sessionId, provider: provider ? availableProvider(provider) : undefined });
  }
};
function store() {
  return repository ||= conversationStore(path.join(CFG.home, "multi-agent", "conversations"), {
    claude: { run: runClaude, handoff: handoffClaude, async ready() {
      const state = await claudeState();
      if (state.loggedIn !== true) throw problem("请先确认 Claude 订阅登录状态", 409);
    } }, codex: codexAdapter, agy: agyAdapter
  }, holdMultiActivity);
}

// Use the same visible transcript parser as the existing conversation screen.
// Do not export hidden thinking, hook input or credentials during migration.
async function createConversation(b) {
  const db = store();
  if (!b.claudeSessionId) {
    const project = b.projectId ? projectById(b.projectId) : null;
    if (b.projectId && !project) throw problem("项目不存在", 404);
    return db.create({ cwd: project?.path || CFG.workspace, projectId: project?.id || "", title: b.title });
  }
  const origin = b.claudeSessionId;
  const existing = db.imported(origin);
  if (existing) return existing;
  const entry = findSessionFile(origin);
  if (!entry) throw problem("找不到原 Claude 会话", 404);
  const meta = peekSession(entry.file);
  if (!meta.cwd || !fs.existsSync(meta.cwd)) throw problem("原项目目录已不可用", 409);
  const turns = [];
  let offset = 0;
  for (;;) {
    const page = await readSessionForward(entry.file, { after: offset, limit: 100 });
    for (const item of page.items) {
      if (item.role === "user") turns.push({ id: item.uuid || randomUUID(), agent: "claude", user: item.text, assistant: "", status: "done" });
      else if (item.role === "tool_result" && turns.length) recordTool(turns.at(-1), "Claude Code", item.text);
      else if (item.role === "assistant" && item.text && turns.length) {
        const t = turns.at(-1);
        t.assistant += (t.assistant ? "\n" : "") + item.text;
      }
    }
    if (page.next >= page.size || page.next <= offset) break;
    offset = page.next;
  }
  return db.create({ cwd: meta.cwd, projectId: projectOfDir(meta.cwd)?.id || "", origin, turns, title: meta.title || meta.firstMsg });
}
export function stopMulti() {
  try { repository?.shutdown(); }
  finally {
    endLogin();
    stopAgyLogin();
    shutdownAgentTransports();
  }
}
export async function routeMulti(req, res, u, p) {
  if (!p.startsWith("/api/multi/")) return NEXT;
  const action = p.slice("/api/multi/".length);
  try {
    if (action === "state" && req.method === "GET") {
      const db = store();
      const id = u.searchParams.get("id");
      refreshAccount();
      const s = await claudeState();
      const google = agyState();
      return sendJson(res, 200, { conversations: db.list(), conversation: id ? db.view(id) : null,
        projects: PROJECTS.map(({ id, name }) => ({ id, name })),
        providers: claudeProviders().map(p => ({ id: p.id, label: p.label, model: p.defaultModel || "" })),
        claude: { loggedIn: s.loggedIn, email: s.email }, agy: google, quota: { agy: agyQuota(google), claude: s.quota, codex: quota.filter(w => !w.resetsAt || w.resetsAt > Date.now()) },
        codex: { ...installation(), account, login, error: installation().error || codexError } });
    }
    if (req.method !== "POST") throw problem("请求方式无效", 405);
    let b;
    try { b = JSON.parse((await readBodyBuf(req, 512*1024)).toString("utf8") || "{}"); }
    catch (e) { throw problem("请求正文无效", e.status || 400); }
    if (!b || typeof b !== "object" || Array.isArray(b)) throw problem("请求正文无效");
    switch (action) {
      case "create": return sendJson(res, 201, { id: await createConversation(b) });
      case "send": store().send(b); break;
      case "stop": store().stop(b.id); break;
      case "approve": store().approve(b.id, b.approvalId, b.allow); break;
      case "agy-install": installAgy(); break;
      case "agy-login": await loginAgy(b.code); break;
      case "install": startInstall(); break;
      case "login": await deviceLogin(); break;
      default: throw problem("找不到接口", 404);
    }
    return sendJson(res, 200, { ok: true });
  } catch (e) { return sendJson(res, e.status || 500, { error: e.message, message: e.message }); }
}
