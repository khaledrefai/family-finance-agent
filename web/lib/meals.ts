"use client";

// The week's dinners and the shopping list. A parent marks busy nights (delivery) and says what the family would
// like; the Planner agent reads it, and the core picks dinners that suit everyone (allergy rules, vegetarian, dislikes),
// adds up the shopping list in whole packs at saved prices and checks it against the week's grocery money.
// These types mirror MealsService in core/. Nothing here works out money: every cost and total is the core's.

import { call } from "./api";
import type { MoneyView } from "./live";

export interface Row { label: string; value: string }

export type NightKind = "COOK" | "DELIVERY";

export interface Night {
  date: string;          // 2026-10-27
  label: string;         // Tue 27 Oct
  kind: NightKind;
  key: string;
  name: string;
  about: string;
  minutes: number | null;
  cost: MoneyView | null; // null for kids
}

export interface ShoppingLine {
  key: string;
  name: string;
  pack: string;
  packs: number;
  cost: MoneyView;
  uses: string;          // "You use 1.2 kg of 2 kg"
  aisle: "FRUIT_VEG" | "MEAT_FISH" | "DAIRY_EGGS" | "BAKERY_GRAINS" | "TINS_DRY" | "FROZEN" | "PANTRY";
  halalMark: boolean;
}

export interface MealPlan {
  start: string;
  end: string;
  weekLabel: string;
  nights: Night[];
  list: ShoppingLine[];
  staples: string[];
  shopping: MoneyView;
  dinners: MoneyView;
  inPacks: MoneyView;
  groceryWeek: MoneyView | null;
  forDinners: MoneyView | null;
  weekLeft: MoneyView | null;
  over: MoneyView | null;
  swap: string | null;
  delivery: MoneyView;
  eatingOutLeft: MoneyView | null;
  cookInstead: MoneyView | null;
  leftOut: string[];
  facts: string[];
  why: Row[];
  pricesFrom: string;
}

export interface SavedWeek {
  weekStart: string;
  weekLabel: string;
  nights: Night[];
  shopping: MoneyView | null;   // null for kids
  delivery: MoneyView | null;
  savedBy: string | null;
  savedAt: string;
  plan: MealPlan | null;        // parents only
}

export interface FoodProfile {
  memberId: string;
  name: string;
  child: boolean;
  halal: boolean;
  vegetarian: boolean;
  vegan: boolean;
  dislikes: string[];
  allergies: string[];   // from the family's allergy rules
}

export interface RecipeOption {
  key: string;
  name: string;
  main: "CHICKEN" | "MEAT" | "FISH" | "VEG";
  minutes: number;
  about: string;
  cost: MoneyView;
  vegetarian: boolean;
  suits: boolean;
  unsuitable: string | null;
}

export interface MealsBoard {
  weekStart: string;
  weekLabel: string;
  days: { date: string; label: string }[];
  groceryLimit: MoneyView;
  grocerySpent: MoneyView;
  groceryWeek: MoneyView | null;
  forDinners: MoneyView | null;
  eatingOutLimit: MoneyView;
  eatingOutLeft: MoneyView | null;
  profiles: FoodProfile[];
  recipes: RecipeOption[];
  deliveries: { key: string; name: string; about: string; price: MoneyView; suits: boolean; unsuitable: string | null }[];
  saved: SavedWeek | null;
  pricesFrom: string;
}

export interface PlanAsk { keys: string[]; deliveryDays: string[]; deliveryKeys?: string[] }

export const mealsApi = {
  board: () => call<MealsBoard>("/api/meals"),
  /** The saved week, or null (the core answers 204). Kids get the dinners without prices. */
  saved: () => call<SavedWeek | undefined>("/api/meals/saved").then((s) => s ?? null),
  /** The core checks these dinners for the cooking nights in order; with no keys its engine picks. */
  check: (ask: PlanAsk) => call<MealPlan>("/api/meals/plan", { method: "POST", body: JSON.stringify(ask) }),
  save: (ask: PlanAsk) => call<SavedWeek>("/api/meals/saved", { method: "PUT", body: JSON.stringify(ask) }),
  profiles: (members: { memberId: string; halal: boolean; vegetarian: boolean; vegan: boolean; dislikes: string }[]) =>
    call<FoodProfile[]>("/api/meals/profiles", { method: "PUT", body: JSON.stringify({ members }) }),
};

/** What to send to keep this plan as it is: the cooked dinners in order, the delivery nights and what is ordered. */
export function askFor(plan: MealPlan): PlanAsk {
  return {
    keys: plan.nights.filter((n) => n.kind === "COOK").map((n) => n.key),
    deliveryDays: plan.nights.filter((n) => n.kind === "DELIVERY").map((n) => n.date),
    deliveryKeys: plan.nights.filter((n) => n.kind === "DELIVERY").map((n) => n.key),
  };
}

/** The Planner reads the words, the core plans. If the agent service is down, the core's engine picks the dinners. */
export async function askMeals(text: string, deliveryDays: string[]): Promise<{ plan: MealPlan | null; reply: string }> {
  try {
    return await call<{ plan: MealPlan | null; reply: string }>("/agent/meals", { method: "POST", body: JSON.stringify({ text, deliveryDays }) });
  } catch {
    const plan = await mealsApi.check({ keys: [], deliveryDays });
    return { plan, reply: plan.facts.filter((f) => f.startsWith("Shopping list") || f.startsWith("That is")).join(" ") };
  }
}
