import { CFG, PATHS } from "../gw/config.mjs";
import "./fixtures/uc-baseline.mjs";
import { after, test } from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import fs from "node:fs";
import path from "node:path";

const dir = fs.mkdtempSync(path.join(CFG.workspace, "multi-claude-"));
const sessionId = randomUUID();
const loader = path.join(dir, "fixture.cjs");
const providerFile = path.join(CFG.home, "ccapp-providers.json");
const beforeProviders = fs.existsSync(providerFile) ? fs.readFileSync(providerFile) : null;
const priorPaths = { claude: PATHS.claude, claudeLoader: PATHS.claudeLoader };
fs.writeFileSync(providerFile, JSON.stringify({ active: "anthropic", providers: {
  "custom-multi-fixture": { label: "Handoff fixture", baseUrl: "https://fixture.invalid/v1", apiKey: "fixture", defaultModel: "selector" },
} }));
PATHS.claudeLoader = loader;
PATHS.claude = process.execPath;
const { runClaude, handoffClaude, claudeProviders, claudeModels } = await import("../gw/multi-claude.mjs");
const { SETTINGS } = await import("../gw/settings-store.mjs");
const beforeTitle = SETTINGS.autoTitle;
SETTINGS.autoTitle = false;
const priorFetch = globalThis.fetch;
globalThis.fetch = async () => { throw new Error("offline fixture"); };
after(() => {
  globalThis.fetch = priorFetch;
  Object.assign(PATHS, priorPaths);
  SETTINGS.autoTitle = beforeTitle;
  if (beforeProviders) fs.writeFileSync(providerFile, beforeProviders);
  else fs.rmSync(providerFile, { force: true });
  fs.rmSync(dir, { recursive: true, force: true });
});

function useScript(body) {
  fs.writeFileSync(loader, "#!" + process.execPath + "\n" + body, { mode: 0o700 });
}

test("Claude work keeps the subscription route, exposes the native ID, and answers tool approval", { timeout: 15000 }, async () => {
  useScript(`
    const {createInterface} = require("node:readline");
    const emit = v => console.log(JSON.stringify(v));
    const args = process.argv;
    if (process.env.CCAPP_PROVIDER !== "anthropic" || process.env.ANTHROPIC_API_KEY || process.env.ANTHROPIC_BASE_URL) process.exit(3);
    if (args[args.indexOf("--permission-mode") + 1] !== "default" || args[args.indexOf("--model") + 1] !== "sonnet") process.exit(4);
    const reader = createInterface({input:process.stdin});
    reader.on("line", l => {
      const v = JSON.parse(l);
      if (v.type === "user") {
        emit({type:"system",subtype:"init",session_id:${JSON.stringify(sessionId)},model:"fixture",tools:[]});
        emit({type:"control_request", request_id:"approve-1", request:{ subtype:"can_use_tool",tool_name:"Write",input:{file_path:"work.txt",content:"Hello"}}});
      }
      if (v.type === "control_response") {
        if (v.response.response.behavior !== "allow") process.exit(5);
        emit({type:"assistant",message:{content:[{type:"text",text:"finished"}]}});
        emit({type:"result",subtype:"success",result:"finished",is_error:false,num_turns:1,usage:{output_tokens:1}});
        reader.close(); process.stdin.destroy();
      }
    });
  `);
  const seen = [];
  assert.ok((await claudeModels()).models.some(m => m.id === "sonnet" && m.label));
  await assert.rejects(runClaude({ cwd: dir, text: "wrong model", model: "not-a-claude-model" }), /不支持所选模型/);
  const result = await runClaude({ cwd: dir, text: "new work", model: "sonnet", onEvent(ev) {
    seen.push(ev.type);
    if (ev.type === "approval") { assert.match(ev.label, /work.txt/); ev.respond(true); }
  } });
  assert.ok(seen.includes("approval"));
  assert.ok(seen.includes("thread"));
  assert.ok(seen.includes("delta"));
  assert.equal(result.sessionId, sessionId);
  assert.equal(result.text, "finished");
});

test("third-party handoff forks the source, disables tools and uses only the selected credentials", async () => {
  useScript(`
    const fs = require("node:fs");
    const prompt = fs.readFileSync(0,"utf8");
    const args = process.argv, val = k => args[args.indexOf(k)+1];
    const settings = JSON.parse(val("--settings"));
    const good = args.includes("--fork-session") && args.includes("--no-session-persistence")
      && val("--resume") === ${JSON.stringify(sessionId)} && val("--model") === "selector"
      && args.includes("--strict-mcp-config") && val("--tools") === "" && settings.disableAllHooks
      && settings.apiKeyHelper && !settings.hooks && !process.env.CLAUDE_CODE_OAUTH_TOKEN
      && process.env.CCAPP_PROVIDER === "custom-multi-fixture" && prompt === "handoff next work";
    console.log(JSON.stringify({result:good ? "source context" : "unexpected fixture arguments",is_error:!good}));
  `);
  assert.ok(claudeProviders().some(p => p.id === "custom-multi-fixture"));
  const out = await handoffClaude({ cwd: dir, sessionId, provider: "custom-multi-fixture", text: "handoff next work" });
  assert.equal(out.text, "source context");
  await assert.rejects(handoffClaude({ cwd: dir, sessionId, provider: "deleted", text: "budget" }), /不可用/);
  await assert.rejects(handoffClaude({ cwd: dir, sessionId, provider: "anthropic", text: "budget" }), /不可用/);
});

test("a handoff with a stalled CLI releases on cancel", async () => {
  useScript("process.stdin.resume(); setInterval(() => {}, 1000);");
  const ac = new AbortController();
  const running = handoffClaude({ cwd: dir, sessionId, signal: ac.signal, text: "hand off" });
  const timer = setTimeout(() => ac.abort(), 80);
  try { await assert.rejects(running, /停止/); } finally { clearTimeout(timer); }
});
