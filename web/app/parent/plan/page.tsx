"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { aed } from "@/lib/api";
import { core, Goal, PlanOptions, useAccounts, useBuckets, useMoney } from "@/lib/live";
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
      x.fits ? `Leaves ${x.spareInTightest.text} spare in ${x.tightestMonth}, the tightest month.` : `Does not fit in ${x.tightestMonth}.`}`,
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
        const goal = g.find((x) => x.tier === "GOOD_LIFE") ?? g[0];
        if (goal) setPlan(await core.options(goal.id));
      } catch {
        /* sample stays */
      }
    })();
  }, [me]);

  if (!me) return <Loading />;
  const live = money.live && plan !== null;
  const options: Option[] = plan ? toOptions(plan) : samplePlanOptions;
  const current = pace ?? options.find((o) => o.pick)?.id ?? options[0].id;
  const chosen = options.find((o) => o.id === current)!;
  const steps = live ? liveSteps(money.monthly.commitmentsMinor, accounts, goals ?? []) : sampleSteps;
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

      <section className="stack" style={{ gap: 10 }}>
        <div>
          <h2 className="first">{goalTitle}</h2>
          <p className="muted">Three ways to get there</p>
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

      <button onClick={savePace}>Use the {chosen.name.toLowerCase()} plan</button>
      {saved && <p role="status" className="muted">{saved}</p>}
      <p className="trust" style={{ textAlign: "center" }}>{TRUST}</p>

      <section className="card" style={{ display: "grid", gap: 6 }}>
        <p className="card-title">Also in Plan</p>
        <p className="muted">Weekly dinners and a shopping list, big purchases and the long-term calculator are coming soon.</p>
      </section>
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
function liveSteps(commitmentsMinor: number, accounts: ReturnType<typeof useAccounts>["accounts"], goals: Goal[]) {
  const card = accounts.find((a) => a.kind === "CREDIT_CARD" && a.bill);
  const buffer = goals.find((g) => g.tier === "SURVIVAL");
  return [
    { title: "Protect the commitments.", text: `${aed(commitmentsMinor)} this month for rent and school fee reserves and the bills comes out first.`, done: true },
    card?.bill
      ? { title: "Clear the card.", text: `${card.label}: ${aed(card.bill.soFarMinor)} due on ${card.bill.due}. Pay it in full, so no interest.`, done: card.bill.ok }
      : { title: "Clear the card.", text: "No card bill coming up.", done: true },
    buffer
      ? { title: "Build a buffer.", text: `${buffer.monthly ? `${buffer.monthly.text} a month` : "Nothing a month yet"} until it reaches ${buffer.target.text}${buffer.fullBy ? ` in ${buffer.fullBy}` : ""}. ${buffer.saved.text} saved so far.`, done: !!buffer.monthly }
      : { title: "Build a buffer.", text: "Add an emergency buffer goal first.", done: false },
    { title: "Pick the family goal pace.", text: "", done: false },
  ];
}
