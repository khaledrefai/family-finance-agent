"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { Suspense, useEffect, useState } from "react";
import { advice, BudgetSpend, BudgetSpending, day } from "@/lib/live";
import { Back, Loading, Masthead } from "../../../../ui";
import { useSession } from "../../../../use-session";

// One budget's payments for a month: what its bar on Budgets (or the monthly summary) adds up. Each has its date,
// shop, who paid, where it came from and what paid it; a receipt's lines open under it. The total is the core's.
function Spending() {
  const params = useSearchParams();
  const category = params.get("c") ?? "";
  const month = params.get("month");
  const { me } = useSession("PARENT");
  const [page, setPage] = useState<BudgetSpending | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    if (!me || !category) return;
    setFailed(false);
    advice.spending(category, month).then(setPage).catch(() => setFailed(true));
  }, [me, category, month]);

  const back = month ? <Back href={`/parent/summary?month=${month}`} label="Summary" /> : <Back href="/parent/money/budgets" label="Budgets" />;
  if (!me) return <Loading />;
  if (failed || !category) {
    return (
      <div className="stack">
        {back}
        <p className="error" role="alert">This budget&apos;s payments didn&apos;t load. Try again in a moment.</p>
      </div>
    );
  }
  if (!page) return <Loading />;

  const over = page.over.minor > 0;
  return (
    <div className="stack">
      {back}
      <Masthead
        title={page.label}
        sub={`${page.monthLabel}${page.running ? " so far" : ""} · ${page.count === 1 ? "1 payment" : `${page.count} payments`}`}
      />

      <section className="card strong" style={{ display: "grid", gap: 4 }}>
        <div className="row" style={{ padding: 0, fontSize: 16 }}>
          <span>Spent</span>
          <b className={over ? "over" : undefined}>{page.spent.text} of {page.limit.text}</b>
        </div>
        <p className={over ? "over" : "muted"} style={{ margin: 0, fontSize: 14 }}>
          {over ? `Over the budget by ${page.over.text}` : `${page.left.text} left${page.running ? " this month" : ""}`}
        </p>
      </section>

      <section className="card">
        {page.spends.length === 0 ? (
          <p style={{ fontSize: 14, margin: 0 }}>
            Nothing spent on {page.label.toLowerCase()} {page.running ? "yet this month" : `in ${page.monthLabel}`}.
          </p>
        ) : (
          <div className="rows">
            {page.spends.map((s) => <SpendRow key={s.id} s={s} />)}
          </div>
        )}
        <p className="trust" style={{ marginTop: 8 }}>
          From receipts, statements and payments you added. Kids&apos; own spending comes out of their pocket money, so it isn&apos;t in here.
        </p>
      </section>

      {page.running && (
        <Link href="/parent/money/add" className="link">Missing something? Add spending</Link>
      )}
    </div>
  );
}

function SpendRow({ s }: { s: BudgetSpend }) {
  const name = s.shop ?? s.description;
  const meta = [day(s.on), s.who, s.source, s.paidFrom].filter(Boolean).join(" · ");
  return (
    <div className="spend">
      <div className="row" style={{ padding: 0 }}>
        <span style={{ fontWeight: 600 }}>{name}</span>
        <b>{s.amount.text}</b>
      </div>
      {s.shop && s.description !== s.shop && <span style={{ fontSize: 14 }}>{s.description}</span>}
      <span className="muted">{meta}</span>
      {s.items.length > 0 && (
        <details className="more">
          <summary>{s.items.length === 1 ? "1 line on the receipt" : `${s.items.length} lines on the receipt`}</summary>
          <div className="rows">
            {s.items.map((i, n) => (
              <div key={n} className="row sub">
                <span>{i.description}</span>
                <b>{i.credit ? `−${i.amount.text}` : i.amount.text}</b>
              </div>
            ))}
          </div>
        </details>
      )}
    </div>
  );
}

export default function BudgetSpendingPage() {
  return (
    <Suspense fallback={<Loading />}>
      <Spending />
    </Suspense>
  );
}
