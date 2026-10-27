"use client";

import { useEffect, useState } from "react";
import { MemberView } from "@/lib/api";
import { setupApi, Starter, typed } from "@/lib/setup";
import { Loading, TRUST } from "./ui";

// The forms behind the family plan, shared by the first-run setup (/setup) and by the screens that change the plan
// later (Budgets, Income and bills). Every amount comes from the core; these only send back what a parent typed.

export function today(plusDays = 0) {
  const d = new Date();
  d.setDate(d.getDate() + plusDays);
  return d.toISOString().slice(0, 10);
}

// ---------------------------------------------------------------- income

export function IncomeAddForm({ parents, title, quiet, onAdded }: {
  parents: MemberView[]; title: string; quiet?: boolean; onAdded: () => void;
}) {
  const [f, setF] = useState({ memberId: "", name: "Salary", kind: "FIXED" as "FIXED" | "VARIABLE", amount: "", baseOverride: "", nextOn: today(7) });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const memberId = f.memberId || parents[0]?.id || "";
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => setF({ ...f, [k]: e.target.value });

  async function add(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await setupApi.addIncome({
        memberId: memberId || undefined, name: f.name, kind: f.kind, amount: f.amount, nextOn: f.nextOn,
        baseOverride: f.kind === "VARIABLE" ? f.baseOverride || undefined : undefined,
      });
      // The next one is most likely the other parent's
      setF({ ...f, name: "Salary", amount: "", baseOverride: "", memberId: parents.find((p) => p.id !== memberId)?.id ?? memberId });
      onAdded();
    } catch (err) {
      setError((err as Error).message);
    }
    setBusy(false);
  }

  return (
    <form className="panel setup-form" onSubmit={add}>
      <strong>{title}</strong>
      <div className="two">
        <label>
          Whose
          <select value={memberId} onChange={set("memberId")}>
            {parents.map((p) => <option key={p.id} value={p.id}>{p.displayName}</option>)}
            <option value="">The family</option>
          </select>
        </label>
        <label>
          Called
          <input value={f.name} onChange={set("name")} maxLength={120} required />
        </label>
      </div>
      <label>
        What kind
        <select value={f.kind} onChange={set("kind")}>
          <option value="FIXED">Fixed salary, the same each month</option>
          <option value="VARIABLE">Commission or sales, it changes</option>
        </select>
      </label>
      <div className="two">
        <label>
          {f.kind === "VARIABLE" ? "A usual month (AED)" : "Take-home a month (AED)"}
          <input inputMode="decimal" value={f.amount} onChange={set("amount")} placeholder="24,000" maxLength={14} required />
        </label>
        <label>
          Next pay day
          <input type="date" value={f.nextOn} onChange={set("nextOn")} min={today(0)} required />
        </label>
      </div>
      {f.kind === "VARIABLE" && (
        <label>
          A careful month to plan on (AED)
          <input inputMode="decimal" value={f.baseOverride} onChange={set("baseOverride")} placeholder="A low month, like 6,500" maxLength={14} required />
          <span className="muted" style={{ fontWeight: 400 }}>The plan uses this, so a slow month doesn&apos;t break it. A good month is extra.</span>
        </label>
      )}
      <button type="submit" className={quiet ? "quiet" : undefined} disabled={busy}>{busy ? "Adding…" : "Add this income"}</button>
      {error && <p className="error" role="alert">{error}</p>}
    </form>
  );
}

// ---------------------------------------------------------------- big regular bills

