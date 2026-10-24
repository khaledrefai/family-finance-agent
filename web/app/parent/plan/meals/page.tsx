"use client";

import Link from "next/link";
import { useState } from "react";
import { aed } from "@/lib/api";
import { meals } from "@/lib/sample";
import { Back, Icon, Masthead, SampleNote } from "../../../ui";
import { useSession } from "../../../use-session";

// Optional weekly dinners and shopping list, inside the food budget and the family's food rules.
// Prices and recipes come from a fixed seeded list; sample week for now.
export default function MealsPage() {
  const { me } = useSession("PARENT");
  const [dinners, setDinners] = useState(meals.dinners);
  const [state, setState] = useState<"open" | "used" | "skipped">("open");
  const [swapped, setSwapped] = useState(false);
  if (!me) return <p className="muted">Loading…</p>;

  // Swapping picks another seeded recipe with its own list price. The week's total is repriced by the app,
  // so after a swap the screen says so instead of adding it up here.
  function swap(i: number) {
    const alt = meals.swaps[i % meals.swaps.length];
    setDinners((ds) => ds.map((d, j) => (j === i ? { ...d, name: alt.name, costMinor: alt.costMinor } : d)));
    setSwapped(true);
  }

  return (
    <div className="stack">
      <Back href="/parent/plan" label="Plan" />
      <Masthead title="This week's dinners" sub={`Optional · ${meals.week} · halal, no peanuts`} />

      <section className="card" style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginTop: -12 }}>
        <div><p className="muted">Planned</p><p style={{ fontSize: 26, fontWeight: 300 }}>{aed(meals.plannedMinor)}</p></div>
        <div style={{ textAlign: "right" }}><p className="muted">Food budget this week</p><p style={{ fontSize: 18 }}>{aed(meals.budgetMinor)}</p></div>
      </section>
      <p style={{ fontSize: 14, marginTop: -6 }}>{swapped ? "You swapped a dinner. The app reprices the week when you tap Use this plan." : meals.saving}</p>

      <section className="card" style={{ paddingTop: 6, paddingBottom: 6 }}>
        <div className="rows">
          {dinners.map((d, i) => (
            <div className="row" key={d.d} style={{ alignItems: "center", padding: "8px 0" }}>
              <span style={{ width: 34, flex: "none", fontSize: 12, fontWeight: 600, color: "var(--ink-soft)" }}>{d.d}</span>
              <span style={{ flex: 1 }}>{d.name}</span>
              <span className="muted">{aed(d.costMinor)}</span>
              <button type="button" className="quiet" style={{ padding: "0 10px", minHeight: 44, fontSize: 12 }} onClick={() => swap(i)}>Swap</button>
            </div>
          ))}
        </div>
      </section>

      <section className="card">
        <div className="row" style={{ padding: 0 }}><span className="card-title">Shopping list</span><span className="muted">{meals.listCount} items</span></div>
        {meals.list.map((g) => (
          <div key={g.group}>
            <p className="label" style={{ margin: "10px 0 2px" }}>{g.group}</p>
            {g.items.map(([name, price]) => (
              <div className="row" key={name} style={{ padding: "4px 0" }}><span>{name}</span><span className="muted">{aed(price)}</span></div>
            ))}
          </div>
        ))}
      </section>

      <p className="muted">Prices come from a fixed price list, not live store prices. Check the halal mark and the pack label for allergens before you buy.</p>

      {state === "open" ? (
        <div className="actions" style={{ marginTop: 0 }}>
          <button onClick={() => setState("used")}>Use this plan</button>
          <button className="quiet" onClick={() => setState("skipped")}>Skip this week</button>
        </div>
      ) : (
        <p role="status">{state === "used" ? "Done. The list is saved for your shop." : "Skipped. No meal plan this week; your receipts still count toward Groceries."}</p>
      )}

      <Link href="/parent/money/receipt" className="card" style={{ display: "flex", gap: 12, alignItems: "center", textDecoration: "none", color: "var(--ink)" }}>
        <Icon name="receipt" size={24} />
        <span style={{ flex: 1 }}><b>Been shopping?</b><br /><span className="muted" style={{ fontSize: 14 }}>Upload the receipt and I'll compare what you bought with the plan.</span></span>
        <span aria-hidden="true">›</span>
      </Link>
      <p className="muted">The meal plan is optional. Turn it off any time in Family, and receipts still count toward Groceries.</p>
      <SampleNote />
    </div>
  );
}
