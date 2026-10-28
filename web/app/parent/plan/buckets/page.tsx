"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { aed } from "@/lib/api";
import { Buckets, core, fromBuckets, useBuckets } from "@/lib/live";
import { changeApi, typed } from "@/lib/setup";
import { gold } from "@/lib/sample";
import { Back, BucketFlow, Loading, Masthead, SampleNote, TRUST } from "../../../ui";
import { useSession } from "../../../use-session";

// Money buckets: every dirham of income gets a job. The flow chart shows how income splits into
// the buckets, and each card below shows what is inside one bucket. The accountant suggests the
// split, a parent confirms it, and the engine works out every amount (GET /api/plan/buckets).
// Confirming is a parent's tap in the app; the agent can only save a draft. A parent can also change the split
// by hand at any time: they type each bucket in AED, Fun gets the rest, and the engine checks it as a draft.
export default function BucketsPage() {
  const { me } = useSession("PARENT");
  const { data: buckets, live, setData } = useBuckets(!!me);
  const [givingOn, setGivingOn] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [changing, setChanging] = useState(false);
  if (!me) return <Loading />;
  const confirmed = buckets.confirmed;

  async function confirm() {
    if (!live) {
      setData({ ...buckets, confirmed: true });
      return;
    }
    try {
      setData(fromBuckets(await core.confirmBuckets()));
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function drop() {
    setError(null);
    try {
      setData(fromBuckets(await changeApi.keepBuckets()));
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <div className="stack">
      <Back href="/parent/plan" label="Plan" />
      <Masthead title="Money buckets" sub={`${buckets.month} · every dirham gets a job`} />

      <section className={`draft${confirmed ? " done" : ""}`}>
        {confirmed ? (
          <>
            <p role="status">
              <span className="confirmed">Confirmed</span> Each pay day the app puts the money into these buckets on paper. No real money moves.
            </p>
            {live && !changing && (
              <button type="button" className="quiet" style={{ justifySelf: "start", marginTop: 8 }} onClick={() => setChanging(true)}>
                Change the split
              </button>
            )}
          </>
        ) : (
          <>
            <p><b>{buckets.proposedBy === "PARENT" ? "Your changes." : "Suggested by the accountant."}</b> It counts once you confirm. You can change any bucket first.</p>
            {!changing && (
              <div style={{ display: "flex", gap: 8, marginTop: 8, flexWrap: "wrap" }}>
                <button onClick={confirm}>Confirm buckets</button>
                {live && <button type="button" className="quiet" onClick={() => setChanging(true)}>Change</button>}
                {live && buckets.proposedBy === "PARENT" && <button type="button" className="text" onClick={drop}>Drop my changes</button>}
              </div>
            )}
          </>
        )}
        {/* The engine's checks: a bucket that holds less than it needs, before or after confirming */}
        {(buckets.warnings ?? []).map((w) => (
          <p key={w} className="warn-line" role="note">{w}</p>
        ))}
        {error && <p className="error" role="alert">{error}</p>}
      </section>

      {changing && (
        <SplitEditor onCancel={() => setChanging(false)}
          onSaved={(b) => { setData(fromBuckets(b)); setChanging(false); window.scrollTo(0, 0); }} />
      )}

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
          {b.id === "goals" && (
            <Link href="/parent/money/gold" className="gold-held">
              <span>
                <b>Kept as gold:</b> {gold.totalGrams.map((g) => `${g.grams} g ${g.purity}`).join(" + ")}, worth {aed(gold.valueMinor)} today
                <br />
                <span className="est">Gold price 24K {aed(gold.price["24K"], { cents: true })}/g, as of {gold.priceAsOf}</span>
              </span>
              <span aria-hidden="true">›</span>
            </Link>
          )}
          {b.id === "invest" && (
            <>
              <p className="muted">Learning only for now. Real investing would come later through a licensed partner.</p>
              <Link href="/parent/plan/calculator?from=invest" className="link">See what it could become in 1, 5 and 10 years ›</Link>
            </>
          )}
          {b.id === "goals" && (
            <Link href="/parent/plan#goals-long-term" className="link">Saving for years? Try the savings calculator ›</Link>
          )}
        </section>
      ))}
      <SampleNote live={live} />
    </div>
  );
}

// Change the split by hand: an AED box for each bucket, filled with what it gets now, and what the family's own
// numbers say it needs under it. Fun is not typed: it gets the rest. The core turns the amounts into shares and
// saves them as a draft (POST /api/plan/buckets/draft/amounts); it refuses a split over the income or short on bills.
function SplitEditor({ onSaved, onCancel }: { onSaved: (b: Buckets) => void; onCancel: () => void }) {
  const [now, setNow] = useState<Buckets | null>(null);
  const [typedIn, setTypedIn] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    core.buckets().then((b) => {
      setNow(b);
      setTypedIn(Object.fromEntries(b.buckets.filter((x) => x.kind !== "FUN").map((x) => [x.kind, typed(x.amount)])));
    }).catch((e) => setError((e as Error).message));
  }, []);

  async function save(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      onSaved(await changeApi.bucketAmounts(typedIn));
    } catch (err) {
      setError((err as Error).message);
      setBusy(false);
    }
  }

  if (!now) return error ? <p className="error" role="alert">{error}</p> : <Loading />;
  return (
    <form className="panel setup-form" onSubmit={save} style={{ margin: 0 }}>
      <strong>Change the split</strong>
      <p className="muted" style={{ margin: 0, fontSize: 14 }}>
        Out of {now.income.text} this month. Type what each bucket gets. Fun gets whatever is left.
      </p>
      {now.buckets.map((b) => (
        <div className="setup-line" key={b.kind}>
          <span><b>{b.label}</b></span>
          {b.kind === "FUN" ? (
            <span className="muted" style={{ gridColumn: "auto", fontSize: 14 }}>Gets the rest</span>
          ) : (
            <span className="money-input">
              <span>AED</span>
              <input inputMode="decimal" aria-label={`${b.label}, AED`} value={typedIn[b.kind] ?? ""}
                onChange={(e) => setTypedIn({ ...typedIn, [b.kind]: e.target.value })} maxLength={12} />
            </span>
          )}
          {b.needed && b.needed.minor > 0 && (
            <span className="muted">
              {b.kind === "COMMITMENTS" ? `Needs at least ${b.needed.text} for the bills due this month`
                : b.kind === "EVERYDAY" ? `The budgets come to ${b.needed.text}`
                : `The goal paces come to ${b.needed.text}`}
            </span>
          )}
        </div>
      ))}
      <div className="actions">
        <button type="button" className="text" onClick={onCancel}>Cancel</button>
        <button type="submit" disabled={busy}>{busy ? "Checking…" : "Check this split"}</button>
      </div>
      <p className="trust" style={{ margin: 0 }}>{TRUST} It stays a draft until you confirm it.</p>
      {error && <p className="error" role="alert">{error}</p>}
    </form>
  );
}
