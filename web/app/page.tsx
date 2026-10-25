import type { Metadata } from "next";
import Link from "next/link";
import { BigStar, Camel, GoalArt, GoalJar, Race } from "./child/kid-ui";
import { Coach } from "./coach";
import { SaveCalculator } from "./landing-parts";
import { Icon } from "./ui";
import "./landing.css";

// The landing page at /: what the app does and why saving matters, for anyone not signed in yet.
// Every feature here is one the app has today (the Shopper says it is coming), and every figure is the demo
// family's, from the seed or the sample data. The app itself starts at /login.
export const metadata: Metadata = {
  title: "Family Finance Coach",
  description:
    "An accountant for parents and a money coach for kids. Plan the month, check every request against your family's rules, and save for what matters. A prototype with demo money.",
};

const PARENT_GROUPS: { title: string; items: { icon: Parameters<typeof Icon>[0]["name"]; title: string; text: string }[] }[] = [
  {
    title: "See the month",
    items: [
      { icon: "today", title: "What is free, today", text: "This month and until pay day, after rent, school fees and bills are put aside." },
      { icon: "plan", title: "Money buckets", text: "Every dirham of pay gets a job: commitments, everyday, goals, fun and giving." },
      { icon: "calendar", title: "The next 12 months", text: "Tight months, like the one with a rent cheque, show up a year ahead." },
      { icon: "money", title: "Family wealth", text: "Bank, cash and gold, minus cards and loans, as one number." },
    ],
  },
  {
    title: "Decide together",
    items: [
      { icon: "family", title: "Requests with a stamp", text: "The kids ask, you approve or decline. A yes is paid from their demo wallet." },
      { icon: "card", title: "Card check", text: "Before you pay by card, it checks the bill will be covered on its due date." },
      { icon: "warn", title: "Family rules in your own words", text: "“No peanuts for Adam.” “Anything over AED 100 needs me.” Checked on every request." },
    ],
  },
  {
    title: "Keep the books",
    items: [
      { icon: "ask", title: "Ask the accountant", text: "“Where did Layla’s money go this month?” Plain answers, with exact totals." },
      { icon: "upload", title: "Receipts and statements", text: "Upload a photo, a PDF or a bank’s CSV. Each line is matched, so nothing counts twice." },
      { icon: "receipt", title: "Odd payments found", text: "Double charges, and monthly payments nobody put on the list." },
    ],
  },
  {
    title: "Stay ahead",
    items: [
      { icon: "warn", title: "Budgets and alerts", text: "Over budget, heading over or above usual, while there is still time to act." },
      { icon: "star", title: "Ways to save", text: "Up to three ideas a month, each with the numbers behind it." },
      { icon: "plan", title: "The goal’s pace", text: "Relaxed, steady or faster, checked against every month’s spare cash." },
      { icon: "bell", title: "Notifications", text: "On your phone and by email, with a plan for the week every Sunday morning." },
    ],
  },
];

const AGENTS = [
  { name: "Bookkeeper", tone: "var(--s1)", icon: "receipt" as const, text: "Reads receipts and statements, and logs what the kids say they bought." },
  { name: "Gatekeeper", tone: "var(--approved)", icon: "warn" as const, text: "Checks every request against the family rules and the family’s money." },
  { name: "Planner", tone: "var(--s2)", icon: "plan" as const, text: "Makes this month’s plan, the bucket split and the words for ways to save." },
  { name: "Coach", tone: "var(--kid-sun-deep)", icon: "star" as const, text: "Talks with the kids about their goals. Savings challenges and lessons are coming soon." },
  { name: "Shopper", tone: "var(--cancelled)", icon: "money" as const, text: "Coming soon: comparing prices, a weekly meal plan and a shopping list." },
];

