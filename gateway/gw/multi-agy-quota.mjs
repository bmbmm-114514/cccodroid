// agy's built-in /usage returns quota buckets for the authenticated Google account.
// ACP's context-window counters do not represent subscription quota.
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { agyUsageOptions } from "./multi-agy.mjs";

const execute = promisify(execFile);
const ttl = 5 * 60_000;

export function parseAgyQuota(raw, now = Date.now()) {
  if (typeof raw !== "string" || Buffer.byteLength(raw) > 2 * 1024 * 1024) return [];
  let result;
  try { result = JSON.parse(raw.trim()); } catch {
    // Android may send native log lines to stdout alongside the single JSON reply.
    for (const line of raw.split("\n").reverse()) {
      const frame = line.trim();
      if (!frame.startsWith("{") || !frame.endsWith("}")) continue;
      let value;
      try { value = JSON.parse(frame); } catch { continue; }
      if (value?.command?.name === "usage") { result = value; break; }
    }
  }
  const command = result?.command;
  if (result?.status !== "SUCCESS" || result.num_turns !== 0 || command?.name !== "usage" || !Array.isArray(command.data?.groups)) return [];
  const windows = [];
  for (const group of command.data.groups) {
    if (typeof group?.name !== "string" || !Array.isArray(group.buckets)) continue;
    // A custom interactive ACP client uses Gemini; a JetBrains-only third-party
    // model group's limits aren't applicable to a turn made by this client.
    if (!/gemini/i.test(group.name)) continue;
    for (const bucket of group.buckets) {
      const label = { "5h": "5 小时", weekly: "每周", "7d": "每周" }[bucket?.window];
      const fraction = bucket?.remaining_fraction;
      if (!label || typeof fraction !== "number" || !Number.isFinite(fraction) || fraction < 0 || fraction > 1) continue;
      const resetsAt = typeof bucket.reset_time === "string" ? Date.parse(bucket.reset_time) : NaN;
      if (Number.isFinite(resetsAt) && resetsAt <= now) continue;
      // Compare the original percentage with the alert threshold. Rounding 5.04% to
      // one decimal place here would incorrectly trigger the 5% alert.
      windows.push({ name: group.name + " · " + label, remaining: fraction * 100,
        resetsAt: Number.isFinite(resetsAt) ? resetsAt : null });
    }
  }
  return windows;
}

export async function queryAgyQuota(options = agyUsageOptions, run = execute) {
  const { command, env, cwd } = await options();
  const { stdout } = await run(command, ["--output-format", "json", "-p", "/usage"], {
    cwd, env, timeout: 15000, killSignal: "SIGKILL", maxBuffer: 2 * 1024 * 1024,
  });
  return parseAgyQuota(stdout);
}

// Polls never hold up the Android chat UI. A new sign-in invalidates both the
// cached rows and any outstanding result for the former account.
export function createAgyQuotaReader(query = queryAgyQuota, clock = Date.now) {
  let cache;
  return function read(state) {
    const now = clock();
    if (!state?.installed || state.login?.status === "waiting" || state.loggedIn === false) {
      cache = null;
      return [];
    }
    if (!cache || cache.revision !== state.accountRevision) {
      cache = { revision: state.accountRevision, afterTurn: state.usageRevision, next: 0, pending: false, windows: [] };
    } else if (cache.afterTurn !== state.usageRevision) {
      cache.next = now;
      cache.afterTurn = state.usageRevision;
    }
    if (!cache.pending && now >= cache.next) {
      const record = cache;
      record.pending = true;
      record.next = now + ttl;
      Promise.resolve().then(query).then(windows => {
        record.windows = windows;
        for (const w of windows) {
          if (w.resetsAt > now && w.resetsAt < record.next) record.next = w.resetsAt;
        }
      }).catch(() => { record.windows = []; }).finally(() => { record.pending = false; });
    }
    return cache.windows.filter(w => !w.resetsAt || w.resetsAt > now);
  };
}
export const agyQuota = createAgyQuotaReader();
