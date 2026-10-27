"use client";

import { useEffect, useState } from "react";
import { badges, kids, saverOfTheMonth } from "@/lib/sample";
import { KidMonth, summaryApi } from "@/lib/summary";
import { Loading } from "../../ui";
import { useSession } from "../../use-session";
import { BigStar, BuddySays, Race, SmallStar, StickerArt } from "../kid-ui";

// Stars and badges, never money. Saver of the Month is scored on how far each kid is toward their own goal,
// so a 9-year-old and a 13-year-old compete fairly. The stars and the race are live from this month's summary;
// the sticker book follows my own goal (the demo family keeps its sample stickers).
const EARN = [
  ["Tell me what you bought", 1],
  ["A week you save as planned", 2],
  ["Reach a goal milestone", 5],
] as const;

export default function StarsPage() {
  const { me } = useSession("CHILD");
  const [month, setMonth] = useState<KidMonth | null>(null);
  useEffect(() => {
    if (me) summaryApi.kid().then(setMonth).catch(() => {});
  }, [me]);
  if (!me) return <Loading />;
  const sample = me.demo ? kids[me.displayName] : undefined;
  const kid = month ? { stars: month.starsTotal, starsThisMonth: month.starsThisMonth } : sample;
  const race = month?.saver?.race ?? (month ? [] : saverOfTheMonth);
  const young = me.ageBand === "CHILD_6_9";
  const pct = month?.goalName ? month.goalPercent ?? 0 : null;
  const stickers = me.demo ? badges : [
    { art: "camel" as const, name: "First star", earned: (month?.starsTotal ?? 0) > 0, hint: "Tell me what you bought" },
    ...(pct === null ? [] : [
      { art: "quarter" as const, name: "A quarter there", earned: pct >= 25 },
      { art: "half" as const, name: "Halfway", earned: pct >= 50 },
      { art: "threequarter" as const, name: "Nearly there", earned: pct >= 75 },
      { art: "trophy" as const, name: "Goal reached", earned: pct >= 100 },
    ]),
  ];

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
          {stickers.map((b) => (
            <div key={b.name} className={`badge${b.earned ? "" : " locked"}`}>
              <StickerArt kind={b.art} earned={b.earned} />
              <span className="badge-name">{b.name}</span>
              <span>{b.earned ? "Got it" : ("hint" in b && b.hint) || "Not yet"}</span>
            </div>
          ))}
        </div>
      </section>

      <section className="card" style={{ display: "grid", gap: 8 }}>
        <p className="card-title">Saver of the Month</p>
        <p className="muted">A race to your own goal, so everyone has a fair chance</p>
        {race.length > 0
          ? <Race runners={race} me={me.displayName} />
          : <p>Pick a goal and save towards it to join the race.</p>}
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
    </div>
  );
}
