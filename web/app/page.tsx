import type { Metadata } from "next";
import Link from "next/link";
import { budgets, cardCheck, monthly } from "@/lib/sample";
import { BigStar, Camel, GoalJar } from "./child/kid-ui";
import { Coach } from "./coach";
import { GrowthChart } from "./landing-parts";
import { BudgetBar, Icon, Waterfall } from "./ui";
import "./landing.css";

// The landing page at /: what the app does for a family's money, for anyone not signed in yet.
// It leads with the family's plan, goals, saving and investing, budgets and credit cards; the kids' side is one band.
// Every feature named is one the app has today, except those marked "Coming soon", and every figure is the demo
// family's (the seed or lib/sample.ts). The growth chart is an illustration at an example rate. The app is at /login.
export const metadata: Metadata = {
  title: "Family Finance Coach",
  description:
    "The family's accountant and money coach. Plan the month, keep budgets and credit cards in check, and save and invest for the goals that matter. A prototype with demo money.",
};

type IconName = Parameters<typeof Icon>[0]["name"];

// The Haddads' October split, from the demo plan (Money buckets)
const BUCKETS = [
  { name: "Commitments", amount: 17_200, color: "var(--s1)" },
  { name: "Everyday", amount: 7_390, color: "var(--s2)" },
  { name: "Goals", amount: 6_500, color: "var(--s3)" },
  { name: "Investing", amount: 1_000, color: "var(--s4)" },
  { name: "Fun", amount: 1_910, color: "var(--s5)" },
  { name: "Giving", amount: 500, color: "var(--s6)" },
];

const TIERS = [
  {
    tier: "Survival", cls: "tier-survival", title: "Cover the basics",
    text: "What it costs to live each month: rent, school fees, bills, food and transport, plus a buffer of three months for surprises.",
    example: "An AED 25,000 buffer by March, AED 5,000 a month.",
  },
  {
    tier: "Good Life", cls: "tier-goodlife", title: "Enjoy the extras",
    text: "Trips, Eid, a better car, the kids' activities. Price each one and give it a date.",
    example: "An AED 12,000 summer trip by June, AED 1,500 a month.",
  },
  {
    tier: "Dream", cls: "tier-dream", title: "Build for the big things",
    text: "A home, money invested for years, gold kept for the future.",
    example: "AED 1,000 a month set aside for investing.",
  },
];

const METHOD = [
  { title: "Know your survival number", text: "Pay, rent, school fees and bills, in your own words. The app works out what the family needs each month, and what is free." },
  { title: "Price the good life and the dream", text: "Give each goal an amount and a date, then pick a relaxed, steady or faster pace that is checked against every month ahead." },
  { title: "Give every dirham a job", text: "On pay day the money goes into buckets: commitments, everyday, goals, investing, fun and giving. Money for goals never mixes with spending money." },
  { title: "Review it together", text: "Share the plan with your partner. A plan for the week every Sunday, a family summary every month, and an alert when something drifts." },
];

