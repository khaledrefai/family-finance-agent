"use client";

import { useState } from "react";
import { aed } from "@/lib/api";
import Link from "next/link";
import { call } from "@/lib/api";
import { Account, core, Doc, docName, docs, fromAccount, goldApi, sampleGold, useLive } from "@/lib/live";
import { AccountKind, accounts as sampleAccounts } from "@/lib/sample";
import { Loading, SampleNote, TRUST } from "../../../ui";
import { useSession } from "../../../use-session";
import { MoneyHeader } from "../money-tabs";

// Accounts and cards: the bank accounts, credit cards and cash the parents typed in by hand.
// Every payment records which one it came from, so the engine can forecast each card's next bill
// against the free cash on its due date. Nothing links to a real bank. From GET /api/accounts, the gold
// total from GET /api/gold and the latest statement from GET /api/documents.
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
  const [form, setForm] = useState({ last4: "", balance: "", limit: "", statementDay: "", dueDay: "" });
  const [added, setAdded] = useState<string | null>(null);
  const [editing, setEditing] = useState<string | null>(null);
  const [balance, setBalance] = useState("");
  const { data: accounts, live, reload } = useLive(async () => (await call<Account[]>("/api/accounts")).map(fromAccount),
    sampleAccounts, !!me);
  const { data: gold } = useLive(goldApi.get, sampleGold, !!me);
  const { data: statement } = useLive(async () => (await docs.list()).find((d) => d.kind.endsWith("STATEMENT")) ?? null,
    null as Doc | null, !!me);
  if (!me) return <Loading />;
  const field = (k: keyof typeof form) => ({ value: form[k], onChange: (e: React.ChangeEvent<HTMLInputElement>) => setForm({ ...form, [k]: e.target.value }) });

  async function saveBalance(e: React.FormEvent, id: string) {
    e.preventDefault();
    try {
      await core.setBalance(id, balance);
      setAdded("Balance updated. It's dated today.");
      setEditing(null);
      setBalance("");
      reload();
    } catch (err) {
      setAdded((err as Error).message);
    }
  }

  async function save(e: React.FormEvent) {
    e.preventDefault();
    if (!live) {
      setAdded(`Added ${label}.`);
      setAdding(false);
      return;
    }
    try {
      await call<Account>("/api/accounts", {
        method: "POST",
        body: JSON.stringify({
          kind, label, last4: form.last4 || null, balance: form.balance || "0", limit: form.limit || null,
          statementDay: form.statementDay ? Number(form.statementDay) : null, dueDay: form.dueDay ? Number(form.dueDay) : null,
        }),
      });
      setAdded(`Added ${label}.`);
      setLabel("");
      setForm({ last4: "", balance: "", limit: "", statementDay: "", dueDay: "" });
      setAdding(false);
      reload();
    } catch (err) {
      setAdded((err as Error).message);
    }
  }

  return (
    <div className="stack">
      <MoneyHeader sub="What the family pays from" />
      <p className="muted">
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
              {live && (editing === a.id ? (
                <form onSubmit={(e) => saveBalance(e, a.id)} style={{ display: "flex", gap: 8, alignItems: "end" }}>
                  <label style={{ flex: 1 }}>
                    {a.kind === "CREDIT_CARD" ? "Spent on it today, AED" : "Balance today, AED"}
                    <input inputMode="decimal" value={balance} onChange={(e) => setBalance(e.target.value)} placeholder="2,500" required autoFocus />
                  </label>
                  <button type="submit">Save</button>
                </form>
              ) : (
                <button type="button" className="link" style={{ justifySelf: "start", background: "none", border: 0, padding: 0, minHeight: 0 }}
                  onClick={() => { setEditing(a.id); setBalance(""); }}>
                  Update {a.kind === "CREDIT_CARD" ? "what's on the card" : "the balance"}
                </button>
              ))}
            </article>
          ))}
        </section>
      ))}

      <section className="stack" style={{ gap: 10 }}>
        <h2 className="first">Gold</h2>
        <Link href="/parent/money/gold" className="card acct" style={{ color: "inherit", textDecoration: "none" }}>
          <div className="acct-head">
            <b style={{ fontWeight: 600 }}>Gold savings</b>
            <span aria-hidden="true">›</span>
          </div>
          <div>
            <p style={{ fontSize: 24, fontWeight: 300, fontVariantNumeric: "tabular-nums" }}>{gold.value.text}</p>
            <p className="est">{gold.grams.length ? gold.grams.map((g) => `${g.grams} g ${g.purity}`).join(" + ") : "Nothing logged yet"} · at today's price</p>
          </div>
        </Link>
      </section>

      <section className="card" style={{ display: "grid", gap: 10 }}>
        <p className="card-title">Latest statement upload</p>
        {statement ? (
          <>
            <Link href={`/parent/money/document?id=${statement.id}`} className="matched" style={{ color: "inherit", textDecoration: "none" }}>
              <span>{docName(statement)} · {statement.counts.lines} lines</span>
              <span className="confirmed">{statement.status === "CONFIRMED" ? "Confirmed" : statement.accountId ? "Matched" : "Pick the account"}</span>
            </Link>
            <p className="muted">{statement.matchReason ? `${statement.matchReason}. ` : ""}Open it to change the account if that's wrong.</p>
          </>
        ) : (
          <p className="muted">
            No statement yet. Upload one in <Link href="/parent/money#uploads" className="link">Money, Uploads</Link>: it's matched to the right card by its last 4 digits.
          </p>
        )}
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
              <input inputMode="numeric" pattern="\d{4}" maxLength={4} placeholder="1234" {...field("last4")} />
            </label>
          )}
          <label>
            {kind === "CREDIT_CARD" ? "Spent on it so far, AED" : "Balance, AED"}
            <input inputMode="decimal" placeholder="2,500" {...field("balance")} />
          </label>
          {kind === "CREDIT_CARD" && (
            <>
              <label>
                Card limit, AED
                <input inputMode="decimal" placeholder="15,000" {...field("limit")} required />
              </label>
              <label>
                Statement day
                <input inputMode="numeric" placeholder="27" {...field("statementDay")} required />
              </label>
              <label>
                Bill due day
                <input inputMode="numeric" placeholder="5" {...field("dueDay")} required />
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
      <SampleNote live={live} />
    </div>
  );
}
