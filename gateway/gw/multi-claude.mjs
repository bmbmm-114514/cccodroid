import { spawn } from "node:child_process";
import { randomUUID } from "node:crypto";
import { onPublish } from "./bus.mjs";
import { claudeBlocked, claudeCmd, claudeEnv, ensureBridge, killTree, settingsArgs } from "./claude.mjs";
import { authStatus } from "./login.mjs";
import { ensureLlama } from "./local.mjs";
import { ensureManagedBridge } from "./managed-bridge.mjs";
import { effortLevelsOf, fetchModels, isLoginMode } from "./models.mjs";
import { PROVIDER, keyOf, providerBrief, providerById } from "./providers.mjs";
import { QUEUE, abortRun, endRun, sidForItem } from "./queue.mjs";
import { execIntent, startRun } from "./run.mjs";
import { RUNS, maxParallel, runOfSession } from "./runs.mjs";
import { RATE_LIMITS } from "./stream.mjs";

const OFFICIAL = "anthropic";
const MAX_HANDOFF = 256 * 1024;
const stopError = () => new Error("已停止 Claude Code");

function subscription() {
  const p = providerById(OFFICIAL);
  if (!p || !isLoginMode(p)) throw new Error("请在设置里启用 Claude 账户登录。订阅回合需要使用官方登录方式。");
  return p;
}

export function claudeProviders() {
  return providerBrief().filter(p => p.usable && !p.login && p.keyMode === "key")
    .map(({ id, label, defaultModel }) => ({ id, label, defaultModel }));
}

export async function claudeModels(requested = "", id = OFFICIAL, refresh = false) {
  const provider = id === OFFICIAL ? subscription() : providerById(id);
  if (!provider) throw new Error("未知的 Claude 接口");
  const list = await fetchModels(refresh, provider, { guess: true, reportErrors: true });
  const defaultModel = provider.defaultModel || "", effortModel = requested || defaultModel;
  const meta = list.find(m => m.id === effortModel) || (!effortModel && list[0]) || {};
  return { models: list.map(m => ({id:m.id,label:m.name || m.id})), defaultModel, effortModel,
    efforts:effortLevelsOf(meta,provider).map(id => ({id,label:id})), defaultEffort:"", serviceTiers:[], defaultServiceTier:"" };

}

export async function claudeState() {
  const account = await authStatus();
  const loggedIn = account.unknown || account.stale ? null : account.loggedIn;
  const quota = [];
  if (loggedIn && RATE_LIMITS && (!RATE_LIMITS.email || RATE_LIMITS.email === account.email)) {
    for (const [name, window] of [["5 小时", RATE_LIMITS.fiveHour], ["每周", RATE_LIMITS.sevenDay]]) {
      if (!Number.isFinite(window?.used)) continue;
      const resetsAt = window.resetsAt ? window.resetsAt * 1000 : null;
      if (resetsAt && resetsAt <= Date.now()) continue;
      quota.push({ name, remaining: Math.max(0, 100 - window.used), resetsAt });
    }
  }
  return { loggedIn, email: loggedIn ? account.email || null : null, quota };
}

// Admit the turn synchronously in the existing gateway runner. Busy native sessions must
// finish first, even if they were opened in the classic conversation view.
export async function runClaude({ sessionId, cwd, text, model, effort, destinationProvider, signal, onEvent = () => {} }) {
  const idProvider = destinationProvider || OFFICIAL;
  const p = idProvider === OFFICIAL ? subscription() : providerById(idProvider);
  if (!p || (idProvider !== OFFICIAL && !p.local && !keyOf(p))) throw new Error("所选 Claude 接口未配置");
  // A resumed CLI session can retain its old model. Explicit "engine default"
  // uses the official default alias (or this provider's configured default).
  const selectedModel = sessionId && model === "" ? p.defaultModel || (idProvider === OFFICIAL ? "default" : undefined) : model;
  const pin = !sessionId || model != null ? { provider: idProvider, model: model || "", ...(effort != null ? {effort} : {}) } : null;
  if (selectedModel && execIntent({ sessionId, cwd, provider: idProvider, pin, model: selectedModel }).model !== selectedModel) {
    throw new Error("Claude Code 不支持所选模型：" + model);
  }
  if (signal?.aborted) throw stopError();
  if (RUNS.size >= maxParallel()) throw new Error("Claude 并行回合已满，请在一轮结束后重试。");
  if (sessionId && (runOfSession(sessionId) || QUEUE.some(q => sidForItem(q) === sessionId))) {
    throw new Error("原 Claude 会话仍有回合在运行或排队。");
  }
  const id = "multi-" + randomUUID();
  const stop = () => abortRun(RUNS.get(id), { hold: false, userStopped: true });
  let answer = "", error = "", unsubscribe;
  const toolNames = new Map();
  const done = new Promise((resolve, reject) => {
    unsubscribe = onPublish(event => {
      if (event.runId !== id) return;
      switch (event.type) {
        case "init":
          onEvent({ type: "thread", threadId: event.sessionId });
          break;
        case "delta":
        case "assistant_text":
          answer += event.text;
          onEvent({ type: "delta", text: event.text });
          break;
        case "discard_partial":
          if (event.text > 0) answer = answer.slice(0, Math.max(0, answer.length - event.text));
          break;
        case "tool_use":
          toolNames.set(event.id, event.name);
          break;
        case "tool_result":
          onEvent({ type: "tool", label: toolNames.get(event.id) || "Claude Code", text: event.text });
          toolNames.delete(event.id);
          break;
        case "perm_request": {
          const input = JSON.stringify(event.input || {}).slice(0, 4000);
          onEvent({ type: "approval", id: event.id, label: event.tool + " " + input,
            respond: allow => RUNS.get(id)?.answerPerm?.(event.id, { behavior: allow === true ? "allow" : "deny" }) || false });
          break;
        }
        case "error":
          error = event.message || error;
          break;
        case "run_end":
          if (event.aborted || signal?.aborted) reject(stopError());
          else if (event.failed || event.isError) reject(new Error(error || event.finalText || "Claude 回合失败"));
          else resolve({ sessionId: event.sessionId, text: event.resultText || answer, usage: event.usage || null });
          break;
      }
    });
    signal?.addEventListener("abort", stop, { once: true });
    startRun({ id, message: text, sessionId, cwd, provider: idProvider, model: selectedModel, effort, pin,
      permissionMode: "default" }).catch(err => {
      const live = RUNS.get(id);
      if (live) { error = err.message; endRun(live, { failed: true }); }
      else reject(err);
    });
  });
  try { return await done; }
  finally { unsubscribe(); signal?.removeEventListener("abort", stop); }
}