const FEATURES: { title: string; items: { icon: IconName; title: string; text: string; soon?: boolean }[] }[] = [
  {
    title: "Plan the month",
    items: [
      { icon: "today", title: "What is free, today", text: "This month and until pay day, after rent, school fees and bills are put aside." },
      { icon: "plan", title: "Money buckets", text: "Every dirham of pay gets a job: commitments, everyday, goals, investing, fun and giving." },
      { icon: "calendar", title: "The next 12 months", text: "Tight months, like the one with a rent cheque, show up a year ahead." },
      { icon: "money", title: "Family wealth", text: "Bank, cash and gold, minus cards and loans, as one number." },
    ],
  },
  {
    title: "Track every budget",
    items: [
      { icon: "warn", title: "Budgets and alerts", text: "Over budget, heading over or above usual, while there is still time to act." },
      { icon: "upload", title: "Receipts and statements", text: "Upload a photo, a PDF or a bank’s CSV. Each line is matched, so nothing counts twice." },
      { icon: "ask", title: "Ask the accountant", text: "“Where did Layla’s money go this month?” Plain answers, with exact totals." },
      { icon: "receipt", title: "Odd payments found", text: "Double charges, and monthly payments nobody put on the list." },
    ],
  },
  {
    title: "Stay on top of credit cards",
    items: [
      { icon: "card", title: "Card check before you pay", text: "Before a card payment, it checks the bill will be covered on its due date." },
      { icon: "calendar", title: "Every card’s next bill", text: "Statement day, due day and the bill so far, for each card the family uses." },
      { icon: "plan", title: "Clear the card first", text: "The plan pays each card in full before the goals, so no interest builds up." },
    ],
  },
  {
    title: "Save and invest",
    items: [
      { icon: "star", title: "Goals at your own pace", text: "Relaxed, steady or faster, checked against every month’s spare cash." },
      { icon: "money", title: "Set aside for investing", text: "Its own bucket, so money for the long term never mixes with spending." },
      { icon: "money", title: "Gold at today’s price", text: "Logged in grams and valued at today’s price for its purity." },
      { icon: "star", title: "Ways to save", text: "Up to three ideas a month, each with the numbers behind it." },
    ],
  },
  {
    title: "Spend smarter",
    items: [
      { icon: "receipt", title: "Restaurants, hotels and trips", text: "Typed in with or without a receipt, each in its own category, so you see what eating out and trips really cost." },
      { icon: "ask", title: "Compare before you buy", text: "Say what you want. The shops’ offers are ranked by the total you pay, and the best one is checked against free cash and the card’s next bill." },
      { icon: "calendar", title: "Restaurants and hotels compared", text: "Prices side by side before you book.", soon: true },
      { icon: "today", title: "Weekly meals and a shopping list", text: "A dinner plan inside the food budget and the family’s food rules.", soon: true },
    ],
  },
  {
    title: "Decide together",
    items: [
      { icon: "family", title: "Requests with a stamp", text: "The kids ask, you approve or decline. A yes is paid from their demo wallet." },
      { icon: "warn", title: "Family rules in your own words", text: "“Anything over AED 100 needs me.” Checked on every request." },
      { icon: "bell", title: "Notifications", text: "On your phone and by email, with a plan for the week every Sunday morning." },
    ],
  },
];

const AGENTS = [
  { name: "Planner", tone: "var(--s2)", icon: "plan" as const, text: "Makes this month’s plan, the bucket split and the goal’s pace, and writes the ways to save." },
  { name: "Bookkeeper", tone: "var(--s1)", icon: "receipt" as const, text: "Reads receipts and statements, finds double charges, and logs what the kids say they bought." },
  { name: "Gatekeeper", tone: "var(--approved)", icon: "warn" as const, text: "Checks every request against the family rules, the family’s money and the card bills." },
  { name: "Coach", tone: "var(--kid-sun-deep)", icon: "star" as const, text: "Talks with the kids about their goals. Savings challenges and lessons are coming soon." },
  { name: "Shopper", tone: "var(--cancelled)", icon: "money" as const, text: "Compares shop prices by the total you pay. Restaurants, hotels and a weekly shopping list are coming soon." },
];

const TRUST = [
  { title: "Exact, not guessed", text: "Every amount is worked out by the app’s money engine in whole fils. The AI only finds the words." },
  { title: "You confirm first", text: "Anything the AI fills in stays a draft until a parent taps Confirm." },
  { title: "No real money moves", text: "No bank is connected and nothing is paid for you. The kids’ wallets hold demo money only." },
  { title: "Advice you can check", text: "Every idea shows the numbers behind it. Information only, not financial advice." },
  { title: "Kids can’t approve themselves", text: "A child can never approve their own request. It is checked in the app and again in the database." },
  { title: "Little data about children", text: "A display name and an age band, with a parent’s consent. No birth date, email or phone." },
];

