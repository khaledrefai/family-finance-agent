// Sample figures for the screens whose core endpoints are not built yet
// (plan, cash flow, budgets, bills, goals, stars, meals, receipts).
// The shapes mirror what the money engine will return, so each screen swaps this for a fetch later
// (GET /api/plan/cashflow, POST /api/plan/options, ...). Every total here is written out as the
// engine would send it: the UI only formats these numbers, it never adds them up.
// Money is in fils, like the rest of the app: 3_450_000 = AED 34,500.

export const SAMPLE_NOTE = "Sample figures. Your own appear once your income and bills are confirmed.";

export interface Line {
  label: string;
  amountMinor: number;
}

export const monthly = {
  month: "October",
  incomeMinor: 3_450_000,
  income: [
    { label: "Omar, fixed salary", amountMinor: 2_800_000 },
    { label: "Sara, cautious base", amountMinor: 650_000 },
  ] as Line[],
  commitmentsMinor: 1_720_000,
  commitments: [
    { label: "Rent reserve (4 cheques a year)", amountMinor: 1_000_000 },
    { label: "School fees reserve (3 terms)", amountMinor: 375_000 },
    { label: "Car loan", amountMinor: 210_000 },
    { label: "DEWA, average", amountMinor: 90_000 },
    { label: "du mobile and internet", amountMinor: 45_000 },
  ] as Line[],
  everydayMinor: 739_000,
  freeMinor: 991_000,
  savingMinor: 650_000,
  savingLabel: "Saving: buffer 5,000 and summer trip 1,500",
  spareMinor: 341_000,
  freeUntilPaydayMinor: 214_000,
  payday: "Saturday 25 October",
  // Where each bar of the step-down chart starts and ends, as a share of income. Sent by the engine.
  steps: [
    { label: "Income to plan on", amountMinor: 3_450_000, from: 0, to: 100, kind: "total" },
    { label: "Commitments and reserves", amountMinor: -1_720_000, from: 50.14, to: 100, kind: "s1" },
    { label: "Everyday spending", amountMinor: -739_000, from: 28.72, to: 50.14, kind: "s2" },
    { label: "Free cash", amountMinor: 991_000, from: 0, to: 28.72, kind: "total" },
    { label: "Saving: buffer 5,000 and summer trip 1,500", amountMinor: -650_000, from: 9.88, to: 28.72, kind: "s3" },
    { label: "Spare", amountMinor: 341_000, from: 0, to: 9.88, kind: "total" },
  ],
};

// Free cash per month for the next 12 months. `share` is the bar height as a share of the largest month.
export const cashflow = {
  note: "December is AED 2,400 tighter: the rent reserve is short before the 1 January cheque. Summer DEWA bills are higher.",
  months: [
    { m: "Nov", freeMinor: 991_000, share: 100 },
    { m: "Dec", freeMinor: 751_000, share: 76, tight: true },
    { m: "Jan", freeMinor: 991_000, share: 100 },
    { m: "Feb", freeMinor: 991_000, share: 100 },
    { m: "Mar", freeMinor: 991_000, share: 100 },
    { m: "Apr", freeMinor: 991_000, share: 100 },
    { m: "May", freeMinor: 991_000, share: 100 },
    { m: "Jun", freeMinor: 941_000, share: 95 },
    { m: "Jul", freeMinor: 941_000, share: 95 },
    { m: "Aug", freeMinor: 941_000, share: 95 },
    { m: "Sep", freeMinor: 941_000, share: 95 },
    { m: "Oct", freeMinor: 991_000, share: 100 },
  ],
};

export const bills = [
  { name: "DEWA", due: "Sun 26 Oct", amountMinor: 85_000, state: "reserved" as const, daysLeft: 3 },
  { name: "du", due: "Sat 1 Nov", amountMinor: 45_000, state: "reserved" as const },
  { name: "Car loan", due: "Wed 5 Nov", amountMinor: 210_000, state: "reserved" as const },
  { name: "Credit card, full balance", due: "Wed 12 Nov", amountMinor: 320_000, state: "reserved" as const },
  { name: "Rent cheque 2 of 4", due: "Thu 1 Jan", amountMinor: 240_000, state: "short" as const },
];

