"use client";

// Cheaper family outings (week 7). A parent says what the family would like ("something free this weekend"); the
// Planner agent reads the month and the kind of outing, and the core prices each outing for this family (adult and
// child tickets) and checks the plan against what is left of the entertainment budget.
// These types mirror OutingsService in core/. Nothing here works out money: every cost and total is the core's.

import { call } from "./api";
import type { MoneyView } from "./live";

export type When = "THIS_MONTH" | "NEXT_MONTH";
export type Kind = "FREE" | "INDOOR" | "OUTDOOR" | "ANIMALS" | "NATURE" | "BEACH" | "CULTURE" | "PLAY";

export interface Row { label: string; value: string }

export interface OutingOption {
  key: string;
  name: string;
  area: string;
  kinds: Kind[];
  cost: MoneyView;      // this family's tickets
  costLine: string;     // "2 adults at AED 50, Layla AED 50, Adam AED 20"
  tickets: Row[];
  openThisMonth: boolean;
  openNextMonth: boolean;
  about: string;
  site: string;
}

export interface OutingPlan {
  when: When;
  month: string;        // 2026-11
  monthLabel: string;
  budget: MoneyView;
  spent: MoneyView;
  allowance: MoneyView; // what the plan may spend: what is left this month, or next month's budget
  picks: OutingOption[];
  total: MoneyView;
  left: MoneyView;
  usualOuting: MoneyView | null;
  usualCost: MoneyView | null;
  saving: MoneyView | null;
  leftOut: string[];
  facts: string[];
  why: Row[];
  pricesFrom: string;
}

export interface EventView { title: string; when: string; where: string; link: string; priceNote: string | null }

export interface SavedPick { key: string; name: string; area: string; about: string; cost: MoneyView | null }

export interface SavedPlan {
  month: string;
  monthLabel: string;
  picks: SavedPick[];
  total: MoneyView | null; // null for kids: they see the places, not the prices
  savedBy: string | null;
  savedAt: string;
}

export interface OutingsBoard {
  thisMonth: string;
  nextMonth: string;
  budget: MoneyView;
  spent: MoneyView;
  leftThisMonth: MoneyView;
  over: MoneyView | null;
  usualOuting: MoneyView | null;
  outingsCounted: number;
  options: OutingOption[];
  pricesFrom: string;
  events: { source: "LIVE" | "CACHE" | "NONE"; checkedAt: string | null; events: EventView[] };
  saved: SavedPlan[];
  facts: string[];
}

export const outingsApi = {
  board: () => call<OutingsBoard>("/api/outings"),
  saved: () => call<SavedPlan[]>("/api/outings/saved"),
  /** The core checks these outings (keys) for the month; with no keys its engine picks. */
  check: (when: When, keys: string[]) => call<OutingPlan>("/api/outings/plan", { method: "POST", body: JSON.stringify({ when, keys }) }),
  save: (when: When, keys: string[]) => call<SavedPlan>("/api/outings/saved", { method: "PUT", body: JSON.stringify({ when, keys }) }),
};

/** The Planner reads the words, the core plans. If the agent service is down, the core's engine picks for the month. */
export async function askOutings(text: string, when: When): Promise<{ plan: OutingPlan | null; reply: string }> {
  try {
    return await call<{ plan: OutingPlan | null; reply: string }>("/agent/outings", { method: "POST", body: JSON.stringify({ text, when }) });
  } catch {
    const plan = await call<OutingPlan>("/api/outings/plan", { method: "POST", body: JSON.stringify({ when, keys: [] }) });
    return { plan, reply: plan.facts.filter((f) => f.startsWith("Together") || f.startsWith("Nothing is left")).join(" ") };
  }
}
