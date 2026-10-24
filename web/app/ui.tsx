"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect } from "react";
import { aed, plain } from "@/lib/api";
import { SAMPLE_NOTE } from "@/lib/sample";

// Shared pieces for the parent and kid screens: tab bars, icons, draft cards, the "Why this?" sheet
// and the four charts. Charts only draw what the money engine (or the sample data) sends;
// bar positions arrive as shares, so nothing here does sums.

const ICONS: Record<string, React.ReactNode> = {
  today: <path d="M3 11l9-7 9 7v9a1 1 0 0 1-1 1h-5v-6h-6v6H4a1 1 0 0 1-1-1z" />,
  money: (
    <>
      <path d="M3 7h16a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />
      <path d="M3 7l12-4v4" />
      <circle cx="16.5" cy="13.5" r="1.2" />
    </>
  ),
  plan: (
    <>
      <path d="M5 21V4" />
      <path d="M5 4h11l-2 4 2 4H5" />
    </>
  ),
  family: (
    <>
      <circle cx="9" cy="8" r="3" />
      <path d="M3 20c0-3.3 2.7-6 6-6s6 2.7 6 6" />
      <circle cx="17" cy="9" r="2.4" />
      <path d="M16 14.2c2.8.3 5 2.6 5 5.8" />
    </>
  ),
  ask: <path d="M4 5h16v11H9l-5 4z" />,
  star: <path d="M12 3l2.7 5.6 6.1.9-4.4 4.3 1 6.1L12 17l-5.4 2.9 1-6.1L3.2 9.5l6.1-.9z" />,
  bell: (
    <>
      <path d="M6 8a6 6 0 1 1 12 0c0 7 3 8 3 8H3s3-1 3-8" />
      <path d="M10 20a2 2 0 0 0 4 0" />
    </>
  ),
  receipt: (
    <>
      <path d="M6 3h12v18l-3-2-3 2-3-2-3 2z" />
      <path d="M9 8h6M9 12h6" />
    </>
  ),
  plus: <path d="M12 5v14M5 12h14" />,
  clip: <path d="M21 12.5l-8.5 8.5a5 5 0 0 1-7-7L14 5.5a3.5 3.5 0 0 1 5 5L10.5 19a2 2 0 0 1-3-3L15 8.5" />,
  warn: (
    <>
      <path d="M12 3l10 18H2z" />
      <path d="M12 10v5M12 18h.01" />
    </>
  ),
  headphones: (
    <>
      <path d="M4 15v-3a8 8 0 0 1 16 0v3" />
      <rect x="3" y="14" width="4" height="6" rx="1.5" />
      <rect x="17" y="14" width="4" height="6" rx="1.5" />
    </>
  ),
};

export function Icon({ name, size = 22 }: { name: keyof typeof ICONS; size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      {ICONS[name]}
    </svg>
  );
}

type Tab = { href: string; label: string; icon: keyof typeof ICONS };

const PARENT_TABS: Tab[] = [
  { href: "/parent", label: "Today", icon: "today" },
  { href: "/parent/money", label: "Money", icon: "money" },
  { href: "/parent/plan", label: "Plan", icon: "plan" },
  { href: "/parent/family", label: "Family", icon: "family" },
  { href: "/parent/ask", label: "Ask", icon: "ask" },
];

const CHILD_TABS: Tab[] = [
  { href: "/child", label: "Home", icon: "today" },
  { href: "/child/ask", label: "Ask", icon: "ask" },
  { href: "/child/stars", label: "Stars", icon: "star" },
];

// The bottom tab bar. A tab is current for its own page and every page under it,
// except the two home tabs, which only match themselves (and the request and summary pages for Today).
export function TabBar({ who }: { who: "parent" | "child" }) {
  const path = usePathname();
  const tabs = who === "parent" ? PARENT_TABS : CHILD_TABS;
  const home = tabs[0].href;
  const current = (href: string) =>
    href === home ? path === home || path.startsWith(`${home}/why`) || path.startsWith(`${home}/summary`) : path.startsWith(href);

  // Room for the fixed bar at the bottom of the page.
  useEffect(() => {
    const main = document.querySelector("main");
    main?.classList.add("with-tabs");
    return () => main?.classList.remove("with-tabs");
  }, []);

  return (
    <nav className="tabbar" aria-label={who === "parent" ? "Parent" : "Kid"}>
      <div className="tabbar-inner">
        {tabs.map((t) => (
          <Link key={t.href} href={t.href} className="tab" aria-current={current(t.href) ? "page" : undefined}>
            <Icon name={t.icon} />
            {t.label}
          </Link>
        ))}
      </div>
    </nav>
  );
}

