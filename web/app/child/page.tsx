"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { agent, aed, call } from "@/lib/api";
import { useLiveEvents } from "@/lib/live-stream";
import { notifyApi, Wallet } from "@/lib/push";
import { mealsApi, SavedWeek } from "@/lib/meals";
import { outingsApi, SavedPlan } from "@/lib/outings";
import { kids } from "@/lib/sample";
import { KidMonth, summaryApi } from "@/lib/summary";
import { Chit } from "../chit";
import { NotifyCard } from "../live-ui";
import { Loading, StackBar } from "../ui";
import { useSession } from "../use-session";
import { artFor, Avatar, BuddySays, Camel, GoalArt, GoalJar, SmallStar } from "./kid-ui";

// Kid Home: my goal jar, my demo wallet, my requests (a parent's answer lands here), the camel's nudge,
// the family's next outings and this week's dinners (once a parent saves a plan: never the prices), and where my
// pocket money went.
// Kids only ever see their own money. Requests, the wallet and the nudge are live: when a parent says yes, the
// answer and the new wallet balance arrive on this screen by themselves. My goal and stars come from my month page;
// the demo family keeps its sample goal plan and pocket money chart. Adam (6 to 9) gets bigger type and fewer words.
const THIS_MONTH = new Intl.DateTimeFormat("en-GB", { month: "long", timeZone: "Asia/Dubai" }).format(new Date());

