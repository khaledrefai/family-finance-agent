"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { core, day, Goal } from "@/lib/live";
import { changeApi, setupApi, typed } from "@/lib/setup";
import { RemoveButton, today } from "../../../plan-forms";
import { Back, Loading, Masthead, TRUST } from "../../../ui";
import { useSession } from "../../../use-session";

// Family goals after setup: change a goal's name, amount or date, drop one, or add a new one. A goal's pace (how much
// a month) is picked on the Plan tab, from the engine's options. Each change is a parent's tap; agents can't do it.
const KINDS: { tier: "SURVIVAL" | "GOOD_LIFE" | "DREAM"; label: string }[] = [
  { tier: "SURVIVAL", label: "Safety net (emergency buffer)" },
  { tier: "GOOD_LIFE", label: "Family goal (a trip, a car)" },
  { tier: "DREAM", label: "Dream (years away)" },
];

export default function GoalsPage() {
  const { me } = useSession("PARENT");
  const [goals, setGoals] = useState<Goal[] | null>(null);
  const [editing, setEditing] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  const [note, setNote] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const load = useCallback(() => core.goals().then(setGoals).catch((e) => setError((e as Error).message)), []);
  useEffect(() => {
    if (me) load();
  }, [me, load]);

  if (!me || goals === null) return error ? <p className="error" role="alert">{error}</p> : <Loading />;

  async function removeGoal(g: Goal) {
    setError(null);
    try {
      await changeApi.removeGoal(g.id);
      setNote(`${g.name} is removed.`);
      setEditing(null);
      await load();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <div className="stack">
      <Back href="/parent/plan" label="Plan" />
      <Masthead title="Family goals" sub="What the family saves for, and by when" />

      {goals.length === 0 && <p className="empty">No family goals yet. Most families start with an emergency buffer.</p>}
      {goals.map((g) => (
        <section className="card" key={g.id} style={{ display: "grid", gap: 6 }}>
          <div className="row" style={{ padding: 0 }}>
            <span className="card-title">{g.name}</span>
            <span className="num" style={{ fontWeight: 600 }}>{g.target.text}</span>
          </div>
          <p className="muted" style={{ margin: 0 }}>
            {g.saved.text} saved · {g.monthly ? `${g.monthly.text} a month` : "no pace yet"}
            {g.targetDate ? ` · wanted by ${day(g.targetDate)}` : ""}{g.fullBy ? ` · full by ${g.fullBy}` : ""}
          </p>
          {editing === g.id ? (
            <GoalForm goal={g} onCancel={() => setEditing(null)} onRemove={() => removeGoal(g)}
              removeQuestion={`Remove ${g.name}? ${g.monthly ? `Its ${g.monthly.text} a month goes back to spare money.` : ""} What is saved so far stays in the history.`}
              onSaved={async (name) => { setEditing(null); setNote(`${name} is saved.`); await load(); }} />
          ) : (
            <div className="row" style={{ padding: 0 }}>
              <button type="button" className="quiet" onClick={() => { setEditing(g.id); setAdding(false); setNote(null); }}>Change</button>
              <Link href={`/parent/plan?goal=${g.id}`} className="link">{g.monthly ? "Change its pace ›" : "Pick its pace ›"}</Link>
            </div>
          )}
        </section>
      ))}

      {adding ? (
        <GoalForm onCancel={() => setAdding(false)}
          onSaved={async (name) => { setAdding(false); setNote(`${name} is added. Pick how fast to save for it.`); await load(); }} />
      ) : (
        <button type="button" className="quiet" onClick={() => { setAdding(true); setEditing(null); setNote(null); }}>Add a goal</button>
      )}
      {note && <p role="status" className="muted">{note}</p>}
      {error && <p className="error" role="alert">{error}</p>}
      <p className="trust">{TRUST} A goal&apos;s pace and what is saved stay as they are when you change it.</p>
    </div>
  );
}

function GoalForm({ goal, onSaved, onCancel, onRemove, removeQuestion }: {
  goal?: Goal; onSaved: (name: string) => void; onCancel: () => void; onRemove?: () => Promise<void>; removeQuestion?: string;
}) {
  const [f, setF] = useState({
    name: goal?.name ?? "", target: goal ? typed(goal.target) : "", targetDate: goal?.targetDate ?? "",
    tier: "GOOD_LIFE" as "SURVIVAL" | "GOOD_LIFE" | "DREAM",
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function save(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      if (goal) await changeApi.updateGoal(goal.id, { name: f.name, target: f.target, targetDate: f.targetDate || undefined });
      else await setupApi.addGoal({ name: f.name, target: f.target, targetDate: f.targetDate || undefined, tier: f.tier });
      onSaved(f.name);
    } catch (err) {
      setError((err as Error).message);
      setBusy(false);
    }
  }

  return (
    <form className="panel setup-form" onSubmit={save} style={{ margin: 0 }}>
      <strong>{goal ? `Change ${goal.name}` : "A new goal"}</strong>
      <div className="two">
        <label>
          Goal
          <input value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} maxLength={120} placeholder="Summer trip" required />
        </label>
        <label>
          Amount (AED)
          <input inputMode="decimal" value={f.target} onChange={(e) => setF({ ...f, target: e.target.value })} maxLength={14} placeholder="12,000" required />
        </label>
      </div>
      {!goal && (
        <label>
          What kind
          <select value={f.tier} onChange={(e) => setF({ ...f, tier: e.target.value as typeof f.tier })}>
            {KINDS.map((k) => <option key={k.tier} value={k.tier}>{k.label}</option>)}
          </select>
        </label>
      )}
      <label>
        Ready by (optional)
        <input type="date" value={f.targetDate} onChange={(e) => setF({ ...f, targetDate: e.target.value })} min={today(1)} />
      </label>
      <div className="actions">
        <button type="button" className="text" onClick={onCancel}>Cancel</button>
        <button type="submit" disabled={busy}>{busy ? "Saving…" : goal ? "Save goal" : "Add this goal"}</button>
      </div>
      {onRemove && <RemoveButton label="Remove this goal" question={removeQuestion ?? "Remove this goal?"} onRemove={onRemove} />}
      {error && <p className="error" role="alert">{error}</p>}
    </form>
  );
}
