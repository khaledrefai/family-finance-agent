"use client";

import { useState } from "react";
import { aed, api } from "@/lib/api";
import { headphones } from "@/lib/sample";
import { AgentChat } from "../../agent-chat";
import { Loading, SampleNote } from "../../ui";
import { useSession } from "../../use-session";
import { BuddySays, GoalArt } from "../kid-ui";

// Kid Ask: tell the agent what I bought, see the agent's options for something I want,
// or ask for something directly. The options card shows the seeded headphones example until the
// assess API is built; picking an option sends a real request to a parent.
export default function ChildAsk() {
  const { me, error, setError, reload } = useSession("CHILD");
  const [title, setTitle] = useState("");
  const [amount, setAmount] = useState("");
  const [link, setLink] = useState("");
  const [sent, setSent] = useState<string | null>(null);

  if (!me) return <Loading />;
  const young = me.ageBand === "CHILD_6_9";

  async function send(requestTitle: string, fils: number, url?: string) {
    try {
      const saved = await api.createRequest(requestTitle, fils, url);
      setError(null);
      // A family rule can stop a request before it reaches a parent; the core says which rule and why
      setSent(saved.status === "BLOCKED"
        ? `Not sent: ${saved.agentSummary ?? "it is against a family rule."} Ask a parent if you think that's wrong.`
        : `Sent. Your parents can see "${requestTitle}" now, and their answer will show on Home.`);
      await reload();
    } catch (err) {
      setError((err as Error).message);
    }
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setSent(null);
    // Convert "300" or "12.50" to fils without floating-point maths.
    const match = amount.trim().match(/^(\d+)(?:\.(\d{1,2}))?$/);
    if (!match) {
      setError("Enter the price as a number, like 300 or 12.50.");
      return;
    }
    const fils = Number(match[1]) * 100 + Number((match[2] ?? "").padEnd(2, "0"));
    await send(title, fils, link || undefined);
    setTitle("");
    setAmount("");
    setLink("");
  }

  return (
    <div className={`stack${young ? " young" : ""}`}>
      <header className="kid-head">
        <h1>Ask</h1>
      </header>
      <BuddySays>
        <p>Tell me what you bought and I'll keep your picture right. Or tell me about something you want.</p>
      </BuddySays>

      <AgentChat
        title="What did you buy?"
        placeholder="12 dirhams shawarma, or paste a link"
        suggestions={["12 dirhams shawarma", "8 dirhams chicken wrap", "5 dirhams juice"]}
      />

      {!young && (
        <section className="stack" style={{ gap: 10 }}>
          <h2 className="first">Something you want</h2>
          <article className="card" style={{ display: "flex", gap: 12, alignItems: "center" }}>
            <span className="art-blob" aria-hidden="true">
              <GoalArt art="headphones" size={56} />
            </span>
            <span style={{ flex: 1 }}>
              <b>{headphones.title}</b>
              <br />
              <span style={{ fontSize: 18 }}>{aed(headphones.priceMinor, { cents: true })}</span>
              <br />
              <span className="muted" style={{ fontSize: 12 }}>{headphones.source}</span>
            </span>
          </article>
          <p className="turn-agent">{headphones.intro}</p>
          {headphones.options.map((o, i) => (
            <div className="card option-card" key={o.id}>
              <span className="option-num" aria-hidden="true">{i + 1}</span>
              <p><b>{o.title}</b> {o.text}</p>
              <button className={i === 0 ? undefined : "quiet"} onClick={() => send(o.requestTitle, o.amountMinor)}>
                {o.button}
              </button>
            </div>
          ))}
          <p className="trust">Your numbers are worked out by the app. Your parent sees this plan and your savings so far.</p>
          <SampleNote />
        </section>
      )}

      <form className="panel" onSubmit={submit}>
        <strong>Ask for something else</strong>
        <label>
          What is it?
          <input value={title} onChange={(e) => setTitle(e.target.value)} required />
        </label>
        <label>
          Price in AED
          <input inputMode="decimal" placeholder="300 or 12.50" value={amount} onChange={(e) => setAmount(e.target.value)} required />
        </label>
        <label>
          Link to it, if it's online
          <input inputMode="url" value={link} onChange={(e) => setLink(e.target.value)} />
        </label>
        <button type="submit">Send to a parent</button>
      </form>
      {error && <p className="error" role="alert">{error}</p>}
      {sent && <p className="muted" role="status">{sent}</p>}
    </div>
  );
}
