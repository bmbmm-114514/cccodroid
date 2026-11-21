import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { CFG } from "../gw/config.mjs";
import { holdMultiActivity, multiBusy, runFlag } from "../gw/runs.mjs";

test("the Android service stays busy until both classic and multi-agent work finish", () => {
  const flag = path.join(CFG.home,".run-active");
  const endChat = holdMultiActivity(), endInstall = holdMultiActivity();
  try {
    assert.equal(multiBusy(),true);
    runFlag(false);
    endChat(); endChat();
    assert.equal(fs.existsSync(flag),true);
    runFlag(true);
    endInstall();
    assert.equal(multiBusy(),false);
    assert.equal(fs.existsSync(flag),true);
    runFlag(false);
    assert.equal(fs.existsSync(flag),false);
  } finally { endChat(); endInstall(); runFlag(false); }
});
