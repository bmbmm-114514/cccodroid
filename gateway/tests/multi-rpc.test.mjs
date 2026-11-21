import assert from "node:assert/strict";
import test from "node:test";
import { createCodexClient } from "../gw/multi-codex.mjs";
import { AgentTransport } from "../gw/multi-rpc.mjs";

function native(script, options = {}) {
  return new AgentTransport({
    label: "Codex", command: process.execPath, args: ["-e", script], timeoutMs: 4000, ...options,
  }, () => {}, () => {});
}

test("stdout EOF before stderr and exit preserves the original code and a credential-free startup cause", async (t) => {
  const rpc = native(`
    const fs = require("node:fs");
    fs.closeSync(1);
    setTimeout(() => {
      fs.writeSync(2, 'WARNING: Authorization: Bearer private-fixture-token\\n');
      fs.writeSync(2, 'Error: CODEX_HOME points to "/private/profile", but that path does not exist\\n');
      process.exit(41);
    }, 40);
  `);
  t.after(() => rpc.close());
  const request = rpc.request("initialize", {});
  const check = e => {
    assert.match(e.message, /退出码 41/);
    assert.match(e.message, /CODEX_HOME.*不存在/);
    assert.doesNotMatch(e.message, /private-fixture-token|private\/profile|Bearer/);
    return true;
  };
  await assert.rejects(request, check);
  await assert.rejects(rpc.request("account/read", {}), check);
});

test("signal death is reported without unknown stderr content or a generic pipe error", async (t) => {
  const rpc = native(`
    require("node:fs").writeSync(2, 'https://accounts.invalid/auth?code=private-code\\n');
    process.kill(process.pid, "SIGTERM");
  `);
  t.after(() => rpc.close());
  await assert.rejects(rpc.request("initialize", {}), e => {
    assert.match(e.message,/信号 SIGTERM/);
    assert.doesNotMatch(e.message,/invalid|private-code|标准通信管道/);
    return true;
  });
});

test("a process abandoning stdout cannot indefinitely strand a request", async (t) => {
  const rpc = native('require("node:fs").closeSync(1); setInterval(() => {}, 30000)', { exitGraceMs: 100 });
  t.after(() => rpc.close());
  await assert.rejects(rpc.request("initialize", {}), /引擎未报告退出状态/);
});

test("unparseable stdout and RPC errors containing authentication material stay private", async (t) => {
  for (const output of ["invalid-private-value", JSON.stringify({ id:1, error:{message:"Authorization: Bearer private-value"} })]) {
    const rpc = native("process.stdout.write(" + JSON.stringify(output+"\n") + ")");
    t.after(() => rpc.close());
    await assert.rejects(rpc.request("initialize", {}), e => {
      assert.doesNotMatch(e.message,/private-value|Authorization/i);
      assert.match(e.message,/协议消息|已隐藏/);
      return true;
    });
  }
});

test("disconnect after issuing a device code emits one terminal state and a retry launches a new server", async (t) => {
  const script = `
    require("node:readline").createInterface({input:process.stdin}).on("line", line => {
      const m = JSON.parse(line);
      if (!m.id) return;
      const result = m.method === "account/login/start" ?
        { type: "chatgptDeviceCode", userCode: "not-logged", loginId: "id", verificationUrl: "https://auth.openai.com" } : {};
      process.stdout.write(JSON.stringify({ id:m.id, result })+"\\n", () => {
        if (m.method === "account/login/start") setTimeout(() => process.exit(17), 20);
      });
    });
  `;
  const events = [];
  let notify;
  const disconnected = () => new Promise(resolve => { notify = resolve; });
  const client = createCodexClient({
    command: process.execPath, args: ["-e",script], timeoutMs:3000, onEvent(e) { events.push(e); notify?.(e); },
  });
  t.after(()=>client.close());
  for (let n=0;n<2;n++) {
    const signal = disconnected();
    assert.equal((await client.login()).type,"chatgptDeviceCode");
    const ev = await signal;
    assert.equal(ev.type,"disconnect");
    assert.match(ev.error,/退出码 17/);
  }
  assert.equal(events.length,2);
  assert.equal(JSON.stringify(events).includes("not-logged"), false);
});
