// Antigravity exposes session settings through ACP. Some catalogs publish the same
// model at several fixed thought levels as separately named native model IDs.
function selector(session, category) {
  const option = session?.configOptions?.find(o => (o.category === category || o.id === category)
    && (!o.type || o.type === "select") && typeof o.id === "string");
  if (!option) return null;
  const choices = new Map();
  function add(options) {
    for (const item of Array.isArray(options) ? options : []) {
      if (Array.isArray(item?.options)) add(item.options);
      else if (typeof item?.value === "string" && item.value) choices.set(item.value, { id:item.value, label:item.name || item.value });
    }
  }
  add(option.options);
  return { key:option.id, value:option.currentValue || "", choices:[...choices.values()] };
}

function modelState(session) {
  const modern = selector(session, "model");
  if (modern) return modern;
  return { key:null, value:session?.models?.currentModelId || "",
    choices:(session?.models?.availableModels || []).filter(m => typeof m?.modelId === "string" && m.modelId)
      .map(m => ({ id:m.modelId, label:m.name || m.modelId })) };
}

// Only collapse variants when both the engine ID and the human-readable label
// identify the same family and level. A literal model whose ID is the family name
// stays an independent real model.
function variantFamilies(models) {
  const groups = new Map(), ids = new Set(models.map(m => m.id));
  for (const m of models) {
    const raw = /^(.+)-(low|medium|high|xhigh)$/i.exec(m.id);
    const name = /^(.+?)\s+\((low|medium|high|xhigh)\)$/i.exec(m.label);
    if (!raw || !name || raw[2].toLowerCase() !== name[2].toLowerCase() || ids.has(raw[1])) continue;
    const g = groups.get(raw[1]) || { id:raw[1], label:name[1], levels:new Map() };
    const level = raw[2].toLowerCase();
    if (g.label !== name[1] || g.levels.has(level)) g.ambiguous = true;
    g.levels.set(level, { id:level, label:name[2], native:m.id });
    groups.set(g.id,g);
  }
  const families = new Map(), byNative = new Map();
  for (const g of groups.values()) if (!g.ambiguous && g.levels.size >= 2) {
    families.set(g.id,g);
    for (const v of g.levels.values()) byNative.set(v.native,g);
  }
  return { families, byNative };
}

export function agyModelState(session) {
  const native = modelState(session);
  const { families, byNative } = variantFamilies(native.choices);
  const display = new Map();
  for (const m of native.choices) {
    const g = byNative.get(m.id);
    const row = g ? { id:g.id, label:g.label } : m;
    display.set(row.id,row);
  }
  return { native, families, byNative, models:[...display.values()],
    defaultModel:byNative.get(native.value)?.id || native.value, thought:selector(session, "thought_level") };
}

// native.value is the model in this session, which may be a retained override.
// A family's default is reproducible from a fresh session, even on resume.
function variantFor(state, group, effort) {
  if (effort) {
    const value = group.levels.get(effort);
    if (!value) throw new Error("Antigravity 的 " + group.label + " 未提供思考强度：" + effort);
    return value;
  }
  return [...group.levels.values()].find(v => v.native === state.native.value) || group.levels.values().next().value;
}

export function agyCatalog(state, requested, defaultModel) {
  const family = state.families.get(requested), concreteFamily = state.byNative.get(requested);
  let efforts = state.thought?.choices || [], defaultEffort = state.thought?.value || "";
  if (family || concreteFamily) {
    const g = family || concreteFamily;
    const variants = [...g.levels.values()].filter(v => family || v.native === requested);
    efforts = variants.map(({ id,label }) => ({ id,label }));
    defaultEffort = variants.find(v => v.native === state.native.value)?.id || variants[0].id;
  }
  return { models:state.models, defaultModel, efforts, defaultEffort, effortModel:requested, fastSupported:false };
}

// Return the latest selector response. Older ACP implementations use set_model
// with an empty result and can deliver the new selectors in a notification.
export async function applyAgyModel(rpc, sessionId, snapshot, requested, effort, currentReport) {
  const state = agyModelState(snapshot), group = state.families.get(requested);
  const nativeId = group ? variantFor(state,group,effort).native : requested;
  if (!nativeId || nativeId === state.native.value) return snapshot;
  if (!state.native.choices.some(m => m.id === nativeId)) throw new Error("Antigravity 当前账号未提供所选模型：" + requested);
  const before = currentReport();
  const changed = state.native.key
    ? await rpc.request("session/set_config_option", { sessionId, configId:state.native.key, value:nativeId })
    : await rpc.request("session/set_model", { sessionId, modelId:nativeId });
  const after = currentReport();
  const fresh = (changed?.configOptions || changed?.models) ? changed : after && after !== before ? after : null;
  if (fresh) {
    const selected = modelState(fresh).value;
    if (selected && selected !== nativeId) throw new Error("Antigravity 未采用所选模型：" + requested);
    // A legacy model list may be returned only on the original session object.
    return { ...snapshot, ...fresh };
  }
  // The legacy set_model ACK confirms the model only, not a previous model's effort settings.
  const configOptions = (snapshot.configOptions || []).filter(o => o.category !== "thought_level" && o.id !== "thought_level").map(o =>
    state.native.key && o.id === state.native.key ? { ...o,currentValue:nativeId } : o);
  return { ...snapshot, configOptions, models:{ ...snapshot.models,currentModelId:nativeId } };
}

export async function applyAgyEffort(rpc, sessionId, state, requested, effort, currentReport) {
  if (!effort) return;
  const group = state.families.get(requested) || state.byNative.get(requested);
  if (group) {
    if (group.levels.get(effort)?.native !== state.native.value) throw new Error("Antigravity 当前模型变体不支持所选思考强度：" + effort);
    return;
  }
  const selector = state.thought;
  if (!selector?.choices.some(o => o.id === effort)) throw new Error("Antigravity 当前模型不支持所选思考强度：" + effort);
  if (selector.value === effort) return;
  const before = currentReport();
  const changed = await rpc.request("session/set_config_option", { sessionId, configId:selector.key, value:effort });
  const after = currentReport();
  const result = changed?.configOptions ? changed : after && after !== before ? after : null;
  if (agyModelState(result).thought?.value !== effort) throw new Error("Antigravity 未确认所选思考强度：" + effort);
}
