"use client";

import Link from "next/link";
import { useEffect } from "react";
import { aed } from "@/lib/api";
import { useMoney, useWealth } from "@/lib/live";
import { Icon, Loading, MonthColumns, SampleNote, TRUST, Waterfall } from "../../ui";
import { useSession } from "../../use-session";
import { MoneyHeader } from "./money-tabs";
import { Uploads } from "./uploads";

// Money overview: where each month's money goes (every line one tap away), bills coming up, the next 12 months,
// family wealth, receipts and statements, and anything odd found in the family's payments. All from the core: the plan
// from GET /api/plan/cashflow, wealth from GET /api/wealth, uploads and findings from /api/documents and /api/findings.
export default function MoneyPage() {
  const { me } = useSession("PARENT");
  const { monthly, cashflow, bills, live } = useMoney(!!me);
  const { wealth } = useWealth(!!me);
  // The page renders after the session loads, so the browser can't jump to #bills or #uploads on its own.
  useEffect(() => {
    if (me && window.location.hash) document.getElementById(window.location.hash.slice(1))?.scrollIntoView();
  }, [me]);
  if (!me) return <Loading />;
  const tight = cashflow.months.filter((c) => c.tight).map((c) => `${aed(c.freeMinor)} in ${c.m}`);

  return (
    <div className="stack">
      <MoneyHeader sub={`${monthly.month} · from your confirmed income and commitments`} />

      <Link href="/parent/money/compare" className="card compare-entry">
        <Icon name="tag" size={24} />
        <span>
          <b>Buying something big?</b>
          <span className="muted" style={{ display: "block" }}>Compare the shops&apos; prices, see if it fits this month, then log it.</span>
        </span>
      </Link>

      <section className="card" style={{ display: "grid", gap: 10 }}>
        <div>
          <p className="card-title">Where each month&apos;s money goes</p>
          <p className="muted">From {aed(monthly.incomeMinor)} coming in to {aed(monthly.spareMinor)} spare</p>
        </div>
        <Waterfall
          steps={monthly.steps}
          label={`Income ${aed(monthly.incomeMinor)}, minus commitments ${aed(monthly.commitmentsMinor)} and everyday spending ${aed(monthly.everydayMinor)}, leaves free cash ${aed(monthly.freeMinor)}. Saving takes ${aed(monthly.savingMinor)}, leaving ${aed(monthly.spareMinor)} spare.`}
        />
        <details className="more">
          <summary>Show every line</summary>
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
        </details>
        <p className="trust">All amounts in AED. {TRUST}</p>
        <Link href="/parent/plan/buckets" className="link">See the same money as buckets</Link>
      </section>

      <section className="card" id="bills" style={{ scrollMarginTop: 16 }}>
        <p className="card-title" style={{ marginBottom: 2 }}>Bills coming up</p>
        <div className="rows">
          {bills.map((b) => (
            <div className="row" key={b.name + b.due} style={{ padding: "10px 0" }}>
              <span>{b.name}<span className="muted" style={{ display: "block" }}>{b.due}</span></span>
              <span style={{ textAlign: "right" }}>
                <b>{aed(b.amountMinor, { cents: b.amountMinor % 100 !== 0 })}</b>
                <span className={`muted${b.state === "short" ? " over" : ""}`} style={{ display: "block" }}>{b.state === "short" ? "Short" : "Reserved"}</span>
              </span>
            </div>
          ))}
        </div>
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

      <Link href="/parent/money/wealth" className="card" style={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 12 }}>
        <span>
          <span className="card-title" style={{ display: "block" }}>Family wealth</span>
          <span className="num" style={{ fontSize: 22 }}>{aed(wealth.totalMinor)}</span>
          <span className="est" style={{ display: "block" }}>Bank, cash and gold, minus cards and loans. Estimated, {wealth.asOf}</span>
        </span>
        <span aria-hidden="true">›</span>
      </Link>

      <Uploads enabled={!!me} demo={me.demo} />
      <SampleNote live={live} />
    </div>
  );
}
