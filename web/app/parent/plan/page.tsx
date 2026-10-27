"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { aed } from "@/lib/api";
import { core, Goal, PlanOptions, shortText, useAccounts, useBuckets, useMoney } from "@/lib/live";
import { planOptions as samplePlanOptions, planSplit, planSteps as sampleSteps } from "@/lib/sample";
import { Loading, Masthead, SampleNote, StackBar, TRUST, WhySheet } from "../../ui";
import { useSession } from "../../use-session";

// The family plan: protect commitments, clear the card, build a buffer, then pick the goal's pace.
// The options come from POST /api/plan/options for the family's Good Life goal, checked by the engine
// against every month's spare cash. Picking one saves the goal's monthly amount (a parent's tap only).
type Option = { id: string; name: string; monthlyMinor: number; monthlyText?: string; text: string; pick?: boolean };

function toOptions(o: PlanOptions): Option[] {
  return o.options.map((x) => ({
    id: x.id,
    name: x.name,
    monthlyMinor: x.monthly.minor,
    monthlyText: x.monthly.text,
    text: `Ready by ${x.readyMonth}.${x.meetsDate ? "" : " Misses the date you set."} ${
      x.fits ? `Leaves ${x.spareInTightest.text} spare in ${x.tightestMonth}, the tightest month.` : shortText(x)}`,
    pick: x.id === o.recommended,
  }));
}

