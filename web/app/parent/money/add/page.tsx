"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { aed } from "@/lib/api";
import { CardCheck, core, dayLongMonth, useAccounts } from "@/lib/live";
import { spendCategories } from "@/lib/sample";
import { AccountPicker, Back, CardWarning, Icon, Loading, Masthead } from "../../../ui";
import { useSession } from "../../../use-session";

// Add spending: upload a receipt for anything, or type in a payment that has no receipt
// (a restaurant, a hotel, a taxi). The core stores it with what paid for it (POST /api/payments),
// and before saving, a card payment gets the engine's card check for that amount.
const FOR_WHOM = ["Whole family", "Omar", "Sara", "A kid"];
const CATEGORY: Record<string, string> = {
  Restaurant: "EATING_OUT", Hotel: "HOTEL", Transport: "TRANSPORT", Groceries: "GROCERIES", Shopping: "SHOPPING", Other: "OTHER",
};
const AMOUNT = /^\d+(\.\d{1,2})?$/;

export default function AddSpendingPage() {
  const { me } = useSession("PARENT");
  const [amount, setAmount] = useState("");
  const [category, setCategory] = useState("Restaurant");
  const [where, setWhere] = useState("");
  const [date, setDate] = useState("");
  const [paidFrom, setPaidFrom] = useState("visa");
  const [forWhom, setForWhom] = useState("Whole family");
  const [error, setError] = useState<string | null>(null);
  const [added, setAdded] = useState<string | null>(null);
  const { accounts, live } = useAccounts(!!me);
  const [check, setCheck] = useState<CardCheck | null>(null);

  // Start on the first card once the family's own accounts load
  useEffect(() => {
    if (live && !accounts.some((a) => a.id === paidFrom)) {
      setPaidFrom((accounts.find((a) => a.kind === "CREDIT_CARD") ?? accounts[0])?.id ?? "");
    }
  }, [live, accounts, paidFrom]);

  // The engine's card check for this amount on this card, a moment after typing stops
  const isCard = accounts.find((a) => a.id === paidFrom)?.kind === "CREDIT_CARD";
  useEffect(() => {
    setCheck(null);
    if (!live || !isCard || !AMOUNT.test(amount.trim())) return;
    const t = setTimeout(() => core.cardCheck(paidFrom, amount.trim()).then(setCheck).catch(() => setCheck(null)), 400);
    return () => clearTimeout(t);
  }, [live, isCard, paidFrom, amount]);

  if (!me) return <Loading />;

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!AMOUNT.test(amount.trim())) {
      setError("Enter the amount as a number, like 186 or 42.50.");
      return;
    }
    setError(null);
    const from = accounts.find((a) => a.id === paidFrom)?.label ?? "";
    const what = `${category.toLowerCase()}${where ? `, ${where}` : ""}`;
    if (live) {
      try {
        const paid = await core.pay({
          amount: amount.trim(), description: where ? `${category}: ${where}` : category, category: CATEGORY[category] ?? "OTHER",
          paidFromAccountId: paidFrom, occurredOn: date || undefined,
        });
        setAdded(`Added: ${paid.amount.text} for ${what}, paid from ${paid.paidFrom}. It counts toward this month's budget.`);
      } catch (err) {
        setError((err as Error).message);
        return;
      }
    } else {
      setAdded(`Added: AED ${amount.trim()} for ${what}, paid from ${from}. It counts toward this month's budget.`);
    }
    setAmount("");
    setWhere("");
  }

  return (
    <div className="stack">
      <Back href="/parent/money" label="Money" />
      <Masthead title="Add spending" sub="Anything the family paid for, with or without a receipt" />

      <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 10 }}>
        <Link href="/parent/money#uploads" className="card" style={{ display: "grid", gap: 6, textDecoration: "none", color: "var(--ink)" }}>
          <Icon name="receipt" size={24} />
          <b>Upload a receipt</b>
          <span className="muted">Photo or PDF. I read the lines for you.</span>
        </Link>
        <div className="card strong" style={{ display: "grid", gap: 6, borderWidth: 2 }} aria-current="true">
          <Icon name="plus" size={24} />
          <b>Add a payment</b>
          <span className="muted">No receipt? Type it in.</span>
        </div>
      </div>

      <Link href="/parent/money/compare" className="link">Buying something big? Compare the shops&apos; prices first</Link>

      <form className="card" onSubmit={submit} style={{ gap: 14 }}>
        <label>
          Amount in AED
          <input inputMode="decimal" placeholder="186.00" value={amount} onChange={(e) => setAmount(e.target.value)} required />
        </label>
        <fieldset style={{ border: 0, padding: 0, margin: 0, display: "grid", gap: 4 }}>
          <legend className="muted" style={{ marginBottom: 4 }}>What was it for?</legend>
          <div className="chips" style={{ marginBottom: 0 }}>
            {spendCategories.map((c) => (
              <button key={c} type="button" className={`chip${c === category ? " on" : ""}`} aria-pressed={c === category} onClick={() => setCategory(c)}>
                {c}
              </button>
            ))}
          </div>
        </fieldset>
        <label>
          Where
          <input placeholder="Family dinner, Dubai Marina" value={where} onChange={(e) => setWhere(e.target.value)} />
        </label>
        <label>
          Date
          <input type="date" value={date} onChange={(e) => setDate(e.target.value)} />
        </label>
        <AccountPicker value={paidFrom} onChange={setPaidFrom} accounts={accounts} />
        {live ? (
          <CardWarning accountId={paidFrom} hint={false} live={check && {
            card: check.card, due: dayLongMonth(check.dueOn), withMinor: check.billWith.minor, freeByDueMinor: check.freeByDue.minor, fits: check.fits,
          }} />
        ) : (
          <CardWarning accountId={paidFrom} hint={false} />
        )}
        {live && check?.fits && <p className="muted">Card check: the bill on {dayLongMonth(check.dueOn)} would be {aed(check.billWith.minor)}, and that is covered.</p>}
        <fieldset style={{ border: 0, padding: 0, margin: 0, display: "grid", gap: 4 }}>
          <legend className="muted" style={{ marginBottom: 4 }}>Who was it for?</legend>
          <div className="chips" style={{ marginBottom: 0 }}>
            {FOR_WHOM.map((w) => (
              <button key={w} type="button" className={`chip${w === forWhom ? " on" : ""}`} aria-pressed={w === forWhom} onClick={() => setForWhom(w)}>
                {w}
              </button>
            ))}
          </div>
        </fieldset>
        <button type="submit">Add it</button>
        {error && <p className="error" role="alert">{error}</p>}
        {added && <p className="muted" role="status">{added}</p>}
      </form>

      <p className="turn-agent" style={{ fontSize: 14 }}>
        Faster: tell <Link href="/parent/ask">Ask</Link> in your own words, like "taxi to the airport 85 dirhams" or "hotel in Ras Al Khaimah 1,450 for the weekend".
      </p>
    </div>
  );
}
