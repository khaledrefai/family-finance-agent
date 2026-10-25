"use client";

// Real figures from the core's money engine (week 2), turned into the same shapes the screens already
// draw from lib/sample.ts. Each hook starts with the sample figures and swaps in the family's own as
// soon as the core answers, so a screen never shows an empty state while it loads, and `live` says
// which one is on screen (the "Sample figures" note only shows when it is false).
// Nothing here works out money: every amount and share is the engine's, the code only reshapes it.

import { useCallback, useEffect, useState } from "react";
import { call } from "./api";
import * as sample from "./sample";

// ---------------------------------------------------------------- what the core sends (mirrors core/ records)

export interface MoneyView {
  minor: number;
  currency: string;
  text: string;
}

interface PlanLine {
  label: string;
  amount: MoneyView;
  note?: string | null;
}

interface Section {
  total: MoneyView;
  lines: PlanLine[];
}

interface Upcoming {
  commitmentId: string | null;
  name: string;
  dueOn: string;
  daysLeft: number;
  amount: MoneyView;
  state: "FROM_PAY" | "RESERVED" | "SHORT";
  shortBy: MoneyView | null;
  label: string | null;
}

export interface Cashflow {
  monthLabel: string;
  income: Section;
  commitments: Section;
  everyday: Section;
  free: MoneyView;
  saving: Section;
  spare: MoneyView;
  steps: { label: string; amount: MoneyView; fromBp: number; toBp: number; kind: string }[];
  untilNextIncome: { nextIncomeOn: string | null; nextIncomeLabel: string | null; free: MoneyView; why: PlanLine[] };
  nextCommitment: Upcoming | null;
  upcoming: Upcoming[];
  months: { month: string; label: string; free: MoneyView; spare: MoneyView; shareOfMax: number; tight: boolean; negative: boolean }[];
  notes: string[];
}

export interface CardBill {
  statementOn: string;
  dueOn: string;
  bill: MoneyView;
  freeByDue: MoneyView;
  ok: boolean;
  shortBy: MoneyView;
}

export interface Account {
  id: string;
  kind: sample.AccountKind;
  label: string;
  last4: string | null;
  ownerName: string | null;
  balance: MoneyView;
  asOf: string;
  savings: boolean;
  limit: MoneyView | null;
  usedPercent: number;
  statementDay: number | null;
  dueDay: number | null;
  bill: CardBill | null;
}

export interface CardCheck {
  accountId: string;
  card: string;
  dueOn: string;
  billWith: MoneyView;
  freeByDue: MoneyView;
  fits: boolean;
  shortBy: MoneyView;
  line: string;
}

interface Bucket {
  kind: string;
  label: string;
  about: string;
  shareBp: number;
  amount: MoneyView;
  fromBp: number;
  toBp: number;
  items: { label: string; amount: MoneyView; shareBp: number; tierLabel: string | null; note: string | null }[];
}

export interface Buckets {
  planId: string | null;
  status: "DRAFT" | "ACTIVE" | "ARCHIVED" | null;
  proposedBy: "PLANNER" | "ENGINE" | "PARENT";
  month: string;
  income: MoneyView;
  buckets: Bucket[];
  warnings: string[];
}

export interface Goal {
  id: string;
  name: string;
  tier: string;
  target: MoneyView;
  saved: MoneyView;
  percent: number;
  monthly: MoneyView | null;
  targetDate: string | null;
  fullBy: string | null;
}

export interface PlanOptions {
  goalId: string;
  goalName: string;
  target: MoneyView;
  options: { id: string; name: string; monthly: MoneyView; readyMonth: string; meetsDate: boolean; fits: boolean; tightestMonth: string; spareInTightest: MoneyView }[];
  recommended: string | null;
  recommendedReason: string | null;
}

export interface Assessment {
  amount: MoneyView;
  verdict: "FITS" | "TIGHT" | "DOES_NOT_FIT";
  freeUntilPay: MoneyView | null;
  nextIncomeOn: string | null;
  freeAfter: MoneyView | null;
  nextCommitment: string | null;
  needsParent: boolean;
  approvalLimit: MoneyView | null;
  kid: { name: string; wallet: MoneyView; walletCovers: boolean; walletAfter: MoneyView } | null;
  card: CardCheck | null;
  facts: string[];
}