export const findings = [
  {
    id: "dup",
    text: "Two charges of AED 189 at Carrefour on 14 October.",
    detail: "Same amount, two minutes apart. Looks like a duplicate.",
    yes: "Mark as duplicate",
    no: "Both are right",
  },
  {
    id: "sub",
    text: "AED 35 to a streaming service every month since March.",
    detail: "It is not on your list yet. Add it as a commitment?",
    yes: "Add it",
    no: "Ignore",
  },
];

// Spent so far against each budget. `fill` and `past` are bar widths where the limit sits at 75%.
export const budgets = {
  daysLeft: 8,
  items: [
    { name: "Groceries", spentMinor: 224_000, limitMinor: 300_000, fill: 56, past: 0 },
    { name: "Eating out", spentMinor: 112_000, limitMinor: 150_000, fill: 56, past: 0 },
    { name: "Entertainment", spentMinor: 156_000, limitMinor: 120_000, fill: 75, past: 22.5, overMinor: 36_000 },
    { name: "Transport", spentMinor: 64_000, limitMinor: 100_000, fill: 48, past: 0 },
    { name: "Kids' pocket money", spentMinor: 60_000, limitMinor: 69_000, fill: 65.2, past: 0 },
  ],
  history: {
    budgetMinor: 739_000,
    budgetShare: 82,
    months: [
      { m: "May", spentMinor: 710_000, share: 79 },
      { m: "Jun", spentMinor: 780_000, share: 87 },
      { m: "Jul", spentMinor: 830_000, share: 92 },
      { m: "Aug", spentMinor: 745_000, share: 83 },
      { m: "Sep", spentMinor: 690_000, share: 77 },
      { m: "Oct", spentMinor: 715_000, share: 79, soFar: true },
    ],
    note: "Summer ran over three months in a row, mostly entertainment and delivery. October so far is in grey.",
  },
};

export const advice = {
  title: "Advice for November and December",
  text: "The rent cheque on 1 January and school fees on 5 January leave AED 2,400 less than usual. Cut entertainment from AED 1,200 to AED 600 a month for November and December, and the summer trip stays on track.",
  why: [
    { label: "Free cash in a usual month", value: "AED 9,910" },
    { label: "Free cash in December", value: "AED 7,510" },
    { label: "Rent cheque, 1 January", value: "AED 30,000" },
    { label: "Rent reserve by then", value: "AED 27,600" },
    { label: "Entertainment, 3-month average", value: "AED 1,520" },
    { label: "Saved by the cut, 2 months", value: "AED 1,200" },
  ],
};

export const familyGoal = {
  name: "Summer trip",
  savedMinor: 360_000,
  targetMinor: 1_200_000,
  percent: 30,
  onTrackFor: "30 June",
  badgeAt: 50,
  // Saved so far by month, and the plan line to the target. y is a share of the target.
  actual: [
    { m: "Aug", share: 10 },
    { m: "Sep", share: 20 },
    { m: "Oct", share: 30 },
  ],
  planMonths: ["Aug", "Sep", "Oct", "Nov", "Dec", "Jan", "Feb", "Mar", "Apr", "May", "Jun"],
};

export const planSteps = [
  { title: "Protect the commitments.", text: "Rent and school fee reserves come out first.", done: true },
  { title: "Clear the card.", text: "AED 3,200 paid in full on 12 November, so no interest.", done: true },
  { title: "Build a buffer.", text: "AED 5,000 a month until it reaches AED 25,000 in March. Larger than usual because Sara's pay varies.", done: true },
  { title: "Pick the family goal pace.", text: "", done: false },
];

export const planOptions = [
  { id: "relaxed", name: "Relaxed", monthlyMinor: 100_000, text: "Ready by 31 October 2027. Misses next summer." },
  { id: "steady", name: "Steady", monthlyMinor: 150_000, text: "Ready by 30 June 2027. Leaves AED 3,410 spare most months and AED 1,010 in December.", pick: true },
  { id: "faster", name: "Faster", monthlyMinor: 200_000, text: "Ready by 30 April 2027. Tight in December: AED 510 spare." },
];

export const planSplit = [
  { label: "Family savings, from the budget", amountMinor: 140_000 },
  { label: "Layla, from pocket money", amountMinor: 6_000 },
  { label: "Adam, from pocket money", amountMinor: 4_000 },
];

