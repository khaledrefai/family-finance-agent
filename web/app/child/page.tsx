"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { agent, aed } from "@/lib/api";
import { useLiveEvents } from "@/lib/live-stream";
import { notifyApi, Wallet } from "@/lib/push";
import { outingsApi, SavedPlan } from "@/lib/outings";
import { kids } from "@/lib/sample";
import { Chit } from "../chit";
import { NotifyCard } from "../live-ui";
import { Loading, SampleNote, StackBar } from "../ui";
import { useSession } from "../use-session";
import { artFor, Avatar, BuddySays, Camel, GoalArt, GoalJar, SmallStar } from "./kid-ui";

// Kid Home: my goal jar, my demo wallet, my requests (a parent's answer lands here), the coin buddy's nudge,
// the family's next outings (once a parent saves a plan: the places only, never the prices), and where my pocket
// money went.
// Kids only ever see their own money. Requests, the wallet and the nudge are live: when a parent says yes, the
// answer and the new wallet balance arrive on this screen by themselves. Goal and the chart are still sample
// figures until the goals endpoint exists. Adam (6 to 9) gets bigger type and fewer words.
const THIS_MONTH = new Intl.DateTimeFormat("en-GB", { month: "long", timeZone: "Asia/Dubai" }).format(new Date());

export default function ChildHome() {
  const { me, requests, error, logout } = useSession("CHILD");
  const [nudge, setNudge] = useState<string | null>(null);
  const [wallet, setWallet] = useState<Wallet | null>(null);
  const [changed, setChanged] = useState(false);
  const [fun, setFun] = useState<SavedPlan[]>([]);

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
  useLiveEvents(["hello", "WALLET_CHANGED"], (e) => loadWallet(e.kind === "WALLET_CHANGED"), !!me);
  const loadFun = useCallback(() => {
    outingsApi.saved().then(setFun).catch(() => {});
  }, []);
  useEffect(() => {
    if (me) loadFun();
  }, [me, loadFun]);
  useLiveEvents(["OUTINGS_PLANNED"], loadFun, !!me);
  useEffect(() => {
    if (!changed) return;
    const t = setTimeout(() => setChanged(false), 2400);
    return () => clearTimeout(t);
  }, [changed]);

  if (!me) return <Loading />;
  const kid = kids[me.displayName];
  const young = me.ageBand === "CHILD_6_9";

  return (
    <div className={`stack${young ? " young" : ""}`}>
      <header className="kid-head">
        <Avatar name={me.displayName} />
        <div style={{ flex: 1 }}>
          <h1>Hi {me.displayName}</h1>
          {kid && (
            <Link href="/child/stars" className="star-chip">
              <SmallStar /> {kid.stars} stars
            </Link>
          )}
        </div>
        <button className="text" onClick={logout}>Log out</button>
      </header>
      {error && <p className="error" role="alert">{error}</p>}

      {kid && (
        <section className="card goal-card">
          <GoalJar percent={kid.percent} size={young ? 150 : 128} />
          <div className="goal-info">
            <p className="label">My goal</p>
            <div className="goal-name">
              <GoalArt art={artFor(kid.goal)} size={44} />
              <span>{kid.goal}</span>
            </div>
            <p className="goal-pct">{kid.percent}%</p>
            <p className="muted">
              {aed(kid.savedMinor)} of {aed(kid.goalMinor)}
            </p>
          </div>
          <p className="goal-plan">{kid.plan}</p>
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
          <Link href="/child/ask" className="link">Tell me in Ask</Link>
        </BuddySays>
      )}

      {kid?.pocket && (
        <section className="card" style={{ display: "grid", gap: 10 }}>
          <div>
            <p className="card-title">Where my pocket money went</p>
            <p className="muted">October so far · {aed(kid.pocket.totalMinor)} from your parents</p>
          </div>
          <StackBar
            parts={kid.pocket.parts}
            label={kid.pocket.parts.map((p) => `${p.label} ${aed(p.amountMinor)}`).join(", ")}
          />
          <p style={{ fontSize: 14 }}>{kid.pocket.note}</p>
        </section>
      )}

      <NotifyCard parent={false} kid />
      {kid && <SampleNote />}
    </div>
  );
}
