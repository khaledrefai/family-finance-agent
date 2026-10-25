"use client";

// Real figures from the core's money engine (week 2), turned into the same shapes the screens already
// draw from lib/sample.ts. Each hook starts with the sample figures and swaps in the family's own as
// soon as the core answers, so a screen never shows an empty state while it loads, and `live` says
// which one is on screen (the "Sample figures" note only shows when it is false).
// Nothing here works out money: every amount and share is the engine's, the code only reshapes it.

import { useCallback, useEffect, useState } from "react";
import { ApiError, call, getToken } from "./api";
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

// ---------------------------------------------------------------- week 3: documents, findings, gold, wealth

export interface DocLine {
  no: number;
  date: string | null;
  description: string;
  amount: MoneyView;
  credit: boolean;
  category: string | null;
  status: "NEW" | "ALREADY_LOGGED" | "ITEM";
  matchNote: string | null;
  possibleDuplicate: boolean;
}

export interface Doc {
  id: string;
  kind: "RECEIPT" | "INVOICE" | "BANK_STATEMENT" | "CARD_STATEMENT";
  fileName: string;
  contentType: string;
  status: "UPLOADED" | "EXTRACTED" | "CONFIRMED" | "FAILED";
  uploadedAt: string;
  uploadedBy: string;
  accountId: string | null;
  accountLabel: string | null;
  accountLast4: string | null;
  matchReason: string | null;
  readBy: "LLM" | "RULES" | "PARENT" | null;
  merchant: string | null;
  date: string | null;
  total: MoneyView | null;
  category: string | null;
  closingBalance: MoneyView | null;
  note: string | null;
  matchedNote: string | null;
  counts: { lines: number; added: number; alreadyLogged: number; possibleDuplicates: number };
  newTotal: MoneyView;
  itemsTotal: MoneyView | null;
  lines: DocLine[];
  summary: string;
}

export interface Finding {
  key: string;
  kind: "DUPLICATE" | "SUBSCRIPTION";
  title: string;
  detail: string;
  amount: MoneyView;
  facts: string[];
}

export type Purity = "24K" | "22K" | "21K" | "18K";

export interface GoldPrice {
  purity: Purity;
  perGram: MoneyView;
  asOf: string;
  source: string;
  seeded: boolean;
  typedByFamily: boolean;
}

export interface GoldHolding {
  id: string;
  description: string;
  grams: string;
  purity: Purity;
  paid: MoneyView;
  boughtOn: string;
  seller: string | null;
  bucket: "GOALS" | "INVEST_ASIDE";
  goalId: string | null;
  goalName: string;
  jewellery: boolean;
  value: MoneyView;
  belowPaid: boolean;
}

export interface Gold {
  value: MoneyView;
  paid: MoneyView;
  grams: { purity: Purity; grams: string; value: MoneyView }[];
  byGoal: { goalId: string; name: string; grams: string; value: MoneyView }[];
  holdings: GoldHolding[];
  prices: GoldPrice[];
  priceAsOf: string;
  priceSource: string;
  priceIsSample: boolean;
  makingNote: string;
}

export interface Wealth {
  total: MoneyView;
  owned: MoneyView;
  owed: MoneyView;
  asOf: string;
  steps: { label: string; amount: MoneyView; fromBp: number; toBp: number; kind: string; asOf: string | null; asOfLatest: string | null }[];
  note: string;
}

/** Sends a file as multipart (the browser sets the boundary), with the same error wording as `call`. */
async function upload(file: File, kind: string, accountId?: string): Promise<Doc> {
  const form = new FormData();
  form.append("file", file);
  form.append("kind", kind);
  if (accountId) form.append("accountId", accountId);
  const token = getToken();
  let res: Response;
  try {
    res = await fetch("/api/documents", { method: "POST", body: form, headers: token ? { Authorization: `Bearer ${token}` } : {} });
  } catch {
    throw new ApiError(0, "Can't connect right now. Check your internet and try again.");
  }
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    const detail = typeof body.detail === "string" && res.status < 500 ? body.detail : null;
    throw new ApiError(res.status, detail ?? (res.status === 413 ? "That file is over 5 MB. Try a smaller photo." : "Something went wrong on our side. Try again in a moment."));
  }
  return res.json();
}

/** A demo file from the core, dated to match the seeded family, ready to upload like any other file. */
async function sampleFile(name: "receipt" | "statement"): Promise<File> {
  const token = getToken();
  const res = await fetch(`/api/documents/samples/${name}`, { headers: token ? { Authorization: `Bearer ${token}` } : {} });
  if (!res.ok) throw new ApiError(res.status, "Couldn't get the demo file. Try again in a moment.");
  const text = await res.text();
  return name === "receipt"
    ? new File([text], "carrefour-receipt.txt", { type: "text/plain" })
    : new File([text], "visa-statement.csv", { type: "text/csv" });
}

