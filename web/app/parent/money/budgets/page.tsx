"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { aed } from "@/lib/api";
import { advice, BudgetMonth, useLive } from "@/lib/live";
import { MealsBoard, mealsApi } from "@/lib/meals";
import { budgets } from "@/lib/sample";
import { BudgetBar, Loading, MonthColumns } from "../../../ui";
import { useSession } from "../../../use-session";
import { MoneyHeader } from "../money-tabs";

// Budgets: spent so far against each limit, and six months of everyday spending against the budget.
// The bars and the overspend alerts are live from the budget engine, and this week's food from the saved dinner plan;
// the history is a sample.
export default function BudgetsPage() {
  const { me } = useSession("PARENT");
  const { data: month, live } = useLive<BudgetMonth | null>(() => advice.budgets(), null, !!me);
  const [meals, setMeals] = useState<MealsBoard | null>(null);
  useEffect(() => {
    if (me) mealsApi.board().then(setMeals).catch(() => {});
  }, [me]);
  if (!me) return <Loading />;
  const h = budgets.history;
  const bars = live && month
    ? month.lines.map((l) => ({ name: l.label, spentMinor: l.spent.minor, limitMinor: l.limit.minor, fill: l.fillBp / 100,
        past: l.pastBp / 100, overMinor: l.over.minor || undefined }))
    : budgets.items;

  return (
    <div className="stack">
      <MoneyHeader sub={live && month ? `${month.monthLabel} · ${month.daysLeft} days to go` : `October · ${budgets.daysLeft} days to go`} />

      <section className="card">
        <div className="row" style={{ padding: 0 }}>
          <p className="card-title">Spent so far against each budget</p>
          {live && <Link href="/parent/money/budgets/change" className="link">Change budgets ›</Link>}
        </div>
        <p className="muted" style={{ margin: "2px 0 6px" }}>The dark line is the limit. Red is over it.</p>
        {bars.map((b) => (
          <BudgetBar key={b.name} {...b} />
        ))}
        {live && month?.alerts.map((b) => (
          <p key={b.category} className={b.state === "OVER" ? "over" : undefined} style={{ fontSize: 14, margin: "6px 0 0" }}>{b.alert}</p>
        ))}
        <p className="trust" style={{ marginTop: 6 }}>All amounts in AED, from receipts, statements and payments you added.</p>
      </section>

      {meals && <FoodWeek meals={meals} />}

      {/* The history and the advice are the demo family's sample; a real family doesn't see them */}
      {me.demo && <>
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
      </>}
    </div>
  );
}

// This week's food: the saved dinner plan's shopping list against the week's grocery money, and its delivery nights
function FoodWeek({ meals }: { meals: MealsBoard }) {
  const saved = meals.saved;
  const deliveries = saved ? saved.nights.filter((n) => n.kind === "DELIVERY").length : 0;
  return (
    <section className="card" style={{ display: "grid", gap: 6 }}>
      <div className="row" style={{ padding: 0 }}>
        <span className="card-title">This week&apos;s food</span>
        {meals.groceryWeek && <span className="muted">{meals.groceryWeek.text} for groceries</span>}
      </div>
      {saved ? (
        <>
          <p className="muted">{saved.weekLabel}</p>
          <div className="rows">
            <div className="row"><span>Dinner shopping list</span><b>{saved.shopping?.text}</b></div>
            {saved.plan?.weekLeft && <div className="row"><span>Left for breakfasts, lunch boxes and the house</span><b>{saved.plan.weekLeft.text}</b></div>}
            {deliveries > 0 && (
              <div className="row"><span>Delivery {deliveries === 1 ? "night" : "nights"}, {deliveries} planned (eating out)</span><b>{saved.delivery?.text}</b></div>
            )}
          </div>
          <Link href="/parent/plan/meals" className="link">See the week&apos;s dinners</Link>
        </>
      ) : (
        <>
          <p style={{ fontSize: 14, margin: 0 }}>No dinners planned for this week yet. A plan picks dinners that suit everyone and makes the shopping list.</p>
          <Link href="/parent/plan/meals" className="link">Plan this week&apos;s dinners</Link>
        </>
      )}
    </section>
  );
}
