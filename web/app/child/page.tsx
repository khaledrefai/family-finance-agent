"use client";

import { useEffect, useState } from "react";
import { agent, api, formatMoney } from "@/lib/api";
import { AgentChat } from "../agent-chat";
import { useSession } from "../use-session";

// Child view: tell the agent what I bought, ask for money or share a product link,
// and see the status of my own requests.
export default function ChildPage() {
  const { me, requests, error, setError, reload, logout } = useSession("CHILD");
  const [nudge, setNudge] = useState<string | null>(null);

  useEffect(() => {
    if (me) agent.nudge().then((n) => setNudge(n.nudge)).catch(() => {});
  }, [me]);
  const [title, setTitle] = useState("");
  const [amount, setAmount] = useState("");
  const [link, setLink] = useState("");

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    // Convert "300" or "12.50" to fils without floating-point maths.
    const match = amount.trim().match(/^(\d+)(?:\.(\d{1,2}))?$/);
    if (!match) {
      setError("Enter an amount like 300 or 12.50");
      return;
    }
    const fils = Number(match[1]) * 100 + Number((match[2] ?? "").padEnd(2, "0"));
    try {
      await api.createRequest(title, fils, link || undefined);
      setTitle("");
      setAmount("");
      setLink("");
      setError(null);
      await reload();
    } catch (err) {
      setError((err as Error).message);
    }
  }

  if (!me) return <p className="muted">Loading…</p>;

  return (
    <>
      <div className="row">
        <h1>Hi {me.displayName}</h1>
        <button className="secondary" onClick={logout}>Log out</button>
      </div>

      <AgentChat
        title="What did you buy?"
        placeholder="e.g. 12 dirhams shawarma"
        intro={nudge}
      />

      <form className="card" onSubmit={submit}>
        <strong>Ask for something</strong>
        <input placeholder="What is it?" value={title} onChange={(e) => setTitle(e.target.value)} required />
        <input placeholder="Price in AED" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} required />
        <input placeholder="Product link (optional)" value={link} onChange={(e) => setLink(e.target.value)} />
        <button type="submit">Send to a parent</button>
        {error && <p className="error">{error}</p>}
      </form>

      <h2>My requests</h2>
      {requests.length === 0 && <p className="muted">Nothing yet.</p>}
      {requests.map((r) => (
        <div className="card row" key={r.id}>
          <div>
            <strong>{r.title}</strong>
            <p className="muted">{formatMoney(r.amountMinor, r.currency)}</p>
          </div>
          <span className="badge">{r.status}</span>
        </div>
      ))}
    </>
  );
}
