import { test } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { randomUUID } from "node:crypto";
import { CFG, PATHS } from "../gw/config.mjs";
import { projectSlug } from "../gw/projects.mjs";
import { routeMulti } from "../gw/multi.mjs";

test("multi HTTP imports public Claude turns once, restores its project, and serves explicit account/limit state", async t => {
  const sid = randomUUID();
  const dir = fs.mkdtempSync(path.join(CFG.workspace, "multi-http-"));
  const folder = path.join(PATHS.projects, projectSlug(dir));
  fs.mkdirSync(folder,{recursive:true});
  t.after(() => { fs.rmSync(folder,{recursive:true,force:true}); fs.rmSync(dir,{recursive:true,force:true}); });
  const history = [
    {type:"user",uuid:randomUUID(),cwd:dir,message:{content:"please implement the second design"}},
    {type:"assistant",uuid:randomUUID(),cwd:dir,message:{content:[{type:"thinking",thinking:"hidden control marker"},{type:"text",text:"chosen design B"}]}},
    {type:"user",cwd:dir,message:{content:[{type:"tool_result",tool_use_id:"a1",content:"tests passed"}]}}
  ];
  fs.writeFileSync(path.join(folder,sid+".jsonl"), history.map(x=>JSON.stringify(x)).join("\n")+"\n");
  const server = http.createServer(async (req,res) => {
    const url = new URL(req.url,"http://localhost");
    await routeMulti(req,res,url,url.pathname);
  });
  await new Promise(resolve=>server.listen(0,"127.0.0.1",resolve));
  t.after(()=>{ server.closeAllConnections(); return new Promise(resolve=>server.close(resolve)); });
  const base = "http://127.0.0.1:"+server.address().port+"/api/multi/";
  const post = (url, body) => fetch(base+url,{method:"POST",body:JSON.stringify(body)});
  const original = await post("create",{claudeSessionId:sid});
  assert.equal(original.status,201);
  const {id} = await original.json();
  assert.equal((await (await post("create",{claudeSessionId:sid})).json()).id,id);
  const state = await (await fetch(base+"state?id="+id)).json();
  assert.equal(state.conversation.activeAgent,"claude");
  assert.equal(state.conversation.turns.length,1);
  assert.equal(state.conversation.turns[0].assistant,"chosen design B");
  assert.equal(state.conversation.turns[0].tools[0].text,"tests passed");
  assert.doesNotMatch(JSON.stringify(state),/hidden control marker/);
  assert.deepEqual(state.quota.agy,[]);
  assert.equal(typeof state.codex.installed,"boolean");
  assert.equal(typeof state.agy.installed,"boolean");
  const dup = {id,agent:"codex",mode:"manual",text:"review",selected:[],requestId:randomUUID()};
  assert.equal((await post("send",dup)).status,200);
  assert.equal((await post("send",dup)).status,200);
  assert.equal((await post("send",{...dup,agent:"claude"})).status,409);
  assert.equal((await post("send",null)).status,400);
});
