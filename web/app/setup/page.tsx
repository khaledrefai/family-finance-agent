"use client";

import { useRouter } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { api, ApiError, getToken, Me, MemberView } from "@/lib/api";
import { Buckets, core, fromBuckets, Goal, MoneyView, PlanOptions } from "@/lib/live";
import { CommitmentView, IncomeView, setupApi, SetupStatus, Starter, typed } from "@/lib/setup";
import { BucketFlow, Loading, TRUST } from "../ui";

// First-run setup for a new family, one step at a time:
//   1 Family   the other parent, and the kids with their age and own login (a parent's consent for each child)
//   2 Income   take-home pay, and for commission or sales a careful month to plan on
//   3 Bills    rent, school fees, loans and other big regular bills (optional)
//   4 Budgets  the app's starting budgets and pocket money; the parent changes them and confirms
//   5 Plan     a first goal (an emergency buffer the app sizes), its pace, then the Planner's money buckets to confirm
// Every amount shown comes from the core. The Planner only drafts the buckets and writes the words; a parent confirms.

const TITLES = ["Who is in the family", "Money coming in", "Big regular bills", "First budgets", "Your first plan"];
const AGES = Array.from({ length: 12 }, (_, k) => k + 6);
const AGE_BAND: Record<string, string> = { CHILD_6_9: "6 to 9", PRETEEN_10_12: "10 to 12", TEEN_13_17: "13 to 17" };

function today(plusDays = 0) {
  const d = new Date();
  d.setDate(d.getDate() + plusDays);
  return d.toISOString().slice(0, 10);
}

export default function SetupPage() {
  const router = useRouter();
  const [me, setMe] = useState<Me | null>(null);
  const [status, setStatus] = useState<SetupStatus | null>(null);
  const [step, setStep] = useState(0);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    const s = await setupApi.status();
    setStatus(s);
    return s;
  }, []);

  useEffect(() => {
    if (!getToken()) {
      router.replace("/login");
      return;
    }
    (async () => {
      try {
        const who = await api.me();
        if (who.role !== "PARENT") return router.replace("/child");
        setMe(who);
        const s = await refresh();
        if (s.done) return router.replace("/parent");
        // Pick up where the family left off
        setStep({ FAMILY: 0, INCOME: 1, BUDGETS: 3, PLAN: 4, FINISH: 4, DONE: 4 }[s.next]);
      } catch (err) {
        if (err instanceof ApiError && err.status === 401) router.replace("/login");
        else setError((err as Error).message);
      }
    })();
  }, [router, refresh]);

  if (error) return <p className="error" role="alert">{error}</p>;
  if (!me || !status) return <Loading />;

  const next = async () => {
    await refresh();
    setStep((s) => Math.min(4, s + 1));
    window.scrollTo(0, 0);
  };
  const back = () => setStep((s) => Math.max(0, s - 1));

  return (
    <div className="stack">
      <div>
        <p className="muted" style={{ margin: 0, fontSize: 13 }}>{status.familyName} · step {step + 1} of 5</p>
        <div className="setup-steps" aria-hidden="true">
          {TITLES.map((t, k) => <span key={t} className={k <= step ? "on" : ""} />)}
        </div>
        <h1>{TITLES[step]}</h1>
      </div>
      {step === 0 && <FamilyStep me={me} onNext={next} />}
      {step === 1 && <IncomeStep onNext={next} onBack={back} />}
      {step === 2 && <BillsStep onNext={next} onBack={back} />}
      {step === 3 && <BudgetsStep onNext={next} onBack={back} />}
      {step === 4 && <PlanStep status={status} onBack={back} onDone={() => router.replace("/parent")} refresh={refresh} />}
    </div>
  );
}

// ---------------------------------------------------------------- 1. family