export const BILL_KINDS: { kind: string; label: string; name: string; frequency: string }[] = [
  { kind: "RENT", label: "Rent", name: "Rent", frequency: "QUARTERLY" },
  { kind: "SCHOOL_FEES", label: "School fees", name: "School fees", frequency: "TERMLY" },
  { kind: "LOAN", label: "Loan (car, personal)", name: "Car loan", frequency: "MONTHLY" },
  { kind: "UTILITY", label: "DEWA, cooling, gas", name: "DEWA", frequency: "MONTHLY" },
  { kind: "TELECOM", label: "Phone and internet", name: "Phone and internet", frequency: "MONTHLY" },
  { kind: "INSURANCE", label: "Insurance", name: "Insurance", frequency: "YEARLY" },
  { kind: "SUBSCRIPTION", label: "Subscription", name: "Streaming", frequency: "MONTHLY" },
  { kind: "OTHER", label: "Something else", name: "", frequency: "MONTHLY" },
];
export const HOW_OFTEN: Record<string, string> = {
  MONTHLY: "Every month", QUARTERLY: "Every 3 months (rent cheques)", TERMLY: "Each school term (3 a year)", YEARLY: "Once a year",
};

export function BillAddForm({ onAdded }: { onAdded: () => void }) {
  const [f, setF] = useState({ ...BILL_KINDS[0], amount: "", reserved: "", nextDueOn: today(30) });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function add(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await setupApi.addCommitment({ name: f.name, kind: f.kind, amount: f.amount, frequency: f.frequency, nextDueOn: f.nextDueOn,
        estimate: f.kind === "UTILITY", reserved: f.frequency !== "MONTHLY" ? f.reserved || undefined : undefined });
      setF({ ...f, amount: "", reserved: "" });
      onAdded();
    } catch (err) {
      setError((err as Error).message);
    }
    setBusy(false);
  }

  return (
    <form className="panel setup-form" onSubmit={add}>
      <strong>Add a bill</strong>
      <label>
        What
        <select value={f.kind} onChange={(e) => {
          const k = BILL_KINDS.find((x) => x.kind === e.target.value)!;
          setF({ ...f, ...k });
        }}>
          {BILL_KINDS.map((k) => <option key={k.kind} value={k.kind}>{k.label}</option>)}
        </select>
      </label>
      <div className="two">
        <label>
          Called
          <input value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} maxLength={120} required />
        </label>
        <label>
          Each payment (AED)
          <input inputMode="decimal" value={f.amount} onChange={(e) => setF({ ...f, amount: e.target.value })} placeholder="30,000" maxLength={14} required />
        </label>
      </div>
      <div className="two">
        <label>
          How often
          <select value={f.frequency} onChange={(e) => setF({ ...f, frequency: e.target.value })}>
            {Object.entries(HOW_OFTEN).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
          </select>
        </label>
        <label>
          Next payment
          <input type="date" value={f.nextDueOn} onChange={(e) => setF({ ...f, nextDueOn: e.target.value })} min={today(0)} required />
        </label>
      </div>
      {f.frequency !== "MONTHLY" && (
        <label>
          Already put aside for the next one (AED, optional)
          <input inputMode="decimal" value={f.reserved} onChange={(e) => setF({ ...f, reserved: e.target.value })} placeholder="0" maxLength={14} />
        </label>
      )}
      <button type="submit" className="quiet" disabled={busy}>{busy ? "Adding…" : "Add this bill"}</button>
      {error && <p className="error" role="alert">{error}</p>}
    </form>
  );
}

// ---------------------------------------------------------------- budgets and pocket money

/**
 * The monthly budgets and weekly pocket money, each in a box to change. `load` is the app's start in setup, or the
 * family's budgets as they are now later on. Saving replaces the budgets there were.
 */
