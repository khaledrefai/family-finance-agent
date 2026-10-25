"use client";

import { useEffect, useRef, useState } from "react";

// The landing page's one interactive piece: the same amount each month, kept as cash or invested, over 20 years.
// An illustration only (cash at 0%, invested at an example 5% a year, compounded monthly). The app's own figures
// always come from its money engine, and the app does not invest anyone's money.
const MONTHLY = [500, 1000, 2000, 5000];
const YEARS = 20;
const RATE = 0.05;
const SHOWN = [5, 10, 15, 20];

const aed = (n: number) => `AED ${n.toLocaleString("en-US")}`;
const compact = (n: number) => (n >= 1_000_000 ? `${+(n / 1_000_000).toFixed(1)}M` : n >= 1000 ? `${Math.round(n / 1000)}K` : `${n}`);

function grow(monthly: number) {
  const r = RATE / 12;
  return Array.from({ length: YEARS + 1 }, (_, year) => {
    const n = 12 * year;
    return { year, cash: monthly * n, invested: n === 0 ? 0 : Math.round((monthly * ((1 + r) ** n - 1)) / r) };
  });
}

// A round step that keeps the top tick close to the data (at most about 5 steps)
function niceStep(max: number) {
  const raw = max / 5;
  const pow = 10 ** Math.floor(Math.log10(raw));
  for (const m of [1, 2, 2.5, 5]) if (m * pow >= raw) return m * pow;
  return 10 * pow;
}

// Drawn at the width it is shown, so one unit is one pixel and the text stays readable on a phone
const L = 44, T = 14, B = 30;