export const core = {
  cashflow: () => call<Cashflow>("/api/plan/cashflow"),
  accounts: () => call<Account[]>("/api/accounts"),
  buckets: () => call<Buckets>("/api/plan/buckets"),
  confirmBuckets: () => call<Buckets>("/api/plan/buckets/confirm", { method: "POST" }),
  goals: () => call<Goal[]>("/api/plan/goals"),
  options: (goalId: string) => call<PlanOptions>("/api/plan/options", { method: "POST", body: JSON.stringify({ goalId }) }),
  setPace: (goalId: string, monthly: string) =>
    call<Goal>(`/api/plan/goals/${goalId}/pace`, { method: "POST", body: JSON.stringify({ monthly }) }),
  cardCheck: (accountId: string, amount: string) =>
    call<CardCheck>(`/api/accounts/${accountId}/card-check?amount=${encodeURIComponent(amount)}`),
  assess: (amount: string, requesterId?: string, paidFromAccountId?: string) =>
    call<Assessment>("/api/requests/assess", { method: "POST", body: JSON.stringify({ amount, requesterId, paidFromAccountId }) }),
  pay: (body: { amount: string; description?: string; merchant?: string; category: string; paidFromAccountId: string; occurredOn?: string }) =>
    call<{ id: string; paidFrom: string; amount: MoneyView }>("/api/payments", { method: "POST", body: JSON.stringify(body) }),
};

// ---------------------------------------------------------------- one hook per screen's data

