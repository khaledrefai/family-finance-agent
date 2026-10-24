"use client";

import Link from "next/link";
import { aed } from "@/lib/api";
import { budgets, food } from "@/lib/sample";
import { BudgetBar, MonthColumns, SampleNote } from "../../../ui";
import { useSession } from "../../../use-session";
import { MoneyHeader } from "../money-tabs";

// Budgets: spent so far against each limit, and six months of everyday spending against the budget.
export default function BudgetsPage() {
  const { me } = useSession("PARENT");
  if (!me) return <p className="muted">Loading…</p>;
  const h = budgets.history;

  return (
    <div className="stack">
      <MoneyHeader sub={`October · ${budgets.daysLeft} days to go`} />

      <section className="card">
        <p className="card-title">Spent so far against each budget</p>
        <p className="muted" style={{ margin: "2px 0 6px" }}>The dark line is the limit. Red is over it.</p>
        {budgets.items.map((b) => (
          <BudgetBar key={b.name} {...b} />
        ))}
        <p className="trust" style={{ marginTop: 6 }}>All amounts in AED, from receipts, statements and payments you added.</p>
      </section>

      <section className="card" style={{ display: "grid", gap: 6 }}>
        <div className="row" style={{ padding: 0 }}>
          <span className="card-title">This week's food</span>
          <span className="muted">{aed(food.budgetMinor)}</span>
        </div>
        <p className="muted">{food.week}</p>
        <div className="rows">
          {food.lines.map((l) => (
            <div className="row" key={l.label}><span>{l.label}</span><b>{aed(l.amountMinor)}</b></div>
          ))}
        </div>
        <p style={{ fontSize: 14 }}>{food.note}</p>
      </section>

      <section className="card">
        <p className="card-title">Everyday spending, last 6 months</p>
        <p className="muted" style={{ margin: "2px 0 10px" }}>Dashed line: the AED 7,390 monthly budget</p>
        <MonthColumns
          height={128}
          months={h.months.map((c) => ({ m: c.m, share: c.share, amountMinor: c.spentMinor, tone: c.soFar ? "soft" : undefined }))}
          refShare={h.budgetShare}
          refLabel="7,390"
          label="May 7,100, June 7,800, July 8,300, August 7,450, September 6,900, October 7,150 so far. June, July and August were over budget."
        />
        <p style={{ marginTop: 10, fontSize: 14 }}>{h.note}</p>
      </section>

      <Link href="/parent" className="alert" style={{ background: "var(--chit)", color: "var(--ink)", border: "1px solid var(--ink)" }}>
        <span style={{ flex: 1 }}><b>Advice:</b> cut entertainment to AED 600 for November and December.</span>
        <span aria-hidden="true">›</span>
      </Link>
      <SampleNote />
    </div>
  );
}
