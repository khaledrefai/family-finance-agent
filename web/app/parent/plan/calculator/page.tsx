"use client";

import { useSearchParams } from "next/navigation";
import { Suspense, useEffect, useRef, useState } from "react";
import { Calculator, calculatorApi, CalculatorAsk, YearLine } from "@/lib/calculator";
import { Back, Loading, Masthead, TRUST } from "../../../ui";
import { useSession } from "../../../use-session";

// The long-term savings calculator: what a starting amount plus the same amount each month could come to after
// 1, 5 and 10 years at a rate the parent picks, next to the same money kept as cash. It opens from the Set aside
// for investing bucket (?from=invest) or a family goal (?goal=<id>) with their numbers filled in; anything typed
// replaces them. The core works out every figure (POST /api/plan/calculator). Learning only: the rate is the
// parent's example, and the app never names a fund, a product, gold or a time to buy.
const RATES = ["0", "3", "5", "7"];
const YEARS = [1, 5, 10, 20];

export default function CalculatorPage() {
  return (
    <Suspense fallback={<Loading />}>
      <CalculatorScreen />
    </Suspense>
  );
}

// "AED 1,035" in the box as "1035": the core's own amount, written the way a person types it
const typed = (minor: number) => (minor % 100 === 0 ? String(minor / 100) : (minor / 100).toFixed(2));