/** Starts with `fallback`, then shows the core's answer. Keeps the sample if the core can't answer. */
export function useLive<T>(load: () => Promise<T>, fallback: T, enabled = true) {
  const [data, setData] = useState<T>(fallback);
  const [live, setLive] = useState(false);
  const reload = useCallback(async () => {
    try {
      setData(await load());
      setLive(true);
    } catch {
      /* the sample stays on screen, labelled as sample */
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  useEffect(() => {
    if (enabled) reload();
  }, [enabled, reload]);
  return { data, live, reload, setData };
}

export function useMoney(enabled = true) {
  const { data, live } = useLive(async () => fromCashflow(await core.cashflow()), {
    monthly: sample.monthly,
    cashflow: sample.cashflow,
    bills: sample.bills as Bill[],
    raw: null as Cashflow | null,
  }, enabled);
  return { ...data, live };
}

export function useAccounts(enabled = true) {
  const { data, live } = useLive(async () => (await core.accounts()).map(fromAccount), sample.accounts as AccountRow[], enabled);
  return { accounts: data, live };
}

export function useBuckets(enabled = true) {
  return useLive(async () => fromBuckets(await core.buckets()), sample.buckets as BucketsView, enabled);
}

// ---------------------------------------------------------------- core shape -> screen shape

export type Bill = { name: string; due: string; amountMinor: number; state: "reserved" | "short"; daysLeft?: number };
export type AccountRow = (typeof sample.accounts)[number];
export type BucketsView = Omit<typeof sample.buckets, "list"> & {
  list: { id: string; name: string; color: string; amountMinor: number; percent: number; from: number; to: number; about: string; optional?: boolean; items: { label: string; amountMinor: number; share: number; tier?: sample.Tier; note?: string }[] }[];
  proposedBy?: string;
};

const DAY = new Intl.DateTimeFormat("en-GB", { weekday: "short", day: "numeric", month: "short", timeZone: "UTC" });
const DAY_MONTH = new Intl.DateTimeFormat("en-GB", { day: "numeric", month: "short", timeZone: "UTC" });
const LONG = new Intl.DateTimeFormat("en-GB", { weekday: "long", day: "numeric", month: "long", timeZone: "UTC" });
const DAY_LONG_MONTH = new Intl.DateTimeFormat("en-GB", { day: "numeric", month: "long", timeZone: "UTC" });

/** "2026-11-13" -> "Fri 13 Nov". Dates from the core are plain days, so they are read as UTC. */
export const day = (iso: string) => DAY.format(new Date(iso + "T00:00:00Z")).replace(",", "");
export const dayMonth = (iso: string) => DAY_MONTH.format(new Date(iso + "T00:00:00Z"));
export const longDay = (iso: string) => LONG.format(new Date(iso + "T00:00:00Z")).replace(",", "");
export const dayLongMonth = (iso: string) => DAY_LONG_MONTH.format(new Date(iso + "T00:00:00Z"));

const KIND_TO_BAR: Record<string, string> = { total: "total", s1: "s1", s2: "s2", s3: "s3" };

export function fromCashflow(cf: Cashflow) {
  const savingNames = cf.saving.lines.map((l) => `${l.label.toLowerCase()} ${l.amount.text.replace("AED ", "")}`);
  const savingLabel = savingNames.length ? `Saving: ${savingNames.join(" and ")}` : "Saving for goals";
  const monthly: typeof sample.monthly = {
    month: cf.monthLabel,
    incomeMinor: cf.income.total.minor,
    income: cf.income.lines.map((l) => ({ label: l.label, amountMinor: l.amount.minor })),
    commitmentsMinor: cf.commitments.total.minor,
    commitments: cf.commitments.lines.map((l) => ({ label: l.label, amountMinor: l.amount.minor })),
    everydayMinor: cf.everyday.total.minor,
    freeMinor: cf.free.minor,
    savingMinor: cf.saving.total.minor,
    savingLabel,
    spareMinor: cf.spare.minor,
    freeUntilPaydayMinor: cf.untilNextIncome.free.minor,
    payday: cf.untilNextIncome.nextIncomeOn ? longDay(cf.untilNextIncome.nextIncomeOn) : "",
    steps: cf.steps.map((s) => ({
      label: s.kind === "s3" ? savingLabel : s.label,
      amountMinor: s.amount.minor,
      from: s.fromBp / 100,
      to: s.toBp / 100,
      kind: KIND_TO_BAR[s.kind] ?? "total",
    })),
  };
  const cashflow: typeof sample.cashflow = {
    note: cf.notes[0] ?? "",
    months: cf.months.map((m) => ({ m: m.label, freeMinor: m.free.minor, share: m.shareOfMax, tight: m.tight })),
  };
  const bills: Bill[] = cf.upcoming.map((u) => ({
    name: u.label ? `${u.name}, ${u.label.toLowerCase()}` : u.name,
    due: day(u.dueOn),
    amountMinor: u.state === "SHORT" && u.shortBy ? u.shortBy.minor : u.amount.minor,
    state: u.state === "SHORT" ? "short" : "reserved",
    daysLeft: u.daysLeft,
  }));
  return { monthly, cashflow, bills, raw: cf };
}

export function fromAccount(a: Account): AccountRow {
  const card = a.kind === "CREDIT_CARD";
  return {
    id: a.id,
    kind: a.kind,
    label: a.label,
    last4: a.last4 ?? undefined,
    owner: a.ownerName ?? "Family",
    balanceMinor: card ? -a.balance.minor : a.balance.minor,
    asOf: dayMonth(a.asOf),
    limitMinor: a.limit?.minor,
    usedShare: card ? a.usedPercent : undefined,
    statementDay: a.statementDay ?? undefined,
    dueDay: a.dueDay ?? undefined,
    bill: a.bill
      ? { soFarMinor: a.bill.bill.minor, due: dayLongMonth(a.bill.dueOn), freeByDueMinor: a.bill.freeByDue.minor, ok: a.bill.ok }
      : undefined,
  };
}

const BUCKET_STYLE: Record<string, { id: string; color: string; optional?: boolean }> = {
  COMMITMENTS: { id: "commitments", color: "var(--s1)" },
  EVERYDAY: { id: "everyday", color: "var(--s2)" },
  GOALS: { id: "goals", color: "var(--s3)" },
  INVEST_ASIDE: { id: "invest", color: "var(--s4)" },
  FUN: { id: "fun", color: "var(--s5)" },
  GIVING: { id: "giving", color: "var(--s6)", optional: true },
};

export function fromBuckets(b: Buckets): BucketsView {
  return {
    month: b.month,
    incomeMinor: b.income.minor,
    confirmed: b.status === "ACTIVE",
    proposedBy: b.proposedBy,
    tiers: sample.buckets.tiers,
    list: b.buckets.map((x) => ({
      ...BUCKET_STYLE[x.kind],
      name: x.label,
      amountMinor: x.amount.minor,
      percent: x.shareBp / 100,
      from: x.fromBp / 100,
      to: x.toBp / 100,
      about: x.about,
      items: x.items.map((it) => ({
        label: it.label,
        amountMinor: it.amount.minor,
        share: it.shareBp / 100,
        tier: (it.tierLabel ?? undefined) as sample.Tier | undefined,
        note: it.note ?? undefined,
      })),
    })),
  };
}