function FamilyStep({ me, onNext }: { me: Me; onNext: () => void }) {
  const [members, setMembers] = useState<MemberView[]>([]);
  const [adding, setAdding] = useState<"PARENT" | "CHILD" | null>(null);
  const load = useCallback(() => api.members().then(setMembers).catch(() => {}), []);
  useEffect(() => {
    load();
  }, [load]);
  const parents = members.filter((m) => m.role === "PARENT").length;

  return (
    <>
      <p className="lede" style={{ marginBottom: 0 }}>
        Add the other parent and the kids. Each one logs in with their own username, so the app knows who asked and who decided.
      </p>
      <section className="card" style={{ paddingTop: 4, paddingBottom: 4 }}>
        <div className="rows">
          {members.map((m) => (
            <div className="row" key={m.id} style={{ padding: "10px 0" }}>
              <span><b>{m.displayName}</b> · {m.role === "PARENT" ? "parent" : `child, ${AGE_BAND[m.ageBand] ?? ""}`}</span>
              <span className="muted">{m.id === me.memberId ? "You" : m.role === "PARENT" ? "Decides the kids' asks" : "Own login"}</span>
            </div>
          ))}
        </div>
      </section>

      {adding ? (
        <MemberForm role={adding} onCancel={() => setAdding(null)} onAdded={() => { setAdding(null); load(); }} />
      ) : (
        <div className="actions">
          {parents < 2 && <button type="button" className="quiet" onClick={() => setAdding("PARENT")}>Add the other parent</button>}
          <button type="button" className="quiet" onClick={() => setAdding("CHILD")}>Add a child</button>
        </div>
      )}
      {!adding && <button type="button" onClick={onNext}>Next: money coming in</button>}
      {!adding && <p className="muted" style={{ fontSize: 13, margin: 0 }}>A family of one parent is fine. You can add people later on the Family tab.</p>}
    </>
  );
}

function MemberForm({ role, onCancel, onAdded }: { role: "PARENT" | "CHILD"; onCancel: () => void; onAdded: () => void }) {
  const kid = role === "CHILD";
  const [f, setF] = useState({ displayName: "", age: "", username: "", password: "", email: "", goalName: "", goalPrice: "" });
  const [consent, setConsent] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => setF({ ...f, [k]: e.target.value });

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await setupApi.addMember({
        role, displayName: f.displayName, username: f.username, password: f.password,
        ...(kid
          ? { age: Number(f.age), consent, goalName: f.goalName || undefined, goalPrice: f.goalPrice || undefined }
          : { email: f.email || undefined }),
      });
      onAdded();
    } catch (err) {
      setError((err as Error).message);
      setBusy(false);
    }
  }

  return (
    <form className="panel setup-form" onSubmit={submit}>
      <strong>{kid ? "Add a child" : "Add the other parent"}</strong>
      <div className={kid ? "two" : undefined}>
        <label>
          First name
          <input value={f.displayName} onChange={set("displayName")} maxLength={80} required />
        </label>
        {kid && (
          <label>
            Age
            <select value={f.age} onChange={set("age")} required>
              <option value="" disabled>Pick</option>
              {AGES.map((a) => <option key={a} value={a}>{a}</option>)}
            </select>
          </label>
        )}
      </div>
      <div className="two">
        <label>
          Username
          <input value={f.username} onChange={set("username")} autoCapitalize="none" maxLength={30} required
            placeholder={kid ? "mariam.n" : "khalid.n"} />
        </label>
        <label>
          Password
          <input type="password" value={f.password} onChange={set("password")} autoComplete="new-password"
            minLength={kid ? 6 : 8} required />
        </label>
      </div>
      <span className="muted" style={{ fontSize: 13, marginTop: -6 }}>
        {kid ? "At least 6 characters. Give them the username and password yourself." : "At least 8 characters. They can log in straight away."}
      </span>
      {!kid && (
        <label>
          Their email (optional)
          <input type="email" value={f.email} onChange={set("email")} maxLength={160} />
        </label>
      )}
      {kid && (
        <>
          <div className="two">
            <label>
              Saving for (optional)
              <input value={f.goalName} onChange={set("goalName")} placeholder="A bike" maxLength={120} />
            </label>
            <label>
              It costs (AED)
              <input inputMode="decimal" value={f.goalPrice} onChange={set("goalPrice")} placeholder="450" maxLength={12}
                required={!!f.goalName} />
            </label>
          </div>
          <label className="check">
            <input type="checkbox" checked={consent} onChange={(e) => setConsent(e.target.checked)} required />
            <span>
              I am this child&apos;s parent. I agree to them having their own account, and to the app&apos;s AI reading what
              they type so it can answer them. We keep only their first name, age group and login.
            </span>
          </label>
        </>
      )}
      <div className="actions">
        <button type="submit" disabled={busy}>{busy ? "Adding…" : "Add"}</button>
        <button type="button" className="text" onClick={onCancel}>Cancel</button>
      </div>
      {error && <p className="error" role="alert">{error}</p>}
    </form>
  );
}

// ---------------------------------------------------------------- 2. income