const TRUST = [
  { title: "Exact, not guessed", text: "Every amount is worked out by the app’s money engine in whole fils. The AI only finds the words." },
  { title: "You confirm first", text: "Anything the AI fills in stays a draft until a parent taps Confirm." },
  { title: "Kids can’t approve themselves", text: "A child can never approve their own request. It is checked in the app and again in the database." },
  { title: "Little data about children", text: "A display name and an age band, with a parent’s consent. No birth date, email or phone." },
  { title: "Allergens: warned, never certified", text: "Food that may hold an allergen is flagged. Always check the pack label." },
  { title: "No real money moves", text: "The wallet holds demo money only, and no bank is connected." },
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
            <a href="#why">Why save</a>
            <a href="#parents">Parents</a>
            <a href="#kids">Kids</a>
            <a href="#trust">Trust</a>
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
                Family Finance Coach is an accountant for parents and a money coach for kids. It plans your month, checks every
                request against your family&apos;s rules, and helps everyone save for what matters.
              </p>
              <div className="lp-cta">
                <a href="#demo" className="btn">Try the demo family</a>
                <Link href="/login" className="btn quiet">Log in</Link>
              </div>
              <p className="lp-small">A prototype with demo money. No bank connection and no real payments.</p>
            </div>

            <figure
              className="lp-scene"
              role="img"
              aria-label="Layla asks for wireless headphones for AED 300. A parent's Approved stamp lands on the request, and the coach suggests a saving plan."
            >
              <div className="chit lp-chit" aria-hidden="true">
                <p className="chit-from">Layla asks</p>
                <p className="chit-title">Wireless over-ear headphones</p>
                <p className="chit-amount"><span className="cur">AED</span>300.00</p>
                <span className="stamp approved landed lp-stamp">Approved</span>
                <p className="chit-summary">Layla has AED 120 saved toward them.</p>
              </div>
              <Coach size={132} wave className="lp-scene-coach" />
              <p className="lp-say lp-scene-say" aria-hidden="true">Save AED 20 a week and do 2 chores, and they&apos;re yours by 20 November.</p>
            </figure>
          </div>
        </section>

        <section id="why" className="lp-band lp-ink">
          <div className="lp-wrap lp-split">
            <div>
              <h2>Saving is a habit, not a lump sum.</h2>
              <p className="lp-lede">
                Money worries often start small: a bill nobody saw coming, a subscription nobody uses, a card bill that lands
                before pay day. A plan the whole family can see stops them early.
              </p>
              <ul className="lp-points">
                <li>
                  <h3>Know what is free before you spend</h3>
                  <p>Rent, school fees and bills are put aside first, so the number you see is money you can really use.</p>
                </li>
                <li>
                  <h3>Big bills stop being surprises</h3>
                  <p>A rent cheque or a term&apos;s school fees is saved for month by month, and tight months show up early.</p>
                </li>
                <li>
                  <h3>Small leaks add up</h3>
                  <p>A forgotten AED 35 subscription is AED 420 a year. The app finds the ones nobody listed.</p>
                </li>
                <li>
                  <h3>Kids learn by doing</h3>
                  <p>Asking, waiting and watching a goal jar fill teaches more than a lecture.</p>
                </li>
              </ul>
            </div>
            <SaveCalculator />
          </div>
        </section>

        <section id="how" className="lp-band">
          <div className="lp-wrap">
            <h2>How it works</h2>
            <ol className="lp-steps">
              <li>
                <span className="lp-step-n" aria-hidden="true">1</span>
                <h3>Tell it about your money</h3>
                <p>Type your pay and your bills in your own words, or upload a receipt or a bank statement. Nothing counts until you confirm it.</p>
                <div className="lp-step-art draft" aria-hidden="true">
                  <b>Omar&apos;s salary</b>
                  <span>AED 28,000 a month, on the 25th</span>
                  <span className="lp-mini-btn">Confirm</span>
                </div>
              </li>
              <li>
                <span className="lp-step-n" aria-hidden="true">2</span>
                <h3>Get a plan for the month</h3>
                <p>Rent, school fees and bills are put aside first. You see what is free this month, and until pay day.</p>
                <div className="lp-step-art" aria-hidden="true">
                  <div className="stackbar">
                    {[["var(--s1)", 17200], ["var(--s2)", 7390], ["var(--s3)", 6500], ["var(--s4)", 1000], ["var(--s5)", 1910], ["var(--s6)", 500]].map(([c, n]) => (
                      <span key={c} style={{ flex: n, background: c as string }} />
                    ))}
                  </div>
                  <span>Commitments, everyday, goals, fun and giving</span>
                </div>
              </li>
              <li>
                <span className="lp-step-n" aria-hidden="true">3</span>
                <h3>Kids ask, you stamp</h3>
                <p>The kids ask on their own phones. The app checks your family rules and your money, then you approve or decline with one tap.</p>
                <div className="lp-step-art lp-step-stamps" aria-hidden="true">
                  <span className="stamp approved">Approved</span>
                  <span className="stamp blocked">Blocked<small>by a family rule</small></span>
                </div>
              </li>
            </ol>
          </div>
        </section>

        <section id="parents" className="lp-band lp-paper2">
          <div className="lp-wrap">
            <div className="lp-head">
              <h2>The family&apos;s accountant</h2>
              <p className="lp-lede">For parents. Everything the family pays, plans and decides, in one place.</p>
            </div>
            <div className="lp-parents">
              <PhoneToday />
              <div className="lp-groups">
                {PARENT_GROUPS.map((g) => (
                  <div className="lp-group" key={g.title}>
                    <h3>{g.title}</h3>
                    <ul>
                      {g.items.map((i) => (
                        <li key={i.title}>
                          <span className="lp-ic" aria-hidden="true"><Icon name={i.icon} size={20} /></span>
                          <div>
                            <b>{i.title}</b>
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
          <div className="lp-wrap">
            <div className="lp-head">
              <h2>A money coach for the kids</h2>
              <p className="lp-lede">
                For kids. They see only their own money, in big friendly pictures, and never a lecture. Younger kids get bigger
                type and fewer words.
              </p>
            </div>
            <div className="lp-stickers">
              <article className="lp-sticker">
                <div className="lp-sticker-art"><GoalJar percent={40} size={96} /></div>
                <h3>A goal jar that fills</h3>
                <p>Layla is 40% of the way to her headphones, and every dirham she saves shows.</p>
              </article>
              <article className="lp-sticker">
                <div className="lp-sticker-art"><Camel size={96} /></div>
                <h3>A wallet they can see</h3>
                <p>Pocket money in demo money. When a parent says yes, it is paid from here.</p>
              </article>
              <article className="lp-sticker">
                <div className="lp-sticker-art lp-art-row">
                  <Coach size={64} />
                  <span className="lp-say lp-say-small">12 dirhams shawarma</span>
                </div>
                <h3>Tell the coach what you bought</h3>
                <p>In their own words. It keeps their picture of the month right.</p>
              </article>
              <article className="lp-sticker">
                <div className="lp-sticker-art lp-art-row">
                  <GoalArt art="headphones" size={64} />
                  <span className="lp-say lp-say-small">AED 20 a week and 2 chores</span>
                </div>
                <h3>Ask with a plan</h3>
                <p>Instead of just a price, the coach helps them bring their parents a plan.</p>
              </article>
              <article className="lp-sticker">
                <div className="lp-sticker-art"><BigStar count={14} size={96} /></div>
                <h3>Stars and stickers</h3>
                <p>Earned for keeping track and saving. Stars are not money, and nobody loses them.</p>
              </article>
              <article className="lp-sticker">
                <div className="lp-sticker-art lp-art-race">
                  <Race runners={[{ name: "Adam", percent: 45 }, { name: "Layla", percent: 40 }]} me="" />
                </div>
                <h3>Saver of the Month</h3>
                <p>Scored on how far each kid got toward their own goal, so a 9-year-old and a 13-year-old race fairly.</p>
              </article>
            </div>
          </div>
        </section>

        <section id="agents" className="lp-band">
          <div className="lp-wrap lp-split lp-agents-split">
            <div className="lp-agents-coach">
              <Coach size={220} />
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

        <section id="trust" className="lp-band lp-paper2">
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
                The Haddads: Sara and Omar, Layla who is saving for headphones, and Adam who has a peanut allergy. Pick someone
                and look around. It is a shared demo, so you may see what others tried.
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

// A small, still picture of the parent's Today screen, drawn with the app's own colours.
function PhoneToday() {
  return (
    <div className="lp-phone" role="img" aria-label="The parent's Today screen: AED 9,910 free this month, and Layla's request waiting for a decision">
      <div className="lp-phone-screen" aria-hidden="true">
        <p className="lp-ph-hi">Hi Sara</p>
        <div className="lp-ph-hero">
          <span>Free this month</span>
          <b><small>AED</small> 9,910</b>
          <span>AED 2,140 free until pay day</span>
          <div className="lp-ph-quick"><i /><i /><i /></div>
        </div>
        <p className="lp-ph-h">Needs you <span>2</span></p>
        <div className="lp-ph-chit">
          <small>Layla asks</small>
          <b>Wireless headphones</b>
          <strong><small>AED</small> 300.00</strong>
          <div className="lp-ph-btns"><span>Approve</span><span>Decline</span></div>
        </div>
        <div className="lp-ph-row"><i /><span>Eating out is heading over budget</span></div>
        <p className="lp-ph-h">Coming up</p>
        <div className="lp-ph-list">
          <span>DEWA, Sun 26 Oct<b>AED 850</b></span>
          <span>du, Sat 1 Nov<b>AED 450</b></span>
        </div>
        <div className="lp-ph-tabs"><i className="on" /><i /><i /><i /><i /></div>
      </div>
    </div>
  );
}
