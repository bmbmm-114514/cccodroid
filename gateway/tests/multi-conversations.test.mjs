import { test } from "node:test";
import assert from "node:assert/strict";
import os from "node:os";
import path from "node:path";
import fs from "node:fs";
import { randomUUID } from "node:crypto";
import { conversationStore } from "../gw/multi-conversations.mjs";

function setup(t) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "multi-chat-"));
  t.after(() => fs.rmSync(dir, { force: true, recursive: true }));
  const calls = [];
  const adapters = {};
  for (const agent of ["claude", "codex", "agy"]) adapters[agent] = {
    async run(args) {
      calls.push({ agent, op: "run", ...args });
      args.onEvent({ type: "thread", threadId: agent + "-native" });
      args.onEvent({ type: "delta", text: agent + " reply" });
      return { sessionId: agent + "-native", text: agent + " reply" };
    },
    async handoff(args) {
      calls.push({ agent, op: "handoff", ...args });
      return { text: agent + " summary" };
    }
  };
  const db = conversationStore(dir, adapters);
  const id = db.create({ cwd: dir });
  async function send(agent, text, options = {}) {
    const req = { agent, id, text, requestId: randomUUID(), ...options };
    db.send(req);
    await db.wait(id);
    return req;
  }
  return { dir, db, id, adapters, calls, send };
}
test("outgoing native session selects context, target resumes its own thread on return", async t => {
  const { db, id, calls, send } = setup(t);
  const req = await send("claude", "design");
  db.send(req);
  await send("codex", "implement", {mode:"third-party",provider:"custom",model:"cheap"});
  await send("claude", "review");
  assert.deepEqual(calls.map(v=>[v.agent,v.op,v.sessionId]), [
    ["claude","run",undefined], ["claude","handoff","claude-native"], ["codex","run",undefined],
    ["codex","handoff","codex-native"], ["claude","run","claude-native"]
  ]);
  assert.equal(calls[1].provider, "custom");
  assert.match(calls[2].text, /claude summary/);
  assert.match(calls[4].text, /codex summary/);
  assert.equal(db.view(id).turns.length,3);
});
test("manual handoff sends only selected visible turns and consumes no selector call", async t => {
  const { db, id, calls, send } = setup(t);
  await send("claude","old unrelated");
  await send("claude","wanted constraint");
  await send("codex","next",{mode:"manual",selected:[db.view(id).turns.at(-1).id]});
  assert.equal(calls.filter(v=>v.op==="handoff").length,0);
  assert.match(calls.at(-1).text,/wanted constraint/);
  assert.doesNotMatch(calls.at(-1).text,/old unrelated/);
});
test("failed handoff leaves the outgoing agent selected, never calls the target", async t => {
  const { db, id, calls, adapters, send } = setup(t);
  await send("claude","source");
  adapters.claude.handoff = async () => { throw new Error("incompatible endpoint"); };
  await send("codex","try");
  assert.equal(db.view(id).activeAgent,"claude");
  assert.match(db.view(id).turns.at(-1).error,/incompatible/);
  assert.equal(calls.length,1);
  await send("codex","try manual",{mode:"manual",selected:[]});
  assert.equal(db.view(id).activeAgent,"codex");
});
test("stop and restart leave a single interrupted turn, no silent rerun", async t => {
  const { dir, db, id, adapters } = setup(t);
  adapters.claude.run = async ({ signal, onEvent }) => {
    onEvent({type:"thread",threadId:"native"});
    await new Promise((_, reject)=>signal.addEventListener("abort",()=>reject(Error("stopped")),{once:true}));
  };
  const request = { id,agent:"claude",text:"hold",requestId:randomUUID() };
  db.send(request);
  assert.throws(()=>db.send({...request,requestId:randomUUID()}),/运行中/);
  const other = db.create({cwd:dir});
  assert.throws(()=>db.send({...request,id:other,requestId:randomUUID()}),/运行中/);
  db.stop(id);
  await db.wait(id);
  const reopened = conversationStore(dir,adapters);
  assert.equal(reopened.view(id).turns.at(-1).status,"interrupted");
  reopened.send(request);
  assert.equal(reopened.view(id).turns.length,1);
});
test("manual Antigravity handoff returns to a previous native session", async t => {
  const { db,id,send,calls } = setup(t);
  await send("claude","plan");
  await send("agy","review",{mode:"manual",selected:[db.view(id).turns[0].id]});
  assert.throws(()=>db.send({id,agent:"claude",text:"back",requestId:randomUUID()}),/手动/);
  await send("claude","back",{mode:"manual",selected:[db.view(id).turns.at(-1).id]});
  assert.equal(calls.at(-1).sessionId,"claude-native");
  assert.match(calls.at(-1).text,/agy reply/);
  assert.equal(calls.filter(x => x.op === "handoff").length,0);
});
test("a damaged conversation is isolated; imported native history is linked only once", t => {
  const {dir,adapters,db} = setup(t);
  const origin = randomUUID();
  const id = db.create({cwd:dir,origin});
  const brokenId = randomUUID();
  fs.writeFileSync(path.join(dir,brokenId+".json"),"broken");
  const reopened = conversationStore(dir,adapters);
  assert.equal(reopened.create({cwd:dir,origin}),id);
  assert.throws(()=>reopened.view(brokenId),/损坏/);
  assert.ok(reopened.view(id));
  assert.equal(fs.readFileSync(path.join(dir,brokenId+".json"),"utf8"),"broken");
});
test("an unavailable destination does not spend the outgoing provider's quota", async t => {
  const {db,id,send,calls,adapters} = setup(t);
  await send("claude","existing task");
  adapters.codex.ready = async () => { throw new Error("signed out"); };
  await send("codex","continue");
  assert.equal(calls.length,1);
  assert.equal(db.view(id).activeAgent,"claude");
  assert.equal(db.view(id).turns.at(-1).error,"signed out");
});
