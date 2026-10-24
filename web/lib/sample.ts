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

export const badges: { art: "piggy" | "quarter" | "half" | "threequarter" | "trophy" | "heart"; name: string; earned: boolean; hint?: string }[] = [
  { art: "piggy", name: "Big Saver", earned: true },
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
  source: "From the noon page · no food or age rules apply",
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
    { label: "Price on the noon page", value: "AED 300.00" },
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

export const rules = [
  { said: "No peanuts for Adam", typed: ["Allergen", "Peanuts", "Adam", "Block"], on: true },
  { said: "Anything over AED 100 needs me", typed: ["Approval limit", "AED 100", "Layla, Adam"], on: true },
  { said: "No energy drinks for the kids", typed: ["Blocked category", "Energy drinks", "Layla, Adam"], on: false },
];

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
