// ACP session/load replays visible updates before returning. Keep the newest context within a
// fixed budget even when the native chat has thousands of messages or large command outputs.
const limit = 64000, itemLimit = 16000;
const clipped = "…（此条中段已省略）…";
const earlier = "（较早的部分历史已省略）\n";
function excerpt(text) {
  if (text.length <= itemLimit) return text;
  return text.slice(0, 4000) + clipped + text.slice(4000 + clipped.length - itemLimit);
}
const textParts = content => (Array.isArray(content) ? content : [content])
  .filter(part => part?.type === "text" && typeof part.text === "string").map(part => part.text).join("");

export function agyHistory() {
  const entries = [];
  let size = 0, omitted = false;
  function add(role, body, id = "", whole = true, chunks = false) {
    if (typeof body !== "string" || !body.length) return;
    let row = id ? entries.findLast(entry => entry.id === id && entry.role === role) : null;
    if (!row && !id && chunks && entries.at(-1)?.role === role && !entries.at(-1).id) {
      const last = entries.at(-1);
      if (!last.whole || last.text === body) row = last;
    }
    if (!row) { row = { role, id, text: "", whole: false }; entries.push(row); size += role.length + 2; }
    if (!whole && row.whole) return;
    size -= row.text.length;
    row.text = excerpt(whole ? body : row.text + body);
    row.whole = whole;
    size += row.text.length;
    while (size > limit - earlier.length || entries.length > 512) {
      const oldest = entries.shift();
      size -= oldest.role.length + oldest.text.length + 2;
      omitted = true;
    }
  }
  return {
    push: add,
    update(u) {
      if (!u || typeof u.sessionUpdate !== "string") return;
      const match = /^(user|agent)_message(_chunk)?$/.exec(u.sessionUpdate);
      if (match) {
        add(match[1] === "user" ? "用户" : "agy", textParts(u.content), u.messageId, !match[2], true);
      } else if (["tool_call", "tool_call_update"].includes(u.sessionUpdate) && u.toolCallId) {
        const body = (u.content || []).flatMap(part => {
          if (part.type === "content") return [textParts(part.content)];
          if (part.type === "diff" && typeof part.path === "string") return [part.path];
          return [];
        }).filter(Boolean).join("\n");
        const previous = entries.findLast(entry => entry.role === "工具" && entry.id === u.toolCallId);
        const title = u.title || previous?.text.split("\n", 1)[0] || "工具调用";
        if (body || !previous) add("工具", title + (body ? "\n" + body : ""), u.toolCallId);
      }
    },
    render() {
      return (omitted ? earlier : "") + entries.map(item => item.role + "：" + item.text).join("\n");
    }
  };
}

// Recover from a partial ACP replay even when the last turn was replayed successfully.
// Match the visible source turns in order, accounting for the same bounded context window.
export function coversAgyTurns(text, turns) {
  let end = text.length, remaining = limit;
  for (let i = turns.length - 1; i >= 0 && remaining > 0; i--) {
    const turn = turns[i];
    if (turn.agent !== "agy") continue;
    for (const value of [turn.assistant, turn.user]) {
      if (typeof value !== "string" || !value) continue;
      const tail = value.slice(-100);
      const index = end ? text.lastIndexOf(tail, end - 1) : -1;
      if (index < 0) return false;
      end = index;
      remaining -= Math.min(value.length, itemLimit);
      if (remaining <= 0) break;
    }
  }
  return true;
}

export function recordedAgyHistory(turns) {
  const history = agyHistory();
  for (const turn of turns || []) {
    if (turn.agent !== "agy") continue;
    history.push("收到的交接", turn.handoff?.text);
    history.push("用户", turn.user);
    for (const tool of turn.tools || []) {
      history.push("工具", [tool.label, tool.text].filter(value => typeof value === "string").join("\n"));
    }
    history.push("agy", turn.assistant);
  }
  return history.render();
}
