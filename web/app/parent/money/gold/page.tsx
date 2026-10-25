"use client";

import { useState } from "react";
import { core, dayMonth, dayTime, Gold, goldApi, Purity, sampleGold, useLive } from "@/lib/live";
import { Back, Masthead, SampleNote, TRUST } from "../../../ui";
import { useSession } from "../../../use-session";

// Saving in gold: the parents log each purchase, and the engine values the grams at today's price for their
// purity (GET /api/gold). The app records gold the family already has; it never suggests buying it.
// The price is the shared sample price, or one a parent typed in from the shop; the screen always says which.
const PURITIES: Purity[] = ["24K", "22K", "21K", "18K"];
const INVEST = "invest";

export default function GoldPage() {
  const { me } = useSession("PARENT");
  const { data: gold, live, reload } = useLive(goldApi.get, sampleGold, !!me);
  const goals = useLive(async () => (await core.goals()).map((g) => ({ id: g.id, name: g.name })), [] as { id: string; name: string }[], !!me);
  const [logging, setLogging] = useState(false);
  const [pricing, setPricing] = useState(false);
  const [purity, setPurity] = useState<Purity>("24K");
  const [goal, setGoal] = useState<string>("");
  const [form, setForm] = useState({ description: "", grams: "", paid: "", boughtOn: "", seller: "", jewellery: false });
  const [price, setPrice] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [said, setSaid] = useState<string | null>(null);
  if (!me) return <p className="muted">Loading…</p>;
  const field = (k: "description" | "grams" | "paid" | "boughtOn" | "seller") => ({
    value: form[k], onChange: (e: React.ChangeEvent<HTMLInputElement>) => setForm({ ...form, [k]: e.target.value }),
  });

  async function save(e: React.FormEvent) {
    e.preventDefault();
    if (!/^\d+(\.\d{1,3})?$/.test(form.grams.trim())) {
      setError("Enter the weight in grams, like 10 or 2.5.");
      return;
    }
    if (!live) {
      setError("Logging gold needs the app's server.");
      return;
    }
    try {
      const h = await goldApi.log({
        description: form.description || undefined, grams: form.grams.trim(), purity, paid: form.paid,
        boughtOn: form.boughtOn || undefined, seller: form.seller || undefined, jewellery: form.jewellery,
        goalId: goal && goal !== INVEST ? goal : undefined, bucket: goal === INVEST ? "INVEST_ASIDE" : "GOALS",
      });
      setError(null);
      setSaid(`Logged ${h.grams} g of ${h.purity}, worth ${h.value.text} at today's price.`);
      setForm({ description: "", grams: "", paid: "", boughtOn: "", seller: "", jewellery: false });
      setLogging(false);
      reload();
    } catch (err) {
      setError((err as Error).message);
    }
  }

  async function savePrice(e: React.FormEvent) {
    e.preventDefault();
    try {
      const p = await goldApi.setPrice(purity, price);
      setError(null);
      setSaid(`Using ${p.perGram.text} a gram for ${p.purity} from now.`);
      setPrice("");
      setPricing(false);
      reload();
    } catch (err) {
      setError((err as Error).message);
    }
  }

  const shown = gold.prices.filter((p) => p.purity !== "18K" || gold.grams.some((g) => g.purity === "18K"));

  return (
    <div className="stack">
      <Back href="/parent/money/accounts" label="Accounts" />
      <Masthead title="Gold" sub="Savings the family keeps as gold" />

      <section className="card strong" style={{ display: "grid", gap: 6 }}>
        <p className="muted">Worth today</p>
        <p className="big">{gold.value.text}</p>
        <p style={{ fontSize: 14 }}>
          {gold.grams.length ? gold.grams.map((g) => `${g.grams} g of ${g.purity}`).join(" and ") : "No gold logged yet"} · you paid {gold.paid.text}
        </p>
        <p className="est">
          Gold price {shown.map((p) => `${p.purity} ${p.perGram.text}/g`).join(" · ")}
          <br />
          {gold.priceSource}{gold.priceAsOf ? `, as of ${dayTime(gold.priceAsOf)}` : ""}
          {gold.priceIsSample && " · a sample price, not a live rate"}
        </p>
        {live && !pricing && (
          <button type="button" className="quiet" onClick={() => setPricing(true)} style={{ justifySelf: "start" }}>Type today's price from the shop</button>
        )}
      </section>

      {pricing && (
        <form className="panel" onSubmit={savePrice}>
          <strong>Today's price per gram</strong>
          <div className="chips" role="group" aria-label="Purity">
            {PURITIES.map((p) => (
              <button key={p} type="button" className={`chip${p === purity ? " on" : ""}`} aria-pressed={p === purity} onClick={() => setPurity(p)}>{p}</button>
            ))}
          </div>
          <label>
            AED a gram for {purity}
            <input inputMode="decimal" value={price} onChange={(e) => setPrice(e.target.value)} placeholder="320.50" required />
          </label>
          <button type="submit">Use this price</button>
        </form>
      )}

      {gold.byGoal.length > 0 && (
        <section className="card">
          <p className="card-title" style={{ marginBottom: 4 }}>By goal</p>
          {gold.byGoal.map((g) => (
            <div className="row" key={g.goalId}><span>{g.name} · {g.grams} g</span><b>{g.value.text}</b></div>
          ))}
        </section>
      )}

      <section className="stack" style={{ gap: 10 }}>
        <h2 className="first">What you have logged</h2>
        {gold.holdings.map((p) => (
          <article key={p.id} className="card" style={{ display: "grid", gap: 6 }}>
            <div className="row" style={{ padding: 0 }}>
              <b style={{ fontWeight: 600 }}>{p.description}</b>
              <span className="tier tier-goodlife">{p.grams} g · {p.purity}</span>
            </div>
            <div className="row" style={{ padding: 0 }}>
              <span className="muted">Paid{p.boughtOn ? ` ${dayMonth(p.boughtOn)}` : ""}{p.seller ? `, ${p.seller}` : ""}</span>
              <span className="num">{p.paid.text}</span>
            </div>
            <div className="row" style={{ padding: 0 }}><span>Worth today</span><b>{p.value.text}</b></div>
            <p className="muted">For {p.goalName}</p>
            {p.belowPaid && <p className="card-warn" style={{ gap: 0 }}>{gold.makingNote}</p>}
          </article>
        ))}
      </section>

      {logging ? (
        <form className="panel" onSubmit={save}>
          <strong>Log a gold purchase</strong>
          <label>
            What is it?
            <input placeholder="Coin, bar, bangle" {...field("description")} />
          </label>
          <label>
            Weight in grams
            <input inputMode="decimal" placeholder="10" required {...field("grams")} />
          </label>
          <div style={{ display: "grid", gap: 6 }}>
            <span className="muted">Purity</span>
            <div className="chips" style={{ marginBottom: 0 }} role="group" aria-label="Purity">
              {PURITIES.map((p) => (
                <button key={p} type="button" className={`chip${p === purity ? " on" : ""}`} aria-pressed={p === purity} onClick={() => setPurity(p)}>{p}</button>
              ))}
            </div>
          </div>
          <label>
            Price paid, AED
            <input inputMode="decimal" placeholder="3,200" required {...field("paid")} />
          </label>
          <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 10 }}>
            <label>
              Date
              <input type="date" {...field("boughtOn")} />
            </label>
            <label>
              Shop
              <input placeholder="Gold Souk" {...field("seller")} />
            </label>
          </div>
          <label style={{ display: "flex", gap: 8, alignItems: "center", fontSize: 14 }}>
            <input type="checkbox" checked={form.jewellery} onChange={(e) => setForm({ ...form, jewellery: e.target.checked })} style={{ width: "auto", minHeight: 0 }} />
            It's jewellery (the price included making charges)
          </label>
          <div style={{ display: "grid", gap: 6 }}>
            <span className="muted">Which goal is it for?</span>
            <div className="chips" style={{ marginBottom: 0 }} role="group" aria-label="Goal">
              {[...goals.data, { id: INVEST, name: "Set aside for investing" }].map((g) => (
                <button key={g.id} type="button" className={`chip${g.id === goal ? " on" : ""}`} aria-pressed={g.id === goal} onClick={() => setGoal(g.id === goal ? "" : g.id)}>{g.name}</button>
              ))}
            </div>
          </div>
          {error && <p className="error" role="alert">{error}</p>}
          <button type="submit">Log it</button>
        </form>
      ) : (
        <button type="button" onClick={() => setLogging(true)}>Log a gold purchase</button>
      )}
      {!logging && error && <p className="error" role="alert">{error}</p>}
      {said && <p className="muted" role="status">{said}</p>}
      <p className="trust">{TRUST} The app records gold you already have. It never tells you to buy it.</p>
      <SampleNote live={live} />
    </div>
  );
}
