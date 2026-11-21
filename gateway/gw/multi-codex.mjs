// OpenAI app-server v2 stdio client. Native thread IDs stay with their Codex installation.
import { AgentTransport, deferred, publicAgentError } from "./multi-rpc.mjs";
import { randomUUID } from "node:crypto";
import path from "node:path";

const HANDOFF_KEY = "CCAPP_HANDOFF_RESPONSES_KEY";
const HANDOFF_PROVIDER = "ccapp_handoff";
const approvalMethods = new Set([
  "item/commandExecution/requestApproval", "item/fileChange/requestApproval", "item/permissions/requestApproval",
]);

function stopped() {
  const error = new Error("Codex 回合已停止");
  error.name = "AbortError";
  return error;
}

function approvalResult(method, params, allow) {
  if (method === "item/permissions/requestApproval") {
    return { permissions: allow ? params.permissions : {}, scope: "turn" };
  }
  return { decision: allow ? "accept" : "decline" };
}

const threadArgs = (cwd, model, summary) => ({
  cwd, ...(model ? { model } : {}), modelProvider: "openai",
  sandbox: summary ? "read-only" : "workspace-write", approvalPolicy: summary ? "never" : "on-request",
});

function thirdPartyConfig(provider) {
  const url = new URL(provider.baseUrl);
  if (!["http:", "https:"].includes(url.protocol) || url.username || url.password || url.search || url.hash) {
    throw new Error("Codex 交接模型需要有效的 Responses API 地址");
  }
  return {
    name: provider.id || HANDOFF_PROVIDER, base_url: url.href.replace(/\/$/, ""),
    wire_api: "responses", ...(provider.apiKey ? { env_key: HANDOFF_KEY } : {}),
    requires_openai_auth: false,
  };
}

