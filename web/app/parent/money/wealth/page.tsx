"use client";

import { wealth } from "@/lib/sample";
import { Back, BigAmount, Masthead, SampleNote, TRUST, Waterfall } from "../../../ui";
import { useSession } from "../../../use-session";

// Family wealth: everything the family owns (bank, cash, gold at today's price) minus what it owes
// (card balances, loans), all from what the parents typed in. Sample figures until GET /api/wealth exists.
export default function WealthPage() {
  const { me } = useSession("PARENT");
  if (!me) return <p className="muted">Loading…</p>;

  return (
    <div className="stack">
      <Back href="/parent/money" label="Money" />
      <Masthead title="Family wealth" sub={`What you own minus what you owe · ${wealth.asOf}`} />

      <section className="card strong" style={{ display: "grid", gap: 4 }}>
        <p className="muted">Family wealth</p>
        <BigAmount minor={wealth.totalMinor} />
        <p className="est">An estimate from the balances you typed in. No bank is connected.</p>
      </section>

      <section className="card" style={{ display: "grid", gap: 10 }}>
        <p className="card-title">How it adds up</p>
        <Waterfall steps={wealth.steps} label="Bank 51,050, cash 650 and gold 15,765 make 67,465 owned. Minus cards 7,250 and the car loan 38,400 leaves 21,815." />
        <div className="rows">
          {wealth.steps.filter((s) => s.asOf).map((s) => (
            <div className="row" key={s.label}><span className="muted">{s.label}</span><span className="est">as of {s.asOf}</span></div>
          ))}
        </div>
        <p className="trust">{TRUST}</p>
      </section>
      <p className="muted">Paying a card bill from a bank account moves money between your own accounts, so it does not change this number.</p>
      <SampleNote />
    </div>
  );
}