export const docs = {
  list: () => call<Doc[]>("/api/documents"),
  get: (id: string) => call<Doc>(`/api/documents/${id}`),
  upload,
  sampleFile,
  // The Bookkeeper agent reads the file into a draft. Nothing counts until a parent confirms.
  read: (id: string) => call<{ documentId: string | null; status: Doc["status"] | null; reply: string }>(`/agent/documents/${id}/read`, { method: "POST" }),
  confirm: (id: string, body: { accountId?: string; category?: string; updateBalance?: boolean }) =>
    call<Doc>(`/api/documents/${id}/confirm`, { method: "POST", body: JSON.stringify(body) }),
  discard: (id: string) => call<void>(`/api/documents/${id}/discard`, { method: "POST" }),
  findings: () => call<Finding[]>("/api/findings"),
  decide: (key: string, action: "DUPLICATE" | "KEEP" | "ADD" | "IGNORE") =>
    call<Finding[]>("/api/findings/decide", { method: "POST", body: JSON.stringify({ key, action }) }),
};

export const goldApi = {
  get: () => call<Gold>("/api/gold"),
  log: (body: { description?: string; grams: string; purity: Purity; paid: string; boughtOn?: string; seller?: string; goalId?: string; bucket?: string; jewellery?: boolean }) =>
    call<GoldHolding>("/api/gold", { method: "POST", body: JSON.stringify(body) }),
  setPrice: (purity: Purity, perGram: string) => call<GoldPrice>("/api/gold/price", { method: "POST", body: JSON.stringify({ purity, perGram }) }),
  wealth: () => call<Wealth>("/api/wealth"),
};

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
  setBalance: (accountId: string, balance: string) =>
    call<Account>(`/api/accounts/${accountId}/balance`, { method: "POST", body: JSON.stringify({ balance }) }),
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

// ---------------------------------------------------------------- week 3 shapes

export type WealthView = typeof sample.wealth;

export function fromWealth(w: Wealth): WealthView {
  return {
    totalMinor: w.total.minor,
    asOf: dayMonth(w.asOf),
    steps: w.steps.map((s) => ({
      label: s.label,
      amountMinor: s.amount.minor,
      from: s.fromBp / 100,
      to: s.toBp / 100,
      kind: s.kind,
      asOf: !s.asOf ? "" : s.asOf === s.asOfLatest || !s.asOfLatest ? dayMonth(s.asOf) : `${dayMonth(s.asOf)} to ${dayMonth(s.asOfLatest)}`,
    })),
  };
}

export function useWealth(enabled = true) {
  const { data, live } = useLive(async () => fromWealth(await goldApi.wealth()), sample.wealth, enabled);
  return { wealth: data, live };
}

const TIME = new Intl.DateTimeFormat("en-GB", { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit", hour12: false, timeZone: "Asia/Dubai" });
/** "2026-10-25T06:00:00Z" -> "25 Oct, 10:00" in UAE time. */
export const dayTime = (iso: string) => TIME.format(new Date(iso));

/** Plain words for a document's state, as the family sees it. */
export function docState(d: Doc): { label: string; tone: "good" | "wait" | "bad" | "muted" } {
  switch (d.status) {
    case "CONFIRMED":
      return { label: "Confirmed", tone: "good" };
    case "EXTRACTED":
      return { label: "Check and confirm", tone: "wait" };
    case "FAILED":
      return { label: "Couldn't read it", tone: "bad" };
    default:
      return { label: "Not read yet", tone: "muted" };
  }
}

export function docName(d: Doc) {
  if (d.kind === "RECEIPT" || d.kind === "INVOICE") return d.merchant ? `${d.merchant} receipt` : "Receipt";
  if (d.accountLabel) return `${d.accountLabel} statement`;
  return d.kind === "CARD_STATEMENT" ? "Card statement" : "Statement";
}

const mv = (minor: number): MoneyView => ({ minor, currency: "AED", text: aedText(minor) });
const aedText = (minor: number) =>
  `AED ${(minor / 100).toLocaleString("en-US", { minimumFractionDigits: minor % 100 ? 2 : 0, maximumFractionDigits: 2 })}`;

/** The mockup's gold figures in the core's shape, shown (labelled as sample) until the core answers. */
export const sampleGold: Gold = {
  value: mv(sample.gold.valueMinor),
  paid: mv(sample.gold.paidMinor),
  grams: sample.gold.totalGrams.map((g) => ({ purity: g.purity, grams: String(g.grams), value: mv(0) })),
  byGoal: [],
  holdings: sample.gold.purchases.map((p) => ({
    id: p.id, description: p.what, grams: String(p.grams), purity: p.purity, paid: mv(p.paidMinor), boughtOn: "", seller: p.shop,
    bucket: "GOALS", goalId: null, goalName: p.goal, jewellery: p.belowPaid, value: mv(p.valueMinor), belowPaid: p.belowPaid,
  })),
  prices: (["24K", "22K", "21K"] as const).map((p) => ({
    purity: p, perGram: mv(sample.gold.price[p]), asOf: "", source: sample.gold.priceSource, seeded: true, typedByFamily: false,
  })),
  priceAsOf: "",
  priceSource: sample.gold.priceSource,
  priceIsSample: true,
  makingNote: sample.gold.makingNote,
};