export function Masthead({ title, sub, action }: { title: string; sub?: string; action?: React.ReactNode }) {
  return (
    <header className="masthead">
      <div>
        <h1>{title}</h1>
        {sub && <p className="muted">{sub}</p>}
      </div>
      {action}
    </header>
  );
}

export function Back({ href, label }: { href: string; label: string }) {
  return (
    <Link href={href} className="back">
      ‹ {label}
    </Link>
  );
}

// Shown on any screen that still uses sample figures.
export function SampleNote() {
  return <p className="sample">{SAMPLE_NOTE}</p>;
}

export const TRUST = "Worked out exactly by the app, not guessed by the AI.";

export function BigAmount({ minor }: { minor: number }) {
  return (
    <p className="big">
      <span className="cur">AED</span>
      {plain(minor)}
    </p>
  );
}

export function Progress({ percent, label, thin = false }: { percent: number; label: string; thin?: boolean }) {
  return (
    <div className={`bar${thin ? " thin" : ""}`} role="img" aria-label={label}>
      <span style={{ width: `${percent}%` }} />
    </div>
  );
}

// A bottom sheet listing the figures behind a piece of advice.
export function WhySheet({
  title,
  groups,
  footer,
  onClose,
  children,
}: {
  title: string;
  groups: { heading: string; rows: { label: string; value: string; good?: boolean }[] }[];
  footer?: string;
  onClose: () => void;
  children?: React.ReactNode;
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  return (
    <div className="sheet-backdrop" onClick={onClose}>
      <section className="sheet" role="dialog" aria-modal="true" aria-label={title} onClick={(e) => e.stopPropagation()}>
        <span className="grip" aria-hidden="true" />
        <div className="sheet-head">
          <h2>{title}</h2>
          <button type="button" className="close" aria-label="Close" onClick={onClose} autoFocus>
            ×
          </button>
        </div>
        {groups.map((g) => (
          <div key={g.heading}>
            <p className="label">{g.heading}</p>
            <div className="rows">
              {g.rows.map((r) => (
                <div className="row" key={r.label}>
                  <span>{r.label}</span>
                  <b className={r.good ? "good" : undefined}>{r.value}</b>
                </div>
              ))}
            </div>
          </div>
        ))}
        <p className="trust">{footer ?? TRUST}</p>
        {children}
      </section>
    </div>
  );
}

// ---------- Charts ----------

const SERIES: Record<string, string> = { total: "var(--ink)", s1: "var(--s1)", s2: "var(--s2)", s3: "var(--s3)", s4: "var(--s4)" };

// Step-down chart: income at the top, each cost taken off the right end, down to what is spare.
export function Waterfall({ steps, label }: { steps: { label: string; amountMinor: number; from: number; to: number; kind: string }[]; label: string }) {
  return (
    <div className="wf" role="img" aria-label={label}>
      {steps.map((s) => (
        <div className="wf-row" key={s.label} title={`${s.label}: ${aed(s.amountMinor)}`}>
          <div className="row" style={s.kind === "total" ? { fontWeight: 600 } : undefined}>
            <span>{s.label}</span>
            <span className="num">{plain(s.amountMinor)}</span>
          </div>
          <div className="wf-track">
            <span style={{ left: `${s.from}%`, width: `${s.to - s.from}%`, background: SERIES[s.kind] }} />
          </div>
        </div>
      ))}
    </div>
  );
}

// Columns for monthly figures. `share` is each column's height as a share of the tallest.
export function MonthColumns({
  months,
  height = 92,
  refShare,
  refLabel,
  label,
}: {
  months: { m: string; share: number; amountMinor: number; tone?: "tight" | "soft" }[];
  height?: number;
  refShare?: number;
  refLabel?: string;
  label: string;
}) {
  const bars = height - 20; // room for the month name under each column
  return (
    <div className="cols" role="img" aria-label={label} style={{ height, gridTemplateColumns: `repeat(${months.length}, minmax(0, 1fr))` }}>
      {months.map((c, i) => (
        <div className="col" key={`${c.m}${i}`} title={`${c.m}: ${aed(c.amountMinor)}`}>
          <span className={c.tone} style={{ height: (bars * c.share) / 100 }} />
          {c.m}
        </div>
      ))}
      {refShare !== undefined && (
        <>
          <div className="refline" style={{ bottom: 20 + (bars * refShare) / 100 }} aria-hidden="true" />
          {refLabel && (
            <span aria-hidden="true" style={{ position: "absolute", right: 0, bottom: 24 + (bars * refShare) / 100, fontSize: 11, color: "var(--ink-soft)" }}>
              {refLabel}
            </span>
          )}
        </>
      )}
    </div>
  );
}

// One bar per budget: spent against the limit (the dark tick at 75% of the track), overspend in red.
export function BudgetBar({ name, spentMinor, limitMinor, fill, past, overMinor }: { name: string; spentMinor: number; limitMinor: number; fill: number; past: number; overMinor?: number }) {
  const over = past > 0;
  return (
    <div className="bud" title={`${name}: ${aed(spentMinor)} of ${aed(limitMinor)}`}>
      <div className="row" style={{ padding: 0 }}>
        <span>{name}</span>
        <b className={over ? "over" : undefined}>
          {plain(spentMinor)} of {plain(limitMinor)}
        </b>
      </div>
      <div className="bud-track" role="img" aria-label={`${name}: ${aed(spentMinor)} of ${aed(limitMinor)}${over && overMinor ? `, over by ${aed(overMinor)}` : ""}`}>
        <span className={`fill${over ? " capped" : ""}`} style={{ width: `${fill}%` }} />
        {over && <span className="past" style={{ left: `calc(${fill}% + 2px)`, width: `calc(${past}% - 2px)` }} />}
        <span className="limit" style={{ left: "75%" }} />
      </div>
      {over && overMinor && (
        <p className="over" style={{ margin: "2px 0 0", fontSize: 13, display: "flex", gap: 6, alignItems: "center" }}>
          <Icon name="warn" size={14} />
          Over by {aed(overMinor)}
        </p>
      )}
    </div>
  );
}

// Saving toward a goal: solid line for what is saved, dashed line for the plan to the target.
export function SavingLine({
  months,
  actual,
  savedLabel,
  targetLabel,
  label,
}: {
  months: string[];
  actual: { share: number }[];
  savedLabel: string;
  targetLabel: string;
  label: string;
}) {
  const W = 326, left = 40, right = 316, top = 10, bottom = 120;
  const x = (i: number) => left + ((right - left) * i) / (months.length - 1);
  const y = (share: number) => bottom - ((bottom - top) * share) / 100;
  const last = actual.length - 1;
  const path = actual.map((p, i) => `${i === 0 ? "M" : "L"} ${x(i)} ${y(p.share)}`).join(" ");
  const ticks = [0, Math.floor((months.length - 1) / 2), months.length - 1];
  return (
    <svg viewBox={`0 0 ${W} 150`} role="img" aria-label={label} style={{ display: "block", width: "100%", height: "auto", overflow: "visible" }}>
      {[0, 50, 100].map((s) => (
        <line key={s} x1={left} x2={right} y1={y(s)} y2={y(s)} stroke={s === 0 ? "var(--rule)" : "var(--rule-soft)"} />
      ))}
      <text x={34} y={y(0) + 4} textAnchor="end" fontSize="10" fill="var(--ink-soft)">0</text>
      <text x={34} y={y(100) + 4} textAnchor="end" fontSize="10" fill="var(--ink-soft)">Goal</text>
      <path d={`M ${x(last)} ${y(actual[last].share)} L ${right} ${y(100)}`} fill="none" stroke="var(--field)" strokeWidth="2" strokeDasharray="5 5" strokeLinecap="round" />
      <path d={path} fill="none" stroke="var(--ink)" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
      <circle cx={x(last)} cy={y(actual[last].share)} r="5" fill="var(--ink)" stroke="var(--chit)" strokeWidth="2" />
      <circle cx={right} cy={y(100)} r="4" fill="var(--chit)" stroke="var(--field)" strokeWidth="2" />
      <text x={x(last) + 6} y={y(actual[last].share) + 15} fontSize="11" fontWeight="600" fill="var(--ink)">{savedLabel}</text>
      <text x={right - 4} y={y(100) + 18} textAnchor="end" fontSize="11" fill="var(--ink-soft)">{targetLabel}</text>
      {ticks.map((i) => (
        <text key={i} x={x(i)} y={140} textAnchor={i === 0 ? "start" : i === months.length - 1 ? "end" : "middle"} fontSize="10" fill="var(--ink-soft)">
          {months[i]}
        </text>
      ))}
    </svg>
  );
}

// One bar split into parts, with a legend carrying every amount.
export function StackBar({ parts, label }: { parts: { label: string; amountMinor: number; color: string }[]; label: string }) {
  return (
    <>
      <div className="stackbar" role="img" aria-label={label}>
        {parts.map((p) => (
          <span key={p.label} title={`${p.label}: ${aed(p.amountMinor)}`} style={{ flex: p.amountMinor, background: p.color }} />
        ))}
      </div>
      <div className="legend">
        {parts.map((p) => (
          <span key={p.label}>
            <span className="swatch" style={{ background: p.color }} aria-hidden="true" />
            {p.label} · {aed(p.amountMinor)}
          </span>
        ))}
      </div>
    </>
  );
}

// Money flowing from income into each bucket. `from`/`to` are each bucket's place on the income bar
// (shares of income, from the engine). The chart only lays them out; labels sit beside every bucket.
export function BucketFlow({
  incomeMinor,
  buckets,
}: {
  incomeMinor: number;
  buckets: { id: string; name: string; color: string; amountMinor: number; percent: number; from: number; to: number }[];
}) {
  const top = 30;
  const tall = 300; // height of the income bar
  const gap = 6; // space between buckets on the right
  const unit = tall / 100;
  const nodes = buckets.map((b, i) => ({ ...b, y0: top + b.from * unit + i * gap, y1: top + b.to * unit + i * gap }));
  // Labels follow their bucket but never sit closer than 36px to the one above.
  const labels: number[] = [];
  nodes.forEach((n, i) => {
    const mid = (n.y0 + n.y1) / 2;
    labels.push(i === 0 ? Math.max(mid, top + 14) : Math.max(mid, labels[i - 1] + 36));
  });
  const height = Math.max(nodes[nodes.length - 1].y1, labels[labels.length - 1] + 18) + 6;
  const lx = 16;
  const rx = 146;
  return (
    <svg
      width="100%"
      viewBox={`0 0 358 ${height}`}
      role="img"
      aria-label={`Income ${aed(incomeMinor)} split into ${buckets.map((b) => `${b.name} ${aed(b.amountMinor)}`).join(", ")}`}
      className="flow"
    >
      <text x="0" y="16" fontSize="13" fontWeight="600" fill="var(--ink)">Income {aed(incomeMinor)}</text>
      <rect x="0" y={top} width={lx} height={tall} rx="3" fill="var(--ink)" />
      {nodes.map((n) => {
        const a = top + n.from * unit;
        const b = top + n.to * unit;
        const c = (lx + rx) / 2;
        return (
          <a key={n.id} href={`#${n.id}`}>
            <title>{`${n.name}: ${aed(n.amountMinor)}, ${n.percent}% of income`}</title>
            <path
              d={`M${lx} ${a}C${c} ${a} ${c} ${n.y0} ${rx} ${n.y0}V${n.y1}C${c} ${n.y1} ${c} ${b} ${lx} ${b}Z`}
              fill={n.color}
              opacity="0.35"
            />
            <rect x={rx} y={n.y0} width="14" height={Math.max(2, n.y1 - n.y0)} rx="2" fill={n.color} />
          </a>
        );
      })}
      {nodes.map((n, i) => {
        const mid = (n.y0 + n.y1) / 2;
        const ly = labels[i];
        return (
          <g key={n.id}>
            {Math.abs(ly - mid) > 2 && <path d={`M${rx + 16} ${mid}L${rx + 24} ${ly - 4}`} stroke="var(--ink-soft)" strokeWidth="1" />}
            <text x={rx + 28} y={ly - 2} fontSize="13" fontWeight="600" fill="var(--ink)">{n.name}</text>
            <text x={rx + 28} y={ly + 13} fontSize="12" fill="var(--ink-soft)">
              {aed(n.amountMinor)} · {n.percent}%
            </text>
          </g>
        );
      })}
    </svg>
  );
}
