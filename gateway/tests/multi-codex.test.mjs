import test from "node:test";
import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import { PassThrough, Writable } from "node:stream";
import { createCodexClient } from "../gw/multi-codex.mjs";

// Uses stdio streams so split frames, RPC order, disconnect and replies exercise the same client as a native process.
function fixture(handle, overrides = {}) {
  const processes = [];
  const calls = [];
  const client = createCodexClient({
    command: "/fixture/codex-app-server", args: [], timeoutMs: 750, interruptTimeoutMs: 100,
    ...overrides,
    spawnProcess(command, args, opts) {
      const child = new EventEmitter();
      const out = new PassThrough();
      Object.assign(child, {
        command, args, opts, stdout: out, stderr: new PassThrough(),
        kill() { child.signalCode = "SIGTERM"; out.end(); },
        send(msg) {
          const data = JSON.stringify(msg) + "\n";
          const split = Math.floor(data.length / 2);
          out.write(data.slice(0, split));
          out.write(data.slice(split));
        },
        reply(id, result) { child.send({ id, result }); },
        notify(method, params) { child.send({ method, params }); },
      });
      child.stdin = new Writable({
        write(buffer, enc, cb) {
          const msg = JSON.parse(buffer.toString());
          calls.push(msg);
          queueMicrotask(() => {
            if (child.signalCode || handle?.(child, msg)) return;
            const responses = {
              initialize: {}, "account/read": { account: { type: "chatgpt" } },
              "account/login/start": { type: "chatgptDeviceCode", loginId: "login", verificationUrl: "https://auth.openai.com/codex/device", userCode: "fixture-code" },
              "account/rateLimits/read": { rateLimits: { primary: { usedPercent: 96 } } },
              "thread/start": { thread: { id: "source" }, modelProvider: "openai" },
              "thread/resume": { thread: { id: msg.params?.threadId }, modelProvider: "openai" },
              "thread/fork": { thread: { id: "fork" }, modelProvider: msg.params?.modelProvider },
              "turn/start": { turn: { id: "turn" } }, "turn/interrupt": {},
            };
            if (msg.method in responses) child.reply(msg.id, responses[msg.method]);
          });
          cb();
        },
      });
      processes.push(child);
      return child;
    },
  });
  return { client, processes, calls };
}

const params = { cwd: "/fixture/project", text: "look at the file" };
const complete = (child, threadId, status = "completed", text = "finished") =>
  child.notify("turn/completed", { threadId, turn: {
    id: "turn", status, items: [{ type: "agentMessage", id: "message", text }],
  } });

test("official login, limit snapshots, events and recovery after a failed initialization", async (t) => {
  let first = true;
  const events = [];
  const { client, processes, calls } = fixture((child, msg) => {
    if (msg.method === "initialize" && first) {
      first = false;
      child.send({ id: msg.id, error: { code: -1, message: "fixture error" } });
      return true;
    }
  }, { onEvent: (event) => events.push(event) });
  t.after(() => client.close());
  await assert.rejects(client.account(), /fixture error/);
  const [acct, login, limits] = await Promise.all([client.account(), client.login(), client.limits()]);
  assert.equal(acct.account.type, "chatgpt");
  assert.equal(login.type, "chatgptDeviceCode");
  assert.equal(limits.rateLimits.primary.usedPercent, 96);
  assert.equal(processes.length, 2);
  assert.deepEqual(calls.find((m) => m.method === "account/login/start").params, { type: "chatgptDeviceCode" });
  const child = processes[1];
  child.notify("account/rateLimits/updated", limits);
  child.notify("account/login/completed", { success: true, loginId: "login" });
  await new Promise((r) => setImmediate(r));
  assert.deepEqual(events.map((e) => e.type), ["quota", "login", "account"]);
});

