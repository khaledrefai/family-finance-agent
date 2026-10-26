// The long-term savings calculator (POST /api/plan/calculator). Types mirror PlanService.Calculator and
// SavingsCalculator.Projection in core/. The core works out every amount; the screen only lays them out.
import { call } from "./api";
import type { MoneyView } from "./live";

export interface YearLine {
  year: number;
  putIn: MoneyView;
  growth: MoneyView;
  total: MoneyView;
  asCash: MoneyView;
  totalBp: number; // share of the largest total on the chart, 10000 = the top
  cashBp: number;
}

export interface Reach {
  target: MoneyView;
  months: number | null;
  when: string;
  monthlyNeeded: MoneyView;
  years: number;
  line: string;
}

export interface Projection {
  start: MoneyView;
  monthly: MoneyView;
  rateBp: number;
  rateText: string;
  years: number;
  marks: YearLine[];
  everyYear: YearLine[];
  reach: Reach | null;
  summary: string;
  notes: string[];
}

export interface Calculator {
  from: "GOAL" | "INVEST_ASIDE" | "TYPED";
  goalId: string | null;
  goalName: string | null;
  fromLine: string | null;
  projection: Projection;
}

export interface CalculatorAsk {
  goalId?: string;
  from?: "INVEST_ASIDE";
  start?: string;
  monthly?: string;
  rate?: string;
  years?: number;
  target?: string;
}

export const calculatorApi = {
  project: (ask: CalculatorAsk) => call<Calculator>("/api/plan/calculator", { method: "POST", body: JSON.stringify(ask) }),
};