function IncomeStep({ onNext, onBack }: { onNext: () => void; onBack: () => void }) {
  const [incomes, setIncomes] = useState<IncomeView[]>([]);
  const [parents, setParents] = useState<MemberView[]>([]);
  const [f, setF] = useState({ memberId: "", name: "Salary", kind: "FIXED" as "FIXED" | "VARIABLE", amount: "", baseOverride: "", nextOn: today(7) });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const load = useCallback(() => setupApi.incomes().then(setIncomes).catch(() => {}), []);
  useEffect(() => {
    load();
    api.members().then((m) => {
      const p = m.filter((x) => x.role === "PARENT");
      setParents(p);
      setF((f) => ({ ...f, memberId: f.memberId || p[0]?.id || "" }));
    }).catch(() => {});
  }, [load]);
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => setF({ ...f, [k]: e.target.value });

  async function add(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await setupApi.addIncome({
        memberId: f.memberId || undefined, name: f.name, kind: f.kind, amount: f.amount, nextOn: f.nextOn,
        baseOverride: f.kind === "VARIABLE" ? f.baseOverride || undefined : undefined,
      });
      setF({ ...f, name: "Salary", amount: "", baseOverride: "", memberId: parents.find((p) => !incomes.some((i) => i.memberName === p.displayName) && p.id !== f.memberId)?.id ?? f.memberId });
      await load();
    } catch (err) {
      setError((err as Error).message);
    }
    setBusy(false);
  }

  return (
    <>
      <p className="lede" style={{ marginBottom: 0 }}>
        Type each parent&apos;s take-home pay: what lands in the bank each month. The plan starts from it.
      </p>
      {incomes.length > 0 && (
        <section className="card" style={{ paddingTop: 4, paddingBottom: 4 }}>
          {incomes.map((i) => (
            <div className="setup-line" key={i.id}>
              <span><b>{i.name}</b>{i.memberName ? ` · ${i.memberName}` : ""}</span>
              <b className="num">{i.plannedMonthly.text}</b>
              <span className="muted">{i.kind === "VARIABLE" ? "Commission or sales, planned on a careful month" : "Fixed, every month"} · next on {i.nextOn}</span>
            </div>
          ))}
        </section>
      )}
      <form className="panel setup-form" onSubmit={add}>
        <strong>{incomes.length ? "Add more income" : "Add income"}</strong>
        <div className="two">
          <label>
            Whose
            <select value={f.memberId} onChange={set("memberId")}>
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
        <button type="submit" className={incomes.length ? "quiet" : undefined} disabled={busy}>{busy ? "Adding…" : "Add this income"}</button>
        {error && <p className="error" role="alert">{error}</p>}
      </form>
      <BankBalance parents={parents} />
      <div className="actions">
        <button type="button" className="text" onClick={onBack}>Back</button>
        <button type="button" onClick={onNext} disabled={incomes.length === 0}>Next: big bills</button>
      </div>
    </>
  );
}

// What is in the current account today, typed by hand (no bank link). Without it the app can't say what is free
// until pay day. More accounts and cards can be added later in Money, Accounts.
function BankBalance({ parents }: { parents: MemberView[] }) {
  const [accounts, setAccounts] = useState<{ id: string; kind: string; label: string; balance: { text: string } }[] | null>(null);
  const [f, setF] = useState({ label: "Current account", balance: "" });
  const [error, setError] = useState<string | null>(null);
  const load = useCallback(() => setupApi.accounts().then(setAccounts).catch(() => setAccounts([])), []);
  useEffect(() => {
    load();
  }, [load]);
  const banks = accounts?.filter((a) => a.kind === "BANK") ?? [];

  async function add(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    try {
      await setupApi.addBankAccount({ label: f.label, balance: f.balance, ownerId: parents[0]?.id, asOf: today(0) });
      setF({ ...f, balance: "" });
      await load();
    } catch (err) {
      setError((err as Error).message);
    }
  }

  if (accounts === null) return null;
  return (
    <form className="panel setup-form" onSubmit={add}>
      <strong>Money in the bank today (optional)</strong>
      {banks.map((a) => (
        <div className="row" key={a.id} style={{ padding: 0 }}><span>{a.label}</span><b>{a.balance.text}</b></div>
      ))}
      {banks.length === 0 && (
        <>
          <span className="muted" style={{ fontSize: 14, marginTop: -6 }}>
            Typed by hand, never linked to your bank. It lets the app say what is free until the next pay day.
          </span>
          <div className="two">
            <label>
              Account
              <input value={f.label} onChange={(e) => setF({ ...f, label: e.target.value })} maxLength={80} required />
            </label>
            <label>
              In it today (AED)
              <input inputMode="decimal" value={f.balance} onChange={(e) => setF({ ...f, balance: e.target.value })} placeholder="8,500" maxLength={14} required />
            </label>
          </div>
          <button type="submit" className="quiet">Add this account</button>
        </>
      )}
      {error && <p className="error" role="alert">{error}</p>}
    </form>
  );
}

