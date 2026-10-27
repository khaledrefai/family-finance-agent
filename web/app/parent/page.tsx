"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { aed, api, MemberView, plain } from "@/lib/api";
import { advice as adviceApi, BudgetMonth, CardCheck, core, dayLongMonth, Goal, useAccounts, useLive, useMoney, WaysToSave } from "@/lib/live";
import { advice, familyGoal, waysToSave } from "@/lib/sample";
import { Chit } from "../chit";
import { BellLink } from "../live-ui";
import { notifyApi, WeekPlan } from "@/lib/push";
import { useLiveEvents } from "@/lib/live-stream";
import { CardWarning, Icon, Loading, Masthead, PayFrom, Progress, TRUST, WhySheet } from "../ui";
import { useSession } from "../use-session";

// Today answers one question: does anything need me? In this order: what is free this month (with the three
// quick actions), Needs you (waiting requests, then only the alerts that need a look), the next three bills,
// one way to save at a time, the family goal in one line, and what was decided (folded until asked for).
// A covered bill stays in Coming up; colour only marks what is short or over.
// The Waiting list updates live: when a kid asks on their phone, it lands here without a reload (week 5).
// Requests, bills, cards, free cash and the family goal come from the core's money engine.
// Overspend alerts come from the budget engine, and "Ways to save" from the engine with the Planner's words.
// The one piece of advice is still a sample figure.
// The month the summary link names: today's month in the UAE, like the core's clock.
const THIS_MONTH = new Intl.DateTimeFormat("en-GB", { month: "long", timeZone: "Asia/Dubai" }).format(new Date());