const FAMILY = [
  { as: "sara", name: "Sara", who: "Mum, parent", kid: false },
  { as: "omar", name: "Omar", who: "Dad, parent", kid: false },
  { as: "layla", name: "Layla", who: "13, saving for headphones", kid: true },
  { as: "adam", name: "Adam", who: "9, peanut allergy", kid: true },
];

export default function LandingPage() {
  return (
    <div className="landing">
      <header className="lp-nav">
        <div className="lp-wrap lp-nav-in">
          <Link href="/" className="lp-brand">
            <Logo />
            Family Finance Coach
          </Link>
          <nav className="lp-links" aria-label="On this page">
            <a href="#goals">Goals</a>
            <a href="#grow">Save and invest</a>
            <a href="#manage">Budgets and cards</a>
            <a href="#features">Features</a>
          </nav>
          <Link href="/login" className="btn lp-login">Log in</Link>
        </div>
      </header>

      <main>
        <section className="lp-hero">
          <div className="lp-wrap lp-hero-grid">
            <div>
              <h1>Every dirham gets a job.</h1>
              <p className="lp-lede">
                Family Finance Coach is your family&apos;s accountant and money coach. It plans the month, keeps budgets and
                credit cards in check, and helps you save and invest for the goals that matter, from a safety buffer to a home.
              </p>
              <div className="lp-cta">
                <a href="#demo" className="btn">Try the demo family</a>
                <Link href="/login" className="btn quiet">Log in</Link>
              </div>
              <p className="lp-small">A prototype with demo money. No bank connection and no real payments.</p>
            </div>
            <PlanScene />
          </div>
        </section>

        <section id="goals" className="lp-band">
          <div className="lp-wrap">
            <div className="lp-head">
              <h2>Start with the life you want, then put a price on it</h2>
              <p className="lp-lede">
                Good money plans begin with goals, not budgets. Work out what it costs to live, price the extras and the
                dreams, then give every dirham a job toward them.
              </p>
            </div>
            <ol className="lp-tiers">
              {TIERS.map((t) => (
                <li key={t.tier} className="lp-tier">
                  <span className={`tier ${t.cls}`}>{t.tier}</span>
                  <h3>{t.title}</h3>
                  <p>{t.text}</p>
                  <p className="lp-tier-eg"><span>The Haddads</span>{t.example}</p>
                </li>
              ))}
            </ol>
            <h3 className="lp-method-h">Four steps the app walks you through</h3>
            <ol className="lp-method">
              {METHOD.map((m, i) => (
                <li key={m.title}>
                  <span className="lp-step-n" aria-hidden="true">{i + 1}</span>
                  <div>
                    <h3>{m.title}</h3>
                    <p>{m.text}</p>
                  </div>
                </li>
              ))}
            </ol>
          </div>
        </section>

        <section id="grow" className="lp-band lp-ink">
          <div className="lp-wrap lp-split">
            <div>
              <h2>Small amounts, put to work, become big ones</h2>
              <p className="lp-lede">
                Saving keeps the family safe. Investing for years is what grows it. The order matters: a buffer first, then
                the goals, then the long term.
              </p>
              <ul className="lp-points">
                <li>
                  <h3>Pay the goals first</h3>
                  <p>Savings leave on pay day, before the spending starts, so what is left is yours to spend.</p>
                </li>
                <li>
                  <h3>A buffer before anything else</h3>
                  <p>Three months of bills put aside means a surprise doesn&apos;t turn into a card balance.</p>
                </li>
                <li>
                  <h3>Let time do the work</h3>
                  <p>The longer money stays invested, the more of the total can be growth rather than what you put in.</p>
                </li>
                <li>
                  <h3>Small leaks add up</h3>
                  <p>A forgotten AED 35 subscription is AED 420 a year. The app finds the ones nobody listed.</p>
                </li>
              </ul>
            </div>
            <GrowthChart />
          </div>
        </section>

        <section id="manage" className="lp-band lp-paper2">
          <div className="lp-wrap">
            <div className="lp-head">
              <h2>Know where every dirham goes</h2>
              <p className="lp-lede">
                One clear picture of the month, budgets that warn you while there is still time, and credit cards that never
                catch you out. These are the demo family&apos;s figures for October.
              </p>
            </div>
            <div className="lp-dash">
              <article className="lp-panel">
                <h3>Where the month&apos;s money goes</h3>
                <p className="lp-sub">From AED 34,500 coming in to AED 3,410 spare</p>
                <Waterfall
                  steps={monthly.steps}
                  label="Income AED 34,500, minus commitments AED 17,200 and everyday spending AED 7,390, leaves free cash AED 9,910. Saving takes AED 6,500, leaving AED 3,410 spare."
                />
                <p className="lp-cap">All amounts in AED.</p>
              </article>
              <article className="lp-panel">
                <h3>Budgets, with time to act</h3>
                <p className="lp-sub">Spent so far against each budget. The dark line is the limit, and red is over it.</p>
                {budgets.items.map((b) => <BudgetBar key={b.name} {...b} />)}
                <p className="lp-cap">Alerts come when a budget is over, heading over or above its usual. All amounts in AED.</p>
              </article>
              <article className="lp-panel">
                <h3>Credit cards, before the bill</h3>
                <p className="lp-sub">An AED 300 purchase on Omar&apos;s Visa</p>
                <CardBill />
                <p className="lp-cap">Every card&apos;s statement day, due day and bill so far, checked against what is free on the due date.</p>
              </article>
            </div>
          </div>
        </section>

        <section id="features" className="lp-band">
          <div className="lp-wrap">
            <div className="lp-head">
              <h2>Everything the family&apos;s money needs</h2>
              <p className="lp-lede">For parents. Everything the family pays, plans and decides, in one place.</p>
            </div>
            <div className="lp-parents">
              <PhoneToday />
              <div className="lp-groups">
                {FEATURES.map((g) => (
                  <div className="lp-group" key={g.title}>
                    <h3>{g.title}</h3>
                    <ul>
                      {g.items.map((i) => (
                        <li key={i.title}>
                          <span className="lp-ic" aria-hidden="true"><Icon name={i.icon} size={20} /></span>
                          <div>
                            <b>{i.title}{i.soon && <span className="lp-soon">Coming soon</span>}</b>
                            <p>{i.text}</p>
                          </div>
                        </li>
                      ))}
                    </ul>
                  </div>
                ))}
              </div>
            </div>
          </div>
        </section>

        <section id="kids" className="lp-band lp-kids">
          <div className="lp-wrap lp-kids-in">
            <div className="lp-head">
              <h2>And the kids learn too</h2>
              <p className="lp-lede">
                They see only their own money, in big friendly pictures. Asking a parent comes with a saving plan, and every
                dirham they save fills their jar.
              </p>
            </div>
            <div className="lp-stickers">
              <article className="lp-sticker">
                <div className="lp-sticker-art"><GoalJar percent={40} size={88} /></div>
                <h3>A goal jar that fills</h3>
                <p>Layla is 40% of the way to her headphones.</p>
              </article>
              <article className="lp-sticker">
                <div className="lp-sticker-art"><Camel size={88} /></div>
                <h3>A wallet they can see</h3>
                <p>Pocket money in demo money. A parent&apos;s yes is paid from here.</p>
              </article>
              <article className="lp-sticker">
                <div className="lp-sticker-art"><BigStar count={14} size={88} /></div>
                <h3>Stars for keeping track</h3>
                <p>Not money, and nobody loses them.</p>
              </article>
            </div>
          </div>
        </section>

        <section id="agents" className="lp-band lp-paper2">
          <div className="lp-wrap lp-split lp-agents-split">
            <div className="lp-agents-coach">
              <Coach size={200} />
              <p className="lp-say">I&apos;m the face you talk to. Behind me, five agents share the work.</p>
            </div>
            <div>
              <h2>Five AI agents, one coach</h2>
              <p className="lp-lede">
                Every message goes to the agent that knows the job. The agents never do the maths: every amount comes from the
                app&apos;s money engine, worked out exactly.
              </p>
              <ul className="lp-agents">
                {AGENTS.map((a) => (
                  <li key={a.name}>
                    <span className="lp-agent-dot" style={{ color: a.tone }} aria-hidden="true"><Icon name={a.icon} size={22} /></span>
                    <div>
                      <b>{a.name}</b>
                      <p>{a.text}</p>
                    </div>
                  </li>
                ))}
              </ul>
            </div>
          </div>
        </section>

        <section id="trust" className="lp-band">
          <div className="lp-wrap">
            <h2>Numbers you can trust</h2>
            <ul className="lp-trust">
              {TRUST.map((t) => (
                <li key={t.title}>
                  <span className="lp-seal" aria-hidden="true" />
                  <div>
                    <h3>{t.title}</h3>
                    <p>{t.text}</p>
                  </div>
                </li>
              ))}
            </ul>
          </div>
        </section>

        <section id="demo" className="lp-band lp-ink">
          <div className="lp-wrap lp-demo">
            <div>
              <h2>Meet the demo family</h2>
              <p className="lp-lede">
                The Haddads: Sara and Omar, who run the family&apos;s money, and their kids Layla and Adam. Pick someone and look
                around. It is a shared demo, so you may see what others tried.
              </p>
            </div>
            <div className="lp-family">
              {FAMILY.map((f) => (
                <Link key={f.as} href={`/login?as=${f.as}`}>
                  <span className={`initial${f.kid ? " kid" : ""}`} aria-hidden="true">{f.name[0]}</span>
                  <span className="lp-family-who">
                    {f.name}
                    <small>{f.who}</small>
                  </span>
                </Link>
              ))}
            </div>
          </div>
        </section>
      </main>

      <footer className="lp-foot">
        <div className="lp-wrap lp-foot-in">
          <span className="lp-brand"><Logo size={28} />Family Finance Coach</span>
          <p>A prototype. Demo money only: no real payments and no bank connection. Information only, not financial advice.</p>
          <Link href="/login" className="link">Log in</Link>
        </div>
      </footer>
    </div>
  );
}

