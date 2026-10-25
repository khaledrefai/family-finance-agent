"use client";

import { Comparison, Offer, sourceLine } from "@/lib/prices";

// The core's price comparison as a list: payable total first, what it is made of, and which offer is the best
// re-checked one. Used by the kid's Ask screen and the parent's Compare prices screen. Every figure is the core's.
function priceParts(o: Offer) {
  if (!o.deliveryKnown) return `${o.price.text}, delivery not listed`;
  const delivery = o.delivery!.minor === 0 ? "free delivery" : `${o.delivery!.text} delivery`;
  return `${o.price.text} + ${delivery}${o.fees.minor ? ` + ${o.fees.text} fees` : ""}`;
}

function OfferRow({ o, best, action, onPick, busy, showLink }: {
  o: Offer; best: boolean; action?: string; onPick?: (o: Offer) => void; busy?: boolean; showLink?: boolean;
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
            <button type="button" className={best ? undefined : "quiet"} onClick={() => onPick(o)} disabled={busy}>
              {action}
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

export function PriceResults({ comparison: c, action, onPick, busy, showLinks = false }: {
  comparison: Comparison; action?: string; onPick?: (o: Offer) => void; busy?: boolean; showLinks?: boolean;
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
        <OfferRow key={o.key} o={o} best={o.key === c.best?.key} action={action} onPick={onPick} busy={busy} showLink={showLinks} />
      ))}
      {c.substitutes.length > 0 && (
        <>
          <h3 style={{ margin: "6px 0 0" }}>Similar, but not the same model</h3>
          {c.substitutes.map((o) => (
            <OfferRow key={o.key} o={o} best={false} action={action} onPick={onPick} busy={busy} showLink={showLinks} />
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