export default function TodayPage() {
  const { me, requests, error, setError, reload, logout } = useSession("PARENT");
  const [members, setMembers] = useState<MemberView[]>([]);
  const [justDecided, setJustDecided] = useState<string | null>(null);
  const [adviceState, setAdviceState] = useState<"open" | "accepted" | "later">("open");
  // Ways to save: each tip can be dismissed or snoozed to next month. Live, the core keeps that for the family.
  // The advice and the tips show one at a time; Next idea moves along.
  const [tipState, setTipState] = useState<Record<string, "dismissed" | "snoozed">>({});
  const [snoozed, setSnoozed] = useState(false);
  const [ideaAt, setIdeaAt] = useState(0);
  const [ideaWhy, setIdeaWhy] = useState<Idea | null>(null);
  // Which account a parent would pay a request from. Sample default until requests carry it.
  const [payFrom, setPayFrom] = useState<Record<string, string>>({});
  const [allDecided, setAllDecided] = useState(false);
  const [freeWhy, setFreeWhy] = useState(false);

  const { monthly, bills, raw: cashflow, live: moneyLive } = useMoney(!!me);
  const { accounts, live: accountsLive } = useAccounts(!!me);
  const { data: goals } = useLive<Goal[] | null>(() => core.goals(), null, !!me);
  const { data: budgetMonth } = useLive<BudgetMonth | null>(() => adviceApi.budgets(), null, !!me);
  const { data: ways, live: waysLive, setData: setWays } = useLive<WaysToSave | null>(() => adviceApi.waysToSave(), null, !!me);
  const { data: week, setData: setWeek } = useLive<WeekPlan | null>(() => notifyApi.week(), null, !!me);
  // A new bill or budget alert, or a wallet payment, can change this week's figures
  useLiveEvents(["BILL_DUE", "CARD_DUE", "OVERSPEND", "WEEKLY_PLAN", "REQUEST_DECIDED"], () => {
    notifyApi.week().then(setWeek).catch(() => {});
  }, !!me);

  useEffect(() => {
    if (me) api.members().then(setMembers).catch(() => {});
  }, [me]);

  const nameOf = (id: string) => members.find((m) => m.id === id)?.displayName ?? "Someone";

  async function decide(id: string, decision: "approve" | "reject") {
    try {
      await api.decide(id, decision);
      setJustDecided(id);
      await reload();
    } catch (err) {
      setError((err as Error).message);
    }
  }

  async function tip(key: string, action: "dismissed" | "snoozed") {
    if (action === "snoozed") setSnoozed(true);
    if (!waysLive) {
      setTipState((s) => ({ ...s, [key]: action }));
      return;
    }
    try {
      setWays(await (action === "dismissed" ? adviceApi.dismiss(key) : adviceApi.snooze(key)));
    } catch (err) {
      setError((err as Error).message);
    }
  }

  if (!me) return <Loading />;

  // A real family never sees the demo family's sample advice, tips or goal
  const tips = (waysLive && ways
    ? ways.tips
    : me.demo ? waysToSave.tips.map((t) => ({ key: t.id, title: t.title, text: t.text, why: t.why })) : []
  ).filter((t) => !tipState[t.key]);
  const tipMonth = waysLive && ways ? ways.monthLabel : waysToSave.month;
  const ideas: Idea[] = [
    ...(adviceState === "open" && me.demo ? [{ key: "advice", title: advice.title, text: advice.text, why: advice.why, advice: true }] : []),
    ...tips.map((t) => ({ key: t.key, title: t.title, text: t.text, why: t.why })),
  ];
  const idea = ideas.length ? ideas[ideaAt % ideas.length] : null;
  const pending = requests.filter((r) => r.status === "PENDING");
  const decided = requests.filter((r) => r.status !== "PENDING" && r.id !== justDecided);
  const fresh = requests.find((r) => r.id === justDecided);
  const nextBill = bills[0];
  const tightCard = accounts.find((a) => a.bill && !a.bill.ok);
  const goal = goals?.find((g) => g.tier === "GOOD_LIFE") ?? (me.demo ? undefined : goals?.[0]);
  const defaultCard = accounts.find((a) => a.kind === "CREDIT_CARD")?.id ?? "visa";

  // Only what needs a look: a bill the reserve won't cover, a card bill that won't be covered, budget alerts.
  const alerts: { key: string; href: string; tone: "warn" | "bad"; icon: "calendar" | "card" | "warn"; title: string; text?: string }[] = [];
  if (moneyLive && nextBill?.state === "short") {
    alerts.push({
      key: "bill", href: "/parent/money#bills", tone: (nextBill.daysLeft ?? 0) > 14 ? "warn" : "bad", icon: "calendar",
      title: `${nextBill.name} due ${nextBill.daysLeft === 1 ? "tomorrow" : `in ${nextBill.daysLeft} days`}`,
      text: `${aed(nextBill.amountMinor)} still to put aside before ${nextBill.due}. Money shows the month by month plan.`,
    });
  }
  if (tightCard?.bill) {
    alerts.push({
      key: "card", href: "/parent/money/accounts", tone: "bad", icon: "card",
      title: `${tightCard.label} bill due ${tightCard.bill.due}`,
      text: `${aed(tightCard.bill.soFarMinor)} so far, ${aed(tightCard.bill.freeByDueMinor)} free by then.`,
    });
  }
  budgetMonth?.alerts.forEach((b) => alerts.push({
    key: b.category, href: "/parent/money/budgets", tone: b.state === "OVER" ? "bad" : "warn", icon: "warn", title: b.alert ?? b.label,
  }));
  const needsYou = pending.length + alerts.length;
  // Without a bank balance typed in, "free until pay day" would only count bills against nothing, so it goes
  // below zero. Then Today leads with the month and asks for the balance instead.
  const noBalance = accountsLive && !accounts.some((a) => a.kind === "BANK" || a.kind === "CASH");

  return (
    <div className="stack">
      <Masthead
        title={`Hi ${me.displayName}`}
        sub={me.familyName}
        action={
          <span className="head-actions">
            <BellLink />
            <button className="text" onClick={logout}>Log out</button>
          </span>
        }
      />
      {error && <p className="error" role="alert">{error}</p>}

      <section className="hero" aria-label="Free money">
        {noBalance ? (
          <>
            <p className="hero-label">Free this month, after bills and budgets</p>
            <p className="hero-amount"><span className="cur">AED</span>{plain(monthly.freeMinor)}</p>
            <p className="hero-sub">
              <Link href="/parent/money/accounts" className="hero-link">Type today&apos;s bank balance</Link> to see what is free until pay day.
            </p>
          </>
        ) : (
          <>
            <p className="hero-label">Free to spend until pay day</p>
            <p className="hero-amount"><span className="cur">AED</span>{plain(monthly.freeUntilPaydayMinor)}</p>
            <p className="hero-sub">
              {monthly.payday ? `Pay day is ${monthly.payday}. ` : ""}After bills, budgets and what is set aside for the big ones.
            </p>
          </>
        )}
        <button type="button" className="hero-link text" onClick={() => setFreeWhy(true)}>What does free mean?</button>
        <nav className="quick" aria-label="Quick actions">
          <Link href="/parent/money/add"><Icon name="plus" />Add spending</Link>
          <Link href="/parent/money#uploads"><Icon name="upload" />Upload receipt</Link>
          <Link href="/parent/money/compare"><Icon name="tag" />Compare prices</Link>
          <Link href="/parent/ask"><Icon name="ask" />Ask</Link>
        </nav>
      </section>

      {!me.demo && (
        <StartHere
          familyId={me.familyId}
          hasBalance={accountsLive && !noBalance}
          hasSpending={(budgetMonth?.spent.minor ?? 0) > 0}
          kids={members.filter((m) => m.role === "CHILD").map((m) => m.displayName)}
        />
      )}

      <section>
        <h2 className="first section-head">
          Needs you
          {needsYou > 0 && <span className="count">{needsYou}</span>}
        </h2>
        {fresh && <Chit request={fresh} from={`${nameOf(fresh.requesterId)} asked`} landed />}
        {needsYou === 0 && <p className="empty">Nothing needs you right now. When one of the kids asks for something, it lands here.</p>}
        {pending.map((r) => {
          const from = payFrom[r.id] ?? defaultCard;
          return (
            <Chit key={r.id} request={r} from={`${nameOf(r.requesterId)} asks`} stamp={false}
              corner={<Link href={`/parent/why?id=${r.id}`} className="link">Why this?</Link>}>
              {r.requesterId !== me.memberId && r.childPays ? (
                <>
                  <p className="chit-note">On a yes, it is paid from {r.childPays} (demo money).</p>
                  <div className="chit-actions">
                    <button onClick={() => decide(r.id, "approve")}>Approve</button>
                    <button className="decline" onClick={() => decide(r.id, "reject")}>Decline</button>
                  </div>
                </>
              ) : r.requesterId !== me.memberId ? (
                <>
                  <PayFrom value={from} accounts={accounts} onChange={(id) => setPayFrom((p) => ({ ...p, [r.id]: id }))} />
                  {accountsLive
                    ? <LiveCardWarning accountId={from} amountMinor={r.amountMinor} isCard={accounts.find((a) => a.id === from)?.kind === "CREDIT_CARD"} />
                    : <CardWarning accountId={from} compact />}
                  <div className="chit-actions">
                    <button onClick={() => decide(r.id, "approve")}>Approve</button>
                    <button className="decline" onClick={() => decide(r.id, "reject")}>Decline</button>
                  </div>
                </>
              ) : (
                <p className="chit-note">You asked for this, so the other parent decides.</p>
              )}
            </Chit>
          );
        })}
        {alerts.length > 0 && (
          <div className="list">
            {alerts.map((a) => (
              <Link key={a.key} href={a.href} className={`list-item tone-${a.tone}`}>
                <span className="dot-icon" aria-hidden="true"><Icon name={a.icon} size={18} /></span>
                <span className="body">
                  <b>{a.title}</b>
                  {a.text && <span className="muted">{a.text}</span>}
                </span>
                <span className="chev" aria-hidden="true">›</span>
              </Link>
            ))}
          </div>
        )}
      </section>

      <section className="card" style={{ display: "grid", gap: 6 }}>
        <div className="row" style={{ padding: 0 }}>
          <span className="card-title">Coming up</span>
          {week && <span className="muted">{week.label}</span>}
        </div>
        {week && !noBalance && <p style={{ fontSize: 15 }}>{week.headline}</p>}
        <div className="rows">
          {bills.slice(0, 3).map((b) => (
            <div className="row" key={b.name + b.due}>
              <span>{b.name}<span className="muted" style={{ display: "block" }}>{b.due}</span></span>
              <span style={{ textAlign: "right" }}>
                <b>{aed(b.amountMinor, { cents: b.amountMinor % 100 !== 0 })}</b>
                <span className={`muted${b.state === "short" ? " over" : ""}`} style={{ display: "block" }}>{b.state === "short" ? "Short" : "Reserved"}</span>
              </span>
            </div>
          ))}
        </div>
        {week && week.slowDown.length > 0 && (
          <>
            <p className="label" style={{ margin: "6px 0 0" }}>Go easy on these this week</p>
            <div className="rows">
              {week.slowDown.map((b) => (
                <div className="row" key={`slow-${b.label}`}>
                  <span>{b.label}<span className="muted" style={{ display: "block" }}>{b.note}</span></span>
                  <b>{b.amount.minor === 0 ? "Pause" : b.amount.text}</b>
                </div>
              ))}
            </div>
          </>
        )}
        <Link href="/parent/money#bills" className="link">All bills</Link>
      </section>

      {(idea || adviceState === "accepted") && (
        <section className="card idea" aria-live="polite">
          <div className="row" style={{ padding: 0 }}>
            <span className="card-title">Ways to save</span>
            <span className="muted">{ideas.length > 1 ? `${(ideaAt % ideas.length) + 1} of ${ideas.length}` : tipMonth}</span>
          </div>
          {adviceState === "accepted" && (
            <p className="done-line" role="status">Done. Entertainment is AED 600 a month for November and December. You can change it in Money, Budgets.</p>
          )}
          {snoozed && <p className="muted" role="status">Snoozed tips come back next month.</p>}
          {idea && (
            <>
              <p className="label" style={{ margin: 0 }}>{idea.title}</p>
              <p>{idea.text}</p>
              {idea.advice ? (
                <div className="actions" style={{ marginTop: 2 }}>
                  <button onClick={() => setAdviceState("accepted")}>Accept</button>
                  <button className="quiet" onClick={() => setAdviceState("later")}>Not now</button>
                </div>
              ) : null}
              <div className="tip-actions">
                <button type="button" className="text" onClick={() => setIdeaWhy(idea)}>Why this?</button>
                {!idea.advice && <button type="button" className="text" onClick={() => tip(idea.key, "snoozed")}>Snooze a month</button>}
                {!idea.advice && <button type="button" className="text" onClick={() => tip(idea.key, "dismissed")}>Dismiss</button>}
                {ideas.length > 1 && <button type="button" className="text" onClick={() => setIdeaAt((i) => i + 1)}>Next idea</button>}
              </div>
              {idea.key === "outings" && <Link href="/parent/plan/outings" className="link">Plan cheaper outings ›</Link>}
              <p className="trust">Information only, not financial advice.</p>
            </>
          )}
        </section>
      )}

      {(goal || me.demo) && <Link href="/parent/plan" className="card" style={{ display: "grid", gap: 8 }}>
        <div className="row" style={{ padding: 0 }}>
          <span className="card-title">{goal?.name ?? familyGoal.name}</span>
          <b>{goal?.percent ?? familyGoal.percent}% ›</b>
        </div>
        <Progress percent={goal?.percent ?? familyGoal.percent} label={`${goal?.percent ?? familyGoal.percent} percent saved`} />
        <span className="muted">
          {goal
            ? <>{goal.saved.text} of {goal.target.text.replace("AED ", "")} saved{goal.fullBy ? `, full by ${goal.fullBy}` : ""}</>
            : <>AED 3,600 of 12,000 saved, on track for {familyGoal.onTrackFor}</>}
        </span>
      </Link>}

      {decided.length > 0 && (
        <section>
          <button type="button" className="disclosure" aria-expanded={allDecided} onClick={() => setAllDecided((v) => !v)}>
            <span className="section-head">Decided <span className="count calm">{decided.length}</span></span>
            <span className="muted">{allDecided ? "Hide" : "Show"}</span>
          </button>
          {allDecided && (
            <div style={{ marginTop: 12 }}>
              {decided.map((r) => (
                <Chit key={r.id} request={r} from={nameOf(r.requesterId)} small />
              ))}
            </div>
          )}
        </section>
      )}

      <Link href="/parent/summary" className="link">See {THIS_MONTH}&apos;s family summary so far</Link>
      <p className="trust">{TRUST}</p>
      {freeWhy && (
        <WhySheet
          title="What free means"
          groups={[
            {
              heading: `The whole of ${THIS_MONTH}`,
              rows: [
                { label: "Coming in", value: aed(monthly.incomeMinor) },
                { label: "Bills and reserves", value: aed(-monthly.commitmentsMinor) },
                { label: "Everyday budgets", value: aed(-monthly.everydayMinor) },
                { label: "Free this month", value: aed(monthly.freeMinor), good: monthly.freeMinor >= 0 },
              ],
            },
            ...(cashflow && !noBalance ? [{
              heading: monthly.payday ? `Until pay day, ${monthly.payday}` : "Until pay day",
              rows: [
                ...cashflow.untilNextIncome.why.map((l) => ({ label: l.label, value: l.amount.text })),
                { label: "Free to spend now", value: aed(monthly.freeUntilPaydayMinor), good: monthly.freeUntilPaydayMinor >= 0, over: monthly.freeUntilPaydayMinor < 0 },
              ],
            }] : []),
          ]}
          footer="Free until pay day is what you can spend now without touching money the bills need. The month figure is what the plan leaves for goals and fun once every bill and budget is paid."
          onClose={() => setFreeWhy(false)}
        />
      )}

      {ideaWhy && (
        <WhySheet
          title={ideaWhy.advice ? "Why I suggested this" : "Why this tip"}
          groups={[{ heading: "The numbers behind it", rows: ideaWhy.why }]}
          onClose={() => setIdeaWhy(null)}
        />
      )}
    </div>
  );
}

