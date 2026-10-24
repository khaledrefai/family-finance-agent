"use client";

import Link from "next/link";
import { useState } from "react";
import { aed } from "@/lib/api";
import { buckets, planOptions, planSplit, planSteps } from "@/lib/sample";
import { Masthead, SampleNote, StackBar, TRUST, WhySheet } from "../../ui";
import { useSession } from "../../use-session";

// The family plan: protect commitments, clear the card, build a buffer, then pick the goal's pace.
// Options and the split come from POST /api/plan/options once it exists; sample figures for now.
export default function PlanPage() {
  const { me } = useSession("PARENT");
  const [pace, setPace] = useState(planOptions.find((o) => o.pick)?.id ?? planOptions[0].id);
  const [saved, setSaved] = useState(false);
  const [why, setWhy] = useState(false);
  if (!me) return <p className="muted">Loading…</p>;
  const chosen = planOptions.find((o) => o.id === pace)!;

  return (
    <div className="stack">
      <Masthead title="Family plan" sub="Built from AED 9,910 free each month" />

      <section className="card" style={{ paddingTop: 8, paddingBottom: 8 }}>
        {planSteps.map((s, i) => (
          <div key={s.title} style={{ display: "flex", gap: 12, alignItems: "flex-start", padding: "8px 0", fontSize: 14 }}>
            <span
              aria-hidden="true"
              style={{
                flex: "none", width: 24, height: 24, borderRadius: 99, display: "grid", placeItems: "center",
                fontSize: 12, fontWeight: 600, border: "1.5px solid var(--ink)",
                background: s.done ? "var(--ink)" : "transparent", color: s.done ? "var(--on-ink)" : "var(--ink)",
              }}
            >
              {i + 1}
            </span>
            <span><b>{s.title}</b> {s.text}</span>
          </div>
        ))}
      </section>

      <Link href="/parent/plan/buckets" className="card bucket-link" style={{ display: "grid", gap: 10, color: "inherit", textDecoration: "none" }}>
        <div className="row" style={{ padding: 0 }}>
          <span className="card-title">Money buckets</span>
          <span className="link">See what is in each ›</span>
        </div>
        <StackBar
          parts={buckets.list.map((b) => ({ label: b.name, amountMinor: b.amountMinor, color: b.color }))}
          label={buckets.list.map((b) => `${b.name} ${aed(b.amountMinor)}`).join(", ")}
        />
      </Link>

      <section className="stack" style={{ gap: 10 }}>
        <div>
          <h2 className="first">Summer trip · AED 12,000</h2>
          <p className="muted">Three ways to get there</p>
        </div>
        {planOptions.map((o) => (
          <label className="option" key={o.id}>
            <input type="radio" name="pace" checked={pace === o.id} onChange={() => { setPace(o.id); setSaved(false); }} />
            <span style={{ flex: 1 }}>
              <b>{o.name}</b> · {aed(o.monthlyMinor)} a month
              <br />
              <span className="muted" style={{ fontSize: 14 }}>{o.text}</span>
              {o.pick && <><br /><span className="pill">My pick</span></>}
            </span>
          </label>
        ))}
        <button type="button" className="text" style={{ justifySelf: "start", minHeight: 44 }} onClick={() => setWhy(true)}>Why this?</button>
      </section>

      <section className="card">
        <p className="card-title" style={{ marginBottom: 4 }}>How the steady plan splits</p>
        <div className="rows">
          {planSplit.map((l) => (
            <div className="row" key={l.label}><span>{l.label}</span><b>{aed(l.amountMinor)}</b></div>
          ))}
        </div>
        <p className="muted" style={{ marginTop: 8 }}>
          Each kid also keeps their own goal. Their saving counts toward both, and they earn the family badge when the trip reaches 50%.
        </p>
      </section>

      <button onClick={() => setSaved(true)}>Use the {chosen.name.toLowerCase()} plan</button>
      {saved && <p role="status" className="muted">Saved. The kids will see their goals on their Home screen.</p>}
      <p className="trust" style={{ textAlign: "center" }}>{TRUST}</p>

      <section className="card" style={{ display: "grid", gap: 6 }}>
        <p className="card-title">Also in Plan</p>
        <p className="muted">Weekly dinners and a shopping list, big purchases and the long-term calculator are coming soon.</p>
      </section>
      <SampleNote />

      {why && (
        <WhySheet
          title="Why the steady plan"
          groups={[
            {
              heading: "The numbers behind it",
              rows: [
                { label: "Free cash, usual month", value: "AED 9,910" },
                { label: "Free cash, December", value: "AED 7,510" },
                { label: "Buffer until March", value: "AED 5,000 a month" },
                { label: "Steady plan", value: "AED 1,500 a month" },
                { label: "Spare in December", value: "AED 1,010" },
              ],
            },
          ]}
          footer={`Steady is the fastest pace that still leaves money spare in December. ${TRUST}`}
          onClose={() => setWhy(false)}
        />
      )}
    </div>
  );
}