export default function ChildHome() {
  const { me, requests, error, logout } = useSession("CHILD");
  const [nudge, setNudge] = useState<string | null>(null);
  const [wallet, setWallet] = useState<Wallet | null>(null);
  const [changed, setChanged] = useState(false);
  const [fun, setFun] = useState<SavedPlan[]>([]);
  const [month, setMonth] = useState<KidMonth | null>(null);
  // My goal and stars from my month (live: a yes that pays from my goal jar reaches the goal), and where my
  // pocket money went from the spend engine, the same numbers my parents see
  const [pocket, setPocket] = useState<PocketSummary | null>(null);
  const loadMonth = useCallback(() => {
    summaryApi.kid().then(setMonth).catch(() => {});
    call<PocketSummary>("/api/spend/summary").then(setPocket).catch(() => {});
  }, []);
  useEffect(() => {
    if (me) loadMonth();
  }, [me, loadMonth]);

  useEffect(() => {
    if (me) agent.nudge().then((n) => setNudge(n.nudge)).catch(() => {});
  }, [me]);

  const loadWallet = useCallback((flash = false) => {
    notifyApi.wallet().then((w) => {
      setWallet((old) => {
        if (flash && old && old.balance.minor !== w.balance.minor) setChanged(true);
        return w;
      });
    }).catch(() => {});
  }, []);
  useEffect(() => {
    if (me) loadWallet();
  }, [me, loadWallet]);
  useLiveEvents(["hello", "WALLET_CHANGED"], (e) => {
    loadWallet(e.kind === "WALLET_CHANGED");
    if (e.kind === "WALLET_CHANGED") loadMonth();
  }, !!me);
  const loadFun = useCallback(() => {
    outingsApi.saved().then(setFun).catch(() => {});
  }, []);
  useEffect(() => {
    if (me) loadFun();
  }, [me, loadFun]);
  useLiveEvents(["OUTINGS_PLANNED"], loadFun, !!me);
  const [dinners, setDinners] = useState<SavedWeek | null>(null);
  const loadDinners = useCallback(() => {
    mealsApi.saved().then(setDinners).catch(() => {});
  }, []);
  useEffect(() => {
    if (me) loadDinners();
  }, [me, loadDinners]);
  useLiveEvents(["MEALS_PLANNED"], loadDinners, !!me);
  useEffect(() => {
    if (!changed) return;
    const t = setTimeout(() => setChanged(false), 2400);
    return () => clearTimeout(t);
  }, [changed]);

  if (!me) return <Loading />;
  // My own goal and stars from the core. The demo kids keep their sample saving plan line until the goal is reached.
  const kid = me.demo ? kids[me.displayName] : undefined;
  const reached = (month?.goalPercent ?? 0) >= 100;
  const goal = month?.goalName && month.goalTarget
    ? { name: month.goalName, percent: month.goalPercent ?? 0, saved: month.goalSaved?.text ?? "AED 0", target: month.goalTarget.text,
        plan: reached ? "Goal reached! You saved for it, and it's yours." : kid?.plan ?? null }
    : null;
  const stars = month?.starsTotal ?? null;
  const pocketParts = pocket ? pocketPartsOf(pocket) : [];
  const young = me.ageBand === "CHILD_6_9";

  return (
    <div className={`stack${young ? " young" : ""}`}>
      <header className="kid-head">
        <Avatar name={me.displayName} />
        <div style={{ flex: 1 }}>
          <h1>Hi {me.displayName}</h1>
          {stars !== null && (
            <Link href="/child/stars" className="star-chip">
              <SmallStar /> {stars} stars
            </Link>
          )}
        </div>
        <button className="text" onClick={logout}>Log out</button>
      </header>
      {error && <p className="error" role="alert">{error}</p>}

      <CamelWelcome memberId={me.memberId} young={young} />

      {goal && (
        <section className="card goal-card">
          <GoalJar percent={goal.percent} size={young ? 150 : 128} />
          <div className="goal-info">
            <p className="label">My goal</p>
            <div className="goal-name">
              <GoalArt art={artFor(goal.name)} size={44} />
              <span>{goal.name}</span>
            </div>
            <p className="goal-pct">{goal.percent}%</p>
            <p className="muted">
              {goal.saved} of {goal.target}
            </p>
          </div>
          {goal.plan && <p className="goal-plan">{goal.plan}</p>}
        </section>
      )}


      <Link href="/child/month" className="card month-link">
        <Camel size={young ? 64 : 56} />
        <span style={{ flex: 1 }}>
          <span className="card-title" style={{ display: "block" }}>My {THIS_MONTH} with the camel</span>
          <span className="muted">My goal, my stars and how my month went</span>
        </span>
        <span aria-hidden="true">›</span>
      </Link>

      {(wallet || kid) && (
        <section className={`card wallet${changed ? " changed" : ""}`} aria-live="polite">
          <Camel size={young ? 84 : 72} />
          <div style={{ flex: 1 }}>
            <p className="muted">My wallet</p>
            <p className="wallet-amount">{wallet ? aed(wallet.balance.minor, { cents: true }) : aed(kid!.savedMinor, { cents: true })}</p>
            {changed && wallet?.recent[0] && <p className="muted">{wallet.recent[0].description}</p>}
          </div>
          <span className="demo-tag">DEMO MONEY</span>
        </section>
      )}

      {fun.length > 0 && (
        <section className="card" style={{ display: "grid", gap: 10 }} aria-label="Family fun coming up">
          <div className="row" style={{ padding: 0, alignItems: "center" }}>
            <span>
              <p className="card-title" style={{ margin: 0 }}>Family fun coming up</p>
              <p className="muted" style={{ margin: 0 }}>{fun.map((f) => f.monthLabel).join(" and ")}</p>
            </span>
            <Camel size={young ? 64 : 56} />
          </div>
          <ul className="fun-list">
            {fun.flatMap((f) => f.picks.map((p) => (
              <li key={f.month + p.key}>
                <span className="fun-dot" aria-hidden="true" />
                <b>{p.name}</b>
                {!young && <span className="fun-about">{p.about}</span>}
              </li>
            )))}
          </ul>
        </section>
      )}

      {dinners && (
        <section className="card" style={{ display: "grid", gap: 10 }} aria-label="Dinners this week">
          <div className="row" style={{ padding: 0, alignItems: "center" }}>
            <p className="card-title" style={{ margin: 0 }}>Dinners this week</p>
            <Camel size={young ? 64 : 56} />
          </div>
          <ul className="meal-list">
            {dinners.nights.filter((n) => n.date >= today()).slice(0, young ? 3 : 7).map((n, i) => (
              <li key={n.date} className={i === 0 && n.date === today() ? "today" : undefined}>
                <span className="muted">{n.date === today() ? "Tonight" : n.label.split(" ")[0]}</span>
                <b>{n.kind === "COOK" ? n.name : `Order in: ${n.name}`}</b>
              </li>
            ))}
          </ul>
        </section>
      )}

      <section>
        <h2 className="first">My requests</h2>
        {requests.length === 0 && <p className="empty">When you ask for something, it shows up here with your parent's answer.</p>}
        {requests.map((r) => (
          <Chit key={r.id} request={r} small />
        ))}
      </section>

      {nudge && (
        <BuddySays>
          <p>{nudge}</p>
          <Link href="/child/ask?do=bought" className="link">Tell me in Ask</Link>
        </BuddySays>
      )}

      {pocket && pocket.pocketMoney.minor > 0 && (
        <section className="card" style={{ display: "grid", gap: 10 }}>
          <div>
            <p className="card-title">Where my pocket money went</p>
            <p className="muted">{THIS_MONTH} so far · {aed(pocket.pocketMoney.minor)} from your parents</p>
          </div>
          <StackBar parts={pocketParts} label={pocketParts.map((p) => `${p.label} ${aed(p.amountMinor)}`).join(", ")} />
          {pocket.categories[0] && (
            <p style={{ fontSize: 14 }}>
              Most went on {pocket.categories[0].label}.
              {pocket.categories[0].category === "SCHOOL_CANTEEN" ? " That's normal, and logging it is what makes this picture right." : ""}
            </p>
          )}
        </section>
      )}

      <NotifyCard parent={false} kid />
    </div>
  );
}

/** Today's date in the UAE, as the core writes dates (2026-10-27). */
function today(): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Dubai" }).format(new Date());
}

