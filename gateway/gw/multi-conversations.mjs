import fs from "node:fs";
import path from "node:path";
import { randomUUID } from "node:crypto";
import { saveState } from "./statefile.mjs";

const engines = new Set(["claude", "codex", "agy"]);
const modes = new Set(["outgoing", "third-party", "manual"]);
const uuid = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i;
export function problem(message, status = 400) {
  return Object.assign(new Error(message), { status });
}
export function recordTool(turn, label, text) {
  const tools = turn.tools ||= [];
  if (tools.length >= 40) {
    if (tools.length === 40) tools.push({ label: "…", text: "更多工具结果保存在原生会话中" });
    return;
  }
  const output = String(text || "");
  tools.push({ label: String(label || "工具").slice(0, 300),
    text: output.length > 4000 ? output.slice(0, 4000) + "\n…（已截断）" : output });
}
function summary(c) {
  return { id: c.id, title: c.title, projectId: c.projectId, cwd: c.cwd, updatedAt: c.updatedAt,
    origin: c.origin || "", nativeClaude: c.sessions.claude || "", activeAgent: c.activeAgent,
    created: c.turns[0]?.at || c.updatedAt, pinned: !!c.pinned };
}

export function conversationStore(dir, adapters, holdActivity = () => () => {}) {
  fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
  const summaries = new Map();
  const origins = new Map();
  const jobs = new Map();
  function file(id) {
    if (!uuid.test(id)) throw problem("无效的对话编号");
    return path.join(dir, id + ".json");
  }
  function save(c) {
    c.updatedAt = Date.now();
    if (!saveState(file(c.id), c, { pretty: false })) throw problem("未能保存对话，请检查存储空间", 500);
    summaries.set(c.id, summary(c));
    if (c.origin) origins.set(c.origin, c.id);
    if (c.sessions?.claude) origins.set(c.sessions.claude, c.id);
  }
  function read(id) {
    if (jobs.has(id)) return jobs.get(id).c;
    if (!summaries.has(id)) throw problem("找不到对话", 404);
    if (summaries.get(id).damaged) throw problem("此对话文件损坏，已保留原文件", 409);
    return JSON.parse(fs.readFileSync(file(id), "utf8"));
  }
  for (const name of fs.readdirSync(dir)) {
    if (!name.endsWith(".json") || !uuid.test(name.slice(0, -5))) continue;
    let c;
    try { c = JSON.parse(fs.readFileSync(path.join(dir, name), "utf8")); } catch {}
    if (!c || c.id + ".json" !== name || !Array.isArray(c.turns) || !c.sessions) {
      // Preserve the original file for recovery and keep other conversations usable.
      const id = name.slice(0, -5);
      summaries.set(id, {id, title: "对话数据无法读取", projectId: "", updatedAt: 0, damaged: true});
      continue;
    }
    const last = c.turns.at(-1);
    if (last?.status === "running") {
      last.status = "interrupted";
      last.error = "网关已重新启动，请核对工作区后再继续";
      save(c);
    } else summaries.set(c.id, summary(c));
    if (c.origin) origins.set(c.origin, c.id);
    if (c.sessions?.claude) origins.set(c.sessions.claude, c.id);
  }
  function create({ cwd, projectId = "", title, origin = "", turns = [] }) {
    if (origin && origins.has(origin)) return origins.get(origin);
    const c = { id: randomUUID(), title: String(title || "新对话").slice(0, 96), projectId, cwd: fs.realpathSync(cwd),
      origin, activeAgent: origin ? "claude" : null, sessions: origin ? { claude: origin } : {}, turns };
    save(c);
    return c.id;
  }
  function view(id) {
    const c = read(id);
    const j = jobs.get(id);
    return { ...summary(c), activeAgent: c.activeAgent, turns: c.turns, activeRun: j ? {
      stage: j.stage, agent: j.turn.agent, text: j.turn.assistant, approvals: Array.from(j.pending, ([id, v]) => ({ id, label: v.label }))
    } : null };
  }
  function send(input) {
    const { id, text, agent, mode = "outgoing", requestId } = input;
    if (!engines.has(agent) || !modes.has(mode) || typeof text !== "string" || !text.trim() || text.length > 100000 || typeof requestId !== "string" || !uuid.test(requestId)) {
      throw problem("消息、交接模式或请求编号无效");
    }
    const destinationModel = input.destinationModel ?? "";
    if (typeof destinationModel !== "string" || destinationModel.length > 200 || /[\x00-\x1f\x7f]/.test(destinationModel)) throw problem("目标模型名称无效");
    const c = read(id);
    const old = c.turns.find(t => t.requestId === requestId);
    if (old) {
      if (old.user !== text || old.agent !== agent || (old.model || "") !== destinationModel.trim()) throw problem("请求编号已用于其他消息", 409);
      return;
    }
    if (Array.from(jobs.values()).some(j => (c.projectId && c.projectId === j.c.projectId) ||
        c.cwd === j.c.cwd || c.cwd.startsWith(j.c.cwd + path.sep) || j.c.cwd.startsWith(c.cwd + path.sep))) throw problem("这个项目还有一轮运行中", 409);
    const from = c.activeAgent;
    const switching = from && from !== agent;
    let picked = [];
    if (switching && mode === "manual") {
      if (!Array.isArray(input.selected)) throw problem("请选择要附带的回合");
      const ids = new Set(input.selected);
      picked = c.turns.filter(t => ids.has(t.id));
      if (picked.length !== ids.size) throw problem("所选回合无效");
    }
    if (switching && from === "agy" && mode !== "manual") throw problem("Antigravity 交接请手动选择需要的记录");
    if (switching && mode === "third-party" && !input.provider) throw problem("请选择交接模型接口");
    const turn = { id: randomUUID(), requestId, agent, model: destinationModel.trim(), user: text, assistant: "", status: "running", at: Date.now() };
    c.turns.push(turn);
    if (c.turns.length === 1 && c.title === "新对话") c.title = text.trim().slice(0, 60);
    save(c);
    const j = { c, turn, stage: switching ? "handoff" : "running", pending: new Map(), controller: new AbortController() };
    jobs.set(id, j);
    const release = holdActivity();
    j.done = execute(j, from, switching, input, picked).finally(() => { jobs.delete(id); release(); });
    j.done.catch(() => {}); // save() already reports a persistence failure when the filesystem is unavailable.
  }

  async function execute(j, from, switching, input, picked) {
    const { c, turn } = j;
    const signal = j.controller.signal;
    const mode = input.mode || "outgoing";
    const storeEvent = e => {
      if (signal.aborted) return;
      if (e.type === "thread" && e.threadId) { c.sessions[turn.agent] = e.threadId; save(c); }
      if (e.type === "delta" && e.text) {
        turn.assistant += e.text;
        c.activeAgent = turn.agent;
        if (!j.checkpoint) j.checkpoint = setTimeout(() => {
          j.checkpoint = null;
          try { save(c); } catch (err) { j.storageError = err; j.controller.abort(); }
        }, 3500);
      }
      if (e.type === "tool") recordTool(turn, e.label, e.text);
      if (e.type === "approval") j.pending.set(String(e.id), { label: e.label || e.method || "操作授权", respond: e.respond });
      if (e.type === "approvalDone") j.pending.delete(String(e.id));
    };
    try {
      let context = "";
      if (switching) {
        // Verify the target is available before spending a source-side model call.
        await adapters[turn.agent].ready?.();
        signal.throwIfAborted();
        turn.handoff = { from, mode, provider: mode === "third-party" ? input.provider : from };
        if (mode === "manual") {
          context = picked.map(t => JSON.stringify({ agent: t.agent, user: t.user, assistant: t.assistant, tools: t.tools })).join("\n");
        } else {
          const lastSeen = c.turns.slice(0, -1).findLast(t => t.agent === turn.agent && t.status === "done");
          const instruction = "你正在为用户交接项目。只整理与你收到的下一项任务相关的已确认决定、文件、测试结果与未完成事项。区分已验证与待验证。不要执行工具、修改文件或替用户执行任务。"
            + (lastSeen ? "\n接手方上次处理的请求是：" + JSON.stringify(lastSeen.user.slice(0, 1500)) + "。优先交代此后的变化。" : "")
            + "\n用户下一项任务（仅用于判断相关性）：" + JSON.stringify(turn.user);
          const result = await adapters[from].handoff({ sessionId: c.sessions[from], cwd: c.cwd, text: instruction,
            provider: mode === "third-party" ? input.provider : undefined, model: mode === "third-party" ? input.model : undefined, signal });
          context = result.text;
          if (!context?.trim()) throw problem("交接未返回内容，请重试或手动选择");
        }
        if (context.length > 200000) throw problem("交接内容过长，请缩小手动选择的范围");
        turn.handoff.text = context;
        save(c);
      }
      signal.throwIfAborted();
      j.stage = "running";
      const prompt = context ? "以下是之前会话的参考记录。\n" + JSON.stringify(context) + "\n\n用户本轮请求：\n" + turn.user : turn.user;
      const result = await adapters[turn.agent].run({ sessionId: c.sessions[turn.agent], cwd: c.cwd, model: turn.model, text: prompt, signal, onEvent: storeEvent });
      if (result.sessionId) c.sessions[turn.agent] = result.sessionId;
      if (result.text) turn.assistant = result.text;
      signal.throwIfAborted();
      turn.status = "done";
      c.activeAgent = turn.agent;
    } catch (e) {
      turn.status = signal.aborted ? "interrupted" : "failed";
      turn.error = signal.aborted ? "已停止" : String(e.message || e).slice(0, 2400);
    } finally {
      clearTimeout(j.checkpoint);
      if (j.storageError) turn.error = j.storageError.message;
      save(c);
    }
  }
  function edit(id, changes) {
    const c = read(id);
    if (typeof changes.title === "string" && changes.title.trim()) c.title = changes.title.trim().slice(0, 96);
    if (typeof changes.pinned === "boolean") c.pinned = changes.pinned;
    save(c);
  }
  function stop(id) {
    const j = jobs.get(id);
    if (!j) throw problem("这轮已结束", 409);
    j.controller.abort();
  }
  function approve(id, approvalId, allow) {
    const j = jobs.get(id);
    const a = j?.pending.get(String(approvalId));
    if (!a || typeof allow !== "boolean") throw problem("授权请求已失效", 409);
    j.pending.delete(String(approvalId));
    a.respond(allow);
  }
  function shutdown() {
    for (const j of jobs.values()) {
      j.controller.abort();
      clearTimeout(j.checkpoint);
      j.turn.status = "interrupted";
      j.turn.error = "网关已停止，请核对项目改动后再继续";
      save(j.c);
    }
  }
  return { create, view, send, stop, approve, shutdown, edit, list: () => [...summaries.values()].sort((a,b) => b.updatedAt-a.updatedAt),
    wait: id => jobs.get(id)?.done || Promise.resolve(), imported: sid => origins.get(sid) };
}
