"use client";

// Drawings and pieces for the kids' screens only: the goal jar, the coin buddy, the camel wallet,
// sticker badges, the big star and the Saver of the Month race.
// They only draw what they are given. Every percent and amount comes from the app, never from here.

type Art = "headphones" | "blocks" | "gift";

export function artFor(goal: string): Art {
  if (goal.includes("headphone")) return "headphones";
  if (goal.includes("building") || goal.includes("lego")) return "blocks";
  return "gift";
}

// The thing the kid is saving for, drawn big and friendly.
export function GoalArt({ art, size = 64 }: { art: Art; size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 64 64" aria-hidden="true" className="goal-art">
      {art === "headphones" && (
        <>
          <path d="M12 38V30a20 20 0 0 1 40 0v8" fill="none" stroke="var(--kid-line)" strokeWidth="5" strokeLinecap="round" />
          <rect x="6" y="34" width="14" height="20" rx="6" fill="var(--kid-a)" stroke="var(--kid-line)" strokeWidth="3" />
          <rect x="44" y="34" width="14" height="20" rx="6" fill="var(--kid-a)" stroke="var(--kid-line)" strokeWidth="3" />
          <circle cx="13" cy="44" r="2.5" fill="var(--kid-sun)" />
          <circle cx="51" cy="44" r="2.5" fill="var(--kid-sun)" />
        </>
      )}
      {art === "blocks" && (
        <>
          <rect x="8" y="36" width="22" height="18" rx="3" fill="var(--kid-a)" stroke="var(--kid-line)" strokeWidth="3" />
          <rect x="32" y="36" width="22" height="18" rx="3" fill="var(--kid-mint)" stroke="var(--kid-line)" strokeWidth="3" />
          <rect x="20" y="16" width="22" height="18" rx="3" fill="var(--kid-sun)" stroke="var(--kid-line)" strokeWidth="3" />
          <rect x="23" y="10" width="6" height="6" rx="1.5" fill="var(--kid-sun)" stroke="var(--kid-line)" strokeWidth="2.5" />
          <rect x="33" y="10" width="6" height="6" rx="1.5" fill="var(--kid-sun)" stroke="var(--kid-line)" strokeWidth="2.5" />
        </>
      )}
      {art === "gift" && (
        <>
          <rect x="10" y="26" width="44" height="30" rx="4" fill="var(--kid-a)" stroke="var(--kid-line)" strokeWidth="3" />
          <rect x="6" y="18" width="52" height="10" rx="3" fill="var(--kid-sun)" stroke="var(--kid-line)" strokeWidth="3" />
          <path d="M32 18v38" stroke="var(--kid-line)" strokeWidth="3" />
          <path d="M32 18c-6-10-16-8-12-2s12 2 12 2c0 0 8 4 12-2s-6-8-12 2z" fill="var(--kid-pink)" stroke="var(--kid-line)" strokeWidth="2.5" />
        </>
      )}
    </svg>
  );
}

// Coins sit at fixed spots inside the jar; only the ones below the fill line show.
const COINS = [
  [44, 146], [70, 150], [96, 146], [56, 132], [84, 134], [40, 118], [70, 120], [100, 118],
  [54, 104], [86, 104], [42, 90], [72, 92], [100, 88], [58, 76], [88, 74], [44, 60], [74, 62], [100, 58],
];

// A glass jar that fills with coins up to `percent` (from the app). Marks at 25, 50 and 75.
export function GoalJar({ percent, size = 140 }: { percent: number; size?: number }) {
  const top = 40;
  const bottom = 160;
  const level = bottom - ((bottom - top) * Math.min(100, Math.max(0, percent))) / 100;
  const body = "M32 34h76q12 0 12 14v94q0 18-18 18H38q-18 0-18-18V48q0-14 12-14z";
  return (
    <svg width={(size * 150) / 140} height={(size * 170) / 140} viewBox="0 0 150 170" role="img" aria-label={`Jar ${percent} percent full`} className="jar">
      <defs>
        <clipPath id="jar-inside">
          <path d={body} />
        </clipPath>
      </defs>
      <path d={body} fill="var(--kid-glass)" />
      <g clipPath="url(#jar-inside)">
        <path
          d={`M0 ${level} q17.5 -7 35 0 t35 0 t35 0 t35 0 V170 H0z`}
          fill="var(--kid-mint)"
          opacity="0.9"
        />
        {COINS.filter(([, y]) => y - 6 > level).map(([x, y]) => (
          <g key={`${x}-${y}`}>
            <circle cx={x} cy={y} r="9" fill="var(--kid-sun)" stroke="var(--kid-sun-deep)" strokeWidth="2" />
            <circle cx={x} cy={y} r="4.5" fill="none" stroke="var(--kid-sun-deep)" strokeWidth="1.5" />
          </g>
        ))}
      </g>
      <rect x="28" y="50" width="7" height="60" rx="3.5" fill="#fff" opacity="0.45" />
      <path d={body} fill="none" stroke="var(--kid-line)" strokeWidth="3.5" />
      <rect x="36" y="10" width="68" height="18" rx="6" fill="var(--kid-a)" stroke="var(--kid-line)" strokeWidth="3.5" />
      <rect x="44" y="28" width="52" height="7" rx="2" fill="var(--kid-line)" />
      {[25, 50, 75].map((m) => {
        const y = bottom - ((bottom - top) * m) / 100;
        return (
          <g key={m}>
            <path d={`M120 ${y}h8`} stroke="var(--kid-line)" strokeWidth="2.5" strokeLinecap="round" />
            <text x="131" y={y + 3.5} fontSize="10" fill="var(--ink-soft)">{m}</text>
          </g>
        );
      })}
    </svg>
  );
}

