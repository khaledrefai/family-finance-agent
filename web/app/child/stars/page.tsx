"use client";

import { badges, kids, saverOfTheMonth } from "@/lib/sample";
import { SampleNote } from "../../ui";
import { useSession } from "../../use-session";
import { BigStar, BuddySays, Race, SmallStar, StickerArt } from "../kid-ui";

// Stars and badges, never money. Saver of the Month is scored on how far each kid is toward their own goal,
// so a 9-year-old and a 13-year-old compete fairly. Sample figures until the rewards endpoint exists.
const EARN = [
  ["Tell me what you bought", 1],
  ["A week you save as planned", 2],
  ["Reach a goal milestone", 5],
] as const;

export default function StarsPage() {
  const { me } = useSession("CHILD");
  if (!me) return <p className="muted">Loading…</p>;
  const kid = kids[me.displayName];
  const young = me.ageBand === "CHILD_6_9";

  return (
    <div className={`stack${young ? " young" : ""}`}>
      <header className="kid-head">
        <h1>My stars</h1>
      </header>

      {kid && (
        <section className="card star-hero">
          <BigStar count={kid.stars} size={young ? 140 : 120} />
          <div>
            <p className="goal-pct" style={{ fontSize: "1.75rem" }}>{kid.stars} stars</p>
            <p className="muted">{kid.starsThisMonth} new this month. Thanks for keeping track.</p>
          </div>
        </section>
      )}

      <section className="stack" style={{ gap: 10 }}>
        <h2 className="first">My sticker book</h2>
        <div className="badges">
          {badges.map((b) => (
            <div key={b.name} className={`badge${b.earned ? "" : " locked"}`}>
              <StickerArt kind={b.art} earned={b.earned} />
              <span className="badge-name">{b.name}</span>
              <span>{b.earned ? "Got it" : b.hint ?? "Not yet"}</span>
            </div>
          ))}
        </div>
      </section>

      <section className="card" style={{ display: "grid", gap: 8 }}>
        <p className="card-title">Saver of the Month</p>
        <p className="muted">A race to your own goal, so everyone has a fair chance</p>
        <Race runners={saverOfTheMonth} me={me.displayName} />
      </section>

      <section className="card" style={{ display: "grid", gap: 4 }}>
        <p className="card-title">How to earn stars</p>
        <div className="rows">
          {EARN.map(([what, n]) => (
            <div className="row" key={what}>
              <span>{what}</span>
              <span className="earn">
                +{n} <SmallStar size={16} />
              </span>
            </div>
          ))}
        </div>
      </section>

      <BuddySays>
        <p>Stars are for keeping track and saving. They are not money, and nobody loses them.</p>
      </BuddySays>
      <SampleNote />
    </div>
  );
}