// ---------------------------------------------------------------- 3. big regular bills

const BILL_KINDS: { kind: string; label: string; name: string; frequency: string }[] = [
  { kind: "RENT", label: "Rent", name: "Rent", frequency: "QUARTERLY" },
  { kind: "SCHOOL_FEES", label: "School fees", name: "School fees", frequency: "TERMLY" },
  { kind: "LOAN", label: "Loan (car, personal)", name: "Car loan", frequency: "MONTHLY" },
  { kind: "UTILITY", label: "DEWA, cooling, gas", name: "DEWA", frequency: "MONTHLY" },
  { kind: "TELECOM", label: "Phone and internet", name: "Phone and internet", frequency: "MONTHLY" },
  { kind: "INSURANCE", label: "Insurance", name: "Insurance", frequency: "YEARLY" },
  { kind: "SUBSCRIPTION", label: "Subscription", name: "Streaming", frequency: "MONTHLY" },
  { kind: "OTHER", label: "Something else", name: "", frequency: "MONTHLY" },
];
const HOW_OFTEN: Record<string, string> = {
  MONTHLY: "Every month", QUARTERLY: "Every 3 months (rent cheques)", TERMLY: "Each school term (3 a year)", YEARLY: "Once a year",
};

function BillsStep({ onNext, onBack }: { onNext: () => void; onBack: () => void }) {
  const [bills, setBills] = useState<CommitmentView[]>([]);
  const [f, setF] = useState({ ...BILL_KINDS[0], amount: "", reserved: "", nextDueOn: today(30) });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const load = useCallback(() => setupApi.commitments().then(setBills).catch(() => {}), []);
  useEffect(() => {
    load();
  }, [load]);

  async function add(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await setupApi.addCommitment({ name: f.name, kind: f.kind, amount: f.amount, frequency: f.frequency, nextDueOn: f.nextDueOn,
        estimate: f.kind === "UTILITY", reserved: f.frequency !== "MONTHLY" ? f.reserved || undefined : undefined });
      setF({ ...f, amount: "", reserved: "" });
      await load();
    } catch (err) {
      setError((err as Error).message);
    }
    setBusy(false);
  }

  return (
    <>
      <p className="lede" style={{ marginBottom: 0 }}>
        The bills that come whatever happens. For rent in cheques or school terms, type one payment: the app puts a little aside each month so it is ready.
      </p>
      {bills.length > 0 && (
        <section className="card" style={{ paddingTop: 4, paddingBottom: 4 }}>
          {bills.map((b) => (
            <div className="setup-line" key={b.id}>
              <span><b>{b.name}</b> · {b.kindLabel}</span>
              <b className="num">{b.amount.text}</b>
              <span className="muted">{HOW_OFTEN[b.frequency] ?? b.frequency} · next on {b.nextDueOn}</span>
            </div>
          ))}
        </section>
      )}
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
      <div className="actions">
        <button type="button" className="text" onClick={onBack}>Back</button>
        <button type="button" onClick={onNext}>{bills.length ? "Next: first budgets" : "Skip for now"}</button>
      </div>
    </>
  );
}

// ---------------------------------------------------------------- 4. first budgets

