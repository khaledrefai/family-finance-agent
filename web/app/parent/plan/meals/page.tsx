"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { askFor, askMeals, MealPlan, MealsBoard, mealsApi, Night, PlanAsk, ShoppingLine } from "@/lib/meals";
import { useLiveEvents } from "@/lib/live-stream";
import { Back, Loading, Masthead, TRUST, WhySheet } from "../../../ui";
import { useSession } from "../../../use-session";

// The week's dinners and the shopping list. A parent marks the busy nights (delivery) and says what the family would
// like; the Planner reads it and the core plans: dinners that suit everyone (allergy rules, vegetarian, dislikes),
// a shopping list in whole packs at saved prices, and the check against the week's grocery money. A parent can swap a
// dinner or turn a night into a delivery night (the core checks again), then save the week: everyone hears what's for
// dinner, the kids without the prices. Skipping a week is fine: nothing is saved until a parent presses Save.
const WISHES: { label: string; text: string }[] = [
  { label: "Pasta", text: "the kids want pasta" },
  { label: "Chicken", text: "some chicken dinners" },
  { label: "Fish", text: "fish once this week" },
  { label: "Vegetarian", text: "a few vegetarian dinners" },
  { label: "Rice", text: "rice dishes" },
];

const AISLE: Record<ShoppingLine["aisle"], string> = {
  FRUIT_VEG: "Fruit and veg",
  MEAT_FISH: "Meat and fish",
  DAIRY_EGGS: "Dairy and eggs",
  BAKERY_GRAINS: "Bread, rice and pasta",
  TINS_DRY: "Tins and dry food",
  FROZEN: "Frozen",
  PANTRY: "Cupboard",
};

const DAY = new Intl.DateTimeFormat("en-GB", { day: "numeric", month: "short", timeZone: "Asia/Dubai" });

