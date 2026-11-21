  // One composer and one current conversation. The prefix distinguishes the shared
  // conversation ID from the native Claude session ID used by the original UI.
  const multi = { id: "", data: null, seq: 0, timer: 0, ready: false, pending: false, opening: null,
    keys: {}, drafts: new Map(), warning: [], error: "", indexStamp: "" };
  const multiName = (id) => ({ claude: "Claude Code", codex: "Codex", agy: "Antigravity" })[id] || id;
  const chatChoice = () => typeof currentAgentChoice === "function" ? currentAgentChoice() : { agent: "claude", model: "" };
  const multiId = (sid) => String(sid || "").startsWith("multi:") ? sid.slice(6) : "";
  function multiChatActive() { return document.documentElement.classList.contains("agent-chat"); }
  function multiRequestId() {
    if (typeof crypto.randomUUID === "function") return crypto.randomUUID();
    const b = crypto.getRandomValues(new Uint8Array(16));
    b[6] = (b[6] & 15) | 0x40; b[8] = (b[8] & 63) | 0x80;
    const hex = Array.from(b, n => n.toString(16).padStart(2, "0")).join("");
    return [hex.slice(0,8), hex.slice(8,12), hex.slice(12,16), hex.slice(16,20), hex.slice(20)].join("-");
  }
  function multiShouldSend() { return multiChatActive() || !!multiId(view.sessionId) || chatChoice().agent !== "claude"; }
  function multiConversation() { return multi.id && multi.data?.conversation?.id === multi.id ? multi.data.conversation : null; }
  function multiBusy() { return !!multiConversation()?.activeRun; }
  function multiPause() { ++multi.seq; clearTimeout(multi.timer); }
  function multiError(message) {
    multi.error = message || "";
    $("ma-error").firstElementChild.textContent = multi.error;
    $("ma-error").classList.toggle("hidden", !multi.error);
  }
  const timeline = document.createElement("section");
  timeline.id = "ma-history";
  timeline.innerHTML = '<div id="ma-turns"></div><div id="ma-live" aria-live="polite"></div>';
  chatEl.appendChild(timeline);
  function multiSessionView(sid) {
    multiPause();
    const id = multiId(sid);
    const on = !!id || (!sid && chatChoice().agent !== "claude");
    if (multi.id && multi.id !== id) {
      const d = multi.drafts.get(multi.id) || {};
      d.mode = $("ma-mode").value; d.provider = $("ma-provider").value; d.model = $("ma-model").value;
      d.selected = [...$("ma-checks").querySelectorAll("input:checked")].map(x => x.value);
      multi.drafts.set(multi.id, d);
    }
    multi.id = id; multi.data = null; multi.ready = false; multi.pending = false; multi.keys = {}; multi.opening = null;
    document.documentElement.classList.toggle("agent-chat", on);
    $("chat-agent-controls").classList.toggle("hidden", !on);
    for (const el of ["ma-turns", "ma-live", "ma-checks", "ma-approvals"]) $(el).replaceChildren();
    $("ma-warning").classList.add("hidden"); $("ma-mode-row").classList.add("hidden");
    $("ma-mode").value = multi.drafts.get(id)?.mode || "outgoing";
    $("ma-model").value = multi.drafts.get(id)?.model || "";
    multiError("");
    if (on) setupMode(false);
    // The native history is still available if creating its shared conversation fails.
    if (sid && !id && chatChoice().agent !== "claude") queueMicrotask(() => { if (view.sessionId === sid) multiSyncChoice(); });
    if (!on) { sendEl.disabled = false; sendEl.classList.remove("busy"); }
  }
  function multiSyncChoice() {
    const choice = chatChoice();
    $("chat-agent-chip").textContent = multiName(choice.agent) + (choice.model ? " · " + choice.model : "");
    if (view.sessionId && !multiId(view.sessionId) && choice.agent !== "claude") {
      if (!isBusy()) multiAdoptClaude().catch(e => toast(e.message, true));
      return;
    }
    const on = !!multiId(view.sessionId) || choice.agent !== "claude";
    if (on !== multiChatActive() || multi.id !== multiId(view.sessionId)) {
      multiSessionView(view.sessionId);
      if (!on && !view.sessionId) { showWelcome(); syncInterject(); }
    }
    if (on) {
      multiPaintControls();
      if (!multi.ready && !multi.pending) multiRefresh();
    }
  }
  async function multiAdoptClaude() {
    const sid = view.sessionId, gen = view.gen;
    if (!sid || multiId(sid)) return;
    if (isBusy()) throw new Error(t("请先结束当前回合"));
    if (multi.opening?.sid === sid) return multi.opening.promise;
    const promise = (async () => {
      const r = await post("/api/multi/create", { claudeSessionId: sid });
      if (view.sessionId !== sid || view.gen !== gen || chatChoice().agent === "claude") return;
      // The user is continuing the same chat. Keep its open settings, draft and attachments.
      view.sessionId = "multi:" + r.id; view.userNav = true;
      saveUiState(); draftPersist();
      openSession(view.sessionId, null, null, { keepPage: true });
      loadSessions();
    })();
    const op = multi.opening = { sid, promise };
    try { await promise; } finally { if (multi.opening === op) multi.opening = null; }
  }
  function multiInfo(key, html) { if (multi.keys[key] !== html) { multi.keys[key] = html; $(key).innerHTML = html; } }
  function multiPaintConversation() {
    const c = multiConversation(), turns = c?.turns || [], run = c?.activeRun;
    const last = turns[turns.length - 1];
    const saved = run && last?.status === "running" ? [...turns.slice(0, -1), { ...last, assistant: "" }] : turns;
    const key = JSON.stringify(saved);
    const follow = multi.keys.turns == null || chatEl.scrollHeight - chatEl.scrollTop - chatEl.clientHeight < 80;
    if (multi.keys.turns !== key) {
      const expanded = new Set([...$("ma-turns").querySelectorAll("details[open]")].map(x => x.dataset.detail));
      multi.keys.turns = key;
      const details = (id, label, body) => '<details data-detail="' + esc(id) + '"><summary>' + esc(label) + '</summary>' + body + '</details>';
      $("ma-turns").innerHTML = saved.map(turn => {
        const problem = turn.error || (turn.status === "failed" ? t("出错") : turn.status === "stopped" ? t("已停止") : "");
        const h = turn.handoff, tools = turn.tools || [];
        const handoff = h?.text ? details(turn.id+"-handoff", t("交接记录")+" · "+multiName(h.from)+" → "+multiName(turn.agent),
          '<small>'+esc(h.mode === "third-party" ? h.provider : h.mode === "manual" ? t("手动选择") : multiName(h.from))+'</small><div class="ma-plain">'+esc(h.text)+'</div>') : "";
        const outputs = tools.length ? details(turn.id+"-tools", t("工具调用")+" · "+tools.length, tools.map(x => '<b>'+esc(x.label)+'</b><div class="ma-plain">'+esc(x.text)+'</div>').join("")) : "";
        return '<article><div class="msg user"><div class="bubble ma-user-text" dir="auto">'+esc(turn.user)+'</div></div>'
          + (turn.status === "running" ? "" : '<div class="msg bot"><div class="who">'+esc(multiName(turn.agent))+'</div><div class="bubble" dir="auto">'+renderMd(turn.assistant || "")+'</div></div>')
          + handoff + outputs + (problem ? '<div class="ma-error">'+esc(problem)+' <button type="button" class="btn ghost sm" data-retry-turn="'+esc(turn.id)+'">'+esc(t("重发"))+'</button></div>' : "") + '</article>';
      }).join("");
      for (const d of $("ma-turns").querySelectorAll("details")) d.open = expanded.has(d.dataset.detail);
      enhanceMd($("ma-turns"));
    }
    const liveTitle = run?.stage === "handoff" ? t("正在整理交接内容…") : multiName(run?.agent);
    multiInfo("ma-live", run ? '<div class="msg bot"><div class="who">'+esc(liveTitle)+'</div><div class="bubble" dir="auto">'+renderMd(run.text || t("进行中"))+'</div></div>' : "");
    multiInfo("ma-approvals", (run?.approvals || []).map(x => '<div>'+esc(x.label || t("允许"))+' <button class="btn sm" data-approval="'+esc(x.id)+'" data-allow="1">'+esc(t("允许"))+'</button> <button class="btn ghost sm" data-approval="'+esc(x.id)+'" data-allow="0">'+esc(t("拒绝"))+'</button></div>').join(""));
    const listKey = JSON.stringify(turns.map(x => [x.id,x.user]));
    if (multi.keys.checks !== listKey) {
      const selected = multi.keys.checks == null ? multi.drafts.get(multi.id)?.selected || [] : [...$("ma-checks").querySelectorAll("input:checked")].map(x => x.value);
      multi.keys.checks = listKey;
      $("ma-checks").innerHTML = turns.map(x => '<label><input type="checkbox" value="'+esc(x.id)+'" '+(selected.includes(x.id) ? "checked" : "")+'> '+esc(multiName(x.agent)+" · "+(x.user || "").slice(0,160))+'</label>').join("");
    }
    if (c) { view.histOk = true; view.title = c.title || ""; view.projectId = c.projectId || null; paintTitle(); paintProjChip(); }
    if (follow) chatEl.scrollTop = chatEl.scrollHeight;
  }
  let multiStateRead = null;
  function multiRefresh() {
    if (!multiChatActive() || document.hidden) return Promise.resolve();
    if (multiStateRead?.seq === multi.seq && multiStateRead.id === multi.id) return multiStateRead.promise;
    multiPause();
    const read = { seq:multi.seq, id:multi.id };
    multiStateRead = read;
    read.promise = readMultiState(read.seq, read.id).finally(() => { if (multiStateRead === read) multiStateRead = null; });
    return read.promise;
  }
  async function readMultiState(seq, id) {
    try {
      const d = await api("/api/multi/state" + (id ? "?id="+encodeURIComponent(id) : ""), { timeout: 20000 });
      if (seq !== multi.seq) return;
      multi.data = d; multi.ready = true;
      const attempt = multiAttempt(id);
      if (attempt && d.conversation?.turns.some(turn => turn.requestId === attempt.id)) {
        multiConfirmSend(id, attempt);
        multiError("");
      }
      const providers = d.providers || [], providerKey = JSON.stringify(providers);
      if (multi.keys.providers !== providerKey) {
        const prev = multi.keys.providers ? $("ma-provider").value : multi.drafts.get(id)?.provider;
        multi.keys.providers = providerKey;
        $("ma-provider").innerHTML = '<option value="">'+esc(t("选择"))+'</option>' + providers.map(p => '<option value="'+esc(p.id)+'">'+esc(p.label || p.id)+'</option>').join("");
        if (providers.some(p => p.id === prev)) $("ma-provider").value = prev;
      }
      multiPaintConversation(); multiPaintControls();
      const stamp = [id, d.conversation?.updatedAt || 0].join("/");
      if (id && multi.indexStamp !== stamp) { multi.indexStamp = stamp; loadSessions(); }
    } catch (e) {
      if (seq === multi.seq) { multi.ready = false; multiError(e.message); multiPaintControls(); }
    } finally {
      if (seq === multi.seq && multiChatActive() && !document.hidden) multi.timer = setTimeout(multiRefresh, multiBusy() ? 700 : 5000);
    }
  }
  function multiSendButton() {
    const busy = multiBusy();
    interjectEl.classList.add("hidden");
    sendEl.disabled = !!multi.pending;
    sendEl.classList.toggle("busy", busy);
    const label = busy ? t("停止") : t("发送");
    sendEl.setAttribute("aria-label", label); sendEl.title = label;
  }
  function multiPaintControls() {
    if (!multiChatActive()) return;
    const c = multiConversation(), source = c?.activeAgent, target = chatChoice().agent, switching = source && source !== target && !multiBusy();
    $("ma-mode-row").classList.toggle("hidden", !switching);
    $("ma-transfer").textContent = switching ? multiName(source)+" → "+multiName(target) : "";
    const mode = $("ma-mode").value;
    $("ma-manual").classList.toggle("hidden", !switching || mode !== "manual");
    $("ma-third").classList.toggle("hidden", !switching || mode !== "third-party");
    if (switching && mode === "third-party" && multi.keys.handoffCatalog !== $("ma-provider").value) {
      const id = multi.keys.handoffCatalog = $("ma-provider").value;
      $("ma-catalog-refresh").disabled = !id;
      fillProviderModelChoices(id, $("ma-catalog"), $("ma-model"), $("ma-catalog-status"));
    }
    if (switching && mode === "third-party") $("ma-third-scope").textContent = source === "agy"
      ? t("所选接口将接收本对话的可见记录。") : t("所选接口将接收原会话可恢复的历史。");
    const alerts = [];
    for (const a of new Set([source,target].filter(Boolean))) for (const q of multi.data?.quota?.[a] || []) {
      if (!Number.isFinite(q.remaining)) continue;
      const account = { claude: multi.data.claude?.email, codex: multi.data.codex?.account?.email, agy: multi.data.agy?.accountRevision }[a];
      const key = "cc.multi.quota."+JSON.stringify([a,account || "",q.name,q.resetsAt || ""]);
      if (q.remaining > 5) { if (!q.resetsAt) sessionStorage.removeItem(key); continue; }
      if (!sessionStorage.getItem(key)) alerts.push({ key, text: t("{agent} 的{window}额度剩余 {n}%", { agent: multiName(a), window: q.name, n: +q.remaining.toFixed(1) }) });
    }
    multi.warning = alerts.map(x => x.key);
    $("ma-warning").firstElementChild.textContent = alerts.map(x => x.text).join(" · ");
    $("ma-warning").classList.toggle("hidden", !alerts.length);
    multiSendButton();
  }
  async function multiAction(fn) {
    if (multi.pending) return;
    const gen = view.gen;
    multi.pending = true; multiPause(); multiError(""); multiPaintControls();
    try { await fn(); }
    catch (e) { if (gen === view.gen) { multiError(e.message); toast(e.message, true); } }
    finally { if (gen === view.gen) { multi.pending = false; multiPaintControls(); multiRefresh(); } }
  }
  function multiAttempt(id) {
    if (!id) return null;
    const live = multi.drafts.get(id)?.attempt;
    if (live) return live;
    try { return JSON.parse(sessionStorage.getItem("cc.multi.attempt."+id)); } catch { return null; }
  }
  function multiRemember(id, attempt) {
    const draft = multi.drafts.get(id) || {};
    draft.attempt = attempt;
    multi.drafts.set(id, draft);
    try {
      if (attempt) sessionStorage.setItem("cc.multi.attempt."+id, JSON.stringify(attempt));
      else sessionStorage.removeItem("cc.multi.attempt."+id);
    } catch {}
  }
  function multiConfirmSend(id, attempt) {
    if (multiAttempt(id)?.id !== attempt.id) return;
    multiRemember(id, null);
    multi.drafts.get(id).selected = [];
    if (multi.id !== id || !multiChatActive()) return;
    if (attempt.fromInput) {
      if (inputEl.value === attempt.typed) { inputEl.value = ""; draftLast = ""; }
      state.attachments = state.attachments.filter(a => !attempt.attachments.includes(a.path));
      renderAttachments(); autoGrow(); draftPersist();
    }
    $("ma-checks").querySelectorAll("input:checked").forEach(x => { x.checked = false; });
  }
  async function multiSendMain(text, fromInput) {
    const initialGen = view.gen;
    if (!multiChatActive() && view.sessionId && !multiId(view.sessionId)) {
      try { await multiAdoptClaude(); } catch (e) { toast(e.message, true); }
      return;
    }
    if (!multiChatActive()) multiSyncChoice();
    if (multi.pending || multiBusy()) return;
    if (!multi.ready) {
      await multiRefresh();
      if (initialGen !== view.gen || !multiChatActive() || !multi.ready || multi.pending || multiBusy()) return;
    }
    const choice = chatChoice(), source = multiConversation()?.activeAgent, switching = source && source !== choice.agent;
    const mode = switching ? $("ma-mode").value : "manual";
    const provider = $("ma-provider").value, model = $("ma-model").value.trim();
    if (mode === "third-party" && !provider) { multiError(t("请选择交接接口")); return; }
    const typed = inputEl.value, attachments = state.attachments.map(a => a.path), gen = view.gen;
    const selected = switching && mode === "manual" ? [...$("ma-checks").querySelectorAll("input:checked")].map(x => x.value) : [];
    return multiAction(async () => {
      if (!multi.id) {
        const r = await post("/api/multi/create", { projectId: view.projectId || undefined, title: text.slice(0,65) });
        if (view.gen !== gen) return;
        multi.id = r.id; view.sessionId = "multi:"+r.id; draftAdopt(view.sessionId); saveUiState(); draftPersist(); loadSessions();
      }
      const id = multi.id;
      const body = { id, text, agent: choice.agent, destinationModel: choice.model || "", destinationProvider: choice.provider,
        effort: choice.effort || "", serviceTier: choice.serviceTier || "", mode, selected };
      if (mode === "third-party") Object.assign(body, { provider, model });
      const fingerprint = JSON.stringify(body), previous = multiAttempt(id);
      const attempt = previous?.fingerprint === fingerprint ? previous :
        { fingerprint, id: multiRequestId(), typed, attachments, fromInput };
      multiRemember(id, attempt);
      await post("/api/multi/send", { ...body, requestId: attempt.id });
      multiConfirmSend(id, attempt);
    });
  }
  function multiStop() { if (multi.id && multiBusy()) return multiAction(() => post("/api/multi/stop", {id:multi.id})); }
  $("ma-mode").addEventListener("change", () => { if ($("ma-mode").value === "manual") $("ma-manual").open = true; multiPaintControls(); });
  $("ma-provider").addEventListener("change", () => {
    $("ma-model").value = multi.data?.providers?.find(p => p.id === $("ma-provider").value)?.model || "";
    multiPaintControls();
  });
  $("ma-catalog-refresh").addEventListener("click", () =>
    fillProviderModelChoices($("ma-provider").value, $("ma-catalog"), $("ma-model"), $("ma-catalog-status"), true));
  $("ma-approvals").addEventListener("click", e => { const b=e.target.closest("[data-approval]"); if (b) multiAction(() => post("/api/multi/approve", { id:multi.id, approvalId:b.dataset.approval, allow:b.dataset.allow === "1" })); });
  $("ma-turns").addEventListener("click", e => {
    const id = e.target.closest("[data-retry-turn]")?.dataset.retryTurn;
    const turn = id && multiConversation()?.turns.find(x => x.id === id);
    if (turn && !multiBusy() && (!inputEl.value.trim() || confirm(t("替换当前草稿？")))) {
      inputEl.value = turn.user; autoGrow(); draftPersist(); inputEl.focus();
    }
  });
  $("chat-agent-chip").addEventListener("click", () => openSettings("provider"));
  $("ma-dismiss").addEventListener("click", () => { for (const k of multi.warning) sessionStorage.setItem(k,"1"); multiPaintControls(); });
  $("ma-retry").addEventListener("click", () => { multiError(""); multiRefresh(); });
  window.addEventListener("agent-choice-changed", multiSyncChoice);
  document.addEventListener("visibilitychange", () => { if (document.hidden) multiPause(); else if (multiChatActive()) multiRefresh(); });