function BudgetsStep({ onNext, onBack }: { onNext: () => void; onBack: () => void }) {
  const [starter, setStarter] = useState<Starter | null>(null);
  const [lines, setLines] = useState<Record<string, string>>({});
  const [pocket, setPocket] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    setupApi.starter().then((s) => {
      setStarter(s);
      setLines(Object.fromEntries(s.lines.map((l) => [l.category, typed(l.amount)])));
      setPocket(Object.fromEntries(s.pocket.map((p) => [p.memberId, typed(p.weekly)])));
    }).catch((e) => setError((e as Error).message));
  }, []);

  async function confirm() {
    setBusy(true);
    setError(null);
    try {
      await setupApi.saveBudgets(
        Object.entries(lines).map(([category, amount]) => ({ category, amount })),
        Object.entries(pocket).map(([memberId, weekly]) => ({ memberId, weekly })));
      onNext();
    } catch (err) {
      setError((err as Error).message);
      setBusy(false);
    }
  }

  if (!starter) return error ? <p className="error" role="alert">{error}</p> : <Loading />;

  return (
    <>
      <p className="lede" style={{ marginBottom: 0 }}>
        The app&apos;s starting budgets for a family like yours. Change any of them, then confirm. Nothing counts until you do.
      </p>
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
        <button type="button" className="text" onClick={onBack}>Back</button>
        <button type="button" onClick={confirm} disabled={busy}>{busy ? "Saving…" : "Confirm budgets"}</button>
      </div>
      {error && <p className="error" role="alert">{error}</p>}
    </>
  );
}

// ---------------------------------------------------------------- 5. first goal, its pace, and the money buckets

