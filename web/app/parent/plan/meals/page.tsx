"use client";

import Link from "next/link";
import { Back, Masthead } from "../../../ui";
import { useSession } from "../../../use-session";

// The weekly dinners and shopping list arrive in a later version. Until then, delivery nights stay a line
// in the weekly food budget (Money, Budgets) and receipts still count toward Groceries.
export default function MealsPage() {
  const { me } = useSession("PARENT");
  if (!me) return <p className="muted">Loading…</p>;

  return (
    <div className="stack">
      <Back href="/parent/plan" label="Plan" />
      <Masthead title="Weekly dinners" sub="Coming soon" />
      <section className="card" style={{ display: "grid", gap: 8 }}>
        <p>A weekly dinner plan and shopping list, inside your food budget and the family's food rules, comes in a later version.</p>
        <p className="muted">For now, plan delivery nights in this week's food budget.</p>
        <Link href="/parent/money/budgets" className="link">Go to food budget</Link>
      </section>
    </div>
  );
}
