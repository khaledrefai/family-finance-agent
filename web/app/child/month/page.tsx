"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { Suspense, useEffect, useState } from "react";
import { KidMonth, summaryApi } from "@/lib/summary";
import { Loading } from "../../ui";
import { useSession } from "../../use-session";
import { artFor, BigStar, Camel, GoalArt, GoalJar, Race, SmallStar, StickerArt } from "../kid-ui";

// My month with the camel: the kids' side of the monthly family summary. My own goal jar, the stars I earned, how
// often I told the camel what I bought, my requests, Saver of the Month and how far the family goal has got.
// Never the family's money: the core leaves the bills, budgets and income out of a kid's page, and the camel's note
// (written by the Planner) may only use the numbers on this page. Adam (6 to 9) gets bigger type and fewer words.
function MyMonth() {
  const month = useSearchParams().get("month");
  const { me } = useSession("CHILD");
  const [page, setPage] = useState<KidMonth | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    if (me) summaryApi.kid(month).then(setPage).catch(() => setFailed(true));
  }, [me, month]);

  if (!me) return <Loading />;
  const young = me.ageBand === "CHILD_6_9";
  if (failed) {
    return (
      <div className={`stack${young ? " young" : ""}`}>
        <header className="kid-head"><h1>My month</h1></header>
        <p className="empty">The camel can&apos;t find your month right now. Try again soon.</p>
      </div>
    );
  }
  if (!page) return <Loading />;
  const highlights = young ? page.highlights.slice(0, 3) : page.highlights;

  return (
    <div className={`stack${young ? " young" : ""}`}>
      <header className="kid-head">
        <div style={{ flex: 1 }}>
          <h1>My {page.monthLabel}</h1>
          <p className="muted">{page.running ? `${page.periodLabel}, so far` : page.periodLabel}</p>
        </div>
      </header>

      <section className="camel-hero" aria-label="The camel says">
        <div className="camel-hero-art"><Camel size={young ? 120 : 104} /></div>
        <p className="bubble camel-bubble">{page.camel.text}</p>
      </section>

      {page.saverOfTheMonth && (
        <section className="card saver-sticker">
          <StickerArt kind="trophy" earned />
          <div>
            <p className="card-title" style={{ margin: 0 }}>Saver of the Month</p>
            <p>{page.saver && page.saver.winners.length > 1 ? "You share it this month." : "That's you this month."} {page.saver?.percent}% of the way to your goal.</p>
          </div>
        </section>
      )}

      <div className="month-tiles">
        {page.goalName && page.goalPercent !== null && (
          <section className="card month-tile">
            <GoalJar percent={page.goalPercent} size={young ? 110 : 96} />
            <p className="goal-name" style={{ justifyContent: "center" }}>
              <GoalArt art={artFor(page.goalName)} size={28} /> {page.goalName}
            </p>
            <p className="tile-big">{page.goalPercent}%</p>
            {!young && page.goalSaved && page.goalTarget && <p className="muted">{page.goalSaved.text} of {page.goalTarget.text}</p>}
          </section>
        )}
        <section className="card month-tile">
          <BigStar count={page.starsThisMonth} size={young ? 110 : 96} />
          <p className="tile-label">new stars</p>
          <p className="muted">{page.starsTotal} in all</p>
        </section>
      </div>

      <section className="card" style={{ display: "grid", gap: 8 }}>
        <p className="card-title" style={{ margin: 0 }}>Days you told me what you bought</p>
        <div className="day-dots" role="img" aria-label={`${page.daysLogged} of ${page.daysInPeriod} days`}>
          {Array.from({ length: page.daysInPeriod }, (_, i) => (
            <span key={i} className={i < page.daysLogged ? "on" : undefined} />
          ))}
        </div>
        <p className="muted">{page.daysLogged} of {page.daysInPeriod} days</p>
      </section>

      {page.saver && page.saver.race.length > 1 && (
        <section className="card" style={{ display: "grid", gap: 8 }}>
          <p className="card-title" style={{ margin: 0 }}>Saver of the Month race</p>
          <p className="muted">How far each of you got to your own goal, so everyone has a fair chance</p>
          <Race runners={page.saver.race} me={page.name} />
        </section>
      )}

      {page.familyGoal && (
        <section className="card" style={{ display: "grid", gap: 8 }}>
          <p className="card-title" style={{ margin: 0 }}>The family&apos;s {page.familyGoal.name.toLowerCase()}</p>
          <div className="kid-progress" role="img" aria-label={`${page.familyGoal.percent} percent of the way`}>
            <span style={{ width: `${page.familyGoal.percent}%` }} />
          </div>
          <p className="muted">{page.familyGoal.percent}% of the way. Everyone helps.</p>
        </section>
      )}

      <section className="card" style={{ display: "grid", gap: 4 }}>
        <p className="card-title" style={{ margin: 0 }}>My month in a few lines</p>
        <ul className="fun-list" style={{ marginTop: 6 }}>
          {highlights.map((h) => (
            <li key={h}><span className="fun-dot" aria-hidden="true" /><span>{h}</span></li>
          ))}
        </ul>
      </section>

      <p className="muted" style={{ display: "flex", alignItems: "center", gap: 6 }}>
        <SmallStar size={16} /> Stars are for keeping track and saving. They are not money.
      </p>
      <Link href="/child" className="link">Back to my home</Link>
    </div>
  );
}

export default function MyMonthPage() {
  return (
    <Suspense fallback={<Loading />}>
      <MyMonth />
    </Suspense>
  );
}