test("ChatGPT-only run, stream then interrupt, resume same thread without duplicating replay or streamed items", async (t) => {
  let second = false;
  const { client, calls } = fixture((child, msg) => {
    if (msg.method === "turn/start") {
      const threadId = msg.params.threadId;
      child.notify("item/agentMessage/delta", { threadId, turnId: "past", itemId: "old", delta: "old reply" });
      child.notify("item/agentMessage/delta", { threadId, turnId: "turn", itemId: "message", delta: "fin" });
      child.reply(msg.id, { turn: { id: "turn" } });
      if (second) {
        child.notify("item/completed", { threadId, turnId: "turn", item: { id: "message", type: "agentMessage", text: "finished" } });
        complete(child, threadId);
      }
      return true;
    }
    if (msg.method === "turn/interrupt") {
      child.reply(msg.id, {});
      complete(child, msg.params.threadId, "interrupted");
      return true;
    }
  });
  t.after(() => client.close());
  const controller = new AbortController();
  const events = [];
  await assert.rejects(client.run({ ...params, signal: controller.signal,
    onEvent(e) { events.push(e); if (e.type === "delta") controller.abort(); },
  }), { name: "AbortError" });
  assert.deepEqual(events.map(e => e.type), ["thread", "delta"]);
  assert.equal(events[1].text, "fin");
  assert.deepEqual(calls.find(m => m.method === "turn/interrupt").params, { threadId: "source", turnId: "turn" });
  second = true;
  assert.deepEqual(await client.run({ ...params, threadId: "source" }), { text: "finished", threadId: "source" });
  const resume = calls.find(m => m.method === "thread/resume");
  assert.equal(resume.params.modelProvider, "openai");
  assert.equal(resume.params.sandbox, "workspace-write");
});

test("approvals are scoped to an active turn, reject unknown methods, and expire on disconnect", async (t) => {
  const { client, calls, processes } = fixture((child, msg) => {
    if (msg.method !== "turn/start") return;
    child.reply(msg.id, { turn: { id: "turn" } });
    for (const [i, method, turnId] of [
      [434, "item/fileChange/requestApproval", "other"], [435, "item/commandExecution/requestApproval", "turn"],
      [436, "unknown/approval", "turn"], [437, "item/permissions/requestApproval", "turn"],
    ]) child.send({ id: i, method, params: { threadId: "source", turnId } });
    return true;
  });
  t.after(() => client.close());
  const approvals = [];
  await assert.rejects(client.run({ ...params, onEvent(e) {
    if (e.type !== "approval") return;
    approvals.push(e.id);
    if (approvals.length === 1) {
      assert.equal(client.respond(e.id, true), true);
      assert.equal(client.respond(e.id, true), false);
    } else processes[0].emit("exit", 1);
  } }), /退出/);
  assert.equal(approvals.length, 2);
  assert.deepEqual(calls.find(m => m.id === 434).result, { decision: "decline" });
  assert.deepEqual(calls.find(m => m.id === 435).result, { decision: "accept" });
  assert.equal(calls.find(m => m.id === 436).error.code, -32601);
  assert.equal(client.respond(approvals[1], true), false);
});

test("handoff forks source with explicit Responses provider, denies tool approval, keeps key out of RPC", async (t) => {
  const provider = { id: "Elsewhere", baseUrl: "https://example.invalid/v1", apiKey: "fixture-provider-key" };
  const { client, calls, processes } = fixture((child, msg) => {
    if (msg.method !== "turn/start") return;
    child.reply(msg.id, { turn: { id: "turn" } });
    child.send({ id: 444, method: "item/fileChange/requestApproval", params: { threadId: "fork", turnId: "turn" } });
    complete(child, "fork");
    return true;
  }, { env: { OPENAI_API_KEY: "fixture-unwanted" } });
  t.after(() => client.close());
  await client.account();
  const result = await client.handoff({ ...params, threadId: "source", provider, model: "transfer-model" });
  assert.equal(result.threadId, "fork");
  assert.equal(processes.length, 2);
  const fork = calls.find(m => m.method === "thread/fork");
  assert.equal(fork.params.threadId, "source");
  assert.equal(fork.params.model, "transfer-model");
  assert.equal(fork.params.sandbox, "read-only");
  const config = fork.params.config.model_providers[fork.params.modelProvider];
  assert.equal(config.wire_api, "responses");
  assert.equal(config.requires_openai_auth, false);
  assert.equal(JSON.stringify(calls).includes(provider.apiKey), false);
  assert.equal(processes[1].opts.env[config.env_key], provider.apiKey);
  assert.equal(processes[0].opts.env[config.env_key], undefined);
  assert.equal(processes[1].opts.env.OPENAI_API_KEY, undefined);
  assert.equal(calls.find(m => m.id === 444).result.decision, "decline");
});

test("API key accounts cannot start subscription turns; invalid third-party config never falls back", async (t) => {
  const { client, calls, processes } = fixture((child, msg) => {
    if (msg.method === "account/read") { child.reply(msg.id, { account: { type: "apiKey" } }); return true; }
  });
  t.after(() => client.close());
  await assert.rejects(client.run(params), /ChatGPT/);
  await assert.rejects(client.handoff({ ...params, threadId: "source", provider: { baseUrl: "file:///etc" }, model: "third" }), /地址/);
  assert.equal(processes.length, 1);
  assert.equal(calls.some(m => m.method === "turn/start"), false);
});