// Kids' goals and stars, keyed by first name for the seeded family.
export const kids: Record<string, {
  goal: string; goalMinor: number; savedMinor: number; percent: number; plan: string;
  stars: number; starsThisMonth: number; weekly: string;
  pocket?: { totalMinor: number; parts: { label: string; amountMinor: number; color: string }[]; note: string };
}> = {
  Layla: {
    goal: "headphones",
    goalMinor: 30_000,
    savedMinor: 12_000,
    percent: 40,
    plan: "Save AED 20 a week and do 2 chores, and they're yours by 20 November.",
    stars: 14,
    starsThisMonth: 6,
    weekly: "AED 100 a week",
    pocket: {
      totalMinor: 43_000,
      parts: [
        { label: "Food", amountMinor: 21_000, color: "var(--s1)" },
        { label: "Fun", amountMinor: 10_000, color: "var(--s2)" },
        { label: "Saved", amountMinor: 8_000, color: "var(--s3)" },
        { label: "Not spent yet", amountMinor: 4_000, color: "var(--s4)" },
      ],
      note: "Most went on lunch at school. That's normal, and logging it is what makes this picture right.",
    },
  },
  Adam: {
    goal: "building set",
    goalMinor: 16_000,
    savedMinor: 7_200,
    percent: 45,
    plan: "Almost half.",
    stars: 11,
    starsThisMonth: 5,
    weekly: "AED 60 a week",
  },
};

export const saverOfTheMonth = [
  { name: "Adam", percent: 45 },
  { name: "Layla", percent: 40 },
];

export const badges: { art: "camel" | "quarter" | "half" | "threequarter" | "trophy" | "heart"; name: string; earned: boolean; hint?: string }[] = [
  { art: "camel", name: "Big Saver", earned: true },
  { art: "quarter", name: "A quarter there", earned: true },
  { art: "half", name: "Halfway", earned: false, hint: "AED 30 to go" },
  { art: "threequarter", name: "Nearly there", earned: false },
  { art: "trophy", name: "Goal reached", earned: false },
  { art: "heart", name: "Family badge", earned: false, hint: "Trip at 30% of 50%" },
];

// What the agent offers Layla for the AED 300 headphones (from the assess API once it exists).
export const headphones = {
  title: "Wireless over-ear headphones",
  priceMinor: 30_000,
  source: "Best price the app found · no food or age rules apply",
  intro: "Good pick. You have AED 120 saved, so you need AED 180 more. This month is a tight one for the family, so let's bring Dad a plan instead of just a price. Two ways:",
  options: [
    {
      id: "challenge",
      title: "Savings challenge.",
      text: "Save AED 20 a week for 4 weeks and do 2 chores. You'd have them by 20 November, and earn a star each week.",
      button: "Send this plan to Dad",
      requestTitle: "Headphones, with my savings plan",
      amountMinor: 30_000,
    },
    {
      id: "cheaper",
      title: "A cheaper pair.",
      text: "On-ear wireless headphones for AED 180. You'd need AED 60 more, about 3 weeks of saving.",
      button: "Ask for the AED 180 pair",
      requestTitle: "On-ear wireless headphones",
      amountMinor: 18_000,
    },
  ],
  // The parent's "Why this?" view of the same assessment.
  readLine: "Worth a yes with the plan. Layla has AED 120 saved. AED 20 a week for 4 weeks plus 2 chores covers the rest by 20 November, and the family plan stays on track.",
  kidSide: [
    { label: "Best price found", value: "AED 300.00" },
    { label: "Saved in the demo wallet", value: "AED 120.00" },
    { label: "4 weeks at AED 20", value: "AED 80.00" },
    { label: "2 chores at AED 50", value: "AED 100.00" },
    { label: "Ready on", value: "20 November" },
  ],
  familySide: [
    { label: "Free until pay day (25 Oct)", value: "AED 2,140" },
    { label: "Next big commitment", value: "School fees, 5 Jan" },
    { label: "Effect on the summer trip", value: "None, still 30 June" },
  ],
  rules: [
    { label: "Anything over AED 100 needs a parent", value: "Sent to you" },
    { label: "Food and allergy rules", value: "Not relevant" },
  ],
};


