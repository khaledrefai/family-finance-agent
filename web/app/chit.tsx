import { RequestStatus, SpendingRequest } from "@/lib/api";

// Plain words for each status, so nobody reads raw enum names. The stamp colour comes from the class.
const STAMP: Record<RequestStatus, { cls: string; word: string; note?: string }> = {
  PENDING: { cls: "waiting", word: "Waiting" },
  APPROVED: { cls: "approved", word: "Approved" },
  REJECTED: { cls: "declined", word: "Declined" },
  CANCELLED: { cls: "cancelled", word: "Cancelled" },
  BLOCKED: { cls: "blocked", word: "Blocked", note: "by a family rule" },
};

export function Stamp({ status, landed = false }: { status: RequestStatus; landed?: boolean }) {
  const s = STAMP[status];
  return (
    <span className={`stamp ${s.cls}${landed ? " landed" : ""}`}>
      {s.word}
      {s.note && <small>{s.note}</small>}
    </span>
  );
}

// Splits "AED 300.00" into the currency and the figure, so the figure can be set large.
// It only reformats what the money engine sent; it never calculates.
export function Amount({ minor, currency }: { minor: number; currency: string }) {
  return (
    <p className="chit-amount">
      <span className="cur">{currency}</span>
      {(minor / 100).toFixed(2)}
    </p>
  );
}

// A request shown as a tear-off slip. The stamp in the corner is the decision.
export function Chit({
  request,
  from,
  small = false,
  landed = false,
  stamp = true,
  children,
}: {
  request: SpendingRequest;
  from?: string;
  small?: boolean;
  landed?: boolean;
  stamp?: boolean;
  children?: React.ReactNode;
}) {
  return (
    <article className={`chit${small ? " small" : ""}`} aria-label={`${request.title}, ${STAMP[request.status].word}`}>
      {from && <p className="chit-from">{from}</p>}
      <p className="chit-title">{request.title}</p>
      <Amount minor={request.amountMinor} currency={request.currency} />
      {stamp && <Stamp status={request.status} landed={landed} />}
      {request.agentSummary && <p className="chit-summary">{request.agentSummary}</p>}
      {children}
    </article>
  );
}
