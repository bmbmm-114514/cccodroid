  // The native sessions belong to their respective agents. Selection stays local until Send.
  const multi = {
    id: localStorage.getItem("cc.multi.last") || "", data: null, seq: 0, timer: 0,
    pending: false, ready: false, warning: "", messagesKey: "", checksKey: "",
    agentTouched: false, drafts: new Map(), actionError: "",
  };
  const multiName = (id) => ({ claude: "Claude Code", codex: "Codex", agy: "Antigravity" })[id] || id;
  function multiShowError(message) {
    $("ma-error").classList.toggle("hidden", !message);
    $("ma-error").firstElementChild.textContent = message || "";
  }
  function multiPause() {
    ++multi.seq;
    clearTimeout(multi.timer);
  }
  function multiOptions(select, options, value) {
    const html = options.map(([id, label]) => `<option value="${esc(id)}">${esc(label)}</option>`).join("");
    if (select._options !== html) {
      select.innerHTML = html;
      select._options = html;
    }
    select.value = options.some(([id]) => id === value) ? value : (options[0] || [""])[0];
  }
  function multiDraft() {
    return {
      text: $("ma-input").value, agent: $("ma-agent").value, touched: multi.agentTouched,
      mode: $("ma-mode").value, provider: $("ma-provider").value, model: $("ma-model").value,
      selected: [...$("ma-checks").querySelectorAll("input:checked")].map((x) => x.value),
      project: $("ma-project").value, attempt: multi.drafts.get(multi.id)?.attempt,
    };
  }
  function multiChoose(id) {
    multi.drafts.set(multi.id, multiDraft());
    multi.id = id;
    if (id) localStorage.setItem("cc.multi.last", id); else localStorage.removeItem("cc.multi.last");
    const draft = multi.drafts.get(id) || {};
    multi.agentTouched = !!draft.touched;
    multi.ready = false;
    multi.actionError = "";
    multi.messagesKey = ""; multi.checksKey = "";
    $("ma-turns").replaceChildren(); $("ma-checks").replaceChildren(); $("ma-live").replaceChildren(); $("ma-approvals").replaceChildren();
    $("ma-agent").value = draft.agent || "claude";
    $("ma-mode").value = draft.mode || "outgoing";
    $("ma-input").value = draft.text || "";
    $("ma-model").value = draft.model || "";
    $("ma-conversations").value = id;
    multiPaintControls();
    multiRefresh();
  }
  async function multiOpenClaude(sid, title) {
    try {
      const r = await post("/api/multi/create", { claudeSessionId: sid, title });
      openPage("multi");
      multiChoose(r.id);
    } catch (e) { toast(e.message, true); }
  }
  async function multiRefresh() {
    multiPause();
    if (!pageOn("multi")) return;
    const seq = multi.seq, id = multi.id;
    try {
      const path = "/api/multi/state" + (id ? "?id=" + encodeURIComponent(id) : "");
      const data = await api(path, { timeout: 20000 });
      if (seq !== multi.seq || !pageOn("multi") || id !== multi.id) return;
      const wasReady = multi.ready;
      multi.data = data;
      multi.ready = true;
      if (id && !data.conversations.some((x) => x.id === id) && !data.conversation) {
        multiChoose("");
        return;
      }
      const c = multiConversation(), draft = multi.drafts.get(id) || {};
      // A POST can succeed on the gateway even when the mobile connection drops before its reply.
      // Reconcile the saved request id before enabling another send.
      if (draft.attempt && c?.turns.some((turn) => turn.requestId === draft.attempt.id)) {
        if ($("ma-input").value === draft.text) $("ma-input").value = "";
        draft.attempt = null;
        multi.actionError = "";
      }
      const active = c?.activeRun?.agent || c?.activeAgent;
      if (!multi.agentTouched && active) $("ma-agent").value = active;
      multiOptions($("ma-conversations"), [["", t("新对话")], ...data.conversations.map((x) => [x.id, x.title || t("对话")])], id);
      multiOptions($("ma-project"), [["", t("工作区")], ...data.projects.map((x) => [x.id, x.name])],
        wasReady ? $("ma-project").value : (draft.project || view.projectId || ""));
      const providerId = wasReady ? $("ma-provider").value : (draft.provider || "");
      multiOptions($("ma-provider"), [["", t("选择")], ...data.providers.map((x) => [x.id, x.label || x.id])], providerId);
      multiPaintConversation(c);
      multiPaintAccounts();
      multiPaintControls();
      multiShowError(multi.actionError || data.error || "");
    } catch (e) {
      if (seq === multi.seq) {
        if (id && e.status === 404) { multiChoose(""); return; }
        multi.ready = false;
        multiPaintControls();
        multiShowError(e.message);
      }
    } finally {
      if (seq === multi.seq && pageOn("multi")) {
        const fast = multiConversation()?.activeRun || multi.data?.codex?.installing || multi.data?.codex?.login || multi.data?.agy?.login || multi.data?.agy?.installing;
        multi.timer = setTimeout(multiRefresh, fast ? 1000 : 5000);
      }
    }
  }
  function multiConversation() {
    const c = multi.data?.conversation;
    return c && c.id === multi.id ? c : null;
  }
  function multiPaintConversation(c) {
    const turns = c?.turns || [], run = c?.activeRun;
    const history = $("ma-history");
    const follow = !multi.messagesKey || history.scrollHeight - history.scrollTop - history.clientHeight < 60;
    const last = turns[turns.length - 1];
    const historyTurns = run && last?.status === "running" ? [...turns.slice(0, -1), { ...last, assistant: "" }] : turns;
    const key = JSON.stringify(historyTurns);
    if (multi.messagesKey !== key) {
      multi.messagesKey = key;
      const expanded = new Set([...$("ma-turns").querySelectorAll("details[open]")].map(el => el.dataset.detail));
      $("ma-turns").innerHTML = historyTurns.map((turn) => {
        const problem = turn.error || (turn.status === "failed" ? t("出错") : turn.status === "stopped" ? t("已停止") : "");
        const toolRecords = (turn.tools || []).map(x => `<div class="ma-author">${esc(x.label || t("工具"))}</div><div class="ma-text" dir="auto">${esc(x.text || "")}</div>`).join("");
        return `<article><div class="ma-user"><div class="ma-author">${esc(t("你"))}</div><div class="ma-text" dir="auto">${esc(turn.user || "")}</div></div>
          ${turn.status === "running" ? "" : `<div class="ma-author">${esc(multiName(turn.agent))}</div><div class="ma-text" dir="auto">${esc(turn.assistant || "")}</div>`}
          ${toolRecords ? `<details data-detail="${esc(turn.id)}-tools"><summary>${esc(t("工具调用"))} · ${turn.tools.length}</summary>${toolRecords}</details>` : ""}
          ${turn.handoff?.text ? `<details data-detail="${esc(turn.id)}-handoff"><summary>${esc(t("交接记录"))} · ${esc(multiName(turn.handoff.from))} → ${esc(multiName(turn.agent))}</summary><div class="ma-author">${esc(turn.handoff.mode === "third-party" ? turn.handoff.provider : turn.handoff.mode === "manual" ? t("手动选择") : multiName(turn.handoff.from))}</div><div class="ma-text" dir="auto">${esc(turn.handoff.text)}</div></details>` : ""}
          ${problem ? `<div class="ma-error">${esc(problem)} <button type="button" class="btn ghost sm" data-retry-turn="${esc(turn.id)}">${esc(t("重发"))}</button></div>` : ""}</article>`;
      }).join("");
      for (const el of $("ma-turns").querySelectorAll("details")) el.open = expanded.has(el.dataset.detail);
    }
    const live = run ? (run.stage === "handoff" ? t("正在整理交接内容…") : multiName(run.agent)) : "";
    const liveText = run?.text || "";
    const liveKey = JSON.stringify([live, liveText]);
    if ($("ma-live")._key !== liveKey) {
      $("ma-live")._key = liveKey;
      $("ma-live").innerHTML = live ? `<div class="ma-author">${esc(live)}</div><div class="ma-text" dir="auto">${esc(liveText || t("进行中"))}</div>` : "";
    }
    const approvals = run?.approvals || [], approvalKey = JSON.stringify(approvals);
    if ($("ma-approvals")._key !== approvalKey) {
      $("ma-approvals")._key = approvalKey;
      $("ma-approvals").innerHTML = approvals.map((x) => `<div><span>${esc(x.label || t("允许"))}</span>
        <button type="button" class="btn sm" data-approval="${esc(x.id)}" data-allow="1">${esc(t("允许"))}</button>
        <button type="button" class="btn ghost sm" data-approval="${esc(x.id)}" data-allow="0">${esc(t("拒绝"))}</button></div>`).join("");
    }
    const checkKey = JSON.stringify(turns.map((x) => [x.id, x.user]));
    if (multi.checksKey !== checkKey) {
      const selected = multi.checksKey ? multiDraft().selected : multi.drafts.get(multi.id)?.selected || [];
      multi.checksKey = checkKey;
      $("ma-checks").innerHTML = turns.map((x) => `<label><input type="checkbox" value="${esc(x.id)}" ${selected.includes(x.id) ? "checked" : ""}>
        <span>${esc(multiName(x.agent))} · ${esc((x.user || "").slice(0, 200))}</span></label>`).join("");
    }
    $("ma-project-row").classList.toggle("hidden", !!c);
    $("ma-project-name").textContent = c ? (multi.data.projects.find((p) => p.id === c.projectId)?.name || t("工作区")) : "";
    if (follow) history.scrollTop = history.scrollHeight;
  }
  function multiPaintAccounts() {
    const { claude, codex, agy = {} } = multi.data;
    const acct = codex.account;
    const authStatus = (value) => value == null ? t("未知") : value ? t("已登录") : t("未登录");
    $("ma-claude-account").textContent = claude?.email || authStatus(claude?.loggedIn);
    $("ma-codex-account").textContent = codex.installing ? t("安装中…") : !codex.installed ? t("未安装") :
      acct?.email || (acct ? (acct.type || "ChatGPT") : t("未登录"));
    $("ma-install").classList.toggle("hidden", !!codex.installed);
    $("ma-install").disabled = multi.pending || codex.installing;
    $("ma-login").textContent = acct?.type === "chatgpt" ? t("换个账户登录") : t("登录账户");
    $("ma-login").classList.toggle("hidden", !codex.installed || !!codex.login);
    $("ma-login").disabled = multi.pending;
    $("ma-code").textContent = codex.login?.userCode || "";
    $("ma-device").classList.toggle("hidden", !codex.login?.userCode);
    const googleQuota = multi.data.quota?.agy || [];
    const agyStatus = agy.installing ? t("安装中…") : !agy.installed ? t("未安装") : authStatus(agy.loggedIn);
    $("ma-agy-account").textContent = agyStatus + (agy.installed && !googleQuota.length ? " · " + t("订阅额度") + " " + t("未知") : "");
    $("ma-agy-install").classList.toggle("hidden", !!agy.installed);
    $("ma-agy-install").disabled = multi.pending || !!agy.installing;
    $("ma-agy-login").classList.toggle("hidden", !agy.installed || !!agy.login || agy.loggedIn === true);
    $("ma-agy-login").disabled = multi.pending;
    $("ma-agy-flow").classList.toggle("hidden", !agy.login);
    $("ma-agy-note").textContent = agy.login?.message || "";
    $("ma-agy-device-code").textContent = agy.login?.userCode || "";
    $("ma-agy-verify").classList.toggle("hidden", !agy.login?.verificationUri);
    $("ma-agy-code-row").classList.toggle("hidden", !agy.login?.needsCode);
    $("ma-agy-error").textContent = agy.error || "";
    $("ma-agy-error").classList.toggle("hidden", !agy.error);
    $("ma-codex-error").textContent = codex.error || "";
    $("ma-codex-error").classList.toggle("hidden", !codex.error);
  }
  function multiPaintWarning() {
    const c = multiConversation();
    const agents = new Set([$("ma-agent").value, ...(c?.activeAgent ? [c.activeAgent] : [])]);
    const alerts = [];
    for (const agent of agents) {
      for (const q of multi.data?.quota?.[agent] || []) {
        if (q.resetsAt && q.resetsAt <= Date.now()) continue;
        if (typeof q.remaining !== "number" || !Number.isFinite(q.remaining)) continue;
        const acct = { codex: multi.data.codex?.account?.email, claude: multi.data.claude?.email,
          agy: multi.data.agy?.accountRevision }[agent];
        const key = JSON.stringify([agent, acct || "", q.name, q.resetsAt || ""]);
        const stored = "cc.multi.quota." + key;
        if (q.remaining > 5) { if (!q.resetsAt) sessionStorage.removeItem(stored); continue; }
        if (sessionStorage.getItem(stored)) continue;
        const reset = q.resetsAt ? " · " + new Date(q.resetsAt).toLocaleString() : "";
        alerts.push({ key, text: t("{agent} 的{window}额度剩余 {n}%", { agent: multiName(agent), window: q.name, n: +q.remaining.toFixed(1) }) + reset });
      }
    }
    multi.warning = alerts.map((x) => x.key);
    $("ma-warning").firstElementChild.textContent = alerts.map((x) => x.text).join(" · ");
    $("ma-warning").classList.toggle("hidden", !alerts.length);
  }
  function multiPaintControls() {
    const c = multiConversation(), run = c?.activeRun, agent = $("ma-agent").value;
    const switching = !!c?.activeAgent && c.activeAgent !== agent;
    const manualOnly = switching && c.activeAgent === "agy";
    for (const o of $("ma-mode").options) o.disabled = manualOnly && o.value !== "manual";
    if (manualOnly) $("ma-mode").value = "manual";
    const mode = $("ma-mode").value, third = switching && mode === "third-party";
    $("ma-mode-row").classList.toggle("hidden", !switching);
    $("ma-third").classList.toggle("hidden", !third);
    $("ma-manual").classList.toggle("hidden", !switching || mode !== "manual");
    $("ma-agent").disabled = $("ma-mode").disabled = $("ma-project").disabled = multi.pending;
    $("ma-conversations").disabled = $("ma-new").disabled = multi.pending;
    $("ma-send").classList.toggle("hidden", !!run);
    $("ma-stop").classList.toggle("hidden", !run);
    $("ma-stop").disabled = multi.pending;
    $("ma-send").disabled = multi.pending || !multi.ready || !($("ma-input").value.trim()) || (third && !$("ma-provider").value);
    multiPaintWarning();
  }
  async function multiAction(fn) {
    if (multi.pending) return;
    multi.pending = true;
    multiPause();
    multi.actionError = "";
    multiShowError("");
    multiPaintControls();
    try { await fn(); }
    catch (e) { multi.actionError = e.message; multiShowError(e.message); }
    finally {
      multi.pending = false;
      multiPaintControls();
      multiRefresh();
    }
  }
  async function multiSend() {
    const draft = multiDraft(), text = draft.text.trim();
    if (!text || !multi.ready || multiConversation()?.activeRun) return;
    if (!multi.id) {
      const created = await post("/api/multi/create", { projectId: draft.project || undefined, title: text.slice(0, 65) });
      multi.id = created.id;
      multi.drafts.delete("");
      localStorage.setItem("cc.multi.last", multi.id);
    }
    const switching = multiConversation()?.activeAgent && multiConversation().activeAgent !== draft.agent;
    const body = { id: multi.id, agent: draft.agent, text, mode: switching ? draft.mode : "manual",
      selected: switching && draft.mode === "manual" ? draft.selected : [] };
    if (switching && draft.mode === "third-party") { body.provider = draft.provider; body.model = draft.model.trim(); }
    const fingerprint = JSON.stringify(body);
    const attempt = draft.attempt?.fingerprint === fingerprint ? draft.attempt :
      { fingerprint, id: crypto.randomUUID ? crypto.randomUUID() : 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (x) => {
        const n = crypto.getRandomValues(new Uint8Array(1))[0] & 15; return (x === "x" ? n : ((n & 3) | 8)).toString(16);
      }) };
    multi.drafts.set(multi.id, { ...draft, attempt });
    await post("/api/multi/send", { ...body, requestId: attempt.id });
    if ($("ma-input").value === draft.text) $("ma-input").value = "";
    multi.drafts.set(multi.id, { ...multiDraft(), attempt: null, selected: [] });
    $("ma-checks").querySelectorAll("input:checked").forEach((x) => { x.checked = false; });
    multi.agentTouched = false;
    multi.ready = false;
  }
  $("ma-agent").addEventListener("change", () => { multi.agentTouched = true; multiPaintControls(); });
  $("ma-mode").addEventListener("change", () => { if ($("ma-mode").value === "manual") $("ma-manual").open = true; multiPaintControls(); });
  $("ma-provider").addEventListener("change", () => {
    $("ma-model").value = multi.data?.providers.find((p) => p.id === $("ma-provider").value)?.model || "";
    multiPaintControls();
  });
  $("ma-turns").addEventListener("click", (e) => {
    const id = e.target.closest("[data-retry-turn]")?.dataset.retryTurn;
    if (!id || multi.pending || multiConversation()?.activeRun) return;
    const turn = multiConversation().turns.find((x) => x.id === id);
    if (!turn) return;
    if ($("ma-input").value.trim() && !confirm(t("替换当前草稿？"))) return;
    $("ma-input").value = turn.user;
    $("ma-agent").value = turn.agent;
    multi.agentTouched = true; multiPaintControls(); $("ma-input").focus();
  });
  $("ma-input").addEventListener("input", multiPaintControls);
  $("ma-input").addEventListener("click", ccFocusInput);
  $("ma-form").addEventListener("submit", (e) => { e.preventDefault(); if (!$("ma-send").disabled) multiAction(multiSend); });
  $("ma-input").addEventListener("keydown", (e) => {
    if ((e.ctrlKey || e.metaKey) && e.key === "Enter" && !e.isComposing) { e.preventDefault(); if (!$("ma-send").disabled) multiAction(multiSend); }
  });
  $("ma-new").addEventListener("click", () => {
    if (multi.id) multiChoose("");
    else $("ma-input").focus();
  });
  $("ma-conversations").addEventListener("change", (e) => multiChoose(e.target.value));
  $("ma-stop").addEventListener("click", () => multiAction(() => post("/api/multi/stop", { id: multi.id })));
  $("ma-approvals").addEventListener("click", (e) => {
    const btn = e.target.closest("[data-approval]");
    if (btn) multiAction(() => post("/api/multi/approve", { id: multi.id, approvalId: btn.dataset.approval, allow: btn.dataset.allow === "1" }));
  });
  $("ma-accounts-btn").addEventListener("click", () => {
    const hidden = $("ma-accounts").classList.toggle("hidden");
    $("ma-accounts-btn").setAttribute("aria-expanded", String(!hidden));
  });
  $("ma-dismiss").addEventListener("click", () => {
    for (const key of multi.warning) sessionStorage.setItem("cc.multi.quota." + key, "1");
    multiPaintWarning();
  });
  $("ma-login").addEventListener("click", () => multiAction(() => post("/api/multi/login", {})));
  $("ma-install").addEventListener("click", () => multiAction(() => post("/api/multi/install", {})));
  $("ma-agy-install").addEventListener("click", () => multiAction(() => post("/api/multi/agy-install", {})));
  $("ma-agy-login").addEventListener("click", () => multiAction(() => post("/api/multi/agy-login", {})));
  $("ma-agy-confirm").addEventListener("click", () => multiAction(async () => {
    const code = $("ma-agy-code").value.trim();
    if (!code) return;
    await post("/api/multi/agy-login", { code }); $("ma-agy-code").value = "";
  }));
  $("ma-agy-verify").addEventListener("click", () => openExternal(multi.data?.agy?.login?.verificationUri));
  $("ma-verify").addEventListener("click", () => openExternal(multi.data?.codex?.login?.verificationUri));
  $("ma-copy").addEventListener("click", () => copyText($("ma-code").textContent));
  $("ma-claude-settings").addEventListener("click", () => {
    state.provView = "anthropic";
    provOpen = true;
    openSettings("provider");
  });
  $("ma-retry").addEventListener("click", () => { multi.actionError = ""; multiRefresh(); });
