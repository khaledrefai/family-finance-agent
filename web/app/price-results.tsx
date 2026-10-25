"use client";

import { useEffect, useRef, useState } from "react";
import { Comparison, Offer, sourceLine } from "@/lib/prices";

// What the core does during a search, in order. The steps move on a timer while the answer is on its way (a search
// takes a few seconds when it is live), so the screen never looks stuck; the last step stays until the answer comes.
const SEARCH_STEPS = ["Searching the shops", "Adding delivery and fees", "Re-checking the best offers", "Ranking by what you'd pay"];

export function useSearchStep(searching: boolean, every = 1600) {
  const [step, setStep] = useState(0);
  useEffect(() => {
    setStep(0);
    if (!searching) return;
    const t = setInterval(() => setStep((s) => Math.min(s + 1, SEARCH_STEPS.length - 1)), every);
    return () => clearInterval(t);
  }, [searching, every]);
  return step;
}

// A waiting screen scrolls itself into view: on a phone the search button is often above it, off the screen
export function useShowOnScreen<T extends HTMLElement>() {
  const ref = useRef<T>(null);
  useEffect(() => {
    ref.current?.scrollIntoView({ behavior: "smooth", block: "center" });
  }, []);
  return ref;
}

// The parents' waiting screen: calm, the steps ticking off, and the shape of the results to come.
export function PriceSearching({ what }: { what: string }) {
  const step = useSearchStep(true);
  const ref = useShowOnScreen<HTMLElement>();
  return (
    <section ref={ref} className="card searching" aria-live="polite" aria-busy="true">
      <p className="card-title" style={{ margin: 0 }}>Finding the best total for {what ? `"${what}"` : "this"}</p>
      <ol className="search-steps">
        {SEARCH_STEPS.map((s, i) => (
          <li key={s} className={i < step ? "done" : i === step ? "now" : undefined}>
            {s}{i === step ? "…" : ""}
          </li>
        ))}
      </ol>
      <span className="skeleton" style={{ height: 64 }} />
      <span className="skeleton" style={{ height: 64, opacity: 0.6 }} />
      <p className="muted" style={{ margin: 0 }}>This can take a few seconds when the shops are checked live.</p>
    </section>
  );
}

// The core's price comparison as a list: payable total first, what it is made of, and which offer is the best
// re-checked one. Used by the kid's Ask screen and the parent's Compare prices screen. Every figure is the core's.
function priceParts(o: Offer) {
  if (!o.deliveryKnown) return `${o.price.text}, delivery not listed`;
  const delivery = o.delivery!.minor === 0 ? "free delivery" : `${o.delivery!.text} delivery`;
  return `${o.price.text} + ${delivery}${o.fees.minor ? ` + ${o.fees.text} fees` : ""}`;
}

function OfferRow({ o, best, action, onPick, busy, done, showLink }: {
  o: Offer; best: boolean; action?: string; onPick?: (o: Offer) => void; busy?: boolean; done?: string; showLink?: boolean;
}) {
  return (
    <article className={`card offer${best ? " best" : ""}`}>
      <div className="offer-head">
        <span>
          <b>{o.store}</b>
          {best && <span className="pill" style={{ marginLeft: 8 }}>Best price</span>}
        </span>
        <b className="offer-total">{o.total.text}{o.deliveryKnown ? "" : "+"}</b>
      </div>
      <p className="offer-title">{o.title}</p>
      <p className="muted offer-parts">
        {priceParts(o)}
        {o.verified ? " · re-checked" : ""}
      </p>
      {(onPick || (showLink && o.link)) && (
        <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
          {onPick && action && (
            <button type="button" className={best ? undefined : "quiet"} onClick={() => onPick(o)} disabled={busy || !!done}>
              {done ?? action}
            </button>
          )}
          {showLink && o.link && (
            <a className="link" href={o.link} target="_blank" rel="noreferrer noopener">Open the shop&apos;s page</a>
          )}
        </div>
      )}
    </article>
  );
}

// busy: an action is on its way, so every button waits. done: offer key -> what its button says once the action
// worked ("Sent"), and that button stays off so the same offer can't be sent twice.
export function PriceResults({ comparison: c, action, onPick, busy, done = {}, showLinks = false }: {
  comparison: Comparison; action?: string; onPick?: (o: Offer) => void; busy?: boolean; done?: Record<string, string>;
  showLinks?: boolean;
}) {
  return (
    <section className="stack" style={{ gap: 10 }} aria-label={`Prices for ${c.searchedFor}`}>
      <p className="muted" style={{ margin: 0 }}>
        {sourceLine(c)} · {c.shopsChecked} {c.shopsChecked === 1 ? "shop" : "shops"}
      </p>
      {c.offers.length === 0 && (
        <p className="empty">{c.exact ? "I couldn't find that exact model in the shops I searched." : "I couldn't find that in the shops I searched. Try other words."}</p>
      )}
      {c.offers.map((o) => (
        <OfferRow key={o.key} o={o} best={o.key === c.best?.key} action={action} onPick={onPick} busy={busy} done={done[o.key]} showLink={showLinks} />
      ))}
      {c.substitutes.length > 0 && (
        <>
          <h3 style={{ margin: "6px 0 0" }}>Similar, but not the same model</h3>
          {c.substitutes.map((o) => (
            <OfferRow key={o.key} o={o} best={false} action={action} onPick={onPick} busy={busy} done={done[o.key]} showLink={showLinks} />
          ))}
        </>
      )}
      {c.dropped > 0 && (
        <p className="trust">
          Left out {c.dropped} {c.dropped === 1 ? "offer" : "offers"} priced at less than half the usual price: too good to trust.
        </p>
      )}
      <p className="trust">
        Totals include delivery when the shop lists it. &quot;Best price&quot; is the lowest re-checked total among the shops searched.
      </p>
    </section>
  );
}
