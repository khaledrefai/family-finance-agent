"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { agent, aed } from "@/lib/api";
import { kids } from "@/lib/sample";
import { Chit } from "../chit";
import { Masthead, Progress, SampleNote, StackBar } from "../ui";
import { useSession } from "../use-session";

// Kid Home: my goal, my demo wallet, where my pocket money went, a nudge to log spending, and my requests.
// Kids only ever see their own money. Requests and the nudge are live; goal, wallet and the chart are
// sample figures until the goals endpoint exists. Adam (6 to 9) gets bigger type.
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
      <Masthead
        title={`Hi ${me.displayName}`}
        sub={kid ? `${kid.stars} stars so far` : me.familyName}
        action={<button className="text" onClick={logout}>Log out</button>}
      />
      {error && <p className="error" role="alert">{error}</p>}

      {kid && (
        <section className="card" style={{ display: "grid", gap: 10 }}>
          <div className="row" style={{ padding: 0 }}>
            <span className="card-title">My goal: {kid.goal}</span>
            <span className="muted">{aed(kid.goalMinor)}</span>
          </div>
          <p className="big">{kid.percent}%</p>
          <Progress percent={kid.percent} label={`${kid.percent} percent of the way`} />
          <p style={{ fontSize: young ? undefined : 14 }}>
            {aed(kid.savedMinor)} saved. {kid.plan}
          </p>
        </section>
      )}

      {kid && (
        <section className="card" style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
          <div>
            <p className="muted">My wallet</p>
            <p style={{ fontSize: 24 }}>{aed(kid.savedMinor, { cents: true })}</p>
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

      {nudge && (
        <section className="turn-agent" style={{ display: "grid", gap: 8 }}>
          <p>{nudge}</p>
          <Link href="/child/ask" className="link" style={{ fontSize: 14 }}>Tell me in Ask</Link>
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
