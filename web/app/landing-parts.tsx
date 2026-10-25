"use client";

import { useState } from "react";
import { Coach } from "./coach";

// The landing page's one interactive piece: pick an amount a week and see what it becomes.
// An illustration in whole dirhams times weeks, nothing more. The app's own figures always come from its money engine.
const WEEKLY = [10, 20, 50, 100];
const HEADPHONES = 300;
const fmt = (n: number) => n.toLocaleString("en-US");

export function SaveCalculator() {
  const [weekly, setWeekly] = useState(20);
  const year = weekly * 52;
  const fiveYears = year * 5;
  const weeks = Math.ceil(HEADPHONES / weekly);

  return (
    <div className="lp-calc">
      <div className="lp-calc-head">
        <Coach size={76} />
        <p className="lp-say" aria-live="polite">AED {weekly} a week becomes AED {fmt(year)} by the end of the year.</p>
      </div>
      <p className="lp-calc-q" id="weekly-label">Put aside each week</p>
      <div className="lp-calc-chips" role="group" aria-labelledby="weekly-label">
        {WEEKLY.map((w) => (
          <button type="button" key={w} aria-pressed={w === weekly} onClick={() => setWeekly(w)}>
            AED {w}
          </button>
        ))}
      </div>
      <dl className="lp-calc-out">
        <div className="lead">
          <dt>In a year</dt>
          <dd>AED {fmt(year)}</dd>
        </div>
        <div>
          <dt>In five years</dt>
          <dd>AED {fmt(fiveYears)}</dd>
        </div>
        <div>
          <dt>Layla&apos;s AED {HEADPHONES} headphones</dt>
          <dd>{weeks} {weeks === 1 ? "week" : "weeks"}</dd>
        </div>
      </dl>
      <p className="lp-calc-note">Plain saving, before any profit. Information only, not financial advice.</p>
    </div>
  );
}