export default function MealsPage() {
  const { me } = useSession("PARENT");
  const [board, setBoard] = useState<MealsBoard | null>(null);
  const [busy, setBusy] = useState<string[]>([]);
  const [wish, setWish] = useState("");
  const [asking, setAsking] = useState(false);
  const [reply, setReply] = useState<string | null>(null);
  const [plan, setPlan] = useState<MealPlan | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState<string | null>(null);
  const [why, setWhy] = useState(false);
  const [swapping, setSwapping] = useState<string | null>(null);
  const [showList, setShowList] = useState(true);

  async function load() {
    try {
      const b = await mealsApi.board();
      setBoard(b);
      setError(null);
      return b;
    } catch (e) {
      setError((e as Error).message);
      return null;
    }
  }

  // First visit: the saved week if there is one, otherwise the engine's own week
  useEffect(() => {
    if (!me) return;
    (async () => {
      const b = await load();
      if (!b) return;
      if (b.saved?.plan) {
        setPlan(b.saved.plan);
        setBusy(b.saved.plan.nights.filter((n) => n.kind === "DELIVERY").map((n) => n.date));
      } else {
        ask("", []);
      }
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [me]);
  // The other parent saved the week: show it
  useLiveEvents(["MEALS_PLANNED"], () => { load(); }, !!me);

  async function ask(text: string, days = busy) {
    if (asking) return;
    setAsking(true);
    setSaved(null);
    setSwapping(null);
    try {
      const answer = await askMeals(text, days);
      setPlan(answer.plan);
      setReply(text ? answer.reply : null);
      setError(null);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setAsking(false);
    }
  }

  // Swap a dinner, or make a night a delivery night (or a cooking night again): the core checks the whole week again
  async function change(next: PlanAsk) {
    setSaved(null);
    setSwapping(null);
    try {
      setPlan(await mealsApi.check(next));
      setBusy(next.deliveryDays);
      setError(null);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  function swapTo(night: Night, key: string) {
    if (!plan) return;
    const now = askFor(plan);
    const cook = plan.nights.filter((n) => n.kind === "COOK");
    change({ ...now, keys: cook.map((n) => (n.date === night.date ? key : n.key)) });
  }

  function toggleDelivery(night: Night) {
    if (!plan) return;
    const now = askFor(plan);
    if (night.kind === "DELIVERY") {
      const i = now.deliveryDays.indexOf(night.date);
      change({ ...now, deliveryDays: now.deliveryDays.filter((d) => d !== night.date), deliveryKeys: now.deliveryKeys?.filter((_, j) => j !== i) });
    } else {
      // The dinners keep their nights; the engine moves nothing else
      const cook = plan.nights.filter((n) => n.kind === "COOK" && n.date !== night.date).map((n) => n.key);
      change({ keys: cook, deliveryDays: [...now.deliveryDays, night.date].sort() });
    }
  }

  async function save() {
    if (!plan || saving) return;
    setSaving(true);
    try {
      const s = await mealsApi.save(askFor(plan));
      setSaved(`Saved for ${s.weekLabel}. The family can see what's for dinner; the kids don't see prices.`);
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
        <Masthead title="Weekly dinners" sub="What to cook, and the shopping list" />
        {error ? <p className="error" role="alert">{error}</p> : <Loading />}
      </div>
    );
  }

  const suits = board.recipes.filter((r) => r.suits);
  const rules = profileLine(board);
  const cooking = plan?.nights.filter((n) => n.kind === "COOK").map((n) => n.key) ?? [];
  const aisles = plan ? groupByAisle(plan.list) : [];

  return (
    <div className="stack">
      <Back href="/parent/plan" label="Plan" />
      <Masthead title="Weekly dinners" sub="What to cook, and the shopping list" />

      <section className="card" style={{ display: "grid", gap: 6 }}>
        <div className="row" style={{ padding: 0 }}>
          <span className="card-title">Groceries this week</span>
          <b>{board.groceryWeek ? board.groceryWeek.text : "No budget yet"}</b>
        </div>
        <p style={{ margin: 0 }}>
          {board.groceryWeek && board.forDinners
            ? <>From the {board.groceryLimit.text} a month. Dinners aim for half, <b>{board.forDinners.text}</b>; the rest is breakfasts, lunch boxes and the house.</>
            : <>Set a grocery budget in <Link href="/parent/money/budgets" className="link">Budgets</Link> and the plan checks the shopping against it.</>}
        </p>
        <p className="muted" style={{ margin: 0 }}>
          Suits everyone: {rules} <Link href="/parent/family#food" className="link">Change</Link>
        </p>
      </section>

      <section className="card" style={{ display: "grid", gap: 12 }}>
        <div style={{ display: "grid", gap: 6 }}>
          <span className="card-title">Busy nights</span>
          <span className="muted">Tap a night to order in instead of cooking.</span>
          <div className="chips" style={{ marginBottom: 0 }}>
            {board.days.map((d) => {
              const on = busy.includes(d.date);
              return (
                <button key={d.date} type="button" className={`chip${on ? " on" : ""}`} aria-pressed={on} disabled={asking}
                  onClick={() => {
                    const next = on ? busy.filter((x) => x !== d.date) : [...busy, d.date].sort();
                    if (next.length > 3) return;
                    setBusy(next);
                    if (plan) {
                      const night = plan.nights.find((n) => n.date === d.date);
                      if (night) toggleDelivery(night);
                    } else {
                      ask(wish, next);
                    }
                  }}>
                  {d.label.split(" ").slice(0, 2).join(" ")}
                </button>
              );
            })}
          </div>
        </div>
        <form style={{ display: "grid", gap: 10 }} onSubmit={(e) => { e.preventDefault(); ask(wish); }}>
          <label>
            What would the family like?
            <input placeholder="The kids want pasta, and fish once this week" value={wish} onChange={(e) => setWish(e.target.value)} />
          </label>
          <button type="submit" disabled={asking}>{asking ? "Planning…" : "Plan the week"}</button>
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
          <p className="card-title" style={{ margin: 0 }}>Planning {board.weekLabel}</p>
          <span className="skeleton" style={{ height: 56 }} />
          <span className="skeleton" style={{ height: 56, opacity: 0.7 }} />
          <span className="skeleton" style={{ height: 56, opacity: 0.4 }} />
        </section>
      )}

      {!asking && reply && <p className="turn-agent">{reply}</p>}

      {!asking && plan && (
        <section className="stack" style={{ gap: 10 }} aria-label={`Dinners for ${plan.weekLabel}`}>
          <h2 className="first section-head">{plan.weekLabel}</h2>
          {plan.nights.map((n) => (
            <article key={n.date} className={`card dinner${n.kind === "DELIVERY" ? " delivery" : ""}`}>
              <div className="offer-head">
                <span><span className="muted">{n.label.split(" ")[0]}</span> <b>{n.name}</b></span>
                <b className="offer-total">{n.cost?.text ?? ""}</b>
              </div>
              <p className="offer-title">{n.about}</p>
              <p className="muted offer-parts">
                {n.kind === "COOK" ? `${n.minutes} minutes · share of the shopping list` : "Delivery night · from the eating-out budget"}
              </p>
              <div style={{ display: "flex", gap: 12, flexWrap: "wrap", alignItems: "center" }}>
                {n.kind === "COOK" && (
                  <button type="button" className="quiet" aria-expanded={swapping === n.date}
                    onClick={() => setSwapping((s) => (s === n.date ? null : n.date))}>
                    Swap
                  </button>
                )}
                <button type="button" className="text" onClick={() => toggleDelivery(n)}
                  disabled={n.kind === "COOK" && busy.length >= 3}>
                  {n.kind === "COOK" ? "Order in instead" : "Cook instead"}
                </button>
              </div>
              {swapping === n.date && (
                <ul className="swap-list" aria-label={`Other dinners for ${n.label}`}>
                  {suits.filter((r) => !cooking.includes(r.key)).map((r) => (
                    <li key={r.key}>
                      <button type="button" className="swap-pick" onClick={() => swapTo(n, r.key)}>
                        <span><b>{r.name}</b> <span className="muted">· {r.minutes} min</span></span>
                        <span>about {r.cost.text}</span>
                      </button>
                    </li>
                  ))}
                </ul>
              )}
            </article>
          ))}
          {plan.leftOut.map((l) => <p key={l} className="muted" style={{ margin: 0 }}>{l}</p>)}

          <div className="card" style={{ display: "grid", gap: 6 }}>
            <div className="row" style={{ padding: 0 }}>
              <span className="card-title">Shopping list</span>
              <b className="offer-total">{plan.shopping.text}</b>
            </div>
            <div className="rows">
              {plan.inPacks.minor > 0 && (
                <div className="row"><span>Used by these dinners</span><span>about {plan.dinners.text}</span></div>
              )}
              {plan.forDinners && (
                plan.over
                  ? <div className="row"><span>Over the {plan.forDinners.text} for dinners</span><b className="over">{plan.over.text}</b></div>
                  : <div className="row"><span>Left of the week&apos;s {plan.groceryWeek?.text}</span><b className="good">{plan.weekLeft?.text}</b></div>
              )}
              {plan.delivery.minor > 0 && (
                <div className="row"><span>Delivery nights, eating-out budget</span><b>{plan.delivery.text}</b></div>
              )}
              {plan.cookInstead && plan.cookInstead.minor < plan.delivery.minor && (
                <div className="row"><span>Cooking those nights instead</span><span>about {plan.cookInstead.text}</span></div>
              )}
            </div>
            {plan.swap && <p style={{ margin: 0, fontSize: 14 }}>{plan.swap}</p>}
            <div className="actions" style={{ marginTop: 4 }}>
              <button type="button" onClick={save} disabled={saving}>{saving ? "Saving…" : "Save this week"}</button>
              <button type="button" className="text" onClick={() => setWhy(true)}>Why this?</button>
            </div>
            {saved && <div className="sent-note" role="status">{saved}</div>}
          </div>

          <button type="button" className="disclosure" aria-expanded={showList} onClick={() => setShowList((v) => !v)}>
            <span className="section-head">What to buy <span className="count calm">{plan.list.length}</span></span>
            <span className="muted">{showList ? "Hide" : "Show"}</span>
          </button>
          {showList && (
            <div className="card shop-list" style={{ display: "grid", gap: 10 }}>
              {aisles.map(([aisle, lines]) => (
                <div key={aisle} style={{ display: "grid", gap: 2 }}>
                  <span className="card-title" style={{ fontSize: 14 }}>{AISLE[aisle]}</span>
                  {lines.map((l) => (
                    <div className="row shop-line" key={l.key}>
                      <span>
                        <b>{l.name}</b>{l.packs > 1 ? ` × ${l.packs}` : ""} <span className="muted">· {l.pack}</span>
                        <br />
                        <span className="muted" style={{ fontSize: 13 }}>{l.uses}{l.halalMark ? " · check the halal mark" : ""}</span>
                      </span>
                      <b>{l.cost.text}</b>
                    </div>
                  ))}
                </div>
              ))}
              {plan.staples.length > 0 && (
                <p className="muted" style={{ margin: 0 }}>Check you have: {plan.staples.join(", ")}.</p>
              )}
              <p className="muted" style={{ margin: 0 }}>Always check the pack labels. The app can&apos;t be sure what is in a product, and it never certifies anything as halal.</p>
            </div>
          )}
        </section>
      )}

      {board.saved && (
        <div className="card" style={{ display: "grid", gap: 6 }}>
          <div className="row" style={{ padding: 0 }}>
            <span className="card-title">Saved week</span>
            <b>{board.saved.shopping?.text}</b>
          </div>
          <p style={{ margin: 0 }}>{board.saved.weekLabel}: {board.saved.nights.map((n) => n.kind === "COOK" ? n.name : "delivery").join(", ")}</p>
          {board.saved.savedBy && <span className="muted">Saved by {board.saved.savedBy}</span>}
        </div>
      )}

      <p className="trust">
        Prices are from our saved list of {DAY.format(new Date(board.pricesFrom))}, not live, and shop prices vary. Recipes are for {board.profiles.length} people. {TRUST}
      </p>

      {why && plan && (
        <WhySheet
          title={`Why this plan for ${plan.weekLabel}`}
          groups={[{ heading: "The numbers behind it", rows: plan.why }]}
          footer={plan.facts.find((f) => f.startsWith("The plan keeps dinners")) ?? TRUST}
          onClose={() => setWhy(false)}
        />
      )}
    </div>
  );
}

/** "halal for everyone, no peanuts for Adam, no mushrooms for Layla." from the profiles and the allergy rules. */
function profileLine(board: MealsBoard): string {
  const p = board.profiles;
  const parts: string[] = [];
  if (p.length > 0 && p.every((x) => x.halal)) parts.push("halal for everyone");
  else p.filter((x) => x.halal).forEach((x) => parts.push(`halal for ${x.name}`));
  p.filter((x) => x.vegan).forEach((x) => parts.push(`vegan for ${x.name}`));
  p.filter((x) => x.vegetarian && !x.vegan).forEach((x) => parts.push(`vegetarian for ${x.name}`));
  p.forEach((x) => x.allergies.forEach((a) => parts.push(`no ${a} for ${x.name}`)));
  p.forEach((x) => x.dislikes.forEach((d) => parts.push(`no ${d} for ${x.name}`)));
  return parts.length === 0 ? "no food rules yet." : parts.join(", ") + ".";
}

function groupByAisle(list: ShoppingLine[]): [ShoppingLine["aisle"], ShoppingLine[]][] {
  const out = new Map<ShoppingLine["aisle"], ShoppingLine[]>();
  list.forEach((l) => out.set(l.aisle, [...(out.get(l.aisle) ?? []), l]));
  return [...out.entries()];
}
