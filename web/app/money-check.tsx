"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { agent, agentsApi, Proposal } from "@/lib/api";
import { useLiveEvents } from "@/lib/live-stream";

const KIND_WORDS: Record<Proposal["kind"], string> = {
  NOTE: "To know",
  BUDGET: "Budget",
  NUDGE: "Message to a child",
};

// The weekly money check: an agent that runs by itself every Sunday morning, looks at the family's money and
// proposes at most two things. Nothing happens until a parent approves; a message to a child is sent only then.
export function MoneyCheck({ compact = false }: { compact?: boolean }) {
  const [items, setItems] = useState<Proposal[] | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [why, setWhy] = useState<string | null>(null);

  function load() {
    agentsApi.proposals().then(setItems).catch(() => setItems([]));
  }

  useEffect(load, []);
  useLiveEvents(["AGENT_PROPOSAL"], load);

  async function runNow() {
    setBusy("run");
    setError(null);
    setNote(null);
    try {
      const res = await agent.check();
      setNote(res.proposals.length
        ? `${res.summary} I checked ${res.factsChecked} facts${res.aiChose ? "" : " (AI off, simple rules chose)"}.`
        : `${res.summary || "Nothing needs you this week."} I checked ${res.factsChecked} facts.`);
      load();
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(null);
    }
  }

  async function decide(p: Proposal, action: "approve" | "dismiss") {
    setBusy(p.id);
    setError(null);
    try {
      await (action === "approve" ? agentsApi.approve(p.id) : agentsApi.dismiss(p.id));
      setNote(action === "approve" && p.kind === "NUDGE" ? `Sent to ${p.childName}.` : null);
      load();
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(null);
    }
  }

  const waiting = items ?? [];
  if (compact && items !== null && waiting.length === 0 && !note) {
    return null; // Today only shows the check when it has something to say
  }

  return (
    <section className="card money-check" id="money-check" aria-label="Weekly money check" style={{ display: "grid", gap: 8 }}>
      <div className="row" style={{ padding: 0 }}>
        <span className="card-title">Weekly money check</span>
        <span className="muted">by the Planner agent</span>
      </div>
      <p className="muted" style={{ margin: 0, fontSize: 14 }}>
        Every Sunday morning it looks at the bills, budgets, goals and the kids&apos; month on its own, and suggests
        what to do. Nothing happens until you approve.
      </p>
      {waiting.map((p) => (
        <div key={p.id} className="proposal">
          <p className="label" style={{ margin: 0 }}>{KIND_WORDS[p.kind]}{p.triggeredBy === "SCHEDULE" ? " · Sunday check" : ""}</p>
          <b>{p.title}</b>
          <span>{p.body}</span>
          {p.kind === "NUDGE" && p.childMessage && (
            <span className="proposal-quote">To {p.childName}: &ldquo;{p.childMessage}&rdquo;</span>
          )}
          {why === p.id && (
            <ul className="plain-list muted" style={{ fontSize: 13 }}>
              {p.why.map((w) => <li key={w}>{w}</li>)}
            </ul>
          )}
          <div className="chit-actions">
            <button disabled={busy === p.id} onClick={() => decide(p, "approve")}>
              {p.kind === "NUDGE" ? `Send to ${p.childName}` : "Got it"}
            </button>
            <button className="quiet" disabled={busy === p.id} onClick={() => decide(p, "dismiss")}>Dismiss</button>
            {p.link && <Link href={p.link} className="link">Open</Link>}
            {p.why.length > 0 && (
              <button type="button" className="text" onClick={() => setWhy(why === p.id ? null : p.id)}>Why this?</button>
            )}
          </div>
        </div>
      ))}
      {items !== null && waiting.length === 0 && !note && <p className="empty" style={{ margin: 0 }}>Nothing waiting from the last check.</p>}
      {note && <p role="status" style={{ margin: 0 }}>{note}</p>}
      {error && <p className="error" role="alert">{error}</p>}
      <div className="chit-actions">
        <button type="button" className="quiet" disabled={busy === "run"} onClick={runNow}>
          {busy === "run" ? "Checking…" : "Run the check now"}
        </button>
        <Link href="/parent/agents" className="link">What the agents did</Link>
      </div>
    </section>
  );
}