export const meals = {
  week: "Sun 26 Oct to Fri 31 Oct",
  plannedMinor: 61_200,
  budgetMinor: 69_000,
  saving: "Six nights of delivery cost the family about AED 1,050 last month. Cooking these saves about AED 438.",
  dinners: [
    { d: "Sun", name: "Chicken machboos", costMinor: 12_800 },
    { d: "Mon", name: "Lentil soup and flatbread", costMinor: 4_600 },
    { d: "Tue", name: "Vegetable pasta", costMinor: 6_200 },
    { d: "Wed", name: "Baked fish with rice", costMinor: 14_200 },
    { d: "Thu", name: "Chicken wraps at home", costMinor: 9_600 },
    { d: "Fri", name: "Egg and potato bake", costMinor: 4_800 },
  ],
  swaps: [
    { name: "Grilled chicken with salad", costMinor: 9_200 },
    { name: "Chickpea stew and rice", costMinor: 4_100 },
    { name: "Homemade pizza", costMinor: 5_800 },
  ],
  list: [
    { group: "Meat and fish", items: [["Whole chicken, 2", 4_400], ["Hammour fillet, 1 kg", 6_800]] },
    { group: "Vegetables", items: [["Onions, 2 kg", 800], ["Tomatoes, 1 kg", 600]] },
    { group: "Pantry", items: [["Basmati rice, 5 kg", 3_900], ["Red lentils, 1 kg", 900]] },
  ] as { group: string; items: [string, number][] }[],
  listCount: 31,
};

export const receipt = {
  shop: "Carrefour, Mirdif",
  date: "Sun 26 Oct",
  paidBy: "Omar, credit card",
  accountId: "visa",
  budget: "Groceries",
  totalMinor: 64_150,
  vsPlan: "27 of the 31 planned items bought, plus 4 extras. AED 29.50 over the plan, still AED 48.50 inside the food budget.",
  lines: [
    { name: "Whole chicken, 2", amountMinor: 4_400, inPlan: true },
    { name: "Basmati rice, 5 kg", amountMinor: 3_900, inPlan: true },
    { name: "Hammour fillet, 1 kg", amountMinor: 7_100, inPlan: true },
    { name: "Chocolate biscuits, 3", amountMinor: 1_875, inPlan: false },
    { name: "Ice cream, 2 L", amountMinor: 2_250, inPlan: false },
  ],
  lineCount: 31,
};

export const summary = {
  month: "October",
  sentOn: "Saturday 1 November",
  wentWell: [
    "The credit card was paid in full, so no interest this month.",
    "Layla logged spending on 18 days out of 31.",
    "Every bill was paid from its reserve.",
  ],
  toChange: [
    "Entertainment finished at AED 1,680, 140% of its limit. The new limit of AED 600 starts today.",
    "December is the tight month before the rent cheque. Nothing to do yet; the plan already allows for it.",
  ],
};

export const spendCategories = ["Restaurant", "Hotel", "Transport", "Groceries", "Shopping", "Other"];

