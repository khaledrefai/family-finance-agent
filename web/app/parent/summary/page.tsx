"use client";

import { familyGoal, saverOfTheMonth, summary } from "@/lib/sample";
import { Back, Loading, Masthead, SampleNote, SavingLine } from "../../ui";
import { useSession } from "../../use-session";

// The monthly family summary: one message for all four, figures from the engine, the AI picks what to say.
// Sample month until proactive monitoring sends a real one.
export default function SummaryPage() {
  const { me } = useSession("PARENT");
  if (!me) return <Loading />;
  const [first, second] = saverOfTheMonth;

  return (
    <div className="stack">
      <Back href="/parent" label="Today" />
      <Masthead title={`${summary.month} for ${me.familyName}`} sub={`${summary.sentOn} · sent to the whole family`} />

      <section className="card">
        <p className="label">Progress to the {familyGoal.name.toLowerCase()}</p>
        <SavingLine
          months={familyGoal.planMonths}
          actual={familyGoal.actual}
          savedLabel="3,600 now"
          targetLabel="12,000 by June"
          label="Saved AED 1,200 in August, 2,400 in September, 3,600 in October. On plan the trip reaches AED 12,000 in June."
        />
        <p style={{ marginTop: 8 }}>
          AED 3,600 of 12,000 · {familyGoal.percent}% · on track for {familyGoal.onTrackFor}. The dashed line is the plan. The family badge unlocks at {familyGoal.badgeAt}%.
        </p>
      </section>

      <section className="card">
        <p className="label">Went well</p>
        {summary.wentWell.map((t) => <p key={t} style={{ padding: "4px 0" }}>{t}</p>)}
      </section>

      <section className="card">
        <p className="label">To change in November</p>
        {summary.toChange.map((t) => <p key={t} style={{ padding: "4px 0" }}>{t}</p>)}
      </section>

      <section className="card strong" style={{ borderWidth: 2 }}>
        <p className="label">Saver of the Month</p>
        <p style={{ fontSize: 22, fontWeight: 500 }}>{first.name}</p>
        <p style={{ fontSize: 14, margin: "2px 0 10px" }}>{first.percent}% of the way to their own goal</p>
        <p className="muted">
          {second.name} was close behind at {second.percent}%. Scored on how far each kid got toward their own goal, so age and pocket money don't matter.
        </p>
      </section>

      <p className="trust">Every figure here is worked out exactly by the app, not guessed by the AI. The kids' copy leaves out the bills and income.</p>
      <SampleNote />
    </div>
  );
}
