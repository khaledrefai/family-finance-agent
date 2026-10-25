"use client";

// Price search (week 6). Someone types what they want ("wireless headphones"); the Shopper agent reads it into a
// PurchaseIntent and the core searches the shops, ranks the offers by payable total and checks the best one with
// the money engine and the family rules. These types mirror PricingService.Comparison in core/.
// Nothing here works out money: every price, delivery and total is the core's.

import { call } from "./api";
import type { Assessment, MoneyView } from "./live";

export type OfferSource = "LIVE" | "CACHE" | "SEEDED";

export interface Offer {
  key: string;
  store: string;
  title: string;
  price: MoneyView;
  delivery: MoneyView | null; // null: the shop doesn't list delivery
  deliveryKnown: boolean;
  fees: MoneyView;
  total: MoneyView;
  source: OfferSource;
  verified: boolean; // re-checked with a second, narrower search
  checkedAt: string;
  link: string | null; // the shop's page; parents only
}

export interface Comparison {
  id: string;
  searchedFor: string;
  source: OfferSource;
  checkedAt: string;
  exact: boolean;
  offers: Offer[];
  substitutes: Offer[];
  best: Offer | null;
  shopsChecked: number;
  dropped: number;
  assessment: Assessment | null;
  facts: string[];
}

export interface PriceAnswer {
  comparison: Comparison | null;
  reply: string;
}

/** The Shopper reads the words, the core compares. If the agent service is down, the core searches the words as typed. */
export async function findPrices(text: string, paidFromAccountId?: string): Promise<PriceAnswer> {
  try {
    return await call<PriceAnswer>("/agent/prices", { method: "POST", body: JSON.stringify({ text, paidFromAccountId }) });
  } catch {
    const comparison = await call<Comparison>("/api/pricing/compare", {
      method: "POST",
      body: JSON.stringify({ intent: { query: text.trim().slice(0, 120), said: text }, paidFromAccountId }),
    });
    return { comparison, reply: comparison.facts.slice(0, 2).join(" ") };
  }
}

const TIME = new Intl.DateTimeFormat("en-GB", { hour: "2-digit", minute: "2-digit", day: "numeric", month: "short", timeZone: "Asia/Dubai" });
const DAY = new Intl.DateTimeFormat("en-GB", { day: "numeric", month: "short", timeZone: "Asia/Dubai" });

/** "Live prices, checked 21:30, 25 Oct" or "Saved prices from 25 Oct (not live)". */
export function sourceLine(c: Pick<Comparison, "source" | "checkedAt">) {
  const at = new Date(c.checkedAt);
  if (c.source === "SEEDED") return `Saved prices from ${DAY.format(at)} (not live)`;
  return `${c.source === "LIVE" ? "Live prices" : "Prices"}, checked ${TIME.format(at)}`;
}
