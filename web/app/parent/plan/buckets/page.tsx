"use client";

import Link from "next/link";
import { useState } from "react";
import { aed } from "@/lib/api";
import { buckets } from "@/lib/sample";
import { Back, BucketFlow, Masthead, SampleNote, TRUST } from "../../../ui";
import { useSession } from "../../../use-session";

// Money buckets: every dirham of income gets a job. The flow chart shows how income splits into
// the buckets, and each card below shows what is inside one bucket. The accountant suggests the
// split, a parent confirms it, and the engine works out every amount. Sample figures for now.
export default function BucketsPage() {
  const { me } = useSession("PARENT");
  const [confirmed, setConfirmed] = useState(buckets.confirmed);
  const [givingOn, setGivingOn] = useState(true);
  if (!me) return <p className="muted">Loading…</p>;

  return (
    <div className="stack">
      <Back href="/parent/plan" label="Plan" />
      <Masthead title="Money buckets" sub={`${buckets.month} · every dirham gets a job`} />

      <section className={`draft${confirmed ? " done" : ""}`}>
        {confirmed ? (
          <p role="status">
            <span className="confirmed">Confirmed</span> Each pay day the app puts the money into these buckets on paper. No real money moves.
          </p>
        ) : (
          <>
            <p><b>Suggested by the accountant.</b> It counts once you confirm. You can change any bucket first.</p>
            <div style={{ display: "flex", gap: 8, marginTop: 8 }}>
              <button onClick={() => setConfirmed(true)}>Confirm buckets</button>
              <Link href="/parent/ask" className="link" style={{ alignSelf: "center" }}>Change in Ask</Link>
            </div>
          </>
        )}
      </section>

      <section className="card" style={{ display: "grid", gap: 8 }}>
        <p className="card-title">How the money flows</p>
        <p className="muted">Income on the left, each bucket on the right. Tap a bucket to see what is inside.</p>
        <BucketFlow incomeMinor={buckets.incomeMinor} buckets={buckets.list.filter((b) => givingOn || b.id !== "giving")} />
        <p className="trust">{TRUST}</p>
      </section>

      <section className="card" style={{ display: "grid", gap: 6 }}>
        <p className="card-title">Three kinds of goal</p>
        {buckets.tiers.map((t) => (
          <div className="row" key={t.name}>
            <span className={`tier tier-${t.name.replace(" ", "").toLowerCase()}`}>{t.name}</span>
            <span className="muted">{t.text}</span>
          </div>
        ))}
      </section>

      <h2 className="first">What is in each bucket</h2>
      {buckets.list.map((b) => (
        <section key={b.id} id={b.id} className="card bucket" style={{ borderTopColor: b.color }}>
          <div className="row" style={{ padding: 0, alignItems: "baseline" }}>
            <span className="card-title">
              <span className="swatch" style={{ background: b.color, display: "inline-block", marginRight: 8 }} aria-hidden="true" />
              {b.name}
              {b.optional && <span className="pill" style={{ marginLeft: 8 }}>Optional</span>}
            </span>
            <span className="num" style={{ fontWeight: 600 }}>{aed(b.amountMinor)}</span>
          </div>
          <p className="muted">{b.percent}% of income · {b.about}</p>
          {b.id === "giving" && !givingOn ? (
            <p className="empty">Giving is off. This money stays in Fun until you turn it back on.</p>
          ) : (
            <div className="bucket-items">
              {b.items.map((it) => (
                <div key={it.label} className="bucket-item" title={`${it.label}: ${aed(it.amountMinor)}`}>
                  <div className="row" style={{ padding: 0 }}>
                    <span>
                      {it.label}
                      {"tier" in it && it.tier && (
                        <span className={`tier tier-${it.tier.replace(" ", "").toLowerCase()}`} style={{ marginLeft: 8 }}>{it.tier}</span>
                      )}
                    </span>
                    <span className="num">{aed(it.amountMinor)}</span>
                  </div>
                  <div className="bud-track" aria-hidden="true">
                    <span className="fill" style={{ width: `${it.share}%`, background: b.color }} />
                  </div>
                  {"note" in it && it.note && <p className="muted">{it.note}</p>}
                </div>
              ))}
            </div>
          )}
          {b.optional && (
            <button className="quiet" style={{ justifySelf: "start" }} onClick={() => setGivingOn((v) => !v)}>
              {givingOn ? "Turn off giving" : "Turn on giving"}
            </button>
          )}
          {b.id === "invest" && <p className="muted">Learning only for now. Real investing would come later through a licensed partner.</p>}
        </section>
      ))}
      <SampleNote />
    </div>
  );
}
