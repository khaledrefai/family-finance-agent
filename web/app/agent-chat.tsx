"use client";

import { useState } from "react";
import { agent } from "@/lib/api";

interface Turn {
  from: "me" | "agent";
  text: string;
}

// A small chat box that talks to the agent service. Used by both the child and parent pages.
export function AgentChat({
  title,
  placeholder,
  suggestions = [],
  intro,
  onReply,
}: {
  title: string;
  placeholder: string;
  suggestions?: string[];
  intro?: string | null;
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
      setTurns((t) => [...t, { from: "agent", text: `Sorry, something went wrong: ${(err as Error).message}` }]);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="card chat">
      <strong>{title}</strong>
      {intro && turns.length === 0 && <p className="bubble agent">{intro}</p>}
      {turns.map((t, i) => (
        <p key={i} className={`bubble ${t.from}`}>{t.text}</p>
      ))}
      {busy && <p className="muted">Thinking…</p>}
      {suggestions.length > 0 && (
        <div className="chips">
          {suggestions.map((s) => (
            <button key={s} type="button" className="secondary chip" onClick={() => send(s)} disabled={busy}>
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
        <input placeholder={placeholder} value={text} onChange={(e) => setText(e.target.value)} />
        <button type="submit" disabled={busy}>Send</button>
      </form>
    </div>
  );
}
