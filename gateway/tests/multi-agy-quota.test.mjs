import test from "node:test";
import assert from "node:assert/strict";
import { createAgyQuotaReader, parseAgyQuota, queryAgyQuota } from "../gw/multi-agy-quota.mjs";

const json = (buckets, rest = {}) => JSON.stringify({
  status: "SUCCESS", num_turns: 0, command: { name: "usage", data: { groups: [
    { name: "Gemini Models", buckets },
    { name: "Claude and GPT models", buckets: [{ window: "weekly", remaining_fraction: 0 }] },
  ] } }, ...rest,
});
const poll = () => new Promise(resolve => setImmediate(resolve));

test("Antigravity reads only official, current Gemini subscription windows; unknown values are never zero", () => {
  const now = Date.UTC(2026, 5, 20);
  const rows = [
    { window: "weekly", remaining_fraction: 0, reset_time: new Date(now + 86400000).toISOString() },
    { window: "5h", remaining_fraction: 0.0504 },
    ...[null, undefined, "", 2, -1].map(n => ({ window: "5h", remaining_fraction: n })),
    { window: "5h", remaining_fraction: 0, reset_time: new Date(now - 1).toISOString() },
  ];
  const parsed = parseAgyQuota("ERROR: logging before google.Init: Initialized Android local timezone\n" +
    '{"nativeLog":true}\n' + json(rows) + '\n{"nativeLog":"finished"}\n', now);
  assert.deepEqual(parsed, [
    { name: "Gemini Models · 每周", remaining: 0, resetsAt: now + 86400000 },
    { name: "Gemini Models · 5 小时", remaining: 5.04, resetsAt: null },
  ]);
  assert.deepEqual(parseAgyQuota(json(rows, { num_turns: 1 }), now), []);
  assert.deepEqual(parseAgyQuota(json(rows, { status: "ERROR" }), now), []);
  assert.deepEqual(parseAgyQuota('{"usage":{"input_tokens":123}}'), []);
  assert.deepEqual(parseAgyQuota(json(rows).padStart(2 * 1024 * 1024 + 1), now), []);
});

test("Antigravity quota query uses the verified executable and the ACP login profile with one fixed local command", async () => {
  const profile = { command: "/fixture/agy", cwd: "/fixture/home", env: { HOME: "/fixture/home" } };
  const result = await queryAgyQuota(async () => profile, async (cmd, args, options) => {
    assert.equal(cmd, profile.command);
    assert.equal(options.cwd, profile.cwd);
    assert.equal(options.env, profile.env);
    assert.deepEqual(args, ["--output-format", "json", "-p", "/usage"]);
    return { stdout: json([{ window: "5h", remaining_fraction: 0.04 }]) };
  });
  assert.equal(result[0].remaining, 4);
});

test("Quota is single flight, expires, refreshes after a turn and ignores a previous account's slow response", async () => {
  let now = 1000;
  const pending = [];
  const read = createAgyQuotaReader(() => new Promise(resolve => pending.push(resolve)), () => now);
  const state = { installed: true, loggedIn: true, accountRevision: 1, usageRevision: 0 };
  const old = [{ name: "old", remaining: 4, resetsAt: 1100 }], fresh = [{ name: "new", remaining: 50, resetsAt: null }];
  assert.deepEqual(read(state), []);
  read(state); read(state);
  await poll();
  assert.equal(pending.length, 1);
  state.accountRevision++;
  assert.deepEqual(read(state), []);
  await poll();
  pending[1](fresh); await poll();
  pending[0](old); await poll();
  assert.deepEqual(read(state), fresh);
  state.usageRevision++;
  read(state); read(state); await poll();
  assert.equal(pending.length, 3);
  pending[2](old); await poll();
  assert.deepEqual(read(state), old);
  now = 1101;
  assert.deepEqual(read(state), []);
  await poll(); assert.equal(pending.length, 4);
  pending[3](fresh); await poll();
  now += 5 * 60_000 + 1;
  read(state); await poll(); assert.equal(pending.length, 5);
  state.login = { status: "waiting" };
  assert.deepEqual(read(state), []);
  pending[4](old); await poll();
  state.loggedIn = false;
  assert.deepEqual(read(state), []);
});