export function createCodexClient(options = {}) {
  let transport, ready, modelCatalog;
  const runs = new Map();
  const reserved = new Set();
  const approvals = new Map();
  const handoffs = new Set();

  function emit(run, event) {
    options.onEvent?.(event);
    if (run?.onEvent && run.onEvent !== options.onEvent) run.onEvent(event);
  }

  function teardown(rpc, err) {
    if (rpc !== transport) return;
    transport = null;
    ready = null;
    modelCatalog = undefined;
    approvals.clear();
    for (const run of runs.values()) run.done.reject(err);
    if (rpc.initialized && !rpc.intentionalClose) emit(null, { type: "disconnect", error: err.message });
  }

  function connect() {
    if (ready) return ready;
    const env = { ...(options.env || process.env) };
    delete env.OPENAI_API_KEY;
    delete env.CODEX_API_KEY;
    const rpc = new AgentTransport({ ...options, label: "Codex", command: options.command || "codex",
      args: options.args ?? (options.command ? [] : ["app-server"]), env }, dispatch, teardown);
    transport = rpc;
    ready = rpc.request("initialize", {
      clientInfo: { name: "cccodroid", title: "cccodroid", version: "1.0.0" }, capabilities: { experimentalApi: true },
    }).then(() => {
      rpc.send({ method: "initialized" });
      rpc.initialized = true;
      return rpc;
    }).catch((err) => { rpc.close(err); throw err; });
    return ready;
  }

  async function call(method, params) {
    return (await connect()).request(method, params);
  }

  const account = () => call("account/read", { refreshToken: false });
  const login = () => call("account/login/start", { type: "chatgptDeviceCode" });
  const limits = () => call("account/rateLimits/read", {});

  async function pages(rpc, method, params) {
    const rows = [], seen = new Set();
    let cursor;
    do {
      const page = await rpc.request(method, { ...params, ...(cursor ? { cursor } : {}) });
      if (!Array.isArray(page?.data)) throw new Error("Codex 返回的选项清单无效");
      rows.push(...page.data);
      cursor = page.nextCursor;
      if (cursor && seen.has(cursor)) throw new Error("Codex 选项清单分页重复");
      if (cursor) seen.add(cursor);
    } while (cursor);
    return rows;
  }

  async function readCatalog(rpc, threadId) {
    const rows = await pages(rpc, "model/list", { limit: 100, includeHidden: false });
    const entries = new Map();
    let defaultModel = "";
    const visible = rows.filter(row => !row?.hidden && typeof (row?.model || row?.id) === "string");
    const hasTiers = visible.some(row => row.serviceTiers?.length);
    // The official TUI filters the model's speed tiers by the enabled feature gate.
    const features = hasTiers ? await pages(rpc, "experimentalFeature/list", { limit: 500, ...(threadId ? { threadId } : {}) }) : [];
    const enabled = new Set(features.filter(f => f.enabled).map(f => f.name));
    for (const row of visible) {
      const id = row.model || row.id;
      const efforts = (row.supportedReasoningEfforts || []).filter(e => e?.reasoningEffort).map(e => ({
        id: e.reasoningEffort, label: e.reasoningEffort, description: e.description || "",
      }));
      const serviceTiers = (row.serviceTiers || []).filter(t => t?.id &&
        (t.id === "flex" || enabled.has(t.id === "ultrafast" ? "ultrafast_mode" : "fast_mode")))
        .map(t => ({ id: t.id, label: t.name || t.id, description: t.description || "" }));
      if (serviceTiers.length && !serviceTiers.some(t => t.id === "default")) {
        // Explicit Standard is important for models with a catalog default of Fast.
        serviceTiers.unshift({ id: "default", label: "标准", description: "" });
      }
      entries.set(id, { id, label: row.displayName || id, efforts, serviceTiers,
        defaultEffort: row.defaultReasoningEffort || "",
        defaultServiceTier: serviceTiers.some(t => t.id === row.defaultServiceTier) ? row.defaultServiceTier : "" });
      if (row.isDefault) defaultModel = id;
    }
    const catalog = { entries, defaultModel };
    if (!threadId && rpc === transport) modelCatalog = catalog;
    return catalog;
  }

  async function models(modelId = "") {
    const { entries, defaultModel } = await readCatalog(await connect());
    const effortModel = modelId || defaultModel;
    const row = entries.get(effortModel);
    return { models: [...entries.values()].map(({ id, label }) => ({ id, label })), defaultModel, effortModel,
      efforts: row?.efforts || [], defaultEffort: row?.defaultEffort || "",
      serviceTiers: row?.serviceTiers || [], defaultServiceTier: row?.defaultServiceTier || "" };
  }

  function turnTuning(row, effort, serviceTier) {
    const overrides = {};
    if (effort !== undefined) {
      if (effort !== "" && !row?.efforts.some(e => e.id === effort)) throw new Error("此 Codex 模型不支持所选思考强度");
      const selected = effort || row?.defaultEffort;
      if (selected) overrides.effort = selected;
    }
    if (serviceTier !== undefined) {
      if (serviceTier !== "" && !row?.serviceTiers.some(t => t.id === serviceTier)) throw new Error("此 Codex 模型未启用所选服务档位");
      // Omitting a tier on a resumed thread keeps its previous override.
      overrides.serviceTier = serviceTier || row?.defaultServiceTier || "default";
    }
    return overrides;
  }

  function respond(id, allow) {
    const row = approvals.get(id);
    if (!row || typeof allow !== "boolean") return false;
    approvals.delete(id);
    row.rpc.send({ id: row.request.id, result: approvalResult(row.request.method, row.request.params, allow) });
    return true;
  }

  function handleRunMessage(rpc, run, msg) {
    const p = msg.params || {};
    const eventTurnId = p.turn?.id || p.turnId;
    if (eventTurnId !== run.turnId) {
      if (msg.id != null) rpc.send({ id: msg.id, result: approvalResult(msg.method, p, false) });
      return;
    }
    const threadId = run.threadId;
    if (msg.id != null) {
      if (run.summary || run.signal?.aborted || (!run.onEvent && !options.onEvent)) {
        rpc.send({ id: msg.id, result: approvalResult(msg.method, p, false) });
        return;
      }
      const id = randomUUID();
      approvals.set(id, { rpc, request: msg, run });
      emit(run, { type: "approval", id, method: msg.method, params: p, threadId });
    } else if (msg.method === "item/agentMessage/delta") {
      const delta = p.delta;
      if (typeof delta !== "string" || !delta) return;
      run.items.set(p.itemId, (run.items.get(p.itemId) || "") + delta);
      emit(run, { type: "delta", text: delta, threadId });
    } else if (msg.method === "item/completed" && p.item?.type === "agentMessage") {
      const item = p.item;
      if (typeof item.text === "string") {
        if (!run.items.has(item.id) && item.text) emit(run, { type: "delta", text: item.text, threadId });
        run.items.set(item.id, item.text);
      }
    } else if (msg.method === "item/completed" && p.item?.type === "commandExecution") {
      const item = p.item;
      emit(run, { type: "tool", label: String(item.command || "Codex").slice(0, 300),
        text: [item.exitCode == null ? item.status : "exit " + item.exitCode, item.aggregatedOutput].filter(Boolean).join("\n") });
    } else if (msg.method === "turn/completed") {
      const turn = p.turn;
      if (run.signal?.aborted || turn.status === "interrupted") run.done.reject(stopped());
      else if (turn.status !== "completed") run.done.reject(new Error(publicAgentError(turn.error?.message, "Codex 回合失败")));
      else {
        for (const item of turn.items || []) {
          if (item.type === "agentMessage" && typeof item.text === "string") run.items.set(item.id, item.text);
        }
        run.done.resolve({ threadId, text: [...run.items.values()].join("\n\n") });
      }
    }
  }

  function dispatch(rpc, msg) {
    const p = msg.params || {};
    if (msg.method === "account/rateLimits/updated") return emit(null, { type: "quota", ...p });
    if (msg.method === "account/updated" || msg.method === "account/login/completed") {
      modelCatalog = undefined;
      if (msg.method === "account/login/completed") emit(null, { type: "login", success: p.success, error: p.error ? publicAgentError(p.error, "Codex 登录失败") : null });
      rpc.request("account/read", { refreshToken: false }).then((r) => {
        if (rpc === transport) emit(null, { type: "account", account: r.account });
      }).catch(() => {});
      return;
    }
    if (msg.method === "serverRequest/resolved") {
      for (const [id, row] of approvals) if (row.request.id === p.requestId) approvals.delete(id);
      return;
    }
    if (msg.id != null && !approvalMethods.has(msg.method)) {
      rpc.send({ id: msg.id, error: { code: -32601, message: "Unsupported client request" } });
      return;
    }
    const run = runs.get(p.threadId);
    if (!run || run.rpc !== rpc) {
      if (msg.id != null) rpc.send({ id: msg.id, result: approvalResult(msg.method, p, false) });
      return;
    }
    if (msg.id == null && !["item/agentMessage/delta", "item/completed", "turn/completed"].includes(msg.method)) return;
    if (!run.turnId) run.early.push(msg);
    else handleRunMessage(rpc, run, msg);
  }

  async function execute({ threadId, cwd, text, model, effort, serviceTier, onEvent, signal }, forkConfig) {
    if (!path.isAbsolute(cwd || "") || typeof text !== "string" || !text.trim()) {
      throw new Error("Codex 需要绝对项目目录和非空消息");
    }
    if (signal?.aborted) throw stopped();
    if (threadId && reserved.has(threadId)) throw new Error("这个 Codex 会话正在运行");
    const reservation = threadId;
    if (reservation) reserved.add(reservation);
    let run, grace;
    const interrupt = () => {
      if (!run?.turnId || run.interrupted) return;
      run.interrupted = true;
      for (const [id, row] of approvals) if (row.run === run) respond(id, false);
      grace = setTimeout(() => run.rpc.close(new Error("Codex 未确认停止，已断开进程")), options.interruptTimeoutMs ?? 8000);
      run.rpc.request("turn/interrupt", { threadId: run.threadId, turnId: run.turnId }).catch((e) => run.rpc.close(e));
    };
    try {
      const rpc = await connect();
      if (!forkConfig?.provider) {
        const state = await rpc.request("account/read", { refreshToken: false });
        if (state?.account?.type !== "chatgpt") throw new Error("请先用 ChatGPT 账号登录 Codex");
      }
      if (signal?.aborted) throw stopped();
      const tune = !forkConfig && (effort !== undefined || serviceTier !== undefined);
      if (!forkConfig && threadId && model === "") model = (modelCatalog || await readCatalog(rpc)).defaultModel;
      if (signal?.aborted) throw stopped();
      const params = threadArgs(cwd, model, !!forkConfig);
      if (threadId) params.threadId = threadId;
      if (forkConfig) {
        params.config = {
          "features.shell_tool": false, "features.unified_exec": false, "features.multi_agent": false,
          "web_search": "disabled",
        };
        if (forkConfig.provider) {
          params.modelProvider = HANDOFF_PROVIDER;
          params.config.model_providers = { [HANDOFF_PROVIDER]: forkConfig.provider };
        }
      }
      const method = forkConfig ? "thread/fork" : threadId ? "thread/resume" : "thread/start";
      const reply = await rpc.request(method, params);
      if (!forkConfig && model && reply?.model && reply.model !== model) {
        throw new Error("Codex 未采用所选模型：" + model);
      }
      const id = reply?.thread?.id;
      if (!id || (method === "thread/fork" && id === threadId)) throw new Error("Codex 没有返回有效的会话");
      if (forkConfig?.provider && reply.modelProvider !== HANDOFF_PROVIDER) throw new Error("Codex 未采用指定的第三方接口");
      if (reply.modelProvider && reply.modelProvider !== params.modelProvider) throw new Error("Codex 返回的模型接口与所选账号不一致");
      // A project can disable a speed mode. Ask about the loaded thread before applying the UI selection.
      const catalog = tune ? await readCatalog(rpc, id) : null;
      const tuning = catalog ? turnTuning(catalog.entries.get(model || reply.model || catalog.defaultModel), effort, serviceTier) : {};
      if (signal?.aborted) throw stopped();
      run = { threadId: id, rpc, onEvent, signal, summary: !!forkConfig, items: new Map(), early: [], done: deferred() };
      runs.set(id, run);
      reserved.add(id);
      emit(run, { type: "thread", threadId: id });
      if (signal?.aborted) throw stopped();
      signal?.addEventListener("abort", interrupt, { once: true });
      const started = await rpc.request("turn/start", {
        threadId: id, ...(model ? { model } : {}), ...tuning, input: [{ type: "text", text, text_elements: [] }],
        ...(forkConfig ? { environments: [] } : {}),
      });
      run.turnId = started?.turn?.id;
      if (!run.turnId) throw new Error("Codex 没有返回回合 ID");
      for (const msg of run.early) handleRunMessage(rpc, run, msg);
      run.early.length = 0;
      if (signal?.aborted) interrupt();
      return await run.done.promise;
    } finally {
      clearTimeout(grace);
      signal?.removeEventListener("abort", interrupt);
      if (run) {
        for (const [id, row] of approvals) if (row.run === run) {
          if (row.rpc.dead) approvals.delete(id);
          else respond(id, false);
        }
        runs.delete(run.threadId);
        reserved.delete(run.threadId);
      }
      if (reservation) reserved.delete(reservation);
    }
  }

  async function handoff({ provider, ...input }) {
    if (!input.threadId) throw new Error("交接需要已有的 Codex 会话");
    if (provider && !input.model) throw new Error("请选择第三方交接模型");
    const config = provider ? thirdPartyConfig(provider) : null;
    const env = { ...(options.env || process.env) };
    delete env[HANDOFF_KEY];
    if (provider?.apiKey) env[HANDOFF_KEY] = provider.apiKey;
    const args = [...(options.args ?? (options.command ? [] : ["app-server"]))];
    // On 0.166 the active process provider must agree with the fork provider when turn/start runs.
    // This process owns only the handoff; no global user settings or original thread are changed.
    args.push("-c", "model_provider=" + JSON.stringify(config ? HANDOFF_PROVIDER : "openai"));
    if (config) {
      for (const [key, value] of Object.entries(config)) {
        args.push("-c", "model_providers." + HANDOFF_PROVIDER + "." + key + "=" + JSON.stringify(value));
      }
    }
    for (const feature of ["shell_tool", "unified_exec", "view_image", "goals", "multi_agent", "multi_agent_v2", "plugins", "browser_use", "image_generation"]) {
      args.push("-c", "features." + feature + "=false");
    }
    args.push("-c", 'web_search="disabled"', "-c", "agents.enabled=false",
      "-c", "tools.experimental_request_user_input.enabled=false", "-c", "tools.update_plan.enabled=false");
    const worker = createCodexClient({ ...options, args, env, onEvent: undefined });
    handoffs.add(worker);
    try { return await worker.fork(input, config); }
    finally { worker.close(); handoffs.delete(worker); }
  }

  return {
    account, login, limits, models, respond, handoff, run: (p) => execute(p),
    fork: (p, provider) => execute(p, { provider }),
    close() {
      if (transport) { transport.intentionalClose = true; transport.close(); }
      for (const worker of handoffs) worker.close();
    },
  };
}
