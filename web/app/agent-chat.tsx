"use client";

import { useState } from "react";
import { agent } from "@/lib/api";

interface Turn {
  from: "me" | "agent";
  text: string;
}

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
      setTurns((t) => [...t, { from: "agent", text: res.reply }]);
      onReply?.();
    } catch (err) {
      setTurns((t) => [...t, { from: "agent", text: `That didn't send. ${(err as Error).message}` }]);
    } finally {
      setBusy(false);
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