// A new family's first week: three things that make the rest of the app make sense. Each one ticks itself when
// the app can see it is done (a bank balance, one spending); the kids' logins are ticked by hand. The card goes
// when all three are done, or when the parent closes it. Kept per browser: it is only a hint.
function StartHere({ familyId, hasBalance, hasSpending, kids }: { familyId: string; hasBalance: boolean; hasSpending: boolean; kids: string[] }) {
  const key = `ffa.start.${familyId}`;
  const [state, setState] = useState<{ logins?: boolean; closed?: boolean } | null>(null);
  useEffect(() => {
    try {
      setState(JSON.parse(localStorage.getItem(key) ?? "{}"));
    } catch {
      setState({});
    }
  }, [key]);
  const save = (next: { logins?: boolean; closed?: boolean }) => {
    setState(next);
    try {
      localStorage.setItem(key, JSON.stringify(next));
    } catch {
      /* only a hint: fine if it comes back */
    }
  };
  if (!state || state.closed) return null;
  const steps = [
    { id: "balance", done: hasBalance, title: "Type today's bank balance", text: "Then Today can say what is free until pay day.", href: "/parent/money/accounts" },
    { id: "spend", done: hasSpending, title: "Add one spending", text: "A receipt photo, or type it: \"groceries 240\".", href: "/parent/money/add" },
    ...(kids.length ? [{
      id: "logins", done: !!state.logins, title: `Give ${kids.join(" and ")} their logins`,
      text: "They log in on their own phone and tell the camel what they buy.", href: null,
    }] : []),
  ];
  if (steps.every((x) => x.done)) return null;
  return (
    <section className="card start-here" aria-label="Start here">
      <div className="row" style={{ padding: 0 }}>
        <span className="card-title">Start here</span>
        <button type="button" className="text" onClick={() => save({ ...state, closed: true })}>Hide</button>
      </div>
      <ol className="start-steps">
        {steps.map((x) => (
          <li key={x.id} className={x.done ? "done" : undefined}>
            <span className="tick" aria-hidden="true">{x.done ? "✓" : ""}</span>
            <span>
              {x.href && !x.done ? <Link href={x.href} className="link"><b>{x.title}</b></Link> : <b>{x.title}</b>}
              <span className="muted" style={{ display: "block" }}>{x.text}</span>
              {x.id === "logins" && !x.done && (
                <button type="button" className="text" onClick={() => save({ ...state, logins: true })}>Done</button>
              )}
            </span>
          </li>
        ))}
      </ol>
    </section>
  );
}

type Idea = { key: string; title: string; text: string; why: { label: string; value: string }[]; advice?: boolean };

// The engine's card check for one request on one card: shown only when the bill would not be covered.
function LiveCardWarning({ accountId, amountMinor, isCard }: { accountId: string; amountMinor: number; isCard: boolean }) {
  const [check, setCheck] = useState<CardCheck | null>(null);
  useEffect(() => {
    setCheck(null);
    if (!isCard || amountMinor <= 0) return;
    core.cardCheck(accountId, (amountMinor / 100).toFixed(2)).then(setCheck).catch(() => setCheck(null));
  }, [accountId, amountMinor, isCard]);
  return (
    <CardWarning accountId={accountId} compact live={check && {
      card: check.card, due: dayLongMonth(check.dueOn), withMinor: check.billWith.minor, freeByDueMinor: check.freeByDue.minor, fits: check.fits,
    }} />
  );
}