// Money buckets (GET /api/plan/buckets once it exists). The accountant suggests each bucket's share,
// a parent confirms, and the engine works out the amounts. `from`/`to` place each bucket on the
// income bar and `share` sizes each line inside its bucket, both sent by the engine.
export type Tier = "Survival" | "Good Life" | "Dream";
export const buckets = {
  month: "October",
  incomeMinor: 3_450_000,
  confirmed: false,
  list: [
    {
      id: "commitments", name: "Commitments", color: "var(--s1)", amountMinor: 1_720_000, percent: 49.9, from: 0, to: 49.86,
      about: "Survival first: rent, school and bills are covered before anything else.",
      items: [
        { label: "Rent reserve (4 cheques a year)", amountMinor: 1_000_000, share: 58.1 },
        { label: "School fees reserve (3 terms)", amountMinor: 375_000, share: 21.8 },
        { label: "Car loan", amountMinor: 210_000, share: 12.2 },
        { label: "DEWA, average", amountMinor: 90_000, share: 5.2 },
        { label: "du mobile and internet", amountMinor: 45_000, share: 2.6 },
      ],
    },
    {
      id: "everyday", name: "Everyday", color: "var(--s2)", amountMinor: 739_000, percent: 21.4, from: 49.86, to: 71.28,
      about: "What the family spends week to week. The budgets live here.",
      items: [
        { label: "Groceries", amountMinor: 300_000, share: 40.6 },
        { label: "Eating out", amountMinor: 150_000, share: 20.3 },
        { label: "Entertainment", amountMinor: 120_000, share: 16.2 },
        { label: "Transport", amountMinor: 100_000, share: 13.5 },
        { label: "Kids' pocket money", amountMinor: 69_000, share: 9.3 },
      ],
    },
    {
      id: "goals", name: "Goals", color: "var(--s3)", amountMinor: 650_000, percent: 18.8, from: 71.28, to: 90.12,
      about: "Saving for what the family wants, the safety net first.",
      items: [
        { label: "Emergency buffer", amountMinor: 500_000, share: 76.9, tier: "Survival" as Tier, note: "Full in March, then this money moves to the next goal" },
        { label: "Summer trip", amountMinor: 150_000, share: 23.1, tier: "Good Life" as Tier, note: "AED 3,600 of 12,000 saved" },
        { label: "Home deposit", amountMinor: 0, share: 0, tier: "Dream" as Tier, note: "Starts when the buffer is full" },
      ],
    },
    {
      id: "invest", name: "Set aside for investing", color: "var(--s4)", amountMinor: 100_000, percent: 2.9, from: 90.12, to: 93.02,
      about: "An amount kept apart for the long term. The app never names a fund or product.",
      items: [{ label: "Set aside this month", amountMinor: 100_000, share: 100 }],
    },
    {
      id: "fun", name: "Fun", color: "var(--s5)", amountMinor: 191_000, percent: 5.5, from: 93.02, to: 98.55,
      about: "Money each parent spends as they like, no questions asked.",
      items: [
        { label: "Omar's own money", amountMinor: 70_000, share: 36.6 },
        { label: "Sara's own money", amountMinor: 70_000, share: 36.6 },
        { label: "Family day out", amountMinor: 51_000, share: 26.7 },
      ],
    },
    {
      id: "giving", name: "Giving", color: "var(--s6)", amountMinor: 50_000, percent: 1.4, from: 98.55, to: 100, optional: true,
      about: "Optional. Charity and sadaqah. Turn it off any time.",
      items: [{ label: "Charity and sadaqah", amountMinor: 50_000, share: 100 }],
    },
  ],
  tiers: [
    { name: "Survival" as Tier, text: "Bills plus a 3-month buffer" },
    { name: "Good Life" as Tier, text: "Trips, Eid, the extras" },
    { name: "Dream" as Tier, text: "Big things, like a home" },
  ],
};

// Accounts and cards the parents typed in (GET /api/accounts once it exists). Nothing links to a bank:
// balances are what the family entered, so they are shown as estimated with their as-of date.
// Card bills are the engine's forecast: spend since the last statement plus any carried balance,
// and the free cash the cash-flow calendar expects on the due date.
export type AccountKind = "BANK" | "CREDIT_CARD" | "CASH";
export const accounts: {
  id: string; kind: AccountKind; label: string; last4?: string; owner: string;
  balanceMinor: number; asOf: string;
  limitMinor?: number; usedShare?: number; statementDay?: number; dueDay?: number;
  bill?: { soFarMinor: number; due: string; freeByDueMinor: number; ok: boolean };
}[] = [
  { id: "enbd", kind: "BANK", label: "Emirates NBD current", last4: "4821", owner: "Omar", balanceMinor: 1_845_000, asOf: "24 Oct" },
  { id: "adcb", kind: "BANK", label: "ADCB savings", last4: "1093", owner: "Sara", balanceMinor: 3_260_000, asOf: "20 Oct" },
  {
    id: "visa", kind: "CREDIT_CARD", label: "Omar's Visa", last4: "7710", owner: "Omar", balanceMinor: -590_000, asOf: "24 Oct",
    limitMinor: 2_000_000, usedShare: 29.5, statementDay: 27, dueDay: 5,
    bill: { soFarMinor: 590_000, due: "5 November", freeByDueMinor: 480_000, ok: false },
  },
  {
    id: "mc", kind: "CREDIT_CARD", label: "Sara's Mastercard", last4: "3356", owner: "Sara", balanceMinor: -135_000, asOf: "24 Oct",
    limitMinor: 1_200_000, usedShare: 11.3, statementDay: 1, dueDay: 25,
    bill: { soFarMinor: 135_000, due: "25 November", freeByDueMinor: 920_000, ok: true },
  },
  { id: "cash", kind: "CASH", label: "Cash at home", owner: "Family", balanceMinor: 65_000, asOf: "22 Oct" },
];

// The card check the engine adds to a request paid by card (part of the assess API once it exists).
export const cardCheck: Record<string, { withMinor: number; freeByDueMinor: number; shortMinor: number; due: string } | null> = {
  visa: { withMinor: 620_000, freeByDueMinor: 480_000, shortMinor: 140_000, due: "5 November" },
  mc: null,
  enbd: null,
  adcb: null,
  cash: null,
};

