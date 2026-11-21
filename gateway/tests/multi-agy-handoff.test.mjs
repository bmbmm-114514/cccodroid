import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { EventEmitter } from "node:events";
import { PassThrough, Writable } from "node:stream";
import { handoffAgy, readAgyHandoffHistory } from "../gw/multi-agy.mjs";
import { agyHistory, recordedAgyHistory } from "../gw/multi-agy-history.mjs";
import { CFG } from "../gw/config.mjs";

const source = { sessionId: "source", cwd: CFG.workspace, text: "根据下一个任务整理摘要" };
const parts = text => ({ type: "text", text });
function fixture({ load, newId = "summary", controller, loadSupported = true } = {}) {
  const calls = [], children = [], scopes = [];
  function spawnProcess(_file, args, { cwd, env }) {
    const summary = args.includes("--agent");
    assert.equal(env.AGY_CLI_DISABLE_AUTO_UPDATE, "true");
    assert.equal(JSON.parse(args[1]).toolPermission, "strict");
    assert.ok(JSON.parse(args[1]).permissions.deny.includes("command(*)"));
    assert.ok(args.includes("--include-default-customizations=false"));
    if (summary) {
      const persona = args[args.indexOf("--agent") + 1];
      assert.match(fs.readFileSync(path.join(cwd, ".agents", "agents", persona + ".md"), "utf8"), /^tools: \[\]$/m);
      assert.notEqual(cwd, source.cwd);
      scopes.push(cwd);
    }
    const c = new EventEmitter();
    children.push(c);
    c.stdout = new PassThrough(); c.stderr = new PassThrough();
    const send = msg => c.stdout.write(JSON.stringify({ jsonrpc: "2.0", ...msg }) + "\n");
    const update = (id, data) => send({ method: "session/update", params: { sessionId: id, update: data } });
    c.kill = () => {
      if (!c.signalCode) { c.signalCode = "SIGTERM"; c.stdout.end(); queueMicrotask(() => c.emit("close", null, "SIGTERM")); }
    };
    c.stdin = new Writable({ write(buf, _, cb) {
      const msg = JSON.parse(buf);
      calls.push({ summary, ...msg });
      queueMicrotask(() => {
        if (c.signalCode || !msg.method) return;
        let result = {};
        if (msg.method === "initialize") {
          result = { authMethods: [{id: "oauth-personal"}], agentCapabilities: {loadSession: loadSupported} };
        } else if (msg.method === "authenticate") {
          assert.equal(msg.params.methodId, "oauth-personal");
        } else if (msg.method === "session/load") {
          assert.equal(summary, false);
          if (controller) { controller.abort(); return; }
          if (load) load(update);
          else {
            update("other", { sessionUpdate: "user_message", content: parts("other session") });
            update("source", { sessionUpdate: "user_message_chunk", messageId: "u", content: parts("需要交接") });
            update("source", { sessionUpdate: "agent_thought_chunk", content: parts("private thought") });
            update("source", { sessionUpdate: "agent_message_chunk", messageId: "r", content: parts("已完") });
            update("source", { sessionUpdate: "agent_message", messageId: "r", content: [parts("已完成")] });
            update("source", { sessionUpdate: "tool_call", toolCallId: "t", title: "测试", rawInput: "private args" });
            update("source", { sessionUpdate: "tool_call_update", toolCallId: "t", rawOutput: "private raw output",
              content: [{type: "content", content: parts("单测通过")}] });
          }
          result = { models: {currentModelId: "google-primary"} };
        } else if (msg.method === "session/new") {
          assert.equal(summary, true);
          assert.deepEqual(msg.params.mcpServers, []);
          result = { sessionId: newId, models: { currentModelId: "other", availableModels: [{modelId:"google-primary"}] } };
        } else if (msg.method === "session/set_model") {
          assert.equal(msg.params.modelId, "google-primary");
          assert.equal(msg.params.sessionId, "summary");
        } else if (msg.method === "session/prompt") {
          assert.equal(summary, true);
          assert.equal(msg.params.sessionId, "summary");
          send({ id: 91, method: "session/request_permission", params: {sessionId: "summary"} });
          update("summary", { sessionUpdate: "agent_message_chunk", content: parts("交接摘要") });
          result = { stopReason: "end_turn" };
        }
        send({ id: msg.id, result });
      });
      cb();
    } });
    return c;
  }
  return { opts: { command: "/fixture/agy", spawnProcess, timeoutMs: 1000 }, children, calls, scopes };
}

