"use client";

import { useState } from "react";
import { agent } from "@/lib/api";
import { rulesApi } from "@/lib/live";

interface Turn {
  from: "me" | "agent";
  text: string;
  // A rule the agent just drafted: the parent can turn it on right here (the core only takes that from a parent's tap)
  rule?: { id: string; state: "draft" | "on" | "busy"; note?: string };
}

// The Gatekeeper's answer after drafting a rule: I've written this down as a rule: "No energy drinks for Layla" (...)
const DRAFTED = /I've written this down as a rule: "([^"]+)"/;

// A small chat box that talks to the agent service. Used by both the child and parent pages.
// `source` is a line shown under each agent answer, e.g. where its numbers came from.
export function AgentChat({
  title,
  placeholder,
  suggestions = [],
  intro,
  source,
  onReply,
}: {
  title: string;
  placeholder: string;
  suggestions?: string[];
  intro?: string | null;
  source?: string;
  onReply?: () => void;
}) {
  const [text, setText] = useState("");
  const [turns, setTurns] = useState<Turn[]>([]);
  const [busy, setBusy] = useState(false);

  async function send(message: string) {
    if (!message.trim() || busy) return;
    setBusy(true);
    setTurns((t) => [...t, { from: "me", text: message }]);
    setText("");
    try {
      const res = await agent.chat(message);
      const label = res.reply.match(DRAFTED)?.[1];
      const draft = label ? (await rulesApi.list().catch(() => [])).find((r) => r.status === "DRAFT" && r.label === label) : undefined;
      setTurns((t) => [...t, { from: "agent", text: res.reply, rule: draft && { id: draft.id, state: "draft" } }]);
      onReply?.();
    } catch (err) {
      setTurns((t) => [...t, { from: "agent", text: `That didn't send. ${(err as Error).message}` }]);
    } finally {
      setBusy(false);
    }
  }

  async function turnOn(i: number, id: string) {
    const set = (rule: Turn["rule"]) => setTurns((t) => t.map((x, j) => (j === i ? { ...x, rule } : x)));
    set({ id, state: "busy" });
    try {
      const on = await rulesApi.confirm(id);
      set({ id, state: "on", note: on.replaces.length ? `On. It replaces "${on.replaces.join('", "')}".` : `On. "${on.label}" is checked from now on.` });
      onReply?.();
    } catch (err) {
      set({ id, state: "draft", note: (err as Error).message });
    }
  }

  return (
    <section className="panel chat" aria-label={title}>
      <strong>{title}</strong>
      <div className="turns" aria-live="polite">
        {intro && turns.length === 0 && <p className="turn-agent">{intro}</p>}
        {turns.map((t, i) =>
          t.from === "me" ? (
            <p key={i} className="turn-me">{t.text}</p>
          ) : (
            <div key={i} style={{ display: "contents" }}>
              <p className="turn-agent">{t.text}</p>
              {t.rule && t.rule.state !== "on" && (
                <button type="button" style={{ justifySelf: "start" }} disabled={t.rule.state === "busy"} onClick={() => turnOn(i, t.rule!.id)}>
                  {t.rule.state === "busy" ? "Turning on…" : "Turn on"}
                </button>
              )}
              {t.rule?.note && <p className="muted" role="status">{t.rule.note}</p>}
              {source && <p className="turn-source">{source}</p>}
            </div>
          ),
        )}
        {busy && <p className="thinking">Working it out…</p>}
      </div>
      {suggestions.length > 0 && (
        <div className="chips">
          {suggestions.map((s) => (
            <button key={s} type="button" className="chip" onClick={() => send(s)} disabled={busy}>
              {s}
            </button>
          ))}
        </div>
      )}
      <form
        onSubmit={(e) => {
          e.preventDefault();
          send(text);
        }}
      >
        <input aria-label={title} placeholder={placeholder} value={text} onChange={(e) => setText(e.target.value)} />
        <button type="submit" disabled={busy}>Send</button>
      </form>
    </section>
  );
}