// The app's icon: a paper slip with the green Approved stamp.
function Logo({ size = 34 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 512 512" aria-hidden="true">
      <rect width="512" height="512" rx="112" fill="#17203a" />
      <path d="M136 150h240v228a20 20 0 0 1-20 20H156a20 20 0 0 1-20-20z" fill="#ffffff" />
      <g transform="rotate(-8 300 300)">
        <rect x="222" y="262" width="150" height="72" rx="12" fill="none" stroke="#1d6b3f" strokeWidth="12" />
        <path d="M262 298l20 20 40-40" fill="none" stroke="#1d6b3f" strokeWidth="14" strokeLinecap="round" strokeLinejoin="round" />
      </g>
    </svg>
  );
}

// The hero: the demo family's October plan, the goal it is saving for, a card bill to watch, and the coach.
function PlanScene() {
  return (
    <figure
      className="lp-scene"
      role="img"
      aria-label="The Haddads' October plan: AED 34,500 coming in, split into commitments, everyday, goals, investing, fun and giving. The summer trip is 30% saved, and Omar's Visa bill needs watching. The coach says AED 1,500 a month pays for the trip by June."
    >
      <div className="lp-plan" aria-hidden="true">
        <div className="lp-plan-top">
          <b>October plan</b>
          <span>AED 34,500 coming in</span>
        </div>
        <div className="stackbar lp-plan-bar">
          {BUCKETS.map((b) => <span key={b.name} style={{ flex: b.amount, background: b.color }} />)}
        </div>
        <div className="lp-plan-legend">
          {BUCKETS.map((b) => (
            <span key={b.name}>
              <i className="swatch" style={{ background: b.color }} />
              {b.name}
              <b>{b.amount.toLocaleString("en-US")}</b>
            </span>
          ))}
        </div>
        <div className="lp-plan-goal">
          <div className="row"><span>Summer trip</span><b>AED 3,600 of 12,000</b></div>
          <div className="bar"><span style={{ width: "30%" }} /></div>
        </div>
        <p className="warn-line">
          <Icon name="card" size={16} />
          <span>Omar&apos;s Visa bill on 5 Nov: AED 5,900 so far, AED 4,800 free by then.</span>
        </p>
      </div>
      <Coach size={124} wave className="lp-scene-coach" />
      <p className="lp-say lp-scene-say" aria-hidden="true">AED 1,500 a month, and the summer trip is paid for by June.</p>
    </figure>
  );
}

