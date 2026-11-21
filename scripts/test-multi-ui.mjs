// Run with Node and a local Chromium: CHROMIUM_BIN=chromium node scripts/test-multi-ui.mjs.
// The browser runs the real workbench. All accounts, prompts and approval requests are fixtures.
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { spawn } from "node:child_process";
import { startManagedUiServer } from "../gateway/tests/fixtures/managed-ui-server.mjs";

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
    by("ma-input").value = text;
    by("ma-input").dispatchEvent(new Event("input"));
    by("ma-send").click();
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
  let checkingLogin = false;
  window.addEventListener("error", event => errors.push(event.message));
  window.fetch = async (u, opts) => {
    const addr = new URL(u, location.href);
    if (checkingLogin && addr.pathname === "/api/providers") {
      return Response.json({ active: "fixture", providers: [
        { id: "anthropic", login: true, group: "claude", label: "Claude Code" },
        { id: "fixture", group: "custom", custom: true, active: true, label: "Fixture" },
      ] });
    }
    if (checkingLogin && addr.pathname === "/api/login/status") return Response.json({ loggedIn: false });
    if (!addr.pathname.startsWith("/api/multi/")) return original(u, opts);
    const action = addr.pathname.split("/").pop(), body = opts?.body ? JSON.parse(opts.body) : {};
    calls.push({ action, body });
    let reply = { ok: true };
    if (action === "state") reply = { ...state, conversation: addr.searchParams.get("id") === chat.id ? chat : null };
    if (action === "send") {
      chat.turns.push({ id: "turn-" + (chat.turns.length + 1), requestId: body.requestId, agent: body.agent, user: body.text, assistant: "", status: "running" });
      chat.activeRun = { stage: "handoff", agent: body.agent, text: "", approvals: [] };
    }
    if (action === "stop") {
      chat.turns.at(-1).status = "interrupted"; chat.turns.at(-1).error = "fixture stopped";
      chat.activeRun = null;
    }
    return new Response(JSON.stringify(reply), { headers: { "content-type": "application/json" } });
  };
  const sent = () => calls.filter(x => x.action === "send").at(-1)?.body;
  await until(() => !!window.__cc, "app startup");
  by("btn-menu").click();
  await until(() => by("side-nav").querySelector("[data-page=multi]"), "multi-agent navigation");
  by("side-nav").querySelector("[data-page=multi]").click();
  await until(() => by("ma-conversations").options.length === 2, "conversation menu");
  select("ma-conversations", chat.id);
  await until(() => by("ma-turns").textContent.includes(first.assistant), "restored conversation");
  const toolRecord = by("ma-turns").querySelector("details[data-detail$='-tools']");
  assert(toolRecord && !toolRecord.open && toolRecord.textContent.includes("<script>fixture</script>") && !toolRecord.querySelector("script"), "collapsed, escaped tool evidence");
  select("ma-agent", "codex");
  select("ma-mode", "manual");
  by("ma-checks").querySelector("input").click();
  compose("Review A");
  await until(() => sent()?.agent === "codex" && !by("ma-stop").classList.contains("hidden"), "Codex turn");
  assert(sent().selected.join() === first.id && sent().mode === "manual", "selected history");
  by("ma-stop").click();
  await until(() => by("ma-turns").textContent.includes("fixture stopped"), "interrupted turn");
  by("ma-turns").querySelector("[data-retry-turn]").click();
  select("ma-mode", "third-party"); select("ma-provider", "third");
  by("ma-send").click();
  await until(() => sent()?.mode === "third-party", "third-party retry");
  assert(sent().provider === "third" && sent().model === "model-1", "provider and model");
  chat.activeRun.approvals = [{ id: "approval-1", label: "Run tests?" }];
  by("ma-retry").click();
  await until(() => !!by("ma-approvals").querySelector("[data-approval]"), "approval");
  by("ma-approvals").querySelector("[data-allow='0']").click();
  await until(() => calls.some(x => x.action === "approve" && x.body.approvalId === "approval-1" && x.body.allow === false), "deny approval");
  chat.activeRun = null; chat.turns.at(-1).status = "done"; chat.activeAgent = "agy";
  by("ma-retry").click();
  await until(() => by("ma-agent").value === "agy", "restore Google selection");
  select("ma-agent", "claude");
  assert(by("ma-mode").value === "manual", "manual handoff from Google");
  assert(!by("ma-warning").classList.contains("hidden"), "low quota");
  by("ma-dismiss").click(); by("ma-retry").click();
  await until(() => by("ma-warning").classList.contains("hidden"), "dismissed quota");
  state.claude.email = "another@example.invalid"; by("ma-retry").click();
  await until(() => !by("ma-warning").classList.contains("hidden"), "new account's quota");
  compose("Start with no attachments");
  await until(() => sent()?.text === "Start with no attachments", "manual send with no selected history");
  assert(sent().selected.length === 0, "no automatic history attached");
  checkingLogin = true;
  by("ma-accounts-btn").click(); by("ma-claude-settings").click();
  await until(() => by("set-provider").value === "anthropic" && by("login-actions")?.querySelector("[data-act=login]"), "official Claude login card");
  assert(!by("prov-card").classList.contains("hidden"), "Claude login card expanded");
  assert(!errors.length, errors.join("\n"));
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
        res.end(fixtureHtml.replace("</body>", '<script src="/multi-test.js"></script></body>'));
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
  console.log("PASS multi-agent chat, handoff, approval, retry, empty selection and per-account quota warning");
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