export default function PlanPage() {
  const { me } = useSession("PARENT");
  const money = useMoney(!!me);
  const { accounts } = useAccounts(!!me);
  const { data: buckets, live: bucketsLive } = useBuckets(!!me);
  const [goals, setGoals] = useState<Goal[] | null>(null);
  const [plan, setPlan] = useState<PlanOptions | null>(null);
  const [pace, setPace] = useState<string | null>(null);
  const [saved, setSaved] = useState<string | null>(null);
  const [why, setWhy] = useState(false);

  useEffect(() => {
    if (!me) return;
    (async () => {
      try {
        const g = await core.goals();
        setGoals(g);
        // From Family goals, "Pick its pace" opens this page on that goal (?goal=…); otherwise the family goal
        const asked = new URLSearchParams(window.location.search).get("goal");
        const goal = g.find((x) => x.id === asked) ?? g.find((x) => x.tier === "GOOD_LIFE") ?? g[0];
        if (goal) setPlan(await core.options(goal.id));
        if (asked && goal?.id === asked) document.getElementById("pace")?.scrollIntoView();
      } catch {
        /* sample stays */
      }
    })();
  }, [me]);

  async function pickGoal(id: string) {
    setPace(null);
    setSaved(null);
    try {
      setPlan(await core.options(id));
    } catch (e) {
      setSaved((e as Error).message);
    }
  }

  if (!me) return <Loading />;
  const live = money.live && plan !== null;
  const options: Option[] = plan ? toOptions(plan) : samplePlanOptions;
  // The pace the family already saved (in setup or here) starts selected; otherwise the app's pick
  const paceGoal = plan ? goals?.find((g) => g.id === plan.goalId) : undefined;
  const savedPace = paceGoal?.monthly ? options.find((o) => o.monthlyMinor === paceGoal.monthly!.minor)?.id : undefined;
  const current = pace ?? savedPace ?? options.find((o) => o.pick)?.id ?? options[0].id;
  const chosen = options.find((o) => o.id === current)!;
  const steps = live ? liveSteps(money.monthly.commitmentsMinor, accounts, goals ?? [], paceGoal) : sampleSteps;
  const goalTitle = plan ? `${plan.goalName} · ${plan.target.text}` : "Summer trip · AED 12,000";
  const tightest = plan?.options.find((o) => o.id === current);

  async function savePace() {
    if (!plan) {
      setSaved("Saved. The kids will see their goals on their Home screen.");
      return;
    }
    try {
      await core.setPace(plan.goalId, chosen.monthlyText ?? String(chosen.monthlyMinor / 100));
      setSaved(`Saved. ${plan.goalName} now gets ${chosen.monthlyText} a month.`);
    } catch (e) {
      setSaved((e as Error).message);
    }
  }

  return (
    <div className="stack">
      <Masthead title="Family plan" sub={`Built from ${aed(money.monthly.freeMinor)} free this month`} />

      <section className="card" style={{ paddingTop: 6, paddingBottom: 6 }}>
        <ol className="steps">
          {steps.map((s, i) => (
            <li key={s.title} className={s.done ? "done" : undefined}>
              <span className="n" aria-hidden="true">{s.done ? "✓" : i + 1}</span>
              <span><b>{s.title}</b> {s.text}{s.done && <span className="visually-hidden"> Done.</span>}</span>
            </li>
          ))}
        </ol>
      </section>

      <section className="card" style={{ display: "grid", gap: 6 }}>
        <span className="card-title">Change the plan</span>
        <div className="rows">
          <Link href="/parent/plan/goals" className="row calc-link"><span>Family goals <span className="muted">· amount, date, add or remove</span></span><span aria-hidden="true">›</span></Link>
          <Link href="/parent/money/budgets/change" className="row calc-link"><span>Budgets and pocket money</span><span aria-hidden="true">›</span></Link>
          <Link href="/parent/plan/income-bills" className="row calc-link"><span>Income and bills</span><span aria-hidden="true">›</span></Link>
          <Link href="/parent/plan/buckets" className="row calc-link"><span>Money buckets <span className="muted">· the split</span></span><span aria-hidden="true">›</span></Link>
        </div>
      </section>

      <Link href="/parent/plan/buckets" className="card bucket-link" style={{ display: "grid", gap: 10, color: "inherit", textDecoration: "none" }}>
        <div className="row" style={{ padding: 0 }}>
          <span className="card-title">Money buckets</span>
          <span className="link">See what is in each ›</span>
        </div>
        <StackBar
          parts={buckets.list.map((b) => ({ label: b.name, amountMinor: b.amountMinor, color: b.color }))}
          label={buckets.list.map((b) => `${b.name} ${aed(b.amountMinor)}`).join(", ")}
        />
      </Link>

      <Link href="/parent/plan/meals" className="card" style={{ display: "grid", gap: 4, color: "inherit", textDecoration: "none" }}>
        <div className="row" style={{ padding: 0 }}>
          <span className="card-title">Weekly dinners</span>
          <span className="link">Plan the week ›</span>
        </div>
        <span className="muted">What to cook and the shopping list, inside the grocery budget and the family&apos;s food rules.</span>
      </Link>

      <Link href="/parent/plan/outings" className="card" style={{ display: "grid", gap: 4, color: "inherit", textDecoration: "none" }}>
        <div className="row" style={{ padding: 0 }}>
          <span className="card-title">Family outings</span>
          <span className="link">Plan cheaper ones ›</span>
        </div>
        <span className="muted">Days out that fit the entertainment budget, priced for the whole family.</span>
      </Link>

      <section id="goals-long-term" className="card" style={{ display: "grid", gap: 6 }}>
        <div className="row" style={{ padding: 0 }}>
          <span className="card-title">Savings calculator</span>
          <Link href="/parent/plan/calculator?from=invest" className="link">Investing bucket ›</Link>
        </div>
        <span className="muted">See any goal over 1, 5 and 10 years at a rate you pick. Learning only.</span>
        <div className="rows">
          {(goals ?? []).map((g) => (
            <Link key={g.id} href={`/parent/plan/calculator?goal=${g.id}`} className="row calc-link">
              <span>{g.name} <span className="muted">· {g.saved.text} of {g.target.text}</span></span>
              <span aria-hidden="true">›</span>
            </Link>
          ))}
        </div>
      </section>

      <section id="pace" className="stack" style={{ gap: 10 }}>
        {live && goals && goals.length > 1 && (
          <label style={{ display: "grid", gap: 4, fontWeight: 600 }}>
            How fast to save for
            <select value={plan!.goalId} onChange={(e) => pickGoal(e.target.value)}>
              {goals.map((g) => <option key={g.id} value={g.id}>{g.name}</option>)}
            </select>
          </label>
        )}
        <div>
          <h2 className="first">{goalTitle}</h2>
          <p className="muted">{savedPace ? `Three ways to get there. Now on ${options.find((o) => o.id === savedPace)?.name.toLowerCase()}.` : "Three ways to get there"}</p>
        </div>
        {options.map((o) => (
          <label className="option" key={o.id}>
            <input type="radio" name="pace" checked={current === o.id} onChange={() => { setPace(o.id); setSaved(null); }} />
            <span style={{ flex: 1 }}>
              <b>{o.name}</b> · {aed(o.monthlyMinor)} a month
              <br />
              <span className="muted" style={{ fontSize: 14 }}>{o.text}</span>
              {o.pick && <><br /><span className="pill">My pick</span></>}
            </span>
          </label>
        ))}
        <button type="button" className="text" style={{ justifySelf: "start", padding: 0 }} onClick={() => setWhy(true)}>Why this?</button>
      </section>

      {!live && <section className="card">
        <p className="card-title" style={{ marginBottom: 4 }}>How the steady plan splits</p>
        <div className="rows">
          {planSplit.map((l) => (
            <div className="row" key={l.label}><span>{l.label}</span><b>{aed(l.amountMinor)}</b></div>
          ))}
        </div>
        <p className="muted" style={{ marginTop: 8 }}>
          Each kid also keeps their own goal. Their saving counts toward both, and they earn the family badge when the trip reaches 50%.
        </p>
      </section>}

      {current === savedPace && !saved
        ? <p className="muted" style={{ textAlign: "center", margin: 0 }}>This is the pace you picked. Choose another to change it.</p>
        : <button onClick={savePace}>Use the {chosen.name.toLowerCase()} plan</button>}
      {saved && <p role="status" className="muted">{saved}</p>}
      <p className="trust" style={{ textAlign: "center" }}>{TRUST}</p>

      <SampleNote live={live && bucketsLive} />

      {why && (
        <WhySheet
          title={`Why the ${chosen.name.toLowerCase()} plan`}
          groups={[
            {
              heading: "The numbers behind it",
              rows: live && tightest ? [
                { label: "Free cash this month", value: aed(money.monthly.freeMinor) },
                { label: "Saving already planned", value: aed(money.monthly.savingMinor) },
                { label: `${chosen.name} plan`, value: `${tightest.monthly.text} a month` },
                { label: "Ready by", value: tightest.readyMonth },
                { label: `Spare in ${tightest.tightestMonth}`, value: tightest.spareInTightest.text },
              ] : [
                { label: "Free cash, usual month", value: "AED 9,910" },
                { label: "Free cash, December", value: "AED 7,510" },
                { label: "Buffer until March", value: "AED 5,000 a month" },
                { label: "Steady plan", value: "AED 1,500 a month" },
                { label: "Spare in December", value: "AED 1,010" },
              ],
            },
          ]}
          footer={`${plan?.recommendedReason ? `The app picks ${plan.options.find((o) => o.id === plan.recommended)?.name.toLowerCase()}: ${plan.recommendedReason.toLowerCase()}` : "Steady is the fastest pace that still leaves money spare in December."} ${TRUST}`}
          onClose={() => setWhy(false)}
        />
      )}
    </div>
  );
}

