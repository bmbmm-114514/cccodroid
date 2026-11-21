import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { test } from "node:test";

test("native and classic clients remember independent controls per account and model", async t => {
  const home = fs.mkdtempSync(path.join(os.tmpdir(), "provider-choice-"));
  const previous = process.env.CCAPP_HOME;
  process.env.CCAPP_HOME = home;
  t.after(() => {
    if (previous === undefined) delete process.env.CCAPP_HOME; else process.env.CCAPP_HOME = previous;
    fs.rmSync(home,{recursive:true,force:true});
  });
  const { getChoice, saveChoice } = await import("../gw/multi-preferences.mjs");
  assert.equal(getChoice().configured, false);
  saveChoice({provider:"chatgpt",model:"one",effort:"high",serviceTier:"priority"});
  assert.equal(saveChoice({provider:"google",model:"two",effort:"low"}).serviceTier, "");
  assert.equal(getChoice().agent, "agy");
  assert.equal(getChoice("chatgpt").serviceTier, "priority");
  const empty = saveChoice({agent:"codex",model:"three"});
  assert.deepEqual([empty.effort,empty.serviceTier],["",""]);
  const restored = saveChoice({agent:"codex",model:"one"});
  assert.deepEqual([restored.effort,restored.serviceTier],["high","priority"]);
  assert.equal(saveChoice({serviceTier:"default"}).serviceTier,"default");
  assert.equal(saveChoice({effort:""}).configured,true);
  assert.throws(() => saveChoice({agent:"agy",provider:"chatgpt"}), /不匹配/);
  assert.throws(() => saveChoice({provider:"google",serviceTier:"priority"}), /Codex/);
  assert.throws(() => saveChoice({provider:"constructor"}), /未知/);
  assert.equal(getChoice().provider, "chatgpt");
});
