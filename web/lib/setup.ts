// First-run setup for a new family. Types mirror core/ setup records (SetupService, StarterBudgetEngine).
// Every amount on these screens comes from the core; the screens only show it and send back what a parent typed.

import { call, Role } from "./api";
import { Buckets, Goal, MoneyView } from "./live";

export type Step = "FAMILY" | "INCOME" | "BUDGETS" | "PLAN" | "FINISH" | "DONE";

export interface SetupStatus {
  done: boolean;
  next: Step;
  familyName: string;
  parents: number;
  kids: number;
  incomes: number;
  commitments: number;
  budgets: number;
  goals: number;
  buckets: "NONE" | "DRAFT" | "ACTIVE";
}

export interface MemberAdded {
  id: string;
  displayName: string;
  role: Role;
  ageBand: string;
  username: string;
}

export interface StarterLine {
  category: string;
  label: string;
  bucket: "EVERYDAY" | "FUN";
  amount: MoneyView;
  reason: string;
}

export interface StarterPocket {
  memberId: string;
  name: string;
  weekly: MoneyView;
  monthly: MoneyView;
  reason: string;
}

export interface Starter {
  income: MoneyView;
  commitments: MoneyView;
  room: MoneyView;
  cap: MoneyView;
  total: MoneyView;
  cut: boolean;
  note: string;
  lines: StarterLine[];
  pocket: StarterPocket[];
}

export interface IncomeView {
  id: string;
  memberName: string | null;
  name: string;
  kind: "FIXED" | "VARIABLE" | "ONE_OFF";
  amount: MoneyView;
  plannedMonthly: MoneyView;
  nextOn: string;
  status: "DRAFT" | "ACTIVE" | "ARCHIVED";
  note: string;
}

export interface CommitmentView {
  id: string;
  name: string;
  kind: string;
  kindLabel: string;
  amount: MoneyView;
  frequency: string;
  nextDueOn: string;
  reserved: MoneyView;
  status: "DRAFT" | "ACTIVE" | "ARCHIVED";
}

const post = <T>(path: string, body: unknown) => call<T>(path, { method: "POST", body: JSON.stringify(body) });

export const setupApi = {
  signup: (b: { familyName: string; displayName: string; username: string; password: string; email?: string }) =>
    post<{ accessToken: string; role: Role }>("/api/auth/signup", b),
  status: () => call<SetupStatus>("/api/setup"),
  addMember: (b: {
    role: Role; displayName: string; age?: number; username: string; password: string; email?: string;
    consent?: boolean; goalName?: string; goalPrice?: string;
  }) => post<MemberAdded>("/api/setup/members", b),
  incomes: () => call<IncomeView[]>("/api/plan/income"),
  addIncome: (b: { memberId?: string; name: string; kind: "FIXED" | "VARIABLE"; amount: string; baseOverride?: string; nextOn: string }) =>
    post<IncomeView>("/api/plan/income", { ...b, frequency: "MONTHLY" }),
  commitments: () => call<CommitmentView[]>("/api/plan/commitments"),
  addCommitment: (b: { name: string; kind: string; amount: string; frequency: string; nextDueOn: string; estimate?: boolean; reserved?: string }) =>
    post<CommitmentView>("/api/plan/commitments", b),
  accounts: () => call<{ id: string; kind: string; label: string; balance: MoneyView }[]>("/api/accounts"),
  // Typed in by hand, never linked to a bank: what is in the current account today, so "free until pay day" is right
  addBankAccount: (b: { label: string; balance: string; ownerId?: string; asOf: string }) =>
    post<{ id: string }>("/api/accounts", { kind: "BANK", ...b }),
  starter: () => call<Starter>("/api/setup/budgets"),
  // The budgets as they are now, with the app's start as a guide: for changing them after setup
  currentBudgets: () => call<Starter>("/api/setup/budgets/current"),
  saveBudgets: (lines: { category: string; amount: string }[], pocket: { memberId: string; weekly: string }[]) =>
    post<void>("/api/setup/budgets", { lines, pocket }),
  buffer: () => call<{ target: MoneyView; reason: string }>("/api/setup/buffer"),
  addGoal: (b: { name: string; target: string; targetDate?: string; tier: "SURVIVAL" | "GOOD_LIFE" | "DREAM" }) =>
    post<{ id: string; name: string; target: MoneyView }>("/api/setup/goals", b),
  // The Planner drafts the money buckets (the core engine's shares, the Planner's words). A parent confirms them.
  firstPlan: () => post<{ reply: string }>("/agent/setup/plan", {}),
  finish: () => post<SetupStatus>("/api/setup/finish", {}),
};

const put = <T>(path: string, body: unknown) => call<T>(path, { method: "PUT", body: JSON.stringify(body) });
const remove = (path: string) => call<void>(path, { method: "DELETE" });

// Changing the plan after setup. Each is a parent's tap; the core refuses them from an agent.
export const changeApi = {
  updateIncome: (id: string, b: { name: string; amount: string; nextOn: string; baseOverride?: string }) =>
    put<IncomeView>(`/api/plan/income/${id}`, b),
  removeIncome: (id: string) => remove(`/api/plan/income/${id}`),
  updateCommitment: (id: string, b: { name: string; amount?: string; frequency?: string; nextDueOn?: string; reserved?: string }) =>
    put<CommitmentView>(`/api/plan/commitments/${id}`, b),
  removeCommitment: (id: string) => remove(`/api/plan/commitments/${id}`),
  updateGoal: (id: string, b: { name: string; target: string; targetDate?: string }) =>
    put<Goal>(`/api/plan/goals/${id}`, b),
  removeGoal: (id: string) => remove(`/api/plan/goals/${id}`),
  // What a parent typed for each bucket in AED; Fun gets the rest. Saved as a draft to confirm.
  bucketAmounts: (amounts: Record<string, string>) => post<Buckets>("/api/plan/buckets/draft/amounts", { amounts }),
  keepBuckets: () => call<Buckets>("/api/plan/buckets/draft", { method: "DELETE" }),
};

// Amounts as typed into a box ("2,500") go to the core as text; the core reads them. Major units for an input's value.
export const typed = (m: MoneyView) => String(m.minor / 100);
