"use client";

import { useState } from "react";
import { aed } from "@/lib/api";
import { AccountKind, accounts, statementUpload } from "@/lib/sample";
import { AccountPicker, SampleNote, TRUST } from "../../../ui";
import { useSession } from "../../../use-session";
import { MoneyHeader } from "../money-tabs";

// Accounts and cards: the bank accounts, credit cards and cash the parents typed in by hand.
// Every payment records which one it came from, so the engine can forecast each card's next bill
// against the free cash on its due date. Nothing links to a real bank. Sample figures until
// GET /api/accounts exists.
const GROUPS: { kind: AccountKind; title: string }[] = [
  { kind: "BANK", title: "Bank accounts" },
  { kind: "CREDIT_CARD", title: "Credit cards" },
  { kind: "CASH", title: "Cash" },
];

const KINDS: { kind: AccountKind; label: string }[] = [
  { kind: "BANK", label: "Bank account" },
  { kind: "CREDIT_CARD", label: "Credit card" },
  { kind: "CASH", label: "Cash" },
];

export default function AccountsPage() {
  const { me } = useSession("PARENT");
  const [adding, setAdding] = useState(false);
  const [kind, setKind] = useState<AccountKind>("BANK");
  const [label, setLabel] = useState("");
  const [added, setAdded] = useState<string | null>(null);
  const [statementAccount, setStatementAccount] = useState(statementUpload.accountId);
  if (!me) return <p className="muted">Loading…</p>;

  function save(e: React.FormEvent) {
    e.preventDefault();
    setAdded(`Added ${label}. The app saves it once the accounts service is ready.`);
    setLabel("");
    setAdding(false);
  }

  return (
    <div className="stack">
      <MoneyHeader sub="What the family pays from" />
      <p className="muted" style={{ marginTop: -6 }}>
        You type these in once. The app never connects to your bank, so balances are your own estimate.
      </p>

      {GROUPS.map((g) => (
        <section key={g.kind} className="stack" style={{ gap: 10 }}>
          <h2 className="first">{g.title}</h2>
          {accounts.filter((a) => a.kind === g.kind).map((a) => (
            <article key={a.id} className="card acct">
              <div className="acct-head">
                <span>
                  <b style={{ fontWeight: 600 }}>{a.label}</b>{" "}
                  {a.last4 && <span className="acct-num">···{a.last4}</span>}
                </span>
                <span className="muted">{a.owner}</span>
              </div>

              {a.kind === "CREDIT_CARD" ? (
                <>
                  <div className="row" style={{ padding: 0 }}>
                    <span>Spent on the card</span>
                    <b>{aed(-a.balanceMinor)} of {aed(a.limitMinor!)}</b>
                  </div>
                  <div className="bud-track" role="img" aria-label={`${a.usedShare} percent of the limit used`}>
                    <span className="fill" style={{ width: `${a.usedShare}%` }} />
                  </div>
                  <div className="acct-facts">
                    <span>Statement<b>Day {a.statementDay}</b></span>
                    <span>Bill due<b>Day {a.dueDay}</b></span>
                    <span>As of<b>{a.asOf}</b></span>
                  </div>
                  {a.bill && (
                    <div className={a.bill.ok ? "draft done" : "card-warn"} style={{ gap: 4 }}>
                      {!a.bill.ok && <span className="warn-stamp">Card check</span>}
                      <p style={{ fontSize: 14 }}>
                        Next bill, so far: <b>{aed(a.bill.soFarMinor)}</b>, due {a.bill.due}. {aed(a.bill.freeByDueMinor)} is free by then
                        {a.bill.ok ? ", so it's covered." : ", which is not enough. Pay big things from a bank account until the bill is paid."}
                      </p>
                    </div>
                  )}
                </>
              ) : (
                <div>
                  <p style={{ fontSize: 24, fontWeight: 300, fontVariantNumeric: "tabular-nums" }}>{aed(a.balanceMinor)}</p>
                  <p className="est">Estimated · as of {a.asOf}</p>
                </div>
              )}
            </article>
          ))}
        </section>
      ))}

      <section className="card" style={{ display: "grid", gap: 10 }}>
        <p className="card-title">Latest statement upload</p>
        <div className="matched">
          <span>{statementUpload.name} · {statementUpload.lines} lines</span>
          <span className="confirmed">Matched</span>
        </div>
        <p className="muted">Matched by its {statementUpload.matchedBy}. Change it if that's wrong.</p>
        <AccountPicker label="This statement is for" value={statementAccount} onChange={setStatementAccount} />
      </section>

      {adding ? (
        <form className="panel" onSubmit={save}>
          <strong>Add an account or card</strong>
          <div className="chips" style={{ marginBottom: 0 }} role="group" aria-label="Kind">
            {KINDS.map((k) => (
              <button key={k.kind} type="button" className={`chip${k.kind === kind ? " on" : ""}`} aria-pressed={k.kind === kind} onClick={() => setKind(k.kind)}>
                {k.label}
              </button>
            ))}
          </div>
          <label>
            Name
            <input value={label} onChange={(e) => setLabel(e.target.value)} placeholder="Sara's Visa" required />
          </label>
          {kind !== "CASH" && (
            <label>
              Last 4 digits
              <input inputMode="numeric" pattern="\d{4}" maxLength={4} placeholder="1234" />
            </label>
          )}
          <label>
            {kind === "CREDIT_CARD" ? "Spent on it so far, AED" : "Balance, AED"}
            <input inputMode="decimal" placeholder="2,500" />
          </label>
          {kind === "CREDIT_CARD" && (
            <>
              <label>
                Card limit, AED
                <input inputMode="decimal" placeholder="15,000" />
              </label>
              <label>
                Statement day
                <input inputMode="numeric" placeholder="27" />
              </label>
              <label>
                Bill due day
                <input inputMode="numeric" placeholder="5" />
              </label>
            </>
          )}
          <button type="submit">Save</button>
        </form>
      ) : (
        <button type="button" className="quiet" onClick={() => setAdding(true)}>Add an account or card</button>
      )}
      {added && <p className="muted" role="status">{added}</p>}
      <p className="trust">{TRUST}</p>
      <SampleNote />
    </div>
  );
}