// The coin buddy who says the nudges. Same face for every kid.
export function Buddy({ size = 56 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 64 64" aria-hidden="true" className="buddy">
      <circle cx="32" cy="32" r="28" fill="var(--kid-sun)" stroke="var(--kid-sun-deep)" strokeWidth="3" />
      <circle cx="32" cy="32" r="21" fill="none" stroke="var(--kid-sun-deep)" strokeWidth="1.5" strokeDasharray="3 4" />
      <ellipse cx="24" cy="29" rx="3" ry="4" fill="#1a2340" />
      <ellipse cx="40" cy="29" rx="3" ry="4" fill="#1a2340" />
      <circle cx="25" cy="27.5" r="1" fill="#fff" />
      <circle cx="41" cy="27.5" r="1" fill="#fff" />
      <circle cx="18" cy="37" r="4" fill="var(--kid-pink)" opacity="0.7" />
      <circle cx="46" cy="37" r="4" fill="var(--kid-pink)" opacity="0.7" />
      <path d="M24 39q8 7 16 0" fill="none" stroke="#1a2340" strokeWidth="2.5" strokeLinecap="round" />
      <path d="M16 18q4-6 10-7" fill="none" stroke="#fff" strokeWidth="3" strokeLinecap="round" opacity="0.6" />
    </svg>
  );
}

// The buddy with a speech bubble.
export function BuddySays({ children }: { children: React.ReactNode }) {
  return (
    <div className="buddy-says">
      <Buddy />
      <div className="bubble">{children}</div>
    </div>
  );
}

// A friendly camel carrying a coin bag: the saving character on the wallet and the Big Saver sticker.
function CamelShape({ fill, line, bag }: { fill: string; line: string; bag: string }) {
  return (
    <g strokeLinejoin="round" strokeLinecap="round">
      <path d="M13 38q-5 2-4 8" fill="none" stroke={line} strokeWidth="2.5" />
      {[16, 23, 36, 43].map((x) => (
        <rect key={x} x={x} y="43" width="5" height="15" rx="2.5" fill={fill} stroke={line} strokeWidth="2.5" />
      ))}
      <path d="M43 38L48 21h7l-3 19z" fill={fill} stroke={line} strokeWidth="2.5" />
      <ellipse cx="30" cy="40" rx="18" ry="9.5" fill={fill} stroke={line} strokeWidth="2.5" />
      <path d="M17 36q7-17 17-1" fill={fill} stroke={line} strokeWidth="2.5" />
      <path d="M33 35q5-11 11-1" fill={fill} stroke={line} strokeWidth="2.5" />
      <ellipse cx="55" cy="21" rx="8" ry="5.5" fill={fill} stroke={line} strokeWidth="2.5" />
      <path d="M50 16.5l-1-4 4 2.5" fill={fill} stroke={line} strokeWidth="2" />
      <circle cx="55.5" cy="19.5" r="1.6" fill={line} />
      <path d="M58 23.5q2 1.2 3.5-.5" fill="none" stroke={line} strokeWidth="1.8" />
      <rect x="22" y="37" width="13" height="11" rx="3.5" fill={bag} stroke={line} strokeWidth="2.5" />
      <circle cx="28.5" cy="42.5" r="2.6" fill="var(--kid-sun)" stroke={line} strokeWidth="1.5" />
    </g>
  );
}

export function Camel({ size = 60 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 66 62" aria-hidden="true">
      <CamelShape fill="var(--kid-sand)" line="var(--kid-line)" bag="var(--kid-a)" />
    </svg>
  );
}

const STAR = "50,6 62,36 95,38 69,58 78,91 50,73 22,91 31,58 5,38 38,36";

// A big star with the count written inside it.
export function BigStar({ count, size = 120 }: { count: number; size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 100 100" role="img" aria-label={`${count} stars`}>
      {[0, 45, 90, 135, 180, 225, 270, 315].map((a) => (
        <rect key={a} x="48.5" y="0" width="3" height="8" rx="1.5" fill="var(--kid-sun-deep)" opacity="0.5" transform={`rotate(${a} 50 50) translate(0 -2)`} />
      ))}
      <polygon points={STAR} fill="var(--kid-sun)" stroke="var(--kid-sun-deep)" strokeWidth="5" strokeLinejoin="round" />
      <text x="50" y="62" textAnchor="middle" fontSize="26" fontWeight="700" fill="#1a2340">{count}</text>
    </svg>
  );
}