// The four steps, worded from the family's own figures (each one is the engine's number).
function liveSteps(commitmentsMinor: number, accounts: ReturnType<typeof useAccounts>["accounts"], goals: Goal[], paceGoal?: Goal) {
  const card = accounts.find((a) => a.kind === "CREDIT_CARD" && a.bill);
  const buffer = goals.find((g) => g.tier === "SURVIVAL");
  return [
    { title: "Protect the commitments.", text: `${aed(commitmentsMinor)} this month goes to the bills first, with a little put aside for the big ones.`, done: true },
    card?.bill
      ? { title: "Clear the card.", text: `${card.label}: ${aed(card.bill.soFarMinor)} due on ${card.bill.due}. Pay it in full, so no interest.`, done: card.bill.ok }
      : { title: "Clear the card.", text: "No card bill coming up.", done: true },
    buffer
      ? { title: "Build a buffer.", text: `${buffer.monthly ? `${buffer.monthly.text} a month` : "Nothing a month yet"} until it reaches ${buffer.target.text}${buffer.fullBy ? ` in ${buffer.fullBy}` : ""}. ${buffer.saved.text} saved so far.`, done: !!buffer.monthly }
      : { title: "Build a buffer.", text: "Add an emergency buffer goal first.", done: false },
    // With only the buffer, its pace is step 3; a family goal (a trip, a car) gets its own step once there is one
    paceGoal && paceGoal.id !== buffer?.id
      ? { title: "Pick the family goal pace.", text: paceGoal.monthly ? `${paceGoal.name}: ${paceGoal.monthly.text} a month.` : `${paceGoal.name}: pick a pace below.`, done: !!paceGoal.monthly }
      : null,
  ].filter((x): x is { title: string; text: string; done: boolean } => x !== null);
}
