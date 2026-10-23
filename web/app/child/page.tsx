"use client";

import { useEffect, useState } from "react";
import { agent, api } from "@/lib/api";
import { AgentChat } from "../agent-chat";
import { Chit } from "../chit";
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
      <header className="masthead">
        <div>
          <h1>Hi {me.displayName}</h1>
          <p className="muted">{me.familyName}</p>
        </div>
        <button className="text" onClick={logout}>Log out</button>
      </header>

      <AgentChat
        title="What did you buy?"
        placeholder="12 dirhams shawarma"
        intro={nudge}
      />

      <form className="panel" onSubmit={submit}>
        <strong>Ask for something</strong>
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
        {error && <p className="error" role="alert">{error}</p>}
      </form>

      <h2>My requests</h2>
      {requests.length === 0 && <p className="empty">When you ask for something, it shows up here with your parent's answer.</p>}
      {requests.map((r) => (
        <Chit key={r.id} request={r} small />
      ))}
    </>
  );
}