export function GrowthChart() {
  const [monthly, setMonthly] = useState(1000);
  const [at, setAt] = useState<number | null>(null);
  const [W, setW] = useState(560);
  const box = useRef<HTMLDivElement>(null);
  const svg = useRef<SVGSVGElement>(null);
  useEffect(() => {
    const el = box.current;
    if (!el) return;
    const ro = new ResizeObserver(([e]) => setW(Math.max(280, Math.round(e.contentRect.width))));
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  const H = W < 420 ? 230 : 280;
  const R = W < 420 ? 82 : 100;
  const PW = W - L - R, PH = H - T - B;

  const data = grow(monthly);
  const end = data[YEARS];
  const step = niceStep(end.invested);
  const top = Math.ceil(end.invested / step) * step;
  const ticks = Array.from({ length: Math.round(top / step) + 1 }, (_, i) => i * step);
  const x = (year: number) => L + (PW * year) / YEARS;
  const y = (v: number) => T + PH * (1 - v / top);
  const line = (key: "cash" | "invested") => data.map((d, i) => `${i ? "L" : "M"}${x(d.year)} ${y(d[key])}`).join("");
  const band = `${line("invested")}${[...data].reverse().map((d) => `L${x(d.year)} ${y(d.cash)}`).join("")}Z`;

  function pick(clientX: number) {
    const box = svg.current?.getBoundingClientRect();
    if (!box) return;
    const px = ((clientX - box.left) / box.width) * W;
    setAt(Math.max(0, Math.min(YEARS, Math.round(((px - L) / PW) * YEARS))));
  }
  function key(e: React.KeyboardEvent) {
    if (e.key !== "ArrowLeft" && e.key !== "ArrowRight") return;
    e.preventDefault();
    setAt((a) => Math.max(0, Math.min(YEARS, (a ?? YEARS) + (e.key === "ArrowRight" ? 1 : -1))));
  }
  const hover = at === null ? null : data[at];

  return (
    <div className="lp-grow">
      <div className="lp-grow-head">
        <p className="lp-grow-q" id="monthly-label">Put aside each month</p>
        <div className="lp-chips" role="group" aria-labelledby="monthly-label">
          {MONTHLY.map((m) => (
            <button type="button" key={m} aria-pressed={m === monthly} onClick={() => setMonthly(m)}>
              AED {m.toLocaleString("en-US")}
            </button>
          ))}
        </div>
      </div>

      <p className="lp-grow-stat" aria-live="polite">
        In 20 years you put in <b>{aed(end.cash)}</b>. Invested in this example, it becomes <b>{aed(end.invested)}</b>,
        or {aed(end.invested - end.cash)} more.
      </p>

      <div className="lp-legend" aria-hidden="true">
        <span><i className="key invested" />Invested, example 5% a year</span>
        <span><i className="key cash" />Kept as cash</span>
      </div>

      <div className="lp-chart" ref={box}>
        <svg
          ref={svg}
          viewBox={`0 0 ${W} ${H}`}
          width={W}
          height={H}
          role="img"
          tabIndex={0}
          aria-label={`Over 20 years, AED ${monthly.toLocaleString("en-US")} a month kept as cash becomes ${aed(end.cash)}. Invested at an example 5% a year it becomes ${aed(end.invested)}. Use the left and right arrow keys to read each year.`}
          onPointerMove={(e) => pick(e.clientX)}
          onPointerLeave={() => setAt(null)}
          onFocus={() => setAt((a) => a ?? YEARS)}
          onBlur={() => setAt(null)}
          onKeyDown={key}
        >
          {ticks.map((t) => (
            <g key={t}>
              <line x1={L} x2={L + PW} y1={y(t)} y2={y(t)} className={t === 0 ? "axis" : "grid"} />
              <text x={L - 8} y={y(t) + 4} textAnchor="end" className="tick">{compact(t)}</text>
            </g>
          ))}
          {(W < 420 ? [0, 10, 20] : [0, 5, 10, 15, 20]).map((yr) => (
            <text key={yr} x={x(yr)} y={H - 8} textAnchor={yr === 0 ? "start" : yr === YEARS ? "end" : "middle"} className="tick">
              {yr === 0 ? "Now" : yr === YEARS ? "20 years" : `${yr}`}
            </text>
          ))}
          <path d={band} className="band" />
          <path d={line("cash")} className="ln cash" />
          <path d={line("invested")} className="ln invested" />
          {hover && (
            <>
              <line x1={x(hover.year)} x2={x(hover.year)} y1={T} y2={T + PH} className="cross" />
              <circle cx={x(hover.year)} cy={y(hover.cash)} r="4.5" className="dot cash" />
              <circle cx={x(hover.year)} cy={y(hover.invested)} r="4.5" className="dot invested" />
            </>
          )}
          <circle cx={x(YEARS)} cy={y(end.cash)} r="4.5" className="dot cash" />
          <circle cx={x(YEARS)} cy={y(end.invested)} r="4.5" className="dot invested" />
          <text x={x(YEARS) + 12} y={y(end.invested) - 2} className="end-v">AED {compact(end.invested)}</text>
          <text x={x(YEARS) + 12} y={y(end.invested) + 14} className="end-l">invested</text>
          <text x={x(YEARS) + 12} y={y(end.cash) - 2} className="end-v">AED {compact(end.cash)}</text>
          <text x={x(YEARS) + 12} y={y(end.cash) + 14} className="end-l">as cash</text>
        </svg>
        {hover && (
          <div className="lp-tip" style={{ left: Math.min(Math.max(x(hover.year), 86), W - 86) }} aria-hidden="true">
            <p className="lp-tip-h">{hover.year === 0 ? "Now" : `After ${hover.year} ${hover.year === 1 ? "year" : "years"}`}</p>
            <p><i className="key invested" /><b>{aed(hover.invested)}</b> invested</p>
            <p><i className="key cash" /><b>{aed(hover.cash)}</b> as cash</p>
          </div>
        )}
      </div>

      <details className="more">
        <summary>Show as a table</summary>
        <table className="lp-table">
          <thead>
            <tr><th scope="col">After</th><th scope="col">Kept as cash</th><th scope="col">Invested, example 5%</th></tr>
          </thead>
          <tbody>
            {SHOWN.map((yr) => (
              <tr key={yr}><th scope="row">{yr} years</th><td>{aed(data[yr].cash)}</td><td>{aed(data[yr].invested)}</td></tr>
            ))}
          </tbody>
        </table>
      </details>
      <p className="lp-note">
        An example at 5% a year, not a forecast: investments can fall as well as rise. The app sets money aside for
        investing and keeps track of gold; it does not invest for you. Information only, not financial advice.
      </p>
    </div>
  );
}
