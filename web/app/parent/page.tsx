"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { aed, api, MemberView, plain } from "@/lib/api";
import { advice as adviceApi, BudgetMonth, CardCheck, core, dayLongMonth, Goal, useAccounts, useLive, useMoney, WaysToSave } from "@/lib/live";
import { advice, familyGoal, kids, waysToSave } from "@/lib/sample";
import { Chit } from "../chit";
import { BellLink } from "../live-ui";
import { notifyApi, WeekPlan } from "@/lib/push";
import { useLiveEvents } from "@/lib/live-stream";
import { CardWarning, Icon, Loading, Masthead, PayFrom, Progress, SampleNote, TRUST, WhySheet } from "../ui";
import { useSession } from "../use-session";

// Today: only what needs a parent, in this order: what is free this month (with the three quick actions),
// bills and alerts in one list, waiting requests, this week's plan, the family goal, one piece of advice,
// ways to save, then what was decided (the last three, the rest one tap away).
// A bill that is covered stays neutral; colour only marks what is short or over.
// The Waiting list updates live: when a kid asks on their phone, it lands here without a reload (week 5).
// Requests, bills, cards, free cash and the family goal come from the core's money engine.
// Overspend alerts come from the budget engine, and "Ways to save" from the engine with the Planner's words.
// The one piece of advice is still a sample figure.
export default function TodayPage() {
  const { me, requests, error, setError, reload, logout } = useSession("PARENT");
  const [members, setMembers] = useState<MemberView[]>([]);
  const [justDecided, setJustDecided] = useState<string | null>(null);
  const [adviceState, setAdviceState] = useState<"open" | "accepted" | "later">("open");
  const [why, setWhy] = useState(false);
  // Ways to save: each tip can be dismissed or snoozed to next month. Live, the core keeps that for the family.
  const [tipState, setTipState] = useState<Record<string, "dismissed" | "snoozed">>({});
  const [snoozed, setSnoozed] = useState(false);
  const [tipWhy, setTipWhy] = useState<string | null>(null);
  // Which account a parent would pay a request from. Sample default until requests carry it.
  const [payFrom, setPayFrom] = useState<Record<string, string>>({});
  const [allDecided, setAllDecided] = useState(false);

  const { monthly, bills, live: moneyLive } = useMoney(!!me);
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

  const tips = (waysLive && ways
    ? ways.tips
    : waysToSave.tips.map((t) => ({ key: t.id, title: t.title, text: t.text, why: t.why }))
  ).filter((t) => !tipState[t.key]);
  const tipMonth = waysLive && ways ? ways.monthLabel : waysToSave.month;
  const pending = requests.filter((r) => r.status === "PENDING");
  const decided = requests.filter((r) => r.status !== "PENDING" && r.id !== justDecided);
  const fresh = requests.find((r) => r.id === justDecided);
  const nextBill = bills[0];
  const tightCard = accounts.find((a) => a.bill && !a.bill.ok);
  const goal = goals?.find((g) => g.tier === "GOOD_LIFE");
  const defaultCard = accounts.find((a) => a.kind === "CREDIT_CARD")?.id ?? "visa";
  const shownDecided = allDecided ? decided : decided.slice(0, 3);

  // Bills and alerts, in one list. A covered bill is calm; short, over or heading over gets colour.
  const attention: { key: string; href: string; tone: "calm" | "warn" | "bad"; icon: "calendar" | "card" | "warn"; title: string; text?: string }[] = [];
  if (nextBill) {
    const short = moneyLive && nextBill.state === "short";
    attention.push({
      key: "bill", href: "/parent/money#bills", tone: short ? "bad" : "calm", icon: "calendar",
      title: moneyLive
        ? `${nextBill.name} due ${nextBill.daysLeft === 1 ? "tomorrow" : `in ${nextBill.daysLeft} days`}`
        : `${nextBill.name} due in ${nextBill.daysLeft} days`,
      text: moneyLive
        ? `${aed(nextBill.amountMinor)}${short ? " short: the reserve won't be full by then." : ", planned for this month."}`
        : "AED 850 reserved, enough to cover it.",
    });
  }
  if (tightCard?.bill) {
    attention.push({
      key: "card", href: "/parent/money/accounts", tone: "bad", icon: "card",
      title: `${tightCard.label} bill due ${tightCard.bill.due}`,
      text: `${aed(tightCard.bill.soFarMinor)} so far, ${aed(tightCard.bill.freeByDueMinor)} free by then.`,
    });
  }
  budgetMonth?.alerts.forEach((b) => attention.push({
    key: b.category, href: "/parent/money/budgets", tone: b.state === "OVER" ? "bad" : "warn", icon: "warn", title: b.alert ?? b.label,
  }));
  const needsLook = attention.filter((a) => a.tone !== "calm").length;

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

      <section className="hero" aria-label="This month">
        <p className="hero-label">Free this month, after commitments</p>
        <p className="hero-amount"><span className="cur">AED</span>{plain(monthly.freeMinor)}</p>
        <p className="hero-sub">{aed(monthly.freeUntilPaydayMinor)} free until pay day on {monthly.payday}.</p>
        <p className="hero-trust">{TRUST}</p>
        <nav className="quick" aria-label="Quick actions">
          <Link href="/parent/money/add"><Icon name="plus" />Add spending</Link>
          <Link href="/parent/money#uploads"><Icon name="upload" />Upload a receipt</Link>
          <Link href="/parent/ask"><Icon name="ask" />Ask the accountant</Link>
        </nav>
      </section>

      {attention.length > 0 && (
        <section>
          <h2 className="first section-head">
            Bills and alerts
            {needsLook > 0 && <span className="count" aria-label={`${needsLook} need a look`}>{needsLook}</span>}
          </h2>
          <div className="list">
            {attention.map((a) => (
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
        </section>
      )}

      <section>
        <h2 className="first section-head">
          Waiting for you
          {pending.length > 0 && <span className="count">{pending.length}</span>}
        </h2>
        {fresh && <Chit request={fresh} from={`${nameOf(fresh.requesterId)} asked`} landed />}
        {pending.length === 0 && <p className="empty">Nothing waiting. When one of the kids asks for something, it lands here.</p>}
        {pending.map((r) => {
          const from = payFrom[r.id] ?? defaultCard;
          return (
            <Chit key={r.id} request={r} from={`${nameOf(r.requesterId)} asks`} stamp={false}>
              {r.requesterId !== me.memberId ? (
                <>
                  <PayFrom value={from} accounts={accounts} onChange={(id) => setPayFrom((p) => ({ ...p, [r.id]: id }))} />
                  <div style={{ marginTop: 8 }}>
                    {accountsLive
                      ? <LiveCardWarning accountId={from} amountMinor={r.amountMinor} isCard={accounts.find((a) => a.id === from)?.kind === "CREDIT_CARD"} />
                      : <CardWarning accountId={from} />}
                  </div>
                  <div className="chit-actions">
                    <button onClick={() => decide(r.id, "approve")}>Approve</button>
                    <button className="decline" onClick={() => decide(r.id, "reject")}>Decline</button>
                  </div>
                </>
              ) : (
                <p className="chit-note">You asked for this, so the other parent decides.</p>
              )}
              <Link href={`/parent/why?id=${r.id}`} className="link" style={{ marginTop: 4 }}>Why this?</Link>
            </Chit>
          );
        })}
      </section>

      {week && (
        <section className="card" style={{ display: "grid", gap: 8 }}>
          <div className="row" style={{ padding: 0 }}>
            <span className="card-title">This week</span>
            <span className="muted">{week.label}</span>
          </div>
          <p style={{ fontSize: 15 }}>{week.headline}</p>
          {(week.bills.length > 0 || week.slowDown.length > 0) && (
            <div className="rows">
              {week.bills.map((b) => (
                <div className="row" key={`bill-${b.label}`}>
                  <span>{b.label}<br /><span className="muted">{b.note}</span></span>
                  <b>{b.amount.text}</b>
                </div>
              ))}
              {week.slowDown.map((b) => (
                <div className="row" key={`slow-${b.label}`}>
                  <span>{b.label}<br /><span className="muted">{b.note}</span></span>
                  <b>{b.amount.minor === 0 ? "Pause" : b.amount.text}</b>
                </div>
              ))}
            </div>
          )}
          <p className="trust">{TRUST} Sent to your phone every Sunday morning.</p>
        </section>
      )}

      <section className="card" style={{ display: "grid", gap: 8 }}>
        <div className="row" style={{ padding: 0 }}>
          <span className="card-title">{goal?.name ?? familyGoal.name}</span>
          <span className="muted">Family goal</span>
        </div>
        <Progress percent={goal?.percent ?? familyGoal.percent} label={`${goal?.percent ?? familyGoal.percent} percent saved`} />
        <p style={{ fontSize: 14 }}>
          {goal
            ? <>{goal.saved.text} of {goal.target.text.replace("AED ", "")} saved · {goal.percent}%{goal.fullBy ? ` · full by ${goal.fullBy}` : ""}</>
            : <>AED 3,600 of 12,000 saved · {familyGoal.percent}% · on track for {familyGoal.onTrackFor}</>}
        </p>
        <p className="muted">
          Own goals: Layla {kids.Layla.percent}% · Adam {kids.Adam.percent}%
        </p>
      </section>

      {adviceState !== "later" && (
        <section className="card strong" style={{ display: "grid", gap: 10 }}>
          <p className="label">{advice.title}</p>
          {adviceState === "accepted" ? (
            <p role="status">Done. Entertainment is AED 600 a month for November and December. You can change it in Money, Budgets.</p>
          ) : (
            <>
              <p>{advice.text}</p>
              <div className="actions" style={{ marginTop: 0 }}>
                <button onClick={() => setAdviceState("accepted")}>Accept</button>
                <button className="quiet" onClick={() => setAdviceState("later")}>Not now</button>
              </div>
            </>
          )}
          <button type="button" className="text" style={{ justifySelf: "start", padding: 0 }} onClick={() => setWhy(true)}>
            Why this?
          </button>
        </section>
      )}

      {tips.length > 0 && (
        <section className="card" style={{ display: "grid", gap: 10 }}>
          <div className="row" style={{ padding: 0 }}>
            <span className="card-title">Ways to save</span>
            <span className="muted">{tipMonth}</span>
          </div>
          {tips.map((t) => (
            <div key={t.key} className="tip">
              <p className="label">{t.title}</p>
              <p style={{ fontSize: 15 }}>{t.text}</p>
              <div className="tip-actions">
                <button type="button" className="text" onClick={() => setTipWhy(t.key)}>Why this?</button>
                <button type="button" className="text" onClick={() => tip(t.key, "snoozed")}>Snooze a month</button>
                <button type="button" className="text" onClick={() => tip(t.key, "dismissed")}>Dismiss</button>
              </div>
            </div>
          ))}
          <p className="trust">{TRUST} Information only, not financial advice.</p>
        </section>
      )}
      {snoozed && <p className="muted" role="status">Snoozed tips come back next month.</p>}

      <Link href="/parent/summary" className="link">See October&apos;s family summary</Link>
      <SampleNote live={false} />

      <section>
        <h2 className="first section-head">
          Decided
          {decided.length > 0 && <span className="count calm">{decided.length}</span>}
        </h2>
        {decided.length === 0 && <p className="empty">Approved and declined requests will collect here.</p>}
        {shownDecided.map((r) => (
          <Chit key={r.id} request={r} from={nameOf(r.requesterId)} small />
        ))}
        {decided.length > shownDecided.length && (
          <button type="button" className="quiet" style={{ width: "100%" }} onClick={() => setAllDecided(true)}>
            Show all {decided.length}
          </button>
        )}
      </section>

      {why && (
        <WhySheet
          title="Why I suggested this"
          groups={[{ heading: "The numbers behind it", rows: advice.why }]}
          onClose={() => setWhy(false)}
        />
      )}
      {tipWhy && (
        <WhySheet
          title="Why this tip"
          groups={[{ heading: "The numbers behind it", rows: tips.find((t) => t.key === tipWhy)?.why ?? [] }]}
          onClose={() => setTipWhy(null)}
        />
      )}
    </div>
  );
}

// The engine's card check for one request on one card: shown only when the bill would not be covered.
function LiveCardWarning({ accountId, amountMinor, isCard }: { accountId: string; amountMinor: number; isCard: boolean }) {
  const [check, setCheck] = useState<CardCheck | null>(null);
  useEffect(() => {
    setCheck(null);
    if (!isCard || amountMinor <= 0) return;
    core.cardCheck(accountId, (amountMinor / 100).toFixed(2)).then(setCheck).catch(() => setCheck(null));
  }, [accountId, amountMinor, isCard]);
  return (
    <CardWarning accountId={accountId} live={check && {
      card: check.card, due: dayLongMonth(check.dueOn), withMinor: check.billWith.minor, freeByDueMinor: check.freeByDue.minor, fits: check.fits,
    }} />
  );
}