test("AGY automatically hands off the visible source history in a separate tool-restricted personal session", async () => {
  const { opts, calls, children, scopes } = fixture();
  assert.deepEqual(await handoffAgy(source, opts), {text: "交接摘要"});
  const prompt = calls.find(c => c.method === "session/prompt");
  for (const visible of ["需要交接", "已完成", "单测通过", source.text]) assert.ok(prompt.params.prompt[0].text.includes(visible));
  assert.equal(prompt.params.prompt[0].text.match(/已完/g).length, 1);
  assert.doesNotMatch(prompt.params.prompt[0].text, /private|other session/);
  assert.equal(calls.filter(c => !c.summary).some(c => c.method === "session/prompt"), false);
  assert.equal(calls.find(c => c.id === 91).result.outcome.outcome, "cancelled");
  assert.equal(children.filter(c => c.signalCode).length, 2);
  assert.equal(fs.existsSync(scopes[0]), false);
});

test("AGY falls back to visible app turns when a legacy or partial session replay is unavailable", async () => {
  const turns = [
    { agent: "codex", user: "other agent" },
    { agent: "agy", user: "earlier decision", assistant: "keep fixture B" },
    { agent: "agy", user: "newest question", assistant: "use fixture C", toolRaw: "secret",
      tools: [{ label: "test", text: "passed" }], handoff: { text: "previous handoff" } }
  ];
  const { text } = await readAgyHandoffHistory({ ...source, turns }, fixture().opts);
  assert.match(text, /previous handoff/);
  assert.match(text, /newest question/);
  const partial = fixture({ load(update) {
    update("source", { sessionUpdate: "user_message", content: parts("newest question") });
    update("source", { sessionUpdate: "agent_message", content: parts("use fixture C") });
  } });
  assert.match((await readAgyHandoffHistory({ ...source, turns }, partial.opts)).text, /keep fixture B/);
  assert.match(text, /passed/);
  assert.match(text, /use fixture C/);
  assert.doesNotMatch(text, /other agent|secret/);
  assert.equal((await readAgyHandoffHistory({ ...source, turns }, fixture({loadSupported:false}).opts)).text, text);
  await assert.rejects(readAgyHandoffHistory(source, fixture({loadSupported:false}).opts), /手动交接/);
});

test("cancellation during source replay prevents fallback and never opens a summary session", async () => {
  const controller = new AbortController();
  const { opts, calls, children } = fixture({ controller });
  const turns = [{ agent: "agy", user: "old visible question" }];
  await assert.rejects(handoffAgy({ ...source, turns, signal: controller.signal }, opts), {name: "AbortError"});
  assert.equal(calls.some(c => c.summary), false);
  assert.equal(children[0].signalCode, "SIGTERM");
});

test("a worker returning the original session ID is rejected before any prompt", async () => {
  const { opts, calls } = fixture({ newId: source.sessionId });
  await assert.rejects(handoffAgy(source, opts), /独立会话/);
  assert.equal(calls.some(c => c.method === "session/prompt"), false);
});

test("oversize histories retain bounded, labeled recent context", () => {
  const turns = Array.from({length: 50}, (_, i) => ({agent: "agy", user: "x".repeat(25000), assistant: "result " + i}));
  const text = recordedAgyHistory(turns);
  assert.ok(text.length <= 64000);
  assert.match(text, /省略/);
  assert.match(text, /result 49$/);
  const history = agyHistory();
  history.update({ sessionUpdate:"agent_message",content:[{ type:"resource",resource:{text:"attachment"} }]});
  assert.equal(history.render(), "");
});
