"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { agent, aed } from "@/lib/api";
import { kids } from "@/lib/sample";
import { Chit } from "../chit";
import { SampleNote, StackBar } from "../ui";
import { useSession } from "../use-session";
import { artFor, Avatar, BuddySays, GoalArt, GoalJar, Piggy, SmallStar } from "./kid-ui";

// Kid Home: my goal jar, my demo wallet, where my pocket money went, the coin buddy's nudge, and my requests.
// Kids only ever see their own money. Requests and the nudge are live; goal, wallet and the chart are
// sample figures until the goals endpoint exists. Adam (6 to 9) gets bigger type and fewer words.
export default function ChildHome() {
  const { me, requests, error, logout } = useSession("CHILD");
  const [nudge, setNudge] = useState<string | null>(null);

  useEffect(() => {
    if (me) agent.nudge().then((n) => setNudge(n.nudge)).catch(() => {});
  }, [me]);

  if (!me) return <p className="muted">Loading…</p>;
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

      {nudge && (
        <BuddySays>
          <p>{nudge}</p>
          <Link href="/child/ask" className="link">Tell me in Ask</Link>
        </BuddySays>
      )}

      {kid && (
        <section className="card wallet">
          <Piggy size={young ? 68 : 56} />
          <div style={{ flex: 1 }}>
            <p className="muted">My wallet</p>
            <p className="wallet-amount">{aed(kid.savedMinor, { cents: true })}</p>
          </div>
          <span className="demo-tag">DEMO MONEY</span>
        </section>
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

      <section>
        <h2 className="first">My requests</h2>
        {requests.length === 0 && <p className="empty">When you ask for something, it shows up here with your parent's answer.</p>}
        {requests.map((r) => (
          <Chit key={r.id} request={r} small />
        ))}
      </section>
      {kid && <SampleNote />}
    </div>
  );
}