test("native model catalog follows cursors, excludes hidden rows and selected model reaches resume and turn", async (t) => {
  const { client, calls } = fixture((child, msg) => {
    if (msg.method === "model/list") {
      const data = msg.params.cursor ? [{ id: "ui-b", model: "model-b", displayName: "Second" }]
        : [{ id: "ui-a", model: "model-a", displayName: "First", isDefault: true }, { id: "hidden", hidden: true }];
      child.reply(msg.id, { data, nextCursor: msg.params.cursor ? null : "next" });
      return true;
    }
    if (msg.method === "turn/start") {
      child.reply(msg.id, { turn: { id: "turn" } });
      complete(child, msg.params.threadId);
      return true;
    }
  });
  t.after(() => client.close());
  assert.deepEqual(await client.models(), {
    models: [{ id: "model-a", label: "First" }, { id: "model-b", label: "Second" }], defaultModel: "model-a",
    effortModel: "model-a", efforts: [], defaultEffort: "", serviceTiers: [], defaultServiceTier: "",
  });
  for (const [model, expected] of [["model-b", "model-b"], ["", "model-a"]]) {
    await client.run({ ...params, threadId: "source", model });
    for (const method of ["thread/resume","turn/start"]) assert.equal(calls.filter(m => m.method === method).at(-1).params.model, expected);
  }
});

test("an explicit Codex model cannot silently run a different model", async (t) => {
  const { client, calls } = fixture((child, msg) => {
    if (msg.method === "thread/start") {
      child.reply(msg.id, { thread: { id: "native" }, model: "different", modelProvider: "openai" });
      return true;
    }
  });
  t.after(() => client.close());
  await assert.rejects(client.run({ ...params, model: "chosen" }), /未采用所选模型/);
  assert.equal(calls.some(m => m.method === "turn/start"),false);
});

test("Codex uses the selected model's advertised reasoning and speed, and resets both on resume", async t => {
  let threadFast = true;
  const modelA = { model: "A", isDefault: true, defaultReasoningEffort: "medium",
    supportedReasoningEfforts: [{ reasoningEffort: "medium" }, { reasoningEffort: "xhigh", description: "More reasoning" }],
    serviceTiers: [{ id: "priority", name: "Fast", description: "Increased usage" }] };
  const modelB = { model: "B", defaultReasoningEffort: "low", supportedReasoningEfforts: [{ reasoningEffort: "low" }],
    defaultServiceTier: "priority", serviceTiers: modelA.serviceTiers };
  const {client, calls} = fixture((child, msg) => {
    if (msg.method === "model/list") child.reply(msg.id, { data: [modelA, modelB] });
    else if (msg.method === "experimentalFeature/list") child.reply(msg.id, { data: [{ name: "fast_mode", enabled: !msg.params.threadId || threadFast }] });
    else if (msg.method === "turn/start") {
      child.reply(msg.id, { turn: { id: "turn" } });
      complete(child, msg.params.threadId);
    } else return;
    return true;
  });
  t.after(() => client.close());
  const info = await client.models("");
  assert.equal(info.effortModel, "A");
  assert.deepEqual(info.efforts.map(e => e.id), ["medium", "xhigh"]);
  assert.deepEqual(info.serviceTiers.map(t => [t.id, t.label]), [["default", "标准"], ["priority", "Fast"]]);
  assert.equal((await client.models("B")).defaultServiceTier, "priority");
  assert.deepEqual((await client.models("custom")).efforts, []);
  const tuning = () => calls.filter(m => m.method === "turn/start").map(m => [m.params.effort, m.params.serviceTier]);
  await client.run({ ...params, model: "A", effort: "xhigh", serviceTier: "priority" });
  await client.run({ ...params, threadId: "source", model: "A", effort: "", serviceTier: "" });
  await client.run({ ...params, threadId: "source", model: "B", effort: "", serviceTier: "" });
  await client.run({ ...params, threadId: "source", model: "B", effort: "low", serviceTier: "default" });
  assert.deepEqual(tuning(), [["xhigh", "priority"], ["medium", "default"], ["low", "priority"], ["low", "default"]]);
  await assert.rejects(client.run({ ...params, model: "B", effort: "xhigh" }), /思考强度/);
  threadFast = false;
  await assert.rejects(client.run({ ...params, model: "A", serviceTier: "priority" }), /服务档位/);
  assert.equal(tuning().length, 4);
});
