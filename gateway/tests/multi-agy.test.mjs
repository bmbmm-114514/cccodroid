import test from "node:test";
import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import { PassThrough, Writable } from "node:stream";
import { Readable } from "node:stream";
import fs from "node:fs";
import path from "node:path";
import { gzipSync } from "node:zlib";
import { runAgy, respondAgy, handoffAgy, loginAgy, agyState, stopAgyLogin } from "../gw/multi-agy.mjs";
import { unpackAgy } from "../gw/multi-agy-install.mjs";
import { CFG } from "../gw/config.mjs";
import { multiBusy } from "../gw/runs.mjs";

const turn = { cwd: "/fixture/work", text: "Review this patch" };
function fake(custom, caps = { sessionCapabilities: { resume: {} } }) {
  const calls = [];
  const options = {
    command: "/fixture/agy", timeoutMs: 500,
    spawnProcess(bin, args) {
      assert.equal(args.includes("--dangerously-skip-permissions"), false);
      const child = new EventEmitter();
      child.stdout = new PassThrough();
      child.stderr = new PassThrough();
      child.send = m => child.stdout.write(JSON.stringify({ jsonrpc: "2.0", ...m }) + "\n");
      child.answer = (msg, result) => child.send({ id: msg.id, result });
      child.update = (id, update) => child.send({ method: "session/update", params: { sessionId: id, update } });
      child.kill = () => { child.signalCode = "SIGTERM"; child.stdout.end(); };
      child.stdin = new Writable({ write(buf, _, cb) {
        const msg = JSON.parse(buf);
        assert.equal(msg.jsonrpc, "2.0");
        calls.push(msg);
        queueMicrotask(() => {
          if (child.signalCode || custom?.(child, msg)) return;
          if (msg.method === "initialize") child.answer(msg, { authMethods: [{ id: "oauth-personal" }], agentCapabilities: caps });
          if (msg.method === "authenticate" || msg.method === "session/resume") child.answer(msg, {});
          if (msg.method === "session/new") child.answer(msg, { sessionId: "sess" });
        });
        cb();
      } });
      return child;
    },
  };
  return { options, calls };
}

test("Antigravity personal OAuth, direct human prompt and single-copy visible response", async () => {
  const { options, calls } = fake((child, m) => {
    if (m.method !== "session/prompt") return;
    for (const [kind, text] of [["agent_thought_chunk","internal"],["agent_message_chunk","ok"],["agent_message","okay"],["agent_message_chunk","okay"]]) {
      child.update("sess", { sessionUpdate: kind, messageId: "a", content: {type:"text", text} });
    }
    child.update("sess", { sessionUpdate:"tool_call", toolCallId:"t", title:"Run tests" });
    child.update("sess", { sessionUpdate:"tool_call_update", toolCallId:"t",status:"completed",content:[{type:"content",content:{type:"text",text:"3 passed"}}] });
    child.answer(m,{stopReason:"end_turn"});
    return true;
  });
  const events=[];
  assert.deepEqual(await runAgy({...turn,onEvent:e=>events.push(e)},options), {sessionId:"sess",text:"okay"});
  assert.deepEqual(events.filter(e=>e.type==="delta").map(e=>e.text),["ok","ay"]);
  assert.deepEqual(events.find(e=>e.type==="tool"),{type:"tool",label:"Run tests",text:"3 passed"});
  assert.deepEqual(calls.find(m=>m.method==="authenticate").params,{methodId:"oauth-personal"});
  assert.deepEqual(calls.find(m=>m.method==="session/prompt").params.prompt,[{type:"text",text:turn.text}]);
});

test("resume the destination's own session, deny unrelated requests and grant only one-time approval", async () => {
  const { options, calls } = fake((child, m) => {
    if (m.method !== "session/prompt") return;
    for (const id of [55,56]) {
      child.send({id,method:"session/request_permission",params:{sessionId:id===55?"other":"original",
        toolCall:{title:"Run tests"},options:[{kind:"allow_always",optionId:"persist"},{kind:"allow_once",optionId:"yes"}]}});
    }
    child.answer(m,{stopReason:"end_turn"});
    return true;
  });
  const ids=[];
  await runAgy({...turn,sessionId:"original",onEvent:e=>{
    if(e.type==="approval"){ids.push(e.id);assert.equal(respondAgy(e.id,true),true);}
  }},options);
  assert.equal(calls.filter(m=>m.method==="session/new").length,0);
  assert.equal(calls.find(m=>m.method==="session/resume").params.sessionId,"original");
  assert.equal(calls.find(m=>m.id===55).result.outcome.outcome,"cancelled");
  assert.equal(calls.find(m=>m.id===56).result.outcome.optionId,"yes");
  assert.equal(respondAgy(ids[0],true),false);
  await assert.rejects(runAgy({...turn,sessionId:"original"},fake(null,{loadSession:true}).options),/不支持/);
  await assert.rejects(handoffAgy(),/手动交接/);
});

test("cancel interrupts a native prompt, clears pending approvals and releases its session",async()=>{
  const controller=new AbortController();
  let promptId;
  const {options,calls}=fake((child,m)=>{
    if(m.method==="session/prompt"){
      promptId=m.id;
      queueMicrotask(()=>controller.abort());
      return true;
    }
    if(m.method==="session/cancel"){child.send({id:promptId,result:{stopReason:"cancelled"}});return true;}
  });
  await assert.rejects(runAgy({...turn,sessionId:"old",signal:controller.signal},options),{name:"AbortError"});
  assert.deepEqual(calls.find(m=>m.method==="session/cancel").params,{sessionId:"old"});
});

test("official Google login URL, one-time code delivery, cancel and retry",async()=>{
  assert.equal(agyState().login,null);
  let cli, submitted="";
  const options={command:"/fake/agy",spawnProcess(_,args){
    cli=new EventEmitter(); cli.stdout=new PassThrough();cli.stderr=new PassThrough();
    cli.stdin=new Writable({write(buf,_,cb){submitted=buf.toString();cb();}});
    cli.kill=()=>{};
    assert.ok(args.includes("--login"));
    return cli;
  }};
  const revision=agyState().accountRevision;
  await loginAgy({},options);
  assert.equal(agyState().accountRevision,revision+1);
  assert.equal(multiBusy(),true);
  cli.stdout.write("https://example.invalid/oauth/start ");
  assert.equal(agyState().login.verificationUri,"");
  cli.stdout.write("Open https://accounts.google.com/o/oauth2/auth?test=1\n");
  assert.equal(agyState().login.needsCode,true);
  assert.match(agyState().login.verificationUri,/accounts.google.com/);
  await loginAgy("offline-fixture");
  assert.equal(submitted,"offline-fixture\n");
  assert.equal(agyState().login.needsCode,false);
  cli.emit("close",0);
  assert.equal(agyState().login,null);
  assert.equal(agyState().loggedIn,true);
  assert.equal(multiBusy(),false);
  await loginAgy({},options);
  stopAgyLogin();
  assert.equal(multiBusy(),false);
  assert.equal(agyState().login,null);
  assert.match(agyState().error,/取消/);
  await assert.rejects(loginAgy("late"),/重新/);
});

test("Android installer rejects unverified or malformed archive",async()=>{
 const dir=await fs.promises.mkdtemp(path.join(CFG.home,"agy-test-"));
 try {
  await assert.rejects(unpackAgy(Readable.from([gzipSync(Buffer.alloc(512))]),path.join(dir,"candidate")),/格式异常/);
 } finally { await fs.promises.rm(dir,{recursive:true,force:true}); }
});