function PlanStep({ status, onBack, onDone, refresh }: {
  status: SetupStatus; onBack: () => void; onDone: () => void; refresh: () => Promise<SetupStatus>;
}) {
  const [goals, setGoals] = useState<Goal[] | null>(null);
  const [buffer, setBuffer] = useState<{ target: MoneyView; reason: string } | null>(null);
  const [goalForm, setGoalForm] = useState({ name: "Emergency buffer", target: "", targetDate: "" });
  const [options, setOptions] = useState<PlanOptions | null>(null);
  const [buckets, setBuckets] = useState<Buckets | null>(null);
  const [words, setWords] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const goal = goals?.[0] ?? null;
  const paced = !!goal?.monthly;

  useEffect(() => {
    core.goals().then(setGoals).catch(() => setGoals([]));
    setupApi.buffer().then((b) => {
      setBuffer(b);
      setGoalForm((f) => ({ ...f, target: f.target || typed(b.target) }));
    }).catch(() => {});
    if (status.buckets !== "NONE") core.buckets().then(setBuckets).catch(() => {});
  }, [status.buckets]);

  useEffect(() => {
    if (goal && !paced) core.options(goal.id).then(setOptions).catch((e) => setError((e as Error).message));
  }, [goal, paced]);

  async function run(label: string, f: () => Promise<void>) {
    setBusy(label);
    setError(null);
    try {
      await f();
    } catch (err) {
      setError((err as Error).message);
    }
    setBusy(null);
  }

  const addGoal = (e: React.FormEvent) => {
    e.preventDefault();
    run("goal", async () => {
      await setupApi.addGoal({ name: goalForm.name, target: goalForm.target, targetDate: goalForm.targetDate || undefined,
        tier: /buffer|emergency/i.test(goalForm.name) ? "SURVIVAL" : "GOOD_LIFE" });
      setGoals(await core.goals());
    });
  };

  const pick = (monthly: number) => run("pace", async () => {
    await core.setPace(goal!.id, String(monthly / 100));
    setGoals(await core.goals());
  });

  const draft = () => run("plan", async () => {
    try {
      const r = await setupApi.firstPlan();
      setWords(r.reply.split("\n\n")[0]);
    } catch {
      // The Planner is out of reach: show the app's own split, which the parent can confirm all the same
      setWords("The Planner couldn't write this one, so here is the app's own split. It covers the bills and budgets first.");
    }
    setBuckets(await core.buckets());
  });

  const confirm = () => run("confirm", async () => {
    await core.confirmBuckets();
    await setupApi.finish();
    await refresh();
    onDone();
  });

  if (goals === null) return <Loading />;

  return (
    <>
      <p className="lede" style={{ marginBottom: 0 }}>
        Three short parts: a first goal, how fast to save for it, and the month split into money buckets.
      </p>

      {/* a. the first goal */}
      {!goal ? (
        <form className="panel setup-form" onSubmit={addGoal}>
          <strong>A first goal</strong>
          {buffer && (
            <p className="muted" style={{ margin: 0, fontSize: 14 }}>
              Most families start with an emergency buffer. For yours that is <b>{buffer.target.text}</b>: {buffer.reason}.
            </p>
          )}
          <div className="two">
            <label>
              Goal
              <input value={goalForm.name} onChange={(e) => setGoalForm({ ...goalForm, name: e.target.value })} maxLength={120} required />
            </label>
            <label>
              Amount (AED)
              <input inputMode="decimal" value={goalForm.target} onChange={(e) => setGoalForm({ ...goalForm, target: e.target.value })} maxLength={14} required />
            </label>
          </div>
          <label>
            Ready by (optional)
            <input type="date" value={goalForm.targetDate} onChange={(e) => setGoalForm({ ...goalForm, targetDate: e.target.value })} min={today(31)} />
          </label>
          <button type="submit" disabled={!!busy}>{busy === "goal" ? "Adding…" : "Add this goal"}</button>
        </form>
      ) : (
        <div className="draft done">
          <div className="row" style={{ padding: 0 }}>
            <span><b>{goal.name}</b> · {goal.target.text}</span>
            {paced && <span className="confirmed">{goal.monthly!.text} a month</span>}
          </div>
          {paced && goal.fullBy && <span className="muted" style={{ fontSize: 13 }}>Full by {goal.fullBy}</span>}
        </div>
      )}

      {/* b. its pace */}
      {goal && !paced && options && (
        <section className="stack" style={{ gap: 10 }}>
          <h2 className="first" style={{ fontSize: 17 }}>How fast to save for it</h2>
          {options.options.map((o) => (
            <div key={o.id} className="card option-card">
              <span className="option-num" aria-hidden="true">{o.name[0]}</span>
              <span>
                <b>{o.name}: {o.monthly.text} a month</b>
                {o.id === options.recommended && <span className="confirmed" style={{ marginLeft: 8 }}>Suggested</span>}
                <br />
                <span className="muted" style={{ fontSize: 14 }}>
                  Ready by {o.readyMonth}. {o.fits ? "Fits every month." : `Tight in ${o.tightestMonth}.`}
                </span>
              </span>
              <button type="button" className={o.id === options.recommended ? undefined : "quiet"} disabled={!!busy}
                onClick={() => pick(o.monthly.minor)}>Pick {o.name.toLowerCase()}</button>
            </div>
          ))}
          {options.recommendedReason && <p className="muted" style={{ margin: 0, fontSize: 13 }}>{options.recommendedReason}</p>}
        </section>
      )}

      {/* c. the money buckets */}
      {goal && paced && (
        <section className="stack" style={{ gap: 10 }}>
          <h2 className="first" style={{ fontSize: 17 }}>The month in money buckets</h2>
          {!buckets || buckets.status !== "DRAFT" && buckets.status !== "ACTIVE" ? (
            <>
              <p className="muted" style={{ margin: 0 }}>
                The Planner splits this month&apos;s income: bills first, then budgets, the goal, a little set aside, fun and giving.
              </p>
              <button type="button" onClick={draft} disabled={!!busy}>{busy === "plan" ? "Working it out…" : "Make our first plan"}</button>
            </>
          ) : (
            <BucketsDraft buckets={buckets} words={words} />
          )}
          {buckets && buckets.status === "DRAFT" && (
            <>
              <p className="trust">{TRUST} This is a draft until you confirm it.</p>
              <button type="button" onClick={confirm} disabled={!!busy}>{busy === "confirm" ? "Confirming…" : "Confirm and finish"}</button>
            </>
          )}
          {buckets && buckets.status === "ACTIVE" && (
            <button type="button" onClick={() => run("confirm", async () => { await setupApi.finish(); onDone(); })} disabled={!!busy}>
              Finish
            </button>
          )}
        </section>
      )}

      {error && <p className="error" role="alert">{error}</p>}
      <button type="button" className="text" onClick={onBack} style={{ justifySelf: "start" }}>Back</button>
    </>
  );
}

function BucketsDraft({ buckets, words }: { buckets: Buckets; words: string | null }) {
  const view = fromBuckets(buckets);
  return (
    <div className={`draft${buckets.status === "ACTIVE" ? " done" : ""}`} style={{ gap: 10 }}>
      {words && <p className="planner-words">{words}</p>}
      <BucketFlow incomeMinor={view.incomeMinor} buckets={view.list} />
      <div className="rows">
        {buckets.buckets.map((b) => (
          <div className="setup-line" key={b.kind}>
            <span><b>{b.label}</b></span>
            <b className="num">{b.amount.text}</b>
            {b.items.length > 0 && <span className="muted">{b.items.map((i) => `${i.label} ${i.amount.text}`).join(" · ")}</span>}
          </div>
        ))}
      </div>
      {buckets.warnings.map((w) => <p key={w} className="warn-line" style={{ margin: 0 }}>{w}</p>)}
    </div>
  );
}
