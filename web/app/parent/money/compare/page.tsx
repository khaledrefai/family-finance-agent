"use client";

import { useEffect, useState } from "react";
import { core, dayLongMonth, useAccounts } from "@/lib/live";
import { Comparison, findPrices, Offer } from "@/lib/prices";
import { PriceResults } from "../../../price-results";
import { AccountPicker, Back, CardWarning, Loading, Masthead } from "../../../ui";
import { useSession } from "../../../use-session";

// Compare prices before a big purchase: say what it is, the core searches the shops and ranks the offers by
// payable total, then checks the best one against the family's free cash and the card's next bill.
// "I bought it" adds the payment, with the shop and the core's total, to this month's spending.
const IDEAS = ["Air fryer", "Samsung 55-inch TV", "Dyson V8", "Kids scooter"];

export default function ComparePage() {
  const { me } = useSession("PARENT");
  const { accounts, live } = useAccounts(!!me);
  const [want, setWant] = useState("");
  const [paidFrom, setPaidFrom] = useState("visa");
  const [finding, setFinding] = useState(false);
  const [found, setFound] = useState<{ comparison: Comparison | null; reply: string } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [added, setAdded] = useState<string | null>(null);
  // While a payment is being added the buttons wait, and an offer already added can't be added again
  const [paying, setPaying] = useState(false);
  const [boughtKeys, setBoughtKeys] = useState<Record<string, string>>({});

  // Start on the first card once the family's own accounts load
  useEffect(() => {
    if (live && !accounts.some((a) => a.id === paidFrom)) {
      setPaidFrom((accounts.find((a) => a.kind === "CREDIT_CARD") ?? accounts[0])?.id ?? "");
    }
  }, [live, accounts, paidFrom]);

  if (!me) return <Loading />;

  async function find(text: string, account = paidFrom) {
    if (!text.trim() || finding) return;
    setWant(text);
    setFinding(true);
    setAdded(null);
    setBoughtKeys({});
    try {
      setFound(await findPrices(text, live ? account : undefined));
      setError(null);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setFinding(false);
    }
  }

  async function bought(o: Offer) {
    if (paying || boughtKeys[o.key]) return;
    setPaying(true);
    try {
      const paid = await core.pay({
        amount: (o.total.minor / 100).toFixed(2), description: o.title.slice(0, 200), merchant: o.store,
        category: "SHOPPING", paidFromAccountId: paidFrom,
      });
      setAdded(`Added: ${paid.amount.text} at ${o.store}, paid from ${paid.paidFrom}. It counts toward this month's budget.`);
      setBoughtKeys((m) => ({ ...m, [o.key]: "Added ✓" }));
      setError(null);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setPaying(false);
    }
  }

  const c = found?.comparison;
  const a = c?.assessment;

  return (
    <div className="stack">
      <Back href="/parent/money/add" label="Add spending" />
      <Masthead title="Compare prices" sub="Before a big purchase: the best total, and whether it fits" />

      <form
        className="card"
        style={{ gap: 12 }}
        onSubmit={(e) => {
          e.preventDefault();
          find(want);
        }}
      >
        <label>
          What are you buying?
          <input placeholder="An air fryer, or a model like Philips HD9200" value={want} onChange={(e) => setWant(e.target.value)} />
        </label>
        <AccountPicker label="Paying with" value={paidFrom} onChange={(id) => { setPaidFrom(id); if (want && found) find(want, id); }} accounts={accounts} />
        <button type="submit" disabled={finding}>Compare prices</button>
        <div className="chips" style={{ marginBottom: 0 }}>
          {IDEAS.map((s) => (
            <button key={s} type="button" className="chip" onClick={() => find(s)} disabled={finding}>
              {s}
            </button>
          ))}
        </div>
        {finding && <p className="thinking">Looking in the shops…</p>}
      </form>

      {found && !c && <p className="turn-agent">{found.reply}</p>}
      {c && (
        <>
          {c.best && a && (
            <div className="stack" style={{ gap: 8 }}>
              <p className="turn-agent">
                Best total {c.best.total.text} at {c.best.store}.{" "}
                {a.freeUntilPay && a.freeAfter && (a.freeAfter.minor < 0
                  ? `It doesn't fit before pay day: ${a.freeUntilPay.text} is free until then.`
                  : `${a.freeUntilPay.text} is free until pay day, ${a.freeAfter.text} after this.`)}
              </p>
              {a.card && (
                <CardWarning accountId={paidFrom} live={{
                  card: a.card.card, due: dayLongMonth(a.card.dueOn), withMinor: a.card.billWith.minor, freeByDueMinor: a.card.freeByDue.minor, fits: a.card.fits,
                }} />
              )}
              {a.card?.fits && <p className="muted">Card check: the bill on {dayLongMonth(a.card.dueOn)} would be {a.card.billWith.text}, and that is covered.</p>}
            </div>
          )}
          {added && <div className="sent-note" role="status">{added}</div>}
          {error && <p className="error" role="alert">{error}</p>}
          <PriceResults comparison={c} action={live ? (paying ? "Adding…" : "I bought it") : undefined} onPick={live ? bought : undefined}
            busy={paying} done={boughtKeys} showLinks />
        </>
      )}
      {!c && error && <p className="error" role="alert">{error}</p>}
    </div>
  );
}