export async function handoffClaude({ sessionId, cwd, text, provider, model, signal }) {
  if (!/^[a-z\d][a-z\d_-]{0,79}$/i.test(sessionId || "")) throw new Error("没有可恢复的 Claude 会话");
  if (signal?.aborted) throw stopError();
  if (runOfSession(sessionId)) throw new Error("请先结束当前 Claude 回合再交接。");
  const p = provider ? providerById(provider) : subscription();
  if (!p || (provider && (p.login || (!p.local && !keyOf(p)))) || p.endpointLost) throw new Error("所选交接接口未配置或不可用。");
  const blocked = await claudeBlocked();
  if (blocked) throw new Error(blocked);
  await ensureManagedBridge(p, { signal });
  if (p.id === PROVIDER.id && !isLoginMode(p) && !(await ensureBridge())) throw new Error("无法连接所选接口的本地桥");
  let selected = model || p.defaultModel || "";
  if (p.local) selected = (await ensureLlama(selected, () => {})).id;
  if (signal?.aborted) throw stopError();
  const env = claudeEnv({ model: selected, provider: p });
  const settings = settingsArgs(p);
  const index = settings.indexOf("--settings");
  const cfg = index < 0 ? {} : JSON.parse(settings[index + 1]);
  if (provider && !p.local && !cfg.apiKeyHelper) throw new Error("所选接口的认证配置没有就绪。");
  if (provider) delete env.CLAUDE_CODE_OAUTH_TOKEN;
  // Local/project hooks and tools must not execute during a read-only handoff.
  delete cfg.hooks;
  cfg.disableAllHooks = true;
  if (index < 0) settings.push("--settings", JSON.stringify(cfg));
  else settings[index + 1] = JSON.stringify(cfg);
  const [cmd, args] = claudeCmd([...settings, "-p", "--resume", sessionId, "--fork-session",
    "--no-session-persistence", "--setting-sources", "", "--strict-mcp-config", "--disable-slash-commands",
    "--tools", "", "--max-turns", "1", "--output-format", "json", ...(selected ? ["--model", selected] : [])]);
  return new Promise((resolve, reject) => {
    const child = spawn(cmd, args, { cwd, env, detached: true, stdio: ["pipe", "pipe", "pipe"] });
    let output = "", stderr = "", failure = null;
    const fail = err => { if (!failure) failure = err; killTree(child); };
    const abort = () => fail(stopError());
    const timeout = setTimeout(() => fail(new Error("Claude 交接超时")), 240000);
    signal?.addEventListener("abort", abort, { once: true });
    child.stdout.setEncoding("utf8");
    child.stderr.setEncoding("utf8");
    child.stdout.on("data", chunk => {
      if (failure) return;
      if (output.length + chunk.length > MAX_HANDOFF) return fail(new Error("Claude 的交接结果过长"));
      output += chunk;
    });
    child.stderr.on("data", chunk => { stderr = (stderr + chunk).slice(-1000); });
    child.stdin.on("error", () => {});
    child.once("error", err => { failure = err; });
    child.once("close", code => {
      clearTimeout(timeout);
      signal?.removeEventListener("abort", abort);
      if (failure) return reject(failure);
      let result;
      try { result = JSON.parse(output.trim()); } catch {}
      if (code !== 0 || result?.is_error || !result?.result?.trim()) {
        return reject(new Error(String(result?.result || stderr || "Claude 未返回交接内容").slice(0, 800)));
      }
      resolve({ text: result.result.trim(), usage: result.usage || null });
    });
    // stdin prevents the user's next task from being exposed as a process argument.
    child.stdin.end(text);
  });
}
