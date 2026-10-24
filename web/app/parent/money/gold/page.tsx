"use client";

import { useState } from "react";
import { aed } from "@/lib/api";
import { gold, Purity } from "@/lib/sample";
import { Back, Masthead, SampleNote, TRUST } from "../../../ui";
import { useSession } from "../../../use-session";

// Saving in gold: the parents log each purchase, and the engine values the grams at today's price
// for their purity. The app records gold; it never suggests buying it. Sample figures until
// GET /api/gold and a daily price source exist.
const PURITIES: Purity[] = ["24K", "22K", "21K"];
const GOALS = ["Emergency buffer", "Summer trip", "Home deposit", "Set aside for investing"];

export default function GoldPage() {
  const { me } = useSession("PARENT");
  const [logging, setLogging] = useState(false);
  const [purity, setPurity] = useState<Purity>("24K");
  const [goal, setGoal] = useState(GOALS[2]);
  const [grams, setGrams] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [logged, setLogged] = useState<string | null>(null);
  if (!me) return <p className="muted">Loading…</p>;

  function save(e: React.FormEvent) {
    e.preventDefault();
    if (!/^\d+(\.\d{1,3})?$/.test(grams.trim())) {
      setError("Enter the weight in grams, like 10 or 2.5.");
      return;
    }
    setError(null);
    setLogged(`Logged ${grams.trim()} g of ${purity} for ${goal}. The app values it at today's price once saved.`);
    setGrams("");
    setLogging(false);
  }

  return (
    <div className="stack">
      <Back href="/parent/money/accounts" label="Accounts" />
      <Masthead title="Gold" sub="Savings the family keeps as gold" />

      <section className="card strong" style={{ display: "grid", gap: 6 }}>
        <p className="muted">Worth today</p>
        <p className="big">{aed(gold.valueMinor)}</p>
        <p style={{ fontSize: 14 }}>
          {gold.totalGrams.map((g) => `${g.grams} g of ${g.purity}`).join(" and ")} · you paid {aed(gold.paidMinor)}
        </p>
        <p className="est">
          Gold price {PURITIES.map((p) => `${p} ${aed(gold.price[p], { cents: true })}/g`).join(" · ")}
          <br />
          {gold.priceSource}, as of {gold.priceAsOf}
        </p>
      </section>

      <section className="stack" style={{ gap: 10 }}>
        <h2 className="first">What you have logged</h2>
        {gold.purchases.map((p) => (
          <article key={p.id} className="card" style={{ display: "grid", gap: 6 }}>
            <div className="row" style={{ padding: 0 }}>
              <b style={{ fontWeight: 600 }}>{p.what}</b>
              <span className="tier tier-goodlife">{p.grams} g · {p.purity}</span>
            </div>
            <div className="row" style={{ padding: 0 }}><span className="muted">Paid {p.date}, {p.shop}</span><span className="num">{aed(p.paidMinor)}</span></div>
            <div className="row" style={{ padding: 0 }}><span>Worth today</span><b>{aed(p.valueMinor)}</b></div>
            <p className="muted">For {p.goal}</p>
            {p.belowPaid && <p className="card-warn" style={{ gap: 0 }}>{gold.makingNote}</p>}
          </article>
        ))}
      </section>

      {logging ? (
        <form className="panel" onSubmit={save}>
          <strong>Log a gold purchase</strong>
          <label>
            What is it?
            <input placeholder="Coin, bar, bangle" />
          </label>
          <label>
            Weight in grams
            <input inputMode="decimal" value={grams} onChange={(e) => setGrams(e.target.value)} placeholder="10" required />
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
            <input inputMode="decimal" placeholder="3,200" />
          </label>
          <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 10 }}>
            <label>
              Date
              <input type="date" />
            </label>
            <label>
              Shop
              <input placeholder="Gold Souk" />
            </label>
          </div>
          <div style={{ display: "grid", gap: 6 }}>
            <span className="muted">Which goal is it for?</span>
            <div className="chips" style={{ marginBottom: 0 }} role="group" aria-label="Goal">
              {GOALS.map((g) => (
                <button key={g} type="button" className={`chip${g === goal ? " on" : ""}`} aria-pressed={g === goal} onClick={() => setGoal(g)}>{g}</button>
              ))}
            </div>
          </div>
          {error && <p className="error" role="alert">{error}</p>}
          <button type="submit">Log it</button>
        </form>
      ) : (
        <button type="button" onClick={() => setLogging(true)}>Log a gold purchase</button>
      )}
      {logged && <p className="muted" role="status">{logged}</p>}
      <p className="trust">{TRUST} The app records gold you already have. It never tells you to buy it.</p>
      <SampleNote />
    </div>
  );
}
