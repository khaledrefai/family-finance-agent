"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useRef, useState } from "react";
import { findings as sampleFindings } from "@/lib/sample";
import { Doc, docName, docs, docState, Finding, useAccounts, useLive } from "@/lib/live";
import { AccountPicker } from "../../ui";

// Money > Uploads: a parent uploads a receipt or a statement, the Bookkeeper reads it into a draft, and the
// document page shows that draft to check and confirm. Below: what the engine found in the family's payments
// (double charges, subscriptions nobody listed). All from the core; samples only if it can't be reached.
const KINDS = [
  { kind: "RECEIPT", label: "Receipt" },
  { kind: "STATEMENT", label: "Bank or card statement" },
] as const;

const ACTIONS: Record<Finding["kind"], { yes: "DUPLICATE" | "ADD"; yesLabel: string; no: "KEEP" | "IGNORE"; noLabel: string; done: string }> = {
  DUPLICATE: { yes: "DUPLICATE", yesLabel: "Mark as double charge", no: "KEEP", noLabel: "Both are right", done: "Marked. The extra charge no longer counts. Ask your bank to refund it." },
  SUBSCRIPTION: { yes: "ADD", yesLabel: "Add it", no: "IGNORE", noLabel: "Ignore", done: "Added to your commitments. The plan counts it from now on." },
};

export function Uploads({ enabled }: { enabled: boolean }) {
  const router = useRouter();
  const fileInput = useRef<HTMLInputElement>(null);
  const [kind, setKind] = useState<"RECEIPT" | "STATEMENT">("RECEIPT");
  const [accountId, setAccountId] = useState("");
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const { accounts, live: accountsLive } = useAccounts(enabled);
  const list = useLive(docs.list, [] as Doc[], enabled);
  const found = useLive(docs.findings, [] as Finding[], enabled);
  const live = list.live;

  async function send(file: File, as: "RECEIPT" | "STATEMENT") {
    setError(null);
    setBusy(`Uploading ${file.name}…`);
    try {
      const doc = await docs.upload(file, as, accountId || undefined);
      setBusy("Reading it…");
      await docs.read(doc.id).catch(() => null); // the document page offers to read it again if this failed
      router.push(`/parent/money/document?id=${doc.id}`);
    } catch (err) {
      setError((err as Error).message);
      setBusy(null);
    }
  }

  async function decide(f: Finding, action: "DUPLICATE" | "KEEP" | "ADD" | "IGNORE") {
    try {
      found.setData(await docs.decide(f.key, action));
      setDone(action === "KEEP" || action === "IGNORE" ? "Left as it is." : ACTIONS[f.kind].done);
    } catch (err) {
      setError((err as Error).message);
    }
  }

  return (
    <section id="uploads" className="stack" style={{ gap: 10 }}>
      <h2 className="first">Receipts and statements</h2>

      {!live ? (
        <>
          <div className="card" style={{ display: "grid", gap: 8 }}>
            <p style={{ fontSize: 14 }}>Uploads need the app's server. The findings below are samples.</p>
            <Link href="/parent/money/receipt" className="link">See how a checked receipt looks</Link>
          </div>
          {sampleFindings.map((f) => (
            <div className="card" key={f.id}><p style={{ fontSize: 14 }}><b>{f.text}</b> {f.detail}</p></div>
          ))}
        </>
      ) : (
        <>
          <div className="panel" style={{ margin: 0, display: "grid", gap: 10 }}>
            <strong style={{ marginBottom: 0 }}>Upload a receipt or a statement</strong>
            <div className="chips" style={{ marginBottom: 0 }} role="group" aria-label="What is it?">
              {KINDS.map((k) => (
                <button key={k.kind} type="button" className={`chip${k.kind === kind ? " on" : ""}`} aria-pressed={k.kind === kind} onClick={() => setKind(k.kind)}>
                  {k.label}
                </button>
              ))}
            </div>
            {accountsLive && (
              <AccountPicker
                label={kind === "RECEIPT" ? "Paid from (or leave it: I'll match the card's last 4 digits)" : "Which account or card? (or leave it: I'll match the last 4 digits)"}
                value={accountId}
                onChange={(id) => setAccountId(id === accountId ? "" : id)}
                accounts={accounts.filter((a) => kind === "RECEIPT" || a.kind !== "CASH")}
              />
            )}
            <input
              ref={fileInput}
              type="file"
              accept="image/*,application/pdf,.csv,text/csv,.txt,text/plain"
              style={{ display: "none" }}
              onChange={(e) => {
                const f = e.target.files?.[0];
                e.target.value = "";
                if (f) send(f, kind);
              }}
            />
            <button type="button" disabled={!!busy} onClick={() => fileInput.current?.click()}>
              {kind === "RECEIPT" ? "Take a photo or choose a file" : "Choose the statement (PDF or CSV)"}
            </button>
            <p className="est">Photos and PDFs are read by the AI. A bank's CSV export is read exactly, even with the AI off. Up to 5 MB.</p>
            <div className="chips" style={{ marginBottom: 0 }}>
              <button type="button" className="chip" disabled={!!busy} onClick={async () => send(await docs.sampleFile("receipt"), "RECEIPT")}>Try the demo receipt</button>
              <button type="button" className="chip" disabled={!!busy} onClick={async () => send(await docs.sampleFile("statement"), "STATEMENT")}>Try the demo Visa statement</button>
            </div>
            {busy && <p className="muted" role="status">{busy}</p>}
            {error && <p className="error" role="alert">{error}</p>}
          </div>

          {list.data.length > 0 && (
            <div className="card">
              <p className="card-title" style={{ marginBottom: 4 }}>Uploaded</p>
              <div className="rows">
                {list.data.slice(0, 8).map((d) => {
                  const s = docState(d);
                  return (
                    <Link key={d.id} href={`/parent/money/document?id=${d.id}`} className="row" style={{ padding: "10px 0", color: "inherit", textDecoration: "none" }}>
                      <span>{docName(d)}<span className="muted" style={{ display: "block", fontSize: 12 }}>{d.fileName}</span></span>
                      <b className={s.tone === "good" ? "good" : s.tone === "bad" ? "over" : undefined} style={{ fontSize: 13 }}>{s.label} ›</b>
                    </Link>
                  );
                })}
              </div>
            </div>
          )}

          <h2 className="first" style={{ marginTop: 6 }}>Found in your payments</h2>
          {found.data.length === 0 && (
            <p className="muted">Nothing odd: no double charges, and no repeating payments missing from your commitments.</p>
          )}
          {found.data.map((f) => (
            <div className="card" key={f.key} style={{ display: "grid", gap: 8 }}>
              <p style={{ fontSize: 14 }}><b>{f.title}</b> {f.detail}</p>
              <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
                <button onClick={() => decide(f, ACTIONS[f.kind].yes)}>{ACTIONS[f.kind].yesLabel}</button>
                <button className="quiet" onClick={() => decide(f, ACTIONS[f.kind].no)}>{ACTIONS[f.kind].noLabel}</button>
              </div>
            </div>
          ))}
          {done && <p className="muted" role="status">{done}</p>}
        </>
      )}
    </section>
  );
}
