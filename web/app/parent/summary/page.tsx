"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect, useState } from "react";
import { FamilyMonth, Point, summaryApi, writtenByNote } from "@/lib/summary";
import { Camel } from "../../child/kid-ui";
import { Back, BudgetBar, Loading, Masthead, Progress, TRUST, WhySheet } from "../../ui";
import { useSession } from "../../use-session";

// The monthly family summary: the month in a line, the budgets against their limits, the goals, each kid's month,
// Saver of the Month, what went well and what to watch, and what each kid sees on their own page.
// Every figure is the core's (MonthSummaryEngine); the Planner agent only writes the headline, the story and the
// kids' camel notes, with those figures. This month so far, or one of the two months before.
function Summary() {
  const router = useRouter();
  const month = useSearchParams().get("month");
  const { me } = useSession("PARENT");
  const [page, setPage] = useState<FamilyMonth | null>(null);
  const [failed, setFailed] = useState(false);
  const [why, setWhy] = useState<Point | null>(null);
  const [sent, setSent] = useState<string | null>(null);

  useEffect(() => {
    if (!me) return;
    setFailed(false);
    summaryApi.family(month).then(setPage).catch(() => setFailed(true));
  }, [me, month]);

  if (!me) return <Loading />;
  if (failed) {
    return (
      <div className="stack">
        <Back href="/parent" label="Today" />
        <p className="error" role="alert">The family summary didn&apos;t load. Try again in a moment.</p>
      </div>
    );
  }
  if (!page) return <Loading />;

  const sendNow = () => summaryApi.sendNow()
    .then((r) => setSent(r.sent.some((t) => t.startsWith("The family's")) ? "Sent to the whole family." : "Already sent today."))
    .catch(() => setSent("Couldn't send it just now."));
  const family = me.familyName.replace(/^The /, "the ").replace(/ \(demo\)$/, "");

  return (
    <div className="stack">
      <Back href="/parent" label="Today" />
      <Masthead title={`${page.monthLabel} for ${family}`} sub={page.running ? `${page.periodLabel} · the month so far` : `${page.periodLabel} · the whole month`} />

      <div className="seg" role="group" aria-label="Month">
        {page.months.map((m) => (
          <button key={m.month} type="button" aria-pressed={m.month === page.month}
                  onClick={() => router.replace(m.month === page.months[0].month ? "/parent/summary" : `/parent/summary?month=${m.month}`)}>
            {m.label}
          </button>
        ))}
      </div>

      <section className="card strong summary-lead" style={{ borderWidth: 2 }}>
        <p className="summary-headline">{page.headline.text}</p>
        <p>{page.story.text}</p>
        <p className="muted" style={{ fontSize: 13 }}>{writtenByNote(page.story.writtenBy)}</p>
      </section>

      {page.wentWell.length > 0 && (
        <section className="card">
          <p className="label">Went well</p>
          <Points points={page.wentWell} onWhy={setWhy} />
        </section>
      )}

      <section className="card">
        <p className="label">To watch</p>
        {page.toWatch.length === 0
          ? <p>Nothing to watch this month.</p>
          : <Points points={page.toWatch} onWhy={setWhy} />}
      </section>

      {page.budgets.length > 0 && (
        <section className="card">
          <div className="row" style={{ padding: 0 }}>
            <span className="card-title">Budgets</span>
            <span className="muted">{page.budgetSpent.text} of {page.budgetLimit.text}</span>
          </div>
          <p className="muted" style={{ margin: "2px 0 6px" }}>
            {page.budgetsWithin} of {page.budgets.length} within the limit. The dark line is the limit; red is over it.
          </p>
          {page.budgets.map((l) => (
            <BudgetBar key={l.category} name={l.label} spentMinor={l.spent.minor} limitMinor={l.limit.minor}
                       fill={l.fillBp / 100} past={l.pastBp / 100} overMinor={l.over.minor || undefined} />
          ))}
        </section>
      )}

      {page.goals.length > 0 && (
        <section className="card" style={{ display: "grid", gap: 12 }}>
          <span className="card-title">Goals today</span>
          {page.goals.map((g) => (
            <div key={g.name} style={{ display: "grid", gap: 4 }}>
              <div className="row" style={{ padding: 0 }}>
                <span>{g.name}</span>
                <b>{g.percent}%</b>
              </div>
              <Progress percent={g.percent} thin label={`${g.name}: ${g.saved.text} of ${g.target.text}, ${g.percent}%`} />
              <p className="muted" style={{ fontSize: 13 }}>
                {g.saved.text} of {g.target.text}
                {g.monthly ? ` · ${g.monthly.text} a month` : ""}
                {g.fullBy ? ` · full by ${g.fullBy}` : g.monthly ? " · not full within a year" : " · no monthly amount yet"}
              </p>
            </div>
          ))}
        </section>
      )}

      {page.kids.length > 0 && (
        <section className="card" style={{ display: "grid", gap: 14 }}>
          <span className="card-title">The kids&apos; month</span>
          {page.kids.map((k) => (
            <div key={k.id} className="kid-month">
              <div className="row" style={{ padding: 0 }}>
                <b>{k.name}</b>
                <span className="muted">{k.starsThisMonth} stars</span>
              </div>
              <div className="rows">
                <div className="row"><span>Spent of pocket money</span><b>{k.spent.text} of {k.pocketMoney.text}</b></div>
                <div className="row"><span>Days logged</span><b>{k.daysLogged} of {k.daysInPeriod}</b></div>
                {k.topCategory && <div className="row"><span>Most went on</span><b>{k.topCategory}</b></div>}
                {k.goalName && <div className="row"><span>{k.goalName}</span><b>{k.goalSaved?.text} of {k.goalTarget?.text} · {k.goalPercent}%</b></div>}
                {k.requests.asked > 0 && (
                  <div className="row">
                    <span>Requests</span>
                    <b>{k.requests.asked} asked · {k.requests.approved} yes{k.requests.declined ? ` · ${k.requests.declined} no` : ""}{k.requests.blocked ? ` · ${k.requests.blocked} blocked` : ""}</b>
                  </div>
                )}
                {k.againstRules.length > 0 && <div className="row"><span>Against family rules</span><b className="over">{k.againstRules.join(", ")}</b></div>}
              </div>
            </div>
          ))}
        </section>
      )}

      {page.saver && (
        <section className="card strong" style={{ borderWidth: 2 }}>
          <p className="label">Saver of the Month</p>
          <p style={{ fontSize: 22, fontWeight: 500 }}>{page.saver.winners.join(" and ")}</p>
          <p style={{ fontSize: 14, margin: "2px 0 10px" }}>{page.saver.percent}% of the way to their own goal</p>
          {page.saver.race.length > 1 && (
            <p className="muted">
              {page.saver.race.filter((r) => !page.saver!.winners.includes(r.name)).map((r) => `${r.name} ${r.percent}%`).join(", ")}.
              {" "}Scored on how far each kid got toward their own goal, so age and pocket money don&apos;t matter.
            </p>
          )}
        </section>
      )}

      {page.kidPages.length > 0 && (
        <section className="card" style={{ display: "grid", gap: 12 }}>
          <span className="card-title">What the kids see</span>
          <p className="muted" style={{ marginTop: -8 }}>Their own goal, stars and requests, from the camel. No family money.</p>
          {page.kidPages.map((k) => (
            <div key={k.id} className="camel-note">
              <Camel size={44} />
              <p><b>{k.name}:</b> {k.camel.text}</p>
            </div>
          ))}
        </section>
      )}

      <section className="card" style={{ display: "grid", gap: 8 }}>
        <p className="card-title" style={{ margin: 0 }}>Share it</p>
        <p className="muted">On the 1st of every month the summary goes to the whole family by itself: this page to the parents, their own page to each kid.</p>
        <button type="button" onClick={sendNow}>Send {page.running ? "the month so far" : "it"} to the family now</button>
        {sent && <p role="status">{sent}</p>}
      </section>

      <p className="trust">{TRUST} The kids&apos; pages leave out the bills, budgets and income.</p>

      {why && (
        <WhySheet title="Why this?" groups={[{ heading: "The numbers behind it", rows: why.why }]} onClose={() => setWhy(null)} />
      )}
    </div>
  );
}

function Points({ points, onWhy }: { points: Point[]; onWhy: (p: Point) => void }) {
  return (
    <div style={{ display: "grid", gap: 10 }}>
      {points.map((p) => (
        <div key={p.key} className="tip">
          <p>{p.text}</p>
          {p.why.length > 0 && (
            <div className="tip-actions"><button type="button" className="text" onClick={() => onWhy(p)}>Why this?</button></div>
          )}
        </div>
      ))}
    </div>
  );
}

export default function SummaryPage() {
  return (
    <Suspense fallback={<Loading />}>
      <Summary />
    </Suspense>
  );
}
