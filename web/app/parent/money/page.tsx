"use client";

import Link from "next/link";
import { useState } from "react";
import { aed } from "@/lib/api";
import { useMoney } from "@/lib/live";
import { findings, wealth } from "@/lib/sample";
import { MonthColumns, SampleNote, TRUST, Waterfall } from "../../ui";
import { useSession } from "../../use-session";
import { MoneyHeader } from "./money-tabs";

// Money overview: where each month's money goes, the next 12 months, bills coming up,
// and anything odd found in uploads. The first three come from GET /api/plan/cashflow; wealth and the
// upload findings are still sample figures (weeks 3 and 5).
export default function MoneyPage() {
  const { me } = useSession("PARENT");
  const { monthly, cashflow, bills, live } = useMoney(!!me);
  const [handled, setHandled] = useState<Record<string, string>>({});
  if (!me) return <p className="muted">Loading…</p>;
  const tight = cashflow.months.filter((c) => c.tight).map((c) => `${aed(c.freeMinor)} in ${c.m}`);

  return (
    <div className="stack">
      <MoneyHeader sub={`${monthly.month} · from your confirmed income and commitments`} />

      <Link href="/parent/money/wealth" className="card" style={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 12, color: "inherit", textDecoration: "none" }}>
        <span>
          <span className="muted" style={{ display: "block" }}>Family wealth · estimated, {wealth.asOf}</span>
          <span style={{ fontSize: 24, fontWeight: 300, fontVariantNumeric: "tabular-nums" }}>{aed(wealth.totalMinor)}</span>
          <span className="est" style={{ display: "block" }}>Bank, cash and gold, minus cards and loans</span>
        </span>
        <span aria-hidden="true">›</span>
      </Link>

      <section className="card" style={{ display: "grid", gap: 10 }}>
        <div>
          <p className="card-title">Where each month's money goes</p>
          <p className="muted">From {aed(monthly.incomeMinor)} coming in to {aed(monthly.spareMinor)} spare</p>
        </div>
        <Waterfall
          steps={monthly.steps}
          label={`Income ${aed(monthly.incomeMinor)}, minus commitments ${aed(monthly.commitmentsMinor)} and everyday spending ${aed(monthly.everydayMinor)}, leaves free cash ${aed(monthly.freeMinor)}. Saving takes ${aed(monthly.savingMinor)}, leaving ${aed(monthly.spareMinor)} spare.`}
        />
        <p className="trust">All amounts in AED. The full list is below.</p>
        <Link href="/parent/plan/buckets" className="link">See the same money as buckets</Link>
      </section>

      <section className="card">
        <p className="card-title" style={{ marginBottom: 6 }}>Each month</p>
        <div className="row"><span>Income to plan on</span><b>{aed(monthly.incomeMinor)}</b></div>
        {monthly.income.map((l) => (
          <div className="row sub" key={l.label}><span>{l.label}</span><span className="num">{aed(l.amountMinor)}</span></div>
        ))}
        <div className="row"><span>Commitments and reserves</span><b>{aed(-monthly.commitmentsMinor)}</b></div>
        {monthly.commitments.map((l) => (
          <div className="row sub" key={l.label}><span>{l.label}</span><span className="num">{aed(l.amountMinor)}</span></div>
        ))}
        <div className="row"><span>Everyday budgets</span><b>{aed(-monthly.everydayMinor)}</b></div>
        <div className="row total"><span>Free cash</span><b>{aed(monthly.freeMinor)}</b></div>
        <p className="trust" style={{ marginTop: 8 }}>{TRUST}</p>
      </section>

      <section className="card">
        <div className="row" style={{ padding: 0 }}>
          <span className="card-title">Next 12 months</span>
          <span className="muted">Free cash each month</span>
        </div>
        <div style={{ marginTop: 12 }}>
          <MonthColumns
            months={cashflow.months.map((c) => ({ m: c.m, share: c.share, amountMinor: c.freeMinor, tone: c.tight ? "tight" : undefined }))}
            label={`Free cash each month for the next 12 months${tight.length ? `, lower in tight months: ${tight.join(", ")}` : ""}`}
          />
        </div>
        <p className="muted" style={{ marginTop: 10 }}>{cashflow.note}</p>
      </section>

      <section className="card" id="bills">
        <p className="card-title" style={{ marginBottom: 2 }}>Coming up</p>
        <div className="rows">
          {bills.map((b) => (
            <div className="row" key={b.name + b.due} style={{ padding: "10px 0" }}>
              <span>{b.name} · {b.due}</span>
              <b className={b.state === "short" ? "over" : "good"} style={{ fontSize: 13 }}>
                {aed(b.amountMinor, { cents: b.amountMinor % 100 !== 0 })} {b.state === "short" ? "short" : "reserved"}
              </b>
            </div>
          ))}
        </div>
      </section>

      <section id="uploads" className="stack" style={{ gap: 10 }}>
        <h2 className="first">Found in your uploads</h2>
        {findings.map((f) => (
          <div className="card" key={f.id} style={{ display: "grid", gap: 8 }}>
            <p style={{ fontSize: 14 }}>
              <b>{f.text}</b> {f.detail}
            </p>
            {handled[f.id] ? (
              <p className="muted" role="status">{handled[f.id]}</p>
            ) : (
              <div style={{ display: "flex", gap: 8 }}>
                <button onClick={() => setHandled((h) => ({ ...h, [f.id]: "Done." }))}>{f.yes}</button>
                <button className="quiet" onClick={() => setHandled((h) => ({ ...h, [f.id]: "Left as it is." }))}>{f.no}</button>
              </div>
            )}
          </div>
        ))}
      </section>
      <SampleNote live={live} />
    </div>
  );
}
