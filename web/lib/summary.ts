"use client";

// The monthly family summary. The core's engine builds the whole page (budgets, goals, each kid's month, Saver of
// the Month, what went well, what to watch); the Planner agent only rewrites the headline, the story and each kid's
// camel note, using the engine's numbers. These types mirror MonthSummaryEngine in core/. Nothing here works out
// money: every amount, percent and count is the core's.

import { call } from "./api";
import type { BudgetLine, MoneyView } from "./live";

export type WrittenBy = "LLM" | "RULES" | "ENGINE";

export interface Writing { key: string; about: string; text: string; writtenBy: WrittenBy; facts: string[] }
export interface Row { label: string; value: string }
export interface Point { key: string; text: string; facts: string[]; why: Row[] }
export interface MonthOption { month: string; label: string }
export interface Runner { id: string; name: string; percent: number }
export interface Saver { winners: string[]; percent: number; race: Runner[] }

export interface FamilyGoalLine {
  name: string; tierLabel: string; saved: MoneyView; target: MoneyView; percent: number; monthly: MoneyView | null;
  fullBy: string | null;
}

export interface KidLine {
  id: string; name: string; spent: MoneyView; pocketMoney: MoneyView; spentPercent: number; daysLogged: number;
  daysInPeriod: number; topCategory: string | null; goalName: string | null; goalSaved: MoneyView | null;
  goalTarget: MoneyView | null; goalPercent: number | null; starsThisMonth: number;
  requests: { asked: number; approved: number; declined: number; blocked: number; waiting: number; blockedTitles: string[] };
  againstRules: string[];
}

/** One kid's own page: their goal and stars, never the family's money. */
export interface KidMonth {
  id: string; name: string; young: boolean; month: string; monthLabel: string; periodLabel: string; running: boolean;
  goalName: string | null; goalSaved: MoneyView | null; goalTarget: MoneyView | null; goalPercent: number | null;
  starsThisMonth: number; starsTotal: number; daysLogged: number; daysInPeriod: number; asked: number; yes: number;
  saver: Saver | null; saverOfTheMonth: boolean; familyGoal: { name: string; percent: number } | null;
  highlights: string[]; camel: Writing;
}

export interface FamilyMonth {
  month: string; monthLabel: string; periodLabel: string; running: boolean; from: string; to: string;
  budgetSpent: MoneyView; budgetLimit: MoneyView; budgetsWithin: number; budgets: BudgetLine[];
  goals: FamilyGoalLine[]; kids: KidLine[]; saver: Saver | null; wentWell: Point[]; toWatch: Point[];
  headline: Writing; story: Writing; kidPages: KidMonth[]; months: MonthOption[]; needsWords: boolean;
}

const q = (month?: string | null, more: Record<string, string> = {}) => {
  const p = new URLSearchParams({ ...(month ? { month } : {}), ...more }).toString();
  return p ? `?${p}` : "";
};

export const summaryApi = {
  // The Planner writes the words when the month's facts changed; if the agent service is down, the core's own page.
  family: (month?: string | null) =>
    call<FamilyMonth>(`/agent/summary${q(month)}`, { method: "POST" }).catch(() => call<FamilyMonth>(`/api/summary${q(month)}`)),
  kid: (month?: string | null, memberId?: string) =>
    call<KidMonth>(`/api/summary/kid${q(month, memberId ? { memberId } : {})}`),
  /** For the demo: send this month's summary so far to the whole family now. */
  sendNow: () => call<{ sent: string[] }>("/api/alerts/check?summary=true", { method: "POST" }),
};

export const writtenByNote = (w: WrittenBy) =>
  w === "LLM" ? "Words by the Planner, numbers by the app" : "Words and numbers by the app";