// A card statement upload, matched to an account by its last 4 digits.
export const statementUpload = { name: "Visa statement, September", lines: 48, accountId: "visa", matchedBy: "last 4 digits 7710" };

// Saving in gold (GET /api/gold once it exists). Parents log each purchase; the engine values the grams
// at today's price for their purity. Prices here are sample figures, not a real rate (verify a source).
export type Purity = "24K" | "22K" | "21K";
export const gold = {
  price: { "24K": 32_000, "22K": 29_650, "21K": 28_300 } as Record<Purity, number>, // fils per gram
  priceSource: "Daily retail rate, Dubai (sample)",
  priceAsOf: "24 Oct, 10:00",
  totalGrams: [{ purity: "24K" as Purity, grams: 40 }, { purity: "22K" as Purity, grams: 10 }],
  valueMinor: 1_576_500,
  paidMinor: 1_540_000,
  purchases: [
    { id: "g1", what: "Gold bar", grams: 20, purity: "24K" as Purity, paidMinor: 580_000, date: "10 Mar", shop: "Gold Souk, Deira", goal: "Home deposit", valueMinor: 640_000, belowPaid: false },
    { id: "g2", what: "Gold coins, 4 × 5 g", grams: 20, purity: "24K" as Purity, paidMinor: 620_000, date: "2 Jul", shop: "Mall of the Emirates", goal: "Home deposit", valueMinor: 640_000, belowPaid: false },
    { id: "g3", what: "Bangle", grams: 10, purity: "22K" as Purity, paidMinor: 340_000, date: "15 Sep", shop: "Gold Souk, Deira", goal: "Emergency buffer", valueMinor: 296_500, belowPaid: true },
  ],
  makingNote: "Jewellery prices include making charges, so it is worth less than you paid if you sell it by weight.",
};

// Family wealth (GET /api/wealth once it exists): what the family owns minus what it owes, from typed data.
// `from`/`to` place each line on one bar, as shares of everything owned. Sent by the engine.
export const wealth = {
  totalMinor: 2_181_500,
  asOf: "24 Oct",
  steps: [
    { label: "Bank accounts", amountMinor: 5_105_000, from: 0, to: 75.67, kind: "s1", asOf: "20 to 24 Oct" },
    { label: "Cash", amountMinor: 65_000, from: 75.67, to: 76.63, kind: "s1", asOf: "22 Oct" },
    { label: "Gold, at today's price", amountMinor: 1_576_500, from: 76.63, to: 100, kind: "s4", asOf: "24 Oct" },
    { label: "Everything you own", amountMinor: 6_746_500, from: 0, to: 100, kind: "total", asOf: "" },
    { label: "Credit cards", amountMinor: -725_000, from: 89.25, to: 100, kind: "s2", asOf: "24 Oct" },
    { label: "Car loan left", amountMinor: -3_840_000, from: 32.34, to: 89.25, kind: "s2", asOf: "1 Oct" },
    { label: "Family wealth", amountMinor: 2_181_500, from: 0, to: 32.34, kind: "total", asOf: "" },
  ],
};

// Ways to save: once a month, at most three suggestions built from recurring costs. The engine works out
// every amount and share; the agent only words them. Never a product pitch, never a link to buy.
export const waysToSave = {
  month: "November",
  tips: [
    {
      id: "streaming",
      title: "Streaming",
      text: "You pay AED 180 a month for three streaming services. Dropping one saves about AED 720 a year.",
      why: [
        { label: "Streaming services", value: "3" },
        { label: "Paid each month", value: "AED 180" },
        { label: "Cheapest one, a year", value: "AED 720" },
      ],
    },
    {
      id: "rent",
      title: "Rent",
      text: "Rent is 29% of take-home pay and the lease renews on 1 Mar. That is a good time to compare other flats.",
      why: [
        { label: "Rent, a year", value: "AED 120,000" },
        { label: "Take-home pay, a month", value: "AED 34,500" },
        { label: "Share of take-home pay", value: "29%" },
      ],
    },
    {
      id: "fun",
      title: "Outings",
      text: "Entertainment averaged AED 1,520 a month. Ask the accountant to plan cheaper outings for the family.",
      why: [
        { label: "Entertainment, 3-month average", value: "AED 1,520" },
        { label: "Entertainment budget", value: "AED 1,200" },
      ],
    },
  ],
};
