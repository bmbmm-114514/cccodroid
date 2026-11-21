// The Android and classic interfaces share the provider choice and per-model controls.
import path from "node:path";
import { CFG } from "./config.mjs";
import { PROVIDER, providerById } from "./providers.mjs";
import { asObj, loadState, saveState } from "./statefile.mjs";

const file = path.join(CFG.home, "multi-agent", "preferences.json");
const official = new Map([["claude", "anthropic"], ["codex", "chatgpt"], ["agy", "google"]]);
const controls = ["model", "effort", "serviceTier"];
const error = m => Object.assign(new Error(m), {status:400});
export function checkedSelection(value, limit = 200) {
  if (typeof value !== "string" || value.length > limit || /[\x00-\x1f\x7f]/.test(value)) throw error("设置值无效");
  return value.trim();
}
export function agentFor(provider) {
  if (provider === "chatgpt") return "codex";
  if (provider === "google") return "agy";
  if (typeof provider === "string" && /^[a-z0-9][a-z0-9_-]{0,79}$/i.test(provider) &&
      (provider === "anthropic" || provider !== "constructor" && provider !== "prototype" && provider !== "__proto__" && providerById(provider))) return "claude";
  throw error("未知的接口");
}
function read() {
  return loadState(file, {shape:asObj, fallback:() => ({provider:PROVIDER.id, choices:{}, configured:false})}).data;
}
function view(data, provider) {
  const agent = agentFor(provider), choice = Object.hasOwn(data.choices || {}, provider) ? data.choices[provider] : {};
  const model = choice.model ?? (data.configured === false && provider === data.provider ? PROVIDER.defaultModel || "" : "");
  const byModel = choice.byModel || {};
  const values = Object.hasOwn(byModel, model) ? byModel[model] : {};
  return { configured:data.configured !== false, agent, provider, model, effort:values.effort || "", serviceTier:agent === "codex" ? values.serviceTier || "" : "" };
}
export function getChoice(provider) {
  const d = read();
  if (provider) return view(d, provider);
  try { return view(d, d.provider || "anthropic"); } catch { return view(d, "anthropic"); }
}
export function saveChoice(patch) {
  const d = read();
  const provider = patch.provider ?? (patch.agent ? official.get(patch.agent) : d.provider);
  const agent = agentFor(provider);
  if (patch.agent !== undefined && patch.agent !== agent) throw error("该接口与所选 Agent 不匹配");
  const prior = view(d, provider);
  const model = "model" in patch ? checkedSelection(patch.model) : prior.model;
  const choices = { ...d.choices }, row = Object.hasOwn(choices, provider) ? choices[provider] : {};
  const entries = {...row.byModel}, old = Object.hasOwn(entries, model) ? entries[model] : {};
  const current = {...old};
  for (const key of controls.slice(1)) if (key in patch) current[key] = checkedSelection(patch[key], 120);
  if (agent !== "codex" && current.serviceTier) throw error("服务档位仅适用于 Codex");
  const updated = { provider, choices:{...choices, [provider]:{model, byModel:{...entries,[model]:current}}} };
  if (!saveState(file, updated)) throw Object.assign(new Error("无法保存接口设置"),{status:500});
  return view(updated, provider);
}
