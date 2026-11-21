// Run with Node and a local Chromium: CHROMIUM_BIN=chromium node scripts/test-multi-ui.mjs.
// The browser runs the real workbench. All accounts, prompts and approval requests are fixtures.
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { spawn } from "node:child_process";
import { startManagedUiServer } from "../gateway/tests/fixtures/managed-ui-server.mjs";

function appearanceBridgeFixture() {
  const sid = "multi:project-chat", file = { path:"/fixture/notes.txt", size:10 };
  localStorage.setItem("cc.drafts", JSON.stringify({[sid]:{text:"Classic draft", att:[file]}}));
  const packet = JSON.stringify({id:"test-handoff",sid,project:"p",draft:"Native draft"});
  window.CCApp = {
    chooseFrontend() {}, chooseFrontendState(state) { window.appearanceSent = JSON.parse(state); },
    restoreFrontend() { setTimeout(() => { window.appearanceDelivered = window.__ccAppearanceReceive(packet); }); }
  };
}

async function checkWorkbench() {
  const by = id => document.getElementById(id);
  const assert = (ok, message) => { if (!ok) throw Error(message); };
  async function until(predicate, label) {
    const end = Date.now() + 8000;
    while (Date.now() < end) {
      if (predicate()) return;
      await new Promise(resolve => setTimeout(resolve, 30));
    }
    throw Error("Timed out: " + label);
  }
  function select(id, value) {
    by(id).value = value;
    by(id).dispatchEvent(new Event("change", { bubbles: true }));
  }
  function compose(text) {
    by("input").value = text;
    by("input").dispatchEvent(new Event("input"));
    by("btn-send").click();
  }
  const first = { id: "turn-1", agent: "claude", user: "Decide on A", assistant: "A selected", status: "done", tools: [{ label: "Test", text: "<script>fixture</script>" }] };
  const chat = { id: "project-chat", title: "Fixture", projectId: "p", activeAgent: "claude", turns: [first], activeRun: null };
  const state = {
    projects: [{ id: "p", name: "Project" }], conversations: [chat], providers: [{ id: "third", label: "Fixture", model: "model-1" }],
    codex: { installed: true, account: { email: "codex@example.invalid" } }, agy: { installed: false },
    claude: { loggedIn: true, email: "first@example.invalid" },
    quota: { claude: [{ name: "5h", remaining: 4, resetsAt: Date.now() + 3600000 }], codex: [], agy: [] },
  };
  const original = window.fetch, calls = [], errors = [];
  const savedChoices = new Map();
  let currentProvider = "anthropic";
  function choices(body = {}) {
    const provider = body.provider || currentProvider;
    const agent = { google: "agy", chatgpt: "codex" }[provider] || "claude";
    const result = { configured: true, agent, provider, model: "", effort: "", serviceTier: "", ...savedChoices.get(provider), ...body };
    savedChoices.set(provider, result);
    currentProvider = provider;
    return result;
  }
  let checkingLogin = false, imported = false, simulateDrop = "";
  window.addEventListener("error", event => errors.push(event.message));
  window.fetch = async (u, opts) => {
    const addr = new URL(u, location.href);
    if (addr.pathname === "/api/providers") {
      return Response.json({ active: "fixture", providers: [
        { id: "anthropic", login: true, group: "claude", label: "Claude Code" },
        { id: "fixture", group: "custom", custom: true, active: true, label: "Fixture" },
      ] });
    }
    if (checkingLogin && addr.pathname === "/api/login/status") return Response.json({ loggedIn: false });
    if (addr.pathname === "/api/sessions") {
      const native = await original(u, opts);
      if (!native.ok) return native;
      const list = await native.json();
      return Response.json({ ...list, conversations: imported ? [{ ...chat, origin: "synthetic-ui-a" }] : [] });
    }
    if (!addr.pathname.startsWith("/api/multi/")) return original(u, opts);
    const action = addr.pathname.split("/").pop(), body = opts?.body ? JSON.parse(opts.body) : {};
    calls.push({ action, body });
    let reply = { ok: true };
    if (action === "state") reply = { ...state, conversation: addr.searchParams.get("id") === chat.id ? chat : null };
    if (action === "create") { if (body.claudeSessionId) imported = true; reply = { id: chat.id }; }
    if (action === "preferences") reply = choices(body);
    if (action === "models") reply = {
      agent: addr.searchParams.get("agent"), models: [{ id: "fixture-destination", label: "Fixture" }],
      efforts: [{ id: "high", label: "High" }], defaultEffort: "high",
      serviceTiers: addr.searchParams.get("agent") === "codex" ? [{ id: "fast", label: "Fast" }] : [],
    };
    if (action === "send") {
      if (simulateDrop === "before") { simulateDrop = ""; throw new TypeError("fixture lost connection"); }
      chat.turns.push({ id: "turn-" + (chat.turns.length + 1), requestId: body.requestId, agent: body.agent, user: body.text, assistant: "", status: "running" });
      chat.activeRun = { stage: "handoff", agent: body.agent, text: "", approvals: [] };
      if (simulateDrop === "after") { simulateDrop = ""; throw new TypeError("fixture dropped acknowledgement"); }
    }
    if (action === "stop") {
      chat.turns.at(-1).status = "interrupted"; chat.turns.at(-1).error = "fixture stopped";
      chat.activeRun = null;
    }
    return new Response(JSON.stringify(reply), { headers: { "content-type": "application/json" } });
  };
  const sent = () => calls.filter(x => x.action === "send").at(-1)?.body;
  await until(() => !!window.__cc, "app startup");
  await until(() => window.__cc.srv.connected, "native gateway connection");
  await until(() => window.appearanceDelivered && window.__cc.view.sessionId === "multi:project-chat", "native conversation transferred to classic");
  await until(() => by("ma-turns").textContent.includes(first.assistant), "transferred conversation loaded");
  assert(by("input").value.includes("Classic draft") && by("input").value.includes("Native draft"), "both existing drafts retained");
  assert(window.__cc.state.attachments.some(a => a.path === "/fixture/notes.txt"), "classic attachment retained");
  by("native-appearance").click();
  assert(window.appearanceSent?.sid === "multi:project-chat" && window.appearanceSent.draft.includes("@/fixture/notes.txt"), "classic exports shared conversation and attachment reference");
  window.__cc.openSession("synthetic-ui-a");
  await until(() => window.__cc.view.histOk, "original Claude chat");
  by("native-appearance").click();
  assert(window.appearanceSent.sid === "synthetic-ui-a", "classic exports original Claude session");
  by("chat-agent-chip").click();
  await until(() => [...by("set-provider").options].some(o => o.value === "chatgpt"), "account provider options");
  const order = [...by("set-provider").options].map(o => o.value);
  assert(order.slice(order.indexOf("anthropic"), order.indexOf("anthropic")+3).join(",") === "anthropic,chatgpt,google", "official accounts ordered together");
  select("set-provider", "chatgpt");
  await until(() => !by("set-agent-tier-row").classList.contains("hidden"), "Codex service tier capabilities");
  await until(() => !by("set-agent-login").disabled, "ChatGPT authorization enabled");
  by("set-agent-login").click();
  await until(() => calls.some(x => x.action === "login"), "Codex-specific login request");
  assert(!calls.some(x => x.action === "agy-login"), "Codex login never starts Google authorization");
  select("set-agent-tier", "fast");
  select("set-agent-effort", "high");
  await until(() => calls.some(x => x.action === "create" && x.body.claudeSessionId === "synthetic-ui-a"), "import native Claude chat");
  by("set-x").click();
  assert(!by("side-nav").querySelector("[data-page=multi]"), "agent switching belongs to main chat");
  await until(() => by("ma-turns").textContent.includes(first.assistant), "restored conversation");
  const toolRecord = by("ma-turns").querySelector("details[data-detail$='-tools']");
  assert(toolRecord && !toolRecord.open && toolRecord.textContent.includes("<script>fixture</script>") && !toolRecord.querySelector("script"), "collapsed, escaped tool evidence");

  select("ma-mode", "manual");
  by("ma-checks").querySelector("input").click();
  simulateDrop = "before";
  Object.defineProperty(crypto, "randomUUID", { value: undefined, configurable: true });
  compose("Review A");
  await until(() => calls.some(x => x.action === "send") && !by("ma-error").classList.contains("hidden"), "failed request keeps the draft");
  const firstRequest = sent().requestId;
  assert(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(firstRequest), "WebView fallback UUID");
  assert(by("input").value === "Review A", "original input after network failure");
  by("btn-send").click();
  await until(() => sent()?.agent === "codex" && by("btn-send").classList.contains("busy"), "Codex turn");
  assert(sent().destinationProvider === "chatgpt" && sent().effort === "high" && sent().serviceTier === "fast", "Codex reasoning and Fast reach send");
  assert(sent().requestId === firstRequest, "same request ID after a dropped connection");
  assert(sent().selected.join() === first.id && sent().mode === "manual", "selected history");
  by("btn-send").click();
  await until(() => by("ma-turns").textContent.includes("fixture stopped"), "interrupted turn");
  by("ma-turns").querySelector("[data-retry-turn]").click();
  select("ma-mode", "third-party"); select("ma-provider", "third");
  by("btn-send").click();
  await until(() => sent()?.mode === "third-party", "third-party retry");
  assert(sent().provider === "third" && sent().model === "model-1", "provider and model");
  chat.activeRun.approvals = [{ id: "approval-1", label: "Run tests?" }];
  by("ma-retry").click();
  await until(() => !!by("ma-approvals").querySelector("[data-approval]"), "approval");
  by("ma-approvals").querySelector("[data-allow='0']").click();
  await until(() => calls.some(x => x.action === "approve" && x.body.approvalId === "approval-1" && x.body.allow === false), "deny approval");
  chat.activeRun = null; chat.turns.at(-1).status = "done"; chat.activeAgent = "agy";
  by("ma-retry").click();
  await until(() => !by("btn-send").classList.contains("busy"), "Google handoff completed");
  by("chat-agent-chip").click();
  select("set-provider", "anthropic");
  by("set-x").click();
  assert(by("ma-mode").value === "manual", "manual handoff from Google");
  assert(!by("ma-warning").classList.contains("hidden"), "low quota");
  by("ma-dismiss").click(); by("ma-retry").click();
  await until(() => by("ma-warning").classList.contains("hidden"), "dismissed quota");
  state.claude.email = "another@example.invalid"; by("ma-retry").click();
  await until(() => !by("ma-warning").classList.contains("hidden"), "new account's quota");
  simulateDrop = "after";
  compose("Start with no attachments");
  await until(() => sent()?.text === "Start with no attachments", "manual send with no selected history");
  assert(sent().selected.length === 0, "no automatic history attached");
  await until(() => by("input").value === "", "server accepted request despite lost acknowledgement");
  await until(() => window.__cc.state.linkedClaude?.has("synthetic-ui-a"), "native ID indexed with its shared chat");
  assert(window.__cc.state.sessions.some(row => row.id === "synthetic-ui-b"), "native list preserved after import");
  assert(!window.__cc.state.sessions.some(row => row.id === "synthetic-ui-a"), "imported conversation appears once");
  window.__cc.openSession("synthetic-ui-b");
  await until(() => window.__cc.view.sessionId === "synthetic-ui-b" && window.__cc.view.histOk, "other native chat");
  window.__cc.openSession("synthetic-ui-a");
  await until(() => window.__cc.view.sessionId === "multi:"+chat.id && by("ma-turns").textContent.includes(first.assistant), "native project link restores the shared conversation");
  checkingLogin = true; state.agy.installed = true;
  by("chat-agent-chip").click();
  await until(() => !by("set-ov").classList.contains("hidden") && by("set-provider").value === "anthropic", "main agent settings");
  select("set-provider", "google");
  await until(() => [...by("set-agent-model").options].some(o => o.value === "fixture-destination"), "official model list");
  select("set-agent-model","fixture-destination");
  await until(() => !by("set-agent-effort-row").classList.contains("hidden"), "Google model effort");
  assert(by("set-agent-tier-row").classList.contains("hidden"), "Fast belongs to Codex");
  assert(by("set-agent-login").textContent.includes("Google") && by("prov-card").classList.contains("hidden"), "only Google authorization is visible");
  select("set-agent-effort", "high");
  await until(() => !by("set-agent-login").disabled, "Google authorization enabled");
  by("set-agent-login").click();
  await until(() => calls.some(x => x.action === "agy-login"), "Google-specific authorization request");
  assert(calls.filter(x => x.action === "login").length === 1, "Google login never starts a second Codex login");
  by("set-x").click();
  window.__cc.newConversation();
  chat.activeRun = null; chat.activeAgent = null; chat.turns.length = 0;
  by("input").value = "New Google project";
  by("input").dispatchEvent(new Event("input"));
  await until(() => !by("btn-send").disabled && !by("btn-send").classList.contains("busy"), "new conversation ready to send");
  by("btn-send").click();
  await until(() => sent()?.text === "New Google project", "send from fresh main conversation");
  assert(sent().agent === "agy" && sent().destinationProvider === "google" && sent().destinationModel === "fixture-destination" && sent().effort === "high" && sent().serviceTier === "", "selected Google model reaches the destination");
  assert(calls.some(x => x.action === "create" && !x.body.claudeSessionId && x.body.title === "New Google project"), "new chat created without importing another session");
  assert(!errors.length, errors.join("\n"));

  const expectedSkins = ["native","qiusuo","zhuyue","goucheng","shise"];
  const skinButtons = [...document.querySelectorAll("#seg-skin [data-sk]")];
  assert(JSON.stringify(skinButtons.map(b=>b.dataset.sk)) === JSON.stringify(expectedSkins), "all five classic themes are accessible");
  window.Theme.setType("fs", "xl");
  for (const button of skinButtons) {
    button.click();
    for (const mode of ["auto","light","dark"]) {
      document.querySelector('#seg-mode [data-md="'+mode+'"]').click();
      for (const night of [false,true]) {
        window.Theme.system(night);
        assert(document.documentElement.dataset.skin===button.dataset.sk, "selected classic theme");
        assert(window.Theme.current===(mode==="auto" ? (night ? "dark":"light") : mode), "classic theme brightness");
        assert(document.documentElement.dataset.fs==="xl", "font size is preserved across classic themes");
      }
    }
  }
}