export function SmallStar({ size = 18 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 100 100" aria-hidden="true">
      <polygon points={STAR} fill="var(--kid-sun)" stroke="var(--kid-sun-deep)" strokeWidth="8" strokeLinejoin="round" />
    </svg>
  );
}

type Sticker = "camel" | "quarter" | "half" | "threequarter" | "trophy" | "heart";

const STICKER_FILL: Record<Sticker, string> = {
  camel: "var(--kid-sand)",
  quarter: "var(--kid-mint)",
  half: "var(--kid-a)",
  threequarter: "var(--kid-sun)",
  trophy: "var(--kid-sun)",
  heart: "var(--kid-pink)",
};

// A round sticker for a badge. Locked stickers are grey with a small lock.
export function StickerArt({ kind, earned }: { kind: Sticker; earned: boolean }) {
  const fill = earned ? STICKER_FILL[kind] : "var(--track)";
  const line = earned ? "var(--kid-line)" : "var(--cancelled)";
  return (
    <svg width="64" height="64" viewBox="0 0 52 52" aria-hidden="true" className="sticker">
      <circle cx="26" cy="26" r="24" fill={earned ? "var(--kid-card)" : "transparent"} stroke={line} strokeWidth="2.5" strokeDasharray={earned ? undefined : "4 3"} />
      {kind === "camel" && (
        <g transform="translate(9.5 10) scale(0.5)">
          <CamelShape fill={fill} line={line} bag={earned ? "var(--kid-a)" : "var(--track)"} />
        </g>
      )}
      {(kind === "quarter" || kind === "half" || kind === "threequarter") && (
        <>
          <circle cx="26" cy="26" r="14" fill="none" stroke={line} strokeWidth="2" />
          <path
            d={
              kind === "quarter"
                ? "M26 26V12a14 14 0 0 1 14 14z"
                : kind === "half"
                  ? "M26 26V12a14 14 0 0 1 0 28z"
                  : "M26 26V12a14 14 0 1 1-14 14z"
            }
            fill={fill}
            stroke={line}
            strokeWidth="2"
            strokeLinejoin="round"
          />
        </>
      )}
      {kind === "trophy" && (
        <path d="M18 14h16v6a8 8 0 0 1-16 0zM26 28v6M20 38h12M18 16h-4a4 4 0 0 0 4 6M34 16h4a4 4 0 0 1-4 6" fill={fill} stroke={line} strokeWidth="2" strokeLinejoin="round" strokeLinecap="round" />
      )}
      {kind === "heart" && <path d="M26 38s-12-7-12-15a6 6 0 0 1 12-2 6 6 0 0 1 12 2c0 8-12 15-12 15z" fill={fill} stroke={line} strokeWidth="2" strokeLinejoin="round" />}
      {!earned && (
        <g transform="translate(34 34)">
          <circle r="8" fill="var(--kid-card)" stroke={line} strokeWidth="1.5" />
          <rect x="-4" y="-1" width="8" height="6" rx="1.5" fill={line} />
          <path d="M-2.5 -1v-2a2.5 2.5 0 0 1 5 0v2" fill="none" stroke={line} strokeWidth="1.5" />
        </g>
      )}
    </svg>
  );
}

// Saver of the Month as a race: each kid's face sits at their percent of their own goal.
export function Race({ runners, me }: { runners: { name: string; percent: number }[]; me: string }) {
  return (
    <div className="race">
      {runners.map((r, i) => (
        <div className="lane" key={r.name}>
          <span className="lane-name">{r.name === me ? "You" : r.name}</span>
          <div className="lane-track" role="img" aria-label={`${r.name} ${r.percent} percent of their own goal`}>
            <span
              className={`runner${i === 0 ? " lead" : ""}`}
              style={{ left: `${Math.min(100, r.percent)}%` }}
            >
              {r.name.slice(0, 1)}
            </span>
            <svg className="flag" width="20" height="24" viewBox="0 0 20 24" aria-hidden="true">
              <path d="M3 2v21" stroke="var(--kid-line)" strokeWidth="2.5" strokeLinecap="round" />
              <path d="M4 3h13v9H4z" fill="var(--kid-card)" stroke="var(--kid-line)" strokeWidth="1.5" />
              <path d="M4 3h4.3v4.5H4zM12.6 3H17v4.5h-4.4zM8.3 7.5h4.3V12H8.3z" fill="var(--kid-line)" />
            </svg>
          </div>
          <b className="lane-pct">{r.percent}%</b>
        </div>
      ))}
    </div>
  );
}

export function Avatar({ name }: { name: string }) {
  return <span className="avatar" aria-hidden="true">{name.slice(0, 1)}</span>;
}