function CalculatorScreen() {
  const { me } = useSession("PARENT");
  const params = useSearchParams();
  const goalId = params.get("goal") ?? undefined;
  const fromInvest = params.get("from") === "invest";
  const [result, setResult] = useState<Calculator | null>(null);
  const [start, setStart] = useState("");
  const [monthly, setMonthly] = useState("");
  const [rate, setRate] = useState("5");
  const [other, setOther] = useState(false);
  const [years, setYears] = useState(10);
  const [target, setTarget] = useState("");
  const [error, setError] = useState<string | null>(null);
  const filled = useRef(false);

  // First visit: the goal's or the bucket's own numbers
  useEffect(() => {
    if (!me) return;
    (async () => {
      try {
        const c = await calculatorApi.project({ goalId, from: !goalId && fromInvest ? "INVEST_ASIDE" : undefined, rate, years });
        setResult(c);
        setStart(typed(c.projection.start.minor));
        setMonthly(typed(c.projection.monthly.minor));
        if (c.projection.reach) setTarget(typed(c.projection.reach.target.minor));
        filled.current = true;
      } catch (e) {
        // Nothing to start from (no investing bucket yet): the parent types the numbers
        setError((e as Error).message);
        setMonthly("1000");
        filled.current = true;
      }
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [me]);

  // Every change goes back to the core, a moment after the typing stops
  useEffect(() => {
    if (!me || !filled.current) return;
    const ask: CalculatorAsk = { goalId, start: start || "0", monthly: monthly || "0", rate: rate || "0", years, target: target || undefined };
    const t = setTimeout(async () => {
      try {
        setResult(await calculatorApi.project(ask));
        setError(null);
      } catch (e) {
        setError((e as Error).message);
      }
    }, 350);
    return () => clearTimeout(t);
  }, [me, goalId, start, monthly, rate, years, target]);

  if (!me) return <Loading />;
  const p = result?.projection;

  return (
    <div className="stack">
      {fromInvest ? <Back href="/parent/plan/buckets#invest" label="Buckets" /> : <Back href="/parent/plan" label="Plan" />}
      <Masthead title="Savings calculator" sub="What saving each month could become over the years" />

      <p className="learn-note">
        <b>Learning only.</b> You pick the rate. It is an example, not a forecast, and the app never names a fund or a product.
      </p>

      {result?.fromLine && <section className="card"><p style={{ margin: 0 }}>{result.fromLine}</p></section>}

      <section className="card calc-form">
        <label>
          Already saved (AED)
          <input inputMode="decimal" value={start} onChange={(e) => setStart(e.target.value)} placeholder="0" />
        </label>
        <label>
          Each month (AED)
          <input inputMode="decimal" value={monthly} onChange={(e) => setMonthly(e.target.value)} placeholder="1000" />
        </label>
        {result?.from === "GOAL" && (
          <label>
            Target (AED)
            <input inputMode="decimal" value={target} onChange={(e) => setTarget(e.target.value)} />
          </label>
        )}
        <div>
          <p className="field-label" id="rate-label">Example rate a year</p>
          <div className="chips" style={{ marginBottom: 0 }} role="group" aria-labelledby="rate-label">
            {RATES.map((r) => (
              <button type="button" key={r} className={`chip${!other && rate === r ? " on" : ""}`} aria-pressed={!other && rate === r}
                onClick={() => { setOther(false); setRate(r); }}>
                {r}%{r === "0" ? " (cash)" : ""}
              </button>
            ))}
            <button type="button" className={`chip${other ? " on" : ""}`} aria-pressed={other} onClick={() => setOther(true)}>Other</button>
          </div>
          {other && (
            <label style={{ marginTop: 8 }}>
              Your rate (% a year, 0 to 15)
              <input inputMode="decimal" value={rate} onChange={(e) => setRate(e.target.value)} autoFocus />
            </label>
          )}
        </div>
        <div>
          <p className="field-label" id="years-label">Look ahead</p>
          <div className="seg" role="group" aria-labelledby="years-label">
            {YEARS.map((y) => (
              <button type="button" key={y} aria-pressed={years === y} onClick={() => setYears(y)}>
                {y} {y === 1 ? "year" : "years"}
              </button>
            ))}
          </div>
        </div>
        {error && <p className="error" role="alert">{error}</p>}
      </section>

      {p && (
        <>
          <section className="card calc-marks" aria-live="polite">
            {p.marks.map((m) => (
              <div key={m.year} className="calc-mark">
                <span className="card-title">In {m.year} {m.year === 1 ? "year" : "years"}</span>
                <span className="num calc-total">{m.total.text}</span>
                <span className="muted calc-split">{m.putIn.text} put in{p.rateBp > 0 && <> · {m.growth.text} growth</>}</span>
              </div>
            ))}
          </section>

          <section className="card" style={{ display: "grid", gap: 8 }}>
            <p className="card-title">Over {p.years} {p.years === 1 ? "year" : "years"}</p>
            <GrowthLines points={p.everyYear} rate={p.rateText} label={p.summary} />
            <p className="muted">{p.summary}</p>
          </section>

          {p.reach && (
            <section className="card" style={{ display: "grid", gap: 4 }}>
              <p className="card-title">{result?.goalName ?? "Your target"}: {p.reach.target.text}</p>
              <p style={{ margin: 0 }}>{p.reach.line}</p>
            </section>
          )}

          <section className="card">
            <p className="card-title" style={{ marginBottom: 4 }}>Good to know</p>
            <ul className="calc-notes">
              {p.notes.map((n) => <li key={n}>{n}</li>)}
            </ul>
          </section>
        </>
      )}
      {!p && !error && <Loading />}
      <p className="trust" style={{ textAlign: "center" }}>{TRUST}</p>
    </div>
  );
}

// Two lines over the years: the total at the example rate, and the same money kept as cash. Heights are the
// engine's shares of the top total (totalBp, cashBp); the chart only places them. Labels sit at the line ends.
function GrowthLines({ points, rate, label }: { points: YearLine[]; rate: string; label: string }) {
  const W = 326, left = 8, right = 236, top = 14, bottom = 128;
  const last = points.length - 1;
  const x = (i: number) => left + ((right - left) * i) / Math.max(1, last);
  const y = (bp: number) => bottom - ((bottom - top) * bp) / 10_000;
  const path = (key: "totalBp" | "cashBp") => points.map((pt, i) => `${i ? "L" : "M"} ${x(i)} ${y(pt[key])}`).join(" ");
  const end = points[last];
  const apart = Math.abs(y(end.totalBp) - y(end.cashBp)) >= 26;
  const ticks = [...new Set([0, Math.round(last / 2), last])];
  return (
    <svg viewBox={`0 0 ${W} 156`} role="img" aria-label={label} style={{ display: "block", width: "100%", height: "auto", overflow: "visible" }}>
      <line x1={left} x2={right} y1={bottom} y2={bottom} stroke="var(--rule)" />
      <line x1={left} x2={right} y1={top} y2={top} stroke="var(--rule-soft)" />
      <path d={path("cashBp")} fill="none" stroke="var(--ink-soft)" strokeWidth="2" strokeDasharray="5 5" strokeLinecap="round" />
      <path d={path("totalBp")} fill="none" stroke="var(--s1)" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" />
      <circle cx={x(last)} cy={y(end.totalBp)} r="4.5" fill="var(--s1)" stroke="var(--chit)" strokeWidth="2" />
      <circle cx={x(last)} cy={y(end.cashBp)} r="3.5" fill="var(--chit)" stroke="var(--ink-soft)" strokeWidth="2" />
      <text x={right + 10} y={y(end.totalBp) + 4} fontSize="11" fontWeight="600" fill="var(--ink)">{end.total.text}</text>
      <text x={right + 10} y={y(end.totalBp) + 17} fontSize="10" fill="var(--ink-soft)">at {rate}</text>
      <text x={right + 10} y={y(end.cashBp) + (apart ? 4 : 30)} fontSize="11" fontWeight="600" fill="var(--ink)">{end.asCash.text}</text>
      <text x={right + 10} y={y(end.cashBp) + (apart ? 17 : 43)} fontSize="10" fill="var(--ink-soft)">as cash</text>
      {ticks.map((i) => (
        <text key={i} x={x(i)} y={148} textAnchor={i === 0 ? "start" : i === last ? "end" : "middle"} fontSize="10" fill="var(--ink-soft)">
          {i === 0 ? "Now" : `Year ${points[i].year}`}
        </text>
      ))}
    </svg>
  );
}
