"use client";

import { useState } from "react";
import { aed } from "@/lib/api";
import { receipt } from "@/lib/sample";
import { Back, Masthead, SampleNote } from "../../../ui";
import { useSession } from "../../../use-session";

// A receipt the agent has read, shown as a draft to confirm. When the family follows the meal plan,
// it also shows what was bought against the plan. A sample receipt until the documents endpoint exists.
export default function ReceiptPage() {
  const { me } = useSession("PARENT");
  const [done, setDone] = useState(false);
  if (!me) return <p className="muted">Loading…</p>;

  return (
    <div className="stack">
      <Back href="/parent/money" label="Money" />
      <Masthead title="Check this receipt" sub="Read from your photo · nothing counts until you confirm" />

      <div className={`draft${done ? " done" : ""}`} style={{ marginTop: -12 }}>
        {done && <span className="confirmed">Confirmed</span>}
        <div className="row"><span>Shop</span><b>{receipt.shop}</b></div>
        <div className="row"><span>Date</span><b>{receipt.date}</b></div>
        <div className="row"><span>Paid by</span><b>{receipt.paidBy}</b></div>
        <div className="row"><span>Budget</span><b>{receipt.budget}</b></div>
        <div className="row total" style={{ borderTopWidth: 1 }}><span>Total</span><b>{aed(receipt.totalMinor, { cents: true })}</b></div>
      </div>

      <section className="card">
        <p className="card-title">Against this week's meal plan</p>
        <p style={{ fontSize: 14, margin: "4px 0 8px" }}>{receipt.vsPlan}</p>
        <div className="rows">
          {receipt.lines.map((l) => (
            <div className="row" key={l.name}>
              <span>
                {l.name}{" "}
                <span className="typed" style={{ display: "inline-flex" }}>
                  <span style={l.inPlan ? { background: "var(--approved-wash)", color: "var(--approved)" } : { background: "var(--waiting-wash)", color: "var(--waiting)" }}>
                    {l.inPlan ? "In plan" : "Extra"}
                  </span>
                </span>
              </span>
              <b>{aed(l.amountMinor, { cents: true })}</b>
            </div>
          ))}
        </div>
        <p className="muted" style={{ marginTop: 8 }}>Showing 5 of {receipt.lineCount} lines. Not following a meal plan? The receipt still goes into Groceries, line by line.</p>
      </section>

      <section className="card">
        <p style={{ fontSize: 14 }}>
          <b>This may already be on your card statement.</b> When the October statement is uploaded, I'll match it so it isn't counted twice.
        </p>
      </section>

      {done ? (
        <p role="status">Saved to Groceries.</p>
      ) : (
        <div className="actions" style={{ marginTop: 0 }}>
          <button onClick={() => setDone(true)}>Confirm</button>
          <button className="quiet">Edit lines</button>
        </div>
      )}
      <p className="trust" style={{ textAlign: "center" }}>The AI reads the receipt; the app adds it up.</p>
      <SampleNote />
    </div>
  );
}