// The camel's hello on a kid's first visit: one line per tab, then it goes. Kept per browser: it is only a hint.
function CamelWelcome({ memberId, young }: { memberId: string; young: boolean }) {
  const key = `ffa.welcome.${memberId}`;
  const [show, setShow] = useState(false);
  useEffect(() => {
    try {
      setShow(localStorage.getItem(key) !== "seen");
    } catch {
      setShow(false);
    }
  }, [key]);
  if (!show) return null;
  const close = () => {
    setShow(false);
    try {
      localStorage.setItem(key, "seen");
    } catch {
      /* fine: it shows again next time */
    }
  };
  return (
    <section className="card camel-welcome" aria-label="Hello from the camel">
      <Camel size={young ? 84 : 72} />
      <div>
        <p className="card-title" style={{ margin: 0 }}>Hello, I&apos;m your camel</p>
        <ul className="plain-list">
          <li><b>Home</b> is your goal and your wallet.</li>
          <li><b>Ask</b> is where you tell me what you bought, or what you want.</li>
          <li><b>Stars</b> come for keeping track and saving.</li>
        </ul>
        <button type="button" onClick={close} style={{ marginTop: 10 }}>Got it</button>
      </div>
    </section>
  );
}

/** The parts of GET /api/spend/summary the kid's pocket money chart uses. */
interface PocketSummary {
  pocketMoney: { minor: number };
  total: { minor: number };
  categories: { category: string; label: string; total: { minor: number } }[];
}

const FOOD = ["SCHOOL_CANTEEN", "SNACKS", "SUGARY_DRINKS", "EATING_OUT"];
const FUN = ["IN_GAME_PURCHASES", "TOYS_AND_HOBBIES"];

/** Food, fun, other things, and what is not spent yet: adds up to the pocket money paid this month. */
function pocketPartsOf(p: PocketSummary) {
  const sum = (keys: string[] | null) => p.categories
    .filter((c) => (keys ? keys.includes(c.category) : !FOOD.includes(c.category) && !FUN.includes(c.category)))
    .reduce((t, c) => t + c.total.minor, 0);
  return [
    { label: "Food", amountMinor: sum(FOOD), color: "var(--s1)" },
    { label: "Fun", amountMinor: sum(FUN), color: "var(--s2)" },
    { label: "Other things", amountMinor: sum(null), color: "var(--s3)" },
    { label: "Not spent yet", amountMinor: Math.max(0, p.pocketMoney.minor - p.total.minor), color: "var(--s4)" },
  ].filter((x) => x.amountMinor > 0);
}