export function BudgetsForm({ load, lede, saveLabel, onSaved, onBack }: {
  load: () => Promise<Starter>; lede: string; saveLabel: string; onSaved: () => void; onBack?: () => void;
}) {
  const [starter, setStarter] = useState<Starter | null>(null);
  const [lines, setLines] = useState<Record<string, string>>({});
  const [pocket, setPocket] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    load().then((s) => {
      setStarter(s);
      setLines(Object.fromEntries(s.lines.map((l) => [l.category, typed(l.amount)])));
      setPocket(Object.fromEntries(s.pocket.map((p) => [p.memberId, typed(p.weekly)])));
    }).catch((e) => setError((e as Error).message));
  }, [load]);

  async function confirm() {
    setBusy(true);
    setError(null);
    try {
      await setupApi.saveBudgets(
        Object.entries(lines).map(([category, amount]) => ({ category, amount })),
        Object.entries(pocket).map(([memberId, weekly]) => ({ memberId, weekly })));
      onSaved();
    } catch (err) {
      setError((err as Error).message);
      setBusy(false);
    }
  }

  if (!starter) return error ? <p className="error" role="alert">{error}</p> : <Loading />;

  return (
    <>
      <p className="lede" style={{ marginBottom: 0 }}>{lede}</p>
      <section className="card" style={{ display: "grid", gap: 2 }}>
        <div className="row"><span>Income this month</span><b>{starter.income.text}</b></div>
        <div className="row"><span>Commitments this month</span><b>{starter.commitments.text}</b></div>
        <div className="row total"><span>Left for everything else</span><b>{starter.room.text}</b></div>
        <p className="muted" style={{ margin: "6px 0 0", fontSize: 13 }}>{starter.note}</p>
      </section>

      <section className="card" style={{ paddingTop: 4, paddingBottom: 4 }}>
        <span className="card-title" style={{ display: "block", paddingTop: 10 }}>Each month</span>
        {starter.lines.map((l) => (
          <div className="setup-line" key={l.category}>
            <span><b>{l.label}</b> · {l.bucket === "FUN" ? "fun" : "everyday"}</span>
            <span className="money-input">
              <span>AED</span>
              <input inputMode="decimal" aria-label={`${l.label} a month, AED`} value={lines[l.category] ?? ""}
                onChange={(e) => setLines({ ...lines, [l.category]: e.target.value })} maxLength={12} />
            </span>
            <span className="muted">{l.reason}</span>
          </div>
        ))}
      </section>

      {starter.pocket.length > 0 && (
        <section className="card" style={{ paddingTop: 4, paddingBottom: 4 }}>
          <span className="card-title" style={{ display: "block", paddingTop: 10 }}>Pocket money each week</span>
          {starter.pocket.map((p) => (
            <div className="setup-line" key={p.memberId}>
              <span><b>{p.name}</b></span>
              <span className="money-input">
                <span>AED</span>
                <input inputMode="decimal" aria-label={`${p.name}'s pocket money a week, AED`} value={pocket[p.memberId] ?? ""}
                  onChange={(e) => setPocket({ ...pocket, [p.memberId]: e.target.value })} maxLength={8} />
              </span>
              <span className="muted">{p.reason}. Paid into their wallet as demo money.</span>
            </div>
          ))}
        </section>
      )}
      <p className="trust">{TRUST} Type 0 to leave a budget out.</p>
      <div className="actions">
        {onBack && <button type="button" className="text" onClick={onBack}>Back</button>}
        <button type="button" onClick={confirm} disabled={busy}>{busy ? "Saving…" : saveLabel}</button>
      </div>
      {error && <p className="error" role="alert">{error}</p>}
    </>
  );
}

// ---------------------------------------------------------------- remove, with a second tap to be sure

/** "Remove …" as a text button; the first tap asks once in words (no browser pop-up), the second removes. */
export function RemoveButton({ label, question, onRemove }: { label: string; question: string; onRemove: () => Promise<void> }) {
  const [asking, setAsking] = useState(false);
  const [busy, setBusy] = useState(false);
  if (!asking) {
    return <button type="button" className="text" style={{ justifySelf: "start", paddingLeft: 0 }} onClick={() => setAsking(true)}>{label}</button>;
  }
  return (
    <div className="draft" role="group" aria-label={label}>
      <p style={{ margin: 0 }}>{question}</p>
      <div className="actions" style={{ marginTop: 6 }}>
        <button type="button" className="text" onClick={() => setAsking(false)}>Keep it</button>
        <button type="button" className="decline" disabled={busy}
          onClick={async () => { setBusy(true); await onRemove(); setBusy(false); setAsking(false); }}>
          {busy ? "Removing…" : "Yes, remove"}
        </button>
      </div>
    </div>
  );
}
