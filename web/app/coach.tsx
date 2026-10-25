// The coach: the coin buddy from the kids' screens in a coach's cap, with the app's green stamp tick on the badge
// and on the clipboard. Used on the landing page. Colours are fixed so it looks the same on every surface.
const GOLD = "#ffc83d";
const GOLD_DEEP = "#b07a00";
const INK = "#1a2340";
const CAP = "#17203a";
const TICK = "#1d6b3f";

export function Coach({ size = 160, wave = false, title, className }: { size?: number; wave?: boolean; title?: string; className?: string }) {
  return (
    <svg
      width={size}
      height={(size * 146) / 140}
      viewBox="0 0 140 146"
      className={className}
      role={title ? "img" : undefined}
      aria-label={title}
      aria-hidden={title ? undefined : true}
    >
      <ellipse cx="64" cy="140" rx="36" ry="5" fill={INK} opacity="0.12" />

      {/* the waving arm, behind the coin */}
      <g className={wave ? "coach-wave" : undefined}>
        <path d="M102 96 C114 92 121 80 123 64" fill="none" stroke={GOLD_DEEP} strokeWidth="11" strokeLinecap="round" />
        <path d="M102 96 C114 92 121 80 123 64" fill="none" stroke={GOLD} strokeWidth="5.5" strokeLinecap="round" />
        <circle cx="124" cy="57" r="8.5" fill={GOLD} stroke={GOLD_DEEP} strokeWidth="3" />
      </g>

      {/* the coin */}
      <circle cx="64" cy="86" r="46" fill={GOLD} stroke={GOLD_DEEP} strokeWidth="4" />
      <circle cx="64" cy="86" r="36" fill="none" stroke={GOLD_DEEP} strokeWidth="1.6" strokeDasharray="3 4" />
      <path d="M33 74 q5 -13 17 -18" fill="none" stroke="#fff" strokeWidth="4" strokeLinecap="round" opacity="0.55" />

      {/* face */}
      <ellipse cx="51" cy="85" rx="4.5" ry="6" fill={INK} />
      <ellipse cx="77" cy="85" rx="4.5" ry="6" fill={INK} />
      <circle cx="52.6" cy="83" r="1.5" fill="#fff" />
      <circle cx="78.6" cy="83" r="1.5" fill="#fff" />
      <circle cx="40" cy="96" r="5.5" fill="#ff9fb2" opacity="0.7" />
      <circle cx="88" cy="96" r="5.5" fill="#ff9fb2" opacity="0.7" />
      <path d="M51 98 q13 11 26 0" fill="none" stroke={INK} strokeWidth="3.2" strokeLinecap="round" />

      {/* the coach's cap, with the stamp tick on its badge */}
      <path d="M26 61 C26 22 102 22 102 61 Z" fill={CAP} stroke="#3a4570" strokeWidth="1.5" strokeLinejoin="round" />
      <path d="M64 31 V60" stroke="#2c3760" strokeWidth="2" />
      <circle cx="64" cy="31" r="3.6" fill="#2c3760" />
      <path d="M21 61 Q64 78 107 61 Q64 67 21 61 Z" fill="#0e1528" stroke="#3a4570" strokeWidth="1.2" strokeLinejoin="round" />
      <rect x="53" y="40" width="22" height="14" rx="3.5" fill="#fff" />
      <path d="M58.5 47 l3.2 3.2 l6.6 -6.6" fill="none" stroke={TICK} strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round" />

      {/* the clipboard, held in front */}
      <rect x="3" y="104" width="28" height="34" rx="3.5" fill="#fff" stroke={CAP} strokeWidth="2.5" />
      <rect x="10" y="100" width="14" height="7" rx="2" fill={CAP} />
      <path d="M8.5 115h12M8.5 121h9" stroke="#c9cfdb" strokeWidth="2.2" strokeLinecap="round" />
      <path d="M9 129.5 l3 3 l6.5 -6.5" fill="none" stroke={TICK} strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M27 104 C22 108 21 113 24 117" fill="none" stroke={GOLD_DEEP} strokeWidth="10" strokeLinecap="round" />
      <path d="M27 104 C22 108 21 113 24 117" fill="none" stroke={GOLD} strokeWidth="5" strokeLinecap="round" />
      <circle cx="26" cy="120" r="7" fill={GOLD} stroke={GOLD_DEEP} strokeWidth="3" />
    </svg>
  );
}
