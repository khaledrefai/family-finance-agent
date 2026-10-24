"use client";

import { badges, kids, saverOfTheMonth } from "@/lib/sample";
import { Icon, Masthead, Progress, SampleNote } from "../../ui";
import { useSession } from "../../use-session";

// Stars and badges, never money. Saver of the Month is scored on how far each kid is toward their own goal,
// so a 9-year-old and a 13-year-old compete fairly. Sample figures until the rewards endpoint exists.
const EARN = [
  ["Tell me what you bought", "1 star"],
  ["A week you save as planned", "2 stars"],
  ["Reach a goal milestone", "5 stars"],
];

export default function StarsPage() {
  const { me } = useSession("CHILD");
  if (!me) return <p className="muted">Loading…</p>;
  const kid = kids[me.displayName];
  const young = me.ageBand === "CHILD_6_9";

  return (
    <div className={`stack${young ? " young" : ""}`}>
      <Masthead title="My stars" />

      {kid && (
        <section className="card" style={{ display: "flex", alignItems: "center", gap: 14 }}>
          <span style={{ color: "var(--waiting)" }}><Icon name="star" size={44} /></span>
          <div>
            <p className="big">{kid.stars} stars</p>
            <p className="muted" style={{ fontSize: 14 }}>{kid.starsThisMonth} this month. Thanks for keeping track.</p>
          </div>
        </section>
      )}

      <section className="stack" style={{ gap: 10 }}>
        <h2 className="first">Badges</h2>
        <div className="badges">
          {badges.map((b) => (
            <div key={b.name} className={`badge${b.earned ? "" : " locked"}`}>
              <span className="medal" aria-hidden="true">{b.short}</span>
              <span>{b.name}{b.earned ? "" : ", not yet"}</span>
              {b.hint && <span>{b.hint}</span>}
            </div>
          ))}
        </div>
      </section>

      <section className="card">
        <p className="card-title">Saver of the Month</p>
        <p className="muted" style={{ margin: "2px 0 6px" }}>Scored on how far each of you is toward your own goal</p>
        {saverOfTheMonth.map((s) => (
          <div key={s.name} style={{ padding: "8px 0", display: "grid", gap: 4 }}>
            <div className="row" style={{ padding: 0 }}>
              <span>{s.name === me.displayName ? "You" : s.name}</span>
              <b>{s.percent}%</b>
            </div>
            <Progress percent={s.percent} label={`${s.percent} percent`} thin />
          </div>
        ))}
      </section>

      <section className="card" style={{ paddingTop: 8, paddingBottom: 8 }}>
        <p className="card-title" style={{ margin: "8px 0 2px" }}>How to earn stars</p>
        <div className="rows">
          {EARN.map(([what, stars]) => (
            <div className="row" key={what}><span>{what}</span><span>{stars}</span></div>
          ))}
        </div>
      </section>
      <SampleNote />
    </div>
  );
}
