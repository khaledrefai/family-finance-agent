"use client";

import { useCallback, useEffect, useState } from "react";
import { api, MemberView } from "@/lib/api";
import { day } from "@/lib/live";
import { changeApi, CommitmentView, IncomeView, setupApi, typed } from "@/lib/setup";
import { BillAddForm, HOW_OFTEN, IncomeAddForm, RemoveButton, today } from "../../../plan-forms";
import { Back, Loading, Masthead, TRUST } from "../../../ui";
import { useSession } from "../../../use-session";

// Income and big bills after setup: change a pay rise, a new rent, a loan paid off. Each change is a parent's tap
// (PUT and DELETE on /api/plan/income and /api/plan/commitments); the plan, budgets room and buckets re-run from it.
// Drafts the accountant made from a statement or a chat are confirmed elsewhere, so only live ones are listed here.
export default function IncomeBillsPage() {
  const { me } = useSession("PARENT");
  const [incomes, setIncomes] = useState<IncomeView[] | null>(null);
  const [bills, setBills] = useState<CommitmentView[]>([]);
  const [parents, setParents] = useState<MemberView[]>([]);
  const [editing, setEditing] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const [i, c] = await Promise.all([setupApi.incomes(), setupApi.commitments()]);
      setIncomes(i.filter((x) => x.status === "ACTIVE"));
      setBills(c.filter((x) => x.status === "ACTIVE"));
    } catch (e) {
      setError((e as Error).message);
    }
  }, []);
  useEffect(() => {
    if (!me) return;
    load();
    api.members().then((m) => setParents(m.filter((x) => x.role === "PARENT"))).catch(() => {});
  }, [me, load]);

  if (!me || incomes === null) return error ? <p className="error" role="alert">{error}</p> : <Loading />;

  const done = async (text: string) => {
    setEditing(null);
    setNote(text);
    await load();
  };
  const remove = (what: string, f: () => Promise<void>) => async () => {
    setError(null);
    try {
      await f();
      await done(`${what} is removed.`);
    } catch (e) {
      setError((e as Error).message);
    }
  };

  return (
    <div className="stack">
      <Back href="/parent/plan" label="Plan" />
      <Masthead title="Income and bills" sub="What comes in each month, and the bills that come whatever happens" />

      <h2 className="first">Money coming in</h2>
      <section className="card" style={{ paddingTop: 4, paddingBottom: 4 }}>
        {incomes.map((i) => (
          <div key={i.id} className="setup-line">
            <span><b>{i.name}</b>{i.memberName ? ` · ${i.memberName}` : ""}</span>
            <b className="num">{i.plannedMonthly.text}</b>
            <span className="muted">
              {i.kind === "VARIABLE" ? "Commission or sales, planned on a careful month" : i.kind === "ONE_OFF" ? "One-off" : "Fixed, every month"}
              {" · "}next on {day(i.nextOn)}
            </span>
            {editing === i.id ? (
              <IncomeEdit income={i} onCancel={() => setEditing(null)} onSaved={() => done(`${i.name} is saved.`)}
                onRemove={remove(i.name, () => changeApi.removeIncome(i.id))} />
            ) : (
              <button type="button" className="text" style={{ justifySelf: "start", paddingLeft: 0 }}
                onClick={() => { setEditing(i.id); setNote(null); }}>Change</button>
            )}
          </div>
        ))}
      </section>
      <IncomeAddForm parents={parents} title="Add income" quiet onAdded={() => done("The income is added.")} />

      <h2>Big regular bills</h2>
      {bills.length === 0 && <p className="empty">No bills yet.</p>}
      {bills.length > 0 && (
        <section className="card" style={{ paddingTop: 4, paddingBottom: 4 }}>
          {bills.map((b) => (
            <div key={b.id} className="setup-line">
              <span><b>{b.name}</b> · {b.kindLabel}</span>
              <b className="num">{b.amount.text}</b>
              <span className="muted">{HOW_OFTEN[b.frequency] ?? "On its own dates"} · next on {day(b.nextDueOn)}</span>
              {editing === b.id ? (
                <BillEdit bill={b} onCancel={() => setEditing(null)} onSaved={() => done(`${b.name} is saved.`)}
                  onRemove={remove(b.name, () => changeApi.removeCommitment(b.id))} />
              ) : (
                <button type="button" className="text" style={{ justifySelf: "start", paddingLeft: 0 }}
                  onClick={() => { setEditing(b.id); setNote(null); }}>Change</button>
              )}
            </div>
          ))}
        </section>
      )}
      <BillAddForm onAdded={() => done("The bill is added.")} />

      {note && <p role="status" className="muted">{note}</p>}
      {error && <p className="error" role="alert">{error}</p>}
      <p className="trust">{TRUST} After a change, check the budgets and the money buckets still fit.</p>
    </div>
  );
}

