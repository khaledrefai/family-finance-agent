"use client";

import { useEffect, useState } from "react";
import { askOutings, OutingOption, OutingPlan, OutingsBoard, outingsApi, When } from "@/lib/outings";
import { useLiveEvents } from "@/lib/live-stream";
import { Back, Loading, Masthead, TRUST, WhySheet } from "../../../ui";
import { useSession } from "../../../use-session";

// Family outings that fit the fun budget. The top card says where entertainment stands this month. A parent picks
// the month and says what the family would like (or taps a chip); the Planner reads it and the core plans: each
// outing's cost for this family, and a month that fits what is left of the budget. A parent can take an outing out
// or add one from the list (the core checks again), then save it: the family hears, the kids without the prices.
// "On this week" are live events: extra ideas with the organiser's link, never counted in a plan.
const WISHES: { label: string; text: string }[] = [
  { label: "Free", text: "free outings" },
  { label: "Indoors", text: "indoor outings" },
  { label: "Outdoors", text: "outdoor outings" },
  { label: "Animals", text: "the kids love animals" },
  { label: "Beach", text: "a beach day" },
  { label: "Museums", text: "museums and history" },
];

const DAY = new Intl.DateTimeFormat("en-GB", { day: "numeric", month: "short", timeZone: "Asia/Dubai" });
const TIME = new Intl.DateTimeFormat("en-GB", { hour: "2-digit", minute: "2-digit", day: "numeric", month: "short", timeZone: "Asia/Dubai" });

