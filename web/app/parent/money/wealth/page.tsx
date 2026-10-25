"use client";

import { plain } from "@/lib/api";
import { useWealth } from "@/lib/live";
import { Back, BigAmount, Loading, Masthead, SampleNote, TRUST, Waterfall } from "../../../ui";
import { useSession } from "../../../use-session";

// Family wealth: everything the family owns (bank, cash, gold at today's price) minus what it owes
// (what is on the cards today, loans still to pay), all from what the parents typed in (GET /api/wealth).
export default function WealthPage() {
  const { me } = useSession("PARENT");
  const { wealth, live } = useWealth(!!me);
  if (!me) return <Loading />;
  const own = wealth.steps.filter((s) => s.kind !== "total" && s.amountMinor > 0);
  const owe = wealth.steps.filter((s) => s.kind !== "total" && s.amountMinor < 0);
  const label = `${own.map((s) => `${s.label} ${plain(s.amountMinor)}`).join(", ")} owned. Minus ${owe
    .map((s) => `${s.label.toLowerCase()} ${plain(-s.amountMinor)}`).join(" and ")} leaves ${plain(wealth.totalMinor)}.`;

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
        <Waterfall steps={wealth.steps} label={label} />
        <div className="rows">
          {wealth.steps.filter((s) => s.asOf).map((s) => (
            <div className="row" key={s.label}><span className="muted">{s.label}</span><span className="est">as of {s.asOf}</span></div>
          ))}
        </div>
        <p className="trust">{TRUST}</p>
      </section>
      <p className="muted">Paying a card bill from a bank account moves money between your own accounts, so it does not change this number.</p>
      <SampleNote live={live} />
    </div>
  );
}