function IncomeEdit({ income, onSaved, onCancel, onRemove }: {
  income: IncomeView; onSaved: () => void; onCancel: () => void; onRemove: () => Promise<void>;
}) {
  const variable = income.kind === "VARIABLE";
  const [f, setF] = useState({
    name: income.name, amount: typed(income.amount), nextOn: income.nextOn,
    // Only a careful month the parent typed is shown; otherwise the app works it out from the months paid
    baseOverride: variable && income.note === "Planned on your own base" ? typed(income.plannedMonthly) : "",
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function save(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await changeApi.updateIncome(income.id, { name: f.name, amount: f.amount, nextOn: f.nextOn,
        baseOverride: variable ? f.baseOverride || undefined : undefined });
      onSaved();
    } catch (err) {
      setError((err as Error).message);
      setBusy(false);
    }
  }

  return (
    <form className="panel setup-form edit-line" onSubmit={save}>
      <label>
        Called
        <input value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} maxLength={120} required />
      </label>
      <div className="two">
        <label>
          {variable ? "A usual month (AED)" : "Take-home a month (AED)"}
          <input inputMode="decimal" value={f.amount} onChange={(e) => setF({ ...f, amount: e.target.value })} maxLength={14} required />
        </label>
        <label>
          Next pay day
          <input type="date" value={f.nextOn} onChange={(e) => setF({ ...f, nextOn: e.target.value })} required />
        </label>
      </div>
      {variable && (
        <label>
          A careful month to plan on (AED)
          <input inputMode="decimal" value={f.baseOverride} onChange={(e) => setF({ ...f, baseOverride: e.target.value })} maxLength={14} />
          <span className="muted" style={{ fontWeight: 400 }}>Leave it empty to let the app work it out from the last months paid.</span>
        </label>
      )}
      <div className="actions">
        <button type="button" className="text" onClick={onCancel}>Cancel</button>
        <button type="submit" disabled={busy}>{busy ? "Saving…" : "Save"}</button>
      </div>
      <RemoveButton label="This income stopped" question={`Remove ${income.name}? The plan stops counting it from now on.`} onRemove={onRemove} />
      {error && <p className="error" role="alert">{error}</p>}
    </form>
  );
}

function BillEdit({ bill, onSaved, onCancel, onRemove }: {
  bill: CommitmentView; onSaved: () => void; onCancel: () => void; onRemove: () => Promise<void>;
}) {
  // A bill typed with its own list of dates (each rent cheque) keeps those dates: only its name changes here
  const ownDates = bill.frequency === "CUSTOM";
  const [f, setF] = useState({
    name: bill.name, amount: typed(bill.amount), frequency: bill.frequency, nextDueOn: bill.nextDueOn,
    reserved: bill.reserved.minor ? typed(bill.reserved) : "",
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function save(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await changeApi.updateCommitment(bill.id, ownDates ? { name: f.name } : {
        name: f.name, amount: f.amount, frequency: f.frequency, nextDueOn: f.nextDueOn,
        reserved: f.frequency !== "MONTHLY" ? f.reserved || undefined : undefined,
      });
      onSaved();
    } catch (err) {
      setError((err as Error).message);
      setBusy(false);
    }
  }

  return (
    <form className="panel setup-form edit-line" onSubmit={save}>
      <label>
        Called
        <input value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} maxLength={120} required />
      </label>
      {ownDates ? (
        <p className="muted" style={{ margin: 0, fontSize: 14 }}>This bill is paid on its own dates. To change them, remove it and add it again.</p>
      ) : (
        <>
          <div className="two">
            <label>
              Each payment (AED)
              <input inputMode="decimal" value={f.amount} onChange={(e) => setF({ ...f, amount: e.target.value })} maxLength={14} required />
            </label>
            <label>
              Next payment
              <input type="date" value={f.nextDueOn} onChange={(e) => setF({ ...f, nextDueOn: e.target.value })} min={today(0)} required />
            </label>
          </div>
          <label>
            How often
            <select value={f.frequency} onChange={(e) => setF({ ...f, frequency: e.target.value })}>
              {Object.entries(HOW_OFTEN).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
            </select>
          </label>
          {f.frequency !== "MONTHLY" && (
            <label>
              Already put aside for the next one (AED, optional)
              <input inputMode="decimal" value={f.reserved} onChange={(e) => setF({ ...f, reserved: e.target.value })} placeholder="0" maxLength={14} />
            </label>
          )}
        </>
      )}
      <div className="actions">
        <button type="button" className="text" onClick={onCancel}>Cancel</button>
        <button type="submit" disabled={busy}>{busy ? "Saving…" : "Save"}</button>
      </div>
      <RemoveButton label="This bill ended" question={`Remove ${bill.name}? The plan stops counting it from now on.`} onRemove={onRemove} />
      {error && <p className="error" role="alert">{error}</p>}
    </form>
  );
}