export default function OutingsPage() {
  const { me } = useSession("PARENT");
  const [board, setBoard] = useState<OutingsBoard | null>(null);
  const [when, setWhen] = useState<When | null>(null);
  const [wish, setWish] = useState("");
  const [asking, setAsking] = useState(false);
  const [reply, setReply] = useState<string | null>(null);
  const [plan, setPlan] = useState<OutingPlan | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState<string | null>(null);
  const [why, setWhy] = useState(false);
  const [all, setAll] = useState(false);

  async function load() {
    try {
      const b = await outingsApi.board();
      setBoard(b);
      setError(null);
      return b;
    } catch (e) {
      setError((e as Error).message);
      return null;
    }
  }

  // First visit: the month with money left (next month when this one is used up), and the engine's own plan
  useEffect(() => {
    if (!me) return;
    (async () => {
      const b = await load();
      if (!b) return;
      const first: When = b.leftThisMonth.minor > 0 ? "THIS_MONTH" : "NEXT_MONTH";
      setWhen(first);
      ask("", first);
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [me]);
  // The other parent saved a plan: show it in Saved
  useLiveEvents(["OUTINGS_PLANNED"], () => { load(); }, !!me);

  async function ask(text: string, month = when) {
    if (!month || asking) return;
    setAsking(true);
    setSaved(null);
    try {
      const answer = await askOutings(text, month);
      setPlan(answer.plan);
      setReply(text ? answer.reply : null);
      setError(null);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setAsking(false);
    }
  }

  // Take one out, or add one: the core checks the whole month again (and says if something no longer fits)
  async function change(keys: string[]) {
    if (!plan) return;
    setSaved(null);
    try {
      setPlan(await outingsApi.check(plan.when, keys));
      setError(null);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function save() {
    if (!plan || saving) return;
    setSaving(true);
    try {
      const s = await outingsApi.save(plan.when, plan.picks.map((p) => p.key));
      setSaved(`Saved for ${s.monthLabel}. The family can see it; the kids see the places, not the prices.`);
      await load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setSaving(false);
    }
  }

  if (!me) return <Loading />;
  if (!board) {
    return (
      <div className="stack">
        <Back href="/parent/plan" label="Plan" />
        <Masthead title="Family outings" sub="Cheaper days out that fit the fun budget" />
        {error ? <p className="error" role="alert">{error}</p> : <Loading />}
      </div>
    );
  }

  const month = plan?.when ?? when;
  const open = (o: OutingOption) => (month === "THIS_MONTH" ? o.openThisMonth : o.openNextMonth);
  const inPlan = new Set(plan?.picks.map((p) => p.key) ?? []);
  const others = board.options.filter((o) => !inPlan.has(o.key));

  return (
    <div className="stack">
      <Back href="/parent/plan" label="Plan" />
      <Masthead title="Family outings" sub="Cheaper days out that fit the fun budget" />

      <section className="card" style={{ display: "grid", gap: 6 }}>
        <div className="row" style={{ padding: 0 }}>
          <span className="card-title">Entertainment in {board.thisMonth}</span>
          {board.over ? <span className="stamp-mini over">{board.over.text} over</span> : <b>{board.leftThisMonth.text} left</b>}
        </div>
        <p style={{ margin: 0 }}>
          {board.spent.text} spent of the {board.budget.text} budget.
          {board.usualOuting && <> A usual family outing has cost about <b>{board.usualOuting.text}</b>.</>}
        </p>
      </section>

      <section className="card" style={{ display: "grid", gap: 12 }}>
        <div className="seg" role="group" aria-label="Which month">
          {(["THIS_MONTH", "NEXT_MONTH"] as When[]).map((w) => (
            <button key={w} type="button" aria-pressed={month === w} disabled={asking}
              onClick={() => { setWhen(w); ask(wish, w); }}>
              {w === "THIS_MONTH" ? `Rest of ${board.thisMonth}` : board.nextMonth}
            </button>
          ))}
        </div>
        <form style={{ display: "grid", gap: 10 }} onSubmit={(e) => { e.preventDefault(); ask(wish); }}>
          <label>
            What would the family like?
            <input placeholder="Something free this weekend, or indoors because of the heat" value={wish} onChange={(e) => setWish(e.target.value)} />
          </label>
          <button type="submit" disabled={asking}>{asking ? "Planning…" : "Find outings"}</button>
        </form>
        <div className="chips" style={{ marginBottom: 0 }}>
          {WISHES.map((w) => (
            <button key={w.label} type="button" className={`chip${wish === w.text ? " on" : ""}`} disabled={asking}
              onClick={() => { setWish(w.text); ask(w.text); }}>
              {w.label}
            </button>
          ))}
        </div>
      </section>

      {error && <p className="error" role="alert">{error}</p>}
      {asking && (
        <section className="card searching" aria-busy="true" aria-live="polite">
          <p className="card-title" style={{ margin: 0 }}>Planning {month === "THIS_MONTH" ? `the rest of ${board.thisMonth}` : board.nextMonth}</p>
          <span className="skeleton" style={{ height: 72 }} />
          <span className="skeleton" style={{ height: 72, opacity: 0.6 }} />
        </section>
      )}

      {!asking && reply && <p className="turn-agent">{reply}</p>}

      {!asking && plan && (
        <section className="stack" style={{ gap: 10 }} aria-label={`Outings for ${plan.monthLabel}`}>
          <h2 className="first section-head">{plan.when === "THIS_MONTH" ? `Rest of ${plan.monthLabel}` : plan.monthLabel}</h2>
          {plan.when === "THIS_MONTH" && plan.allowance.minor === 0 && plan.budget.minor > 0 && (
            <p className="muted" style={{ margin: 0 }}>Nothing is left for outings this month, so these are free ones.</p>
          )}
          {plan.picks.length === 0 && <p className="empty">Nothing on the list fits what is left this month. Try {board.nextMonth}.</p>}
          {plan.picks.map((o) => (
            <OutingCard key={o.key} o={o} action="Take out" onAction={() => change(plan.picks.filter((p) => p.key !== o.key).map((p) => p.key))} />
          ))}
          {plan.leftOut.map((l) => <p key={l} className="muted" style={{ margin: 0 }}>{l}</p>)}

          {plan.picks.length > 0 && (
            <div className="card" style={{ display: "grid", gap: 6 }}>
              <div className="row" style={{ padding: 0 }}>
                <span className="card-title">This plan</span>
                <b className="offer-total">{plan.total.minor === 0 ? "Free" : plan.total.text}</b>
              </div>
              <div className="rows">
                {plan.allowance.minor > 0 && (
                  <div className="row"><span>Left of the {plan.allowance.text}</span><b>{plan.left.text}</b></div>
                )}
                {plan.usualCost && (
                  <div className="row"><span>{plan.picks.length} usual {plan.picks.length === 1 ? "outing" : "outings"}</span><span>about {plan.usualCost.text}</span></div>
                )}
                {plan.saving && (
                  <div className="row"><span>Saved with this plan</span><b className="good">{plan.saving.text}</b></div>
                )}
              </div>
              <div className="actions" style={{ marginTop: 4 }}>
                <button type="button" onClick={save} disabled={saving}>{saving ? "Saving…" : "Save this plan"}</button>
                <button type="button" className="text" onClick={() => setWhy(true)}>Why this?</button>
              </div>
              {saved && <div className="sent-note" role="status">{saved}</div>}
            </div>
          )}

          <button type="button" className="disclosure" aria-expanded={all} onClick={() => setAll((v) => !v)}>
            <span className="section-head">More outings on the list <span className="count calm">{others.length}</span></span>
            <span className="muted">{all ? "Hide" : "Show"}</span>
          </button>
          {all && others.map((o) => (
            <OutingCard key={o.key} o={o} closed={!open(o)} action={open(o) ? "Add" : undefined}
              onAction={() => change([...plan.picks.map((p) => p.key), o.key])} />
          ))}
        </section>
      )}

      {board.events.events.length > 0 && (
        <section className="stack" style={{ gap: 8 }}>
          <div>
            <h2 className="first section-head">On this week</h2>
            <p className="muted" style={{ margin: 0 }}>
              {board.events.source === "LIVE" ? "Found live" : "From the last search"}
              {board.events.checkedAt ? `, ${TIME.format(new Date(board.events.checkedAt))}` : ""}. Prices are on the organisers&apos; pages, so they are not in the plan.
            </p>
          </div>
          {board.events.events.map((e) => (
            <article className="card" key={e.link} style={{ display: "grid", gap: 4 }}>
              <b>{e.title}</b>
              <span className="muted">{[e.when, e.where].filter(Boolean).join(" · ")}{e.priceNote ? ` · ${e.priceNote} (their listing)` : ""}</span>
              <a className="link" href={e.link} target="_blank" rel="noreferrer noopener">See the listing</a>
            </article>
          ))}
        </section>
      )}

      {board.saved.length > 0 && (
        <section className="stack" style={{ gap: 8 }}>
          <h2 className="first section-head">Saved plans</h2>
          {board.saved.map((s) => (
            <div className="card" key={s.month} style={{ display: "grid", gap: 6 }}>
              <div className="row" style={{ padding: 0 }}>
                <span className="card-title">{s.monthLabel}</span>
                <b>{s.total ? (s.total.minor === 0 ? "Free" : s.total.text) : ""}</b>
              </div>
              <p style={{ margin: 0 }}>{s.picks.map((p) => p.name).join(", ")}</p>
              {s.savedBy && <span className="muted">Saved by {s.savedBy}</span>}
            </div>
          ))}
        </section>
      )}

      <p className="trust">
        Prices are from our saved list of {DAY.format(new Date(board.pricesFrom))}, not live. Check each one on its site before booking;
        travel, food and extras are not included. {TRUST}
      </p>

      {why && plan && (
        <WhySheet
          title={`Why this plan for ${plan.monthLabel}`}
          groups={[{ heading: "The numbers behind it", rows: plan.why }]}
          footer={plan.facts.find((f) => f.startsWith("Each paid outing")) ?? TRUST}
          onClose={() => setWhy(false)}
        />
      )}
    </div>
  );
}

function OutingCard({ o, action, onAction, closed = false }: { o: OutingOption; action?: string; onAction?: () => void; closed?: boolean }) {
  const free = o.cost.minor === 0;
  return (
    <article className={`card offer${closed ? " closed" : ""}`}>
      <div className="offer-head">
        <span><b>{o.name}</b> <span className="muted">· {o.area}</span></span>
        <b className="offer-total">{free ? "Free" : o.cost.text}</b>
      </div>
      <p className="offer-title">{o.about}</p>
      {(!free || closed) && <p className="muted offer-parts">{free ? "Free to get in" : `For the family: ${o.costLine}`}{closed ? " · closed that month" : ""}</p>}
      <div style={{ display: "flex", gap: 12, flexWrap: "wrap", alignItems: "center" }}>
        {action && onAction && <button type="button" className="quiet" onClick={onAction}>{action}</button>}
        <a className="link" href={o.site} target="_blank" rel="noreferrer noopener">{free ? "See it on the site" : "Check the price on the site"}</a>
      </div>
    </article>
  );
}
