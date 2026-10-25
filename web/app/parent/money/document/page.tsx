"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { Doc, docName, docs, docState, longDay, useAccounts } from "@/lib/live";
import { AccountPicker, Back, Masthead, TRUST } from "../../../ui";
import { useSession } from "../../../use-session";

// One uploaded receipt or statement, as the Bookkeeper read it: a draft (dashed) until a parent confirms.
// Every count, total and "already in your spending" comes from the core, which matched each line against the
// ledger. The agent only copied what was printed. Opened as /parent/money/document?id=...
const CATEGORIES = [
  { id: "GROCERIES", label: "Groceries" },
  { id: "EATING_OUT", label: "Eating out" },
  { id: "ENTERTAINMENT", label: "Entertainment" },
  { id: "TRANSPORT", label: "Transport" },
  { id: "SHOPPING", label: "Shopping" },
  { id: "HEALTH", label: "Health" },
  { id: "HOME", label: "Home" },
  { id: "OTHER", label: "Other" },
];

const LABEL: Record<string, string> = Object.fromEntries(CATEGORIES.map((c) => [c.id, c.label]));

export default function DocumentPage() {
  const { me } = useSession("PARENT");
  const router = useRouter();
  const [id, setId] = useState<string | null>(null);
  const [doc, setDoc] = useState<Doc | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [accountId, setAccountId] = useState("");
  const [category, setCategory] = useState("");
  const [updateBalance, setUpdateBalance] = useState(false);
  const [showAll, setShowAll] = useState(false);
  const { accounts } = useAccounts(!!me);

  // The id is in the address (?id=...). Read here, after the page loads, so the page can be built ahead of time.
  useEffect(() => setId(new URLSearchParams(window.location.search).get("id")), []);
  useEffect(() => {
    if (!me || !id) return;
    docs.get(id).then((d) => {
      setDoc(d);
      setAccountId(d.accountId ?? "");
      setCategory(d.category ?? "");
    }).catch((e) => setError((e as Error).message));
  }, [me, id]);

  if (!me || (!doc && !error)) return <p className="muted">Loading…</p>;
  if (!doc) return (<div className="stack"><Back href="/parent/money#uploads" label="Money" /><p className="error" role="alert">{error}</p></div>);

  const receipt = doc.kind === "RECEIPT" || doc.kind === "INVOICE";
  const draft = doc.status === "EXTRACTED";
  const state = docState(doc);
  const lines = showAll ? doc.lines : doc.lines.slice(0, 12);

  async function act(fn: () => Promise<Doc | void>, after?: () => void) {
    setBusy(true);
    setError(null);
    try {
      const d = await fn();
      if (d) setDoc(d);
      after?.();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  const readAgain = () => act(async () => {
    await docs.read(doc.id);
    return docs.get(doc.id);
  });

  return (
    <div className="stack">
      <Back href="/parent/money#uploads" label="Money" />
      <Masthead
        title={draft ? (receipt ? "Check this receipt" : "Check this statement") : docName(doc)}
        sub={`${doc.fileName} · ${doc.readBy === "LLM" ? "read by the AI" : doc.readBy === "RULES" ? "read line by line" : "uploaded"} · ${state.label.toLowerCase()}`}
      />

      <div className={`draft${doc.status === "CONFIRMED" ? " done" : ""}`} style={{ marginTop: -12 }}>
        {doc.status === "CONFIRMED" && <span className="confirmed">Confirmed</span>}
        <p style={{ fontSize: 14 }}>{doc.summary}</p>
        {receipt && doc.status !== "FAILED" && doc.status !== "UPLOADED" && (
          <>
            <div className="row"><span>Shop</span><b>{doc.merchant ?? "Not found"}</b></div>
            <div className="row"><span>Date</span><b>{doc.date ? longDay(doc.date) : "Not found"}</b></div>
            <div className="row"><span>Budget</span><b>{LABEL[category] ?? "Groceries"}</b></div>
            <div className="row total" style={{ borderTopWidth: 1 }}><span>Total</span><b>{doc.total?.text}</b></div>
          </>
        )}
        {!receipt && doc.closingBalance && (
          <div className="row"><span>Closing balance</span><b>{doc.closingBalance.text}</b></div>
        )}
        {doc.accountLabel && (
          <p className="est">{doc.accountLabel}{doc.accountLast4 ? ` ···${doc.accountLast4}` : ""}{doc.matchReason ? ` · ${doc.matchReason}` : ""}</p>
        )}
        {doc.note && <p className="muted">{doc.note}</p>}
      </div>

      {doc.matchedNote && (
        <section className="card"><p style={{ fontSize: 14 }}><b>Not counted twice.</b> {doc.matchedNote}</p></section>
      )}

      {doc.lines.length > 0 && (
        <section className="card">
          <p className="card-title">{receipt ? `Items (${doc.lines.length})` : `Lines (${doc.lines.length})`}</p>
          {!receipt && (
            <p className="muted" style={{ margin: "2px 0 6px" }}>
              {doc.counts.added} new · {doc.counts.alreadyLogged} already in your spending
              {doc.counts.possibleDuplicates > 0 ? ` · ${doc.counts.possibleDuplicates} charged twice?` : ""}
            </p>
          )}
          <div className="rows">
            {lines.map((l) => (
              <div className="row" key={l.no} style={{ alignItems: "flex-start" }}>
                <span>
                  {l.description}
                  {!receipt && (
                    <span className="typed" style={{ display: "inline-flex", marginLeft: 6 }}>
                      {l.status === "ALREADY_LOGGED" ? (
                        <span style={{ background: "var(--approved-wash)", color: "var(--approved)" }}>Already in</span>
                      ) : l.possibleDuplicate ? (
                        <span style={{ background: "var(--waiting-wash)", color: "var(--waiting)" }}>Charged twice?</span>
                      ) : (
                        <span>{l.credit ? "Money in" : LABEL[l.category ?? ""] ?? "New"}</span>
                      )}
                    </span>
                  )}
                  {(l.date || l.matchNote) && (
                    <span className="muted" style={{ display: "block", fontSize: 12 }}>
                      {l.date ? longDay(l.date) : ""}{l.date && l.matchNote ? " · " : ""}{l.matchNote ?? ""}
                    </span>
                  )}
                </span>
                <b>{l.credit ? "+" : ""}{l.amount.text}</b>
              </div>
            ))}
          </div>
          {doc.lines.length > lines.length && (
            <button type="button" className="quiet" style={{ marginTop: 8 }} onClick={() => setShowAll(true)}>Show all {doc.lines.length}</button>
          )}
          {receipt && doc.itemsTotal && <p className="muted" style={{ marginTop: 8 }}>The items add up to {doc.itemsTotal.text}.</p>}
          {!receipt && draft && <p className="muted" style={{ marginTop: 8 }}>Confirming adds {doc.newTotal.text} of new spending. Lines already in your spending are linked, not added again.</p>}
        </section>
      )}

      {draft && (
        <section className="panel" style={{ margin: 0, display: "grid", gap: 10 }}>
          <AccountPicker
            label={receipt ? "Paid from" : "This statement is for"}
            value={accountId}
            onChange={setAccountId}
            accounts={accounts.filter((a) => receipt || a.kind !== "CASH")}
          />
          {receipt && (
            <div style={{ display: "grid", gap: 6 }}>
              <span className="muted">Budget</span>
              <div className="chips" style={{ marginBottom: 0 }} role="group" aria-label="Budget">
                {CATEGORIES.map((c) => (
                  <button key={c.id} type="button" className={`chip${c.id === category ? " on" : ""}`} aria-pressed={c.id === category} onClick={() => setCategory(c.id)}>{c.label}</button>
                ))}
              </div>
            </div>
          )}
          {!receipt && doc.closingBalance && (
            <label style={{ display: "flex", gap: 8, alignItems: "center", fontSize: 14 }}>
              <input type="checkbox" checked={updateBalance} onChange={(e) => setUpdateBalance(e.target.checked)} style={{ width: "auto", minHeight: 0 }} />
              Also use {doc.closingBalance.text} as this {doc.kind === "CARD_STATEMENT" ? "card's balance" : "account's balance"}
            </label>
          )}
        </section>
      )}

      {error && <p className="error" role="alert">{error}</p>}

      {draft ? (
        <div className="actions" style={{ marginTop: 0 }}>
          <button disabled={busy || !accountId} onClick={() => act(() => docs.confirm(doc.id, { accountId, category: receipt ? category : undefined, updateBalance }))}>
            Confirm
          </button>
          <button className="quiet" disabled={busy} onClick={() => act(() => docs.discard(doc.id), () => router.push("/parent/money#uploads"))}>
            Throw away
          </button>
        </div>
      ) : doc.status === "CONFIRMED" ? (
        <p role="status">{receipt ? `Saved to ${LABEL[doc.category ?? ""] ?? "your spending"}.` : "Saved. New lines are in your spending, tagged with this account."}</p>
      ) : (
        <div className="actions" style={{ marginTop: 0 }}>
          <button disabled={busy} onClick={readAgain}>{busy ? "Reading…" : "Read it again"}</button>
          <button className="quiet" disabled={busy} onClick={() => act(() => docs.discard(doc.id), () => router.push("/parent/money#uploads"))}>Throw away</button>
        </div>
      )}
      {draft && !accountId && <p className="muted">Pick the account or card first.</p>}
      <p className="trust" style={{ textAlign: "center" }}>The AI reads the document; the app adds it up and checks it against your spending. {TRUST}</p>
    </div>
  );
}