// The card check for one purchase, drawn: the bill on the due date against what is free by then.
// Figures are the demo card check (lib/sample.ts); the free bar's length is its share of the bill.
function CardBill() {
  const c = cardCheck.visa!;
  const freeShare = Math.round((c.freeByDueMinor / c.withMinor) * 1000) / 10;
  const fmt = (minor: number) => `AED ${(minor / 100).toLocaleString("en-US")}`;
  return (
    <div className="lp-bill" role="img" aria-label={`Omar's Visa bill on ${c.due} would be ${fmt(c.withMinor)} with this purchase. Only ${fmt(c.freeByDueMinor)} is free by then, ${fmt(c.shortMinor)} short.`}>
      <div aria-hidden="true">
        <div className="row lp-bill-row"><span><i className="swatch" style={{ background: "var(--s2)" }} />Card bill on {c.due}</span><b>{fmt(c.withMinor)}</b></div>
        <div className="lp-bill-track"><span style={{ width: "100%", background: "var(--s2)" }} /></div>
        <div className="row lp-bill-row"><span><i className="swatch" style={{ background: "var(--s1)" }} />Free in the bank by then</span><b>{fmt(c.freeByDueMinor)}</b></div>
        <div className="lp-bill-track">
          <span style={{ width: `${freeShare}%`, background: "var(--s1)" }} />
          <span className="short" style={{ left: `calc(${freeShare}% + 2px)`, width: `calc(${100 - freeShare}% - 2px)` }} />
        </div>
      </div>
      <p className="warn-line">
        <Icon name="warn" size={16} />
        <span>{fmt(c.shortMinor)} short. Pay from the bank instead, or wait until after the statement date.</span>
      </p>
    </div>
  );
}

// A small, still picture of the parent's Today screen, drawn with the app's own colours.
function PhoneToday() {
  return (
    <div className="lp-phone" role="img" aria-label="The parent's Today screen: AED 9,910 free this month, a budget alert, and a request waiting for a decision">
      <div className="lp-phone-screen" aria-hidden="true">
        <p className="lp-ph-hi">Hi Sara</p>
        <div className="lp-ph-hero">
          <span>Free this month</span>
          <b><small>AED</small> 9,910</b>
          <span>AED 2,140 free until pay day</span>
          <div className="lp-ph-quick"><i /><i /><i /></div>
        </div>
        <p className="lp-ph-h">Needs you <span>2</span></p>
        <div className="lp-ph-row"><i /><span>Entertainment is over budget</span></div>
        <div className="lp-ph-chit">
          <small>Layla asks</small>
          <b>Wireless headphones</b>
          <strong><small>AED</small> 300.00</strong>
          <div className="lp-ph-btns"><span>Approve</span><span>Decline</span></div>
        </div>
        <div className="lp-ph-tabs"><i className="on" /><i /><i /><i /><i /></div>
      </div>
    </div>
  );
}