const fixtureHtml = await fs.readFile(new URL("../gateway/web/index.html", import.meta.url), "utf8");
const profile = await fs.mkdtemp(path.join(os.tmpdir(), "cc-multi-ui-"));
let result, finish, browser, fixture, timer;
const complete = new Promise(resolve => { finish = resolve; });
try {
  fixture = await startManagedUiServer({
    async handleRequest(req, res, url) {
      if (url.pathname === "/") {
        res.writeHead(200, { "content-type": "text/html; charset=utf-8" });
        res.end(fixtureHtml.replace("<head>", "<head><script>(" + appearanceBridgeFixture + ")();</script>").replace("</body>", '<script src="/multi-test.js"></script></body>'));
        return true;
      }
      if (url.pathname === "/multi-test.js") {
        res.writeHead(200, { "content-type": "application/javascript" });
        res.end(`(${checkWorkbench})().then(() => "", e => String(e.stack || e)).then(error => fetch("/multi-result", {method:"POST",body:error}));`);
        return true;
      }
      if (url.pathname === "/multi-result" && req.method === "POST") {
        let error = "";
        for await (const chunk of req) error += chunk.toString();
        res.end("ok"); finish(error);
        return true;
      }
      return false;
    },
  });
  browser = spawn(process.env.CHROMIUM_BIN || "chromium", [
    "--headless", "--disable-gpu", "--disable-dev-shm-usage", "--no-first-run",
    "--no-proxy-server", "--disable-background-networking", "--disable-sync", "--disable-extensions",
    "--host-resolver-rules=MAP * ~NOTFOUND, EXCLUDE 127.0.0.1",
    "--user-data-dir=" + profile, "--window-size=480,860", fixture.origin,
  ], { stdio: "ignore" });
  browser.once("error", e => finish(e.message));
  browser.once("exit", code => finish("Chromium exited before reporting results: " + code));
  timer = setTimeout(() => finish("Timed out waiting for Chromium"), 45000);
  result = await complete;
  if (result) throw Error(result);
  console.log("PASS main composer, Claude import, provider order, isolated Codex/Google login, reasoning and Fast, handoffs, approvals, retry, lost acknowledgements, and quota warnings");
} finally {
  clearTimeout(timer);
  if (browser?.pid && browser.exitCode === null) {
    const closed = new Promise(resolve => browser.once("exit", resolve));
    browser.kill();
    await closed;
  }
  if (fixture) await fixture.close();
  await fs.rm(profile, { recursive: true, force: true, maxRetries: 3 });
}
