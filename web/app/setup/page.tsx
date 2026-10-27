"use client";

import { useRouter } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { aed, api, ApiError, getToken, Me, MemberView } from "@/lib/api";
import { Buckets, core, day, fromBuckets, Goal, MoneyView, PlanOptions } from "@/lib/live";
import { CommitmentView, IncomeView, setupApi, SetupStatus, typed } from "@/lib/setup";
import { MemberForm } from "../member-form";
import { BillAddForm, BudgetsForm, HOW_OFTEN, IncomeAddForm, today } from "../plan-forms";
import { BucketFlow, Loading, TRUST } from "../ui";

// First-run setup for a new family, one step at a time:
//   1 Family   the other parent, and the kids with their age and own login (a parent's consent for each child)
//   2 Income   take-home pay, and for commission or sales a careful month to plan on
//   3 Bills    rent, school fees, loans and other big regular bills (optional)
//   4 Budgets  the app's starting budgets and pocket money; the parent changes them and confirms
//   5 Plan     a first goal (an emergency buffer the app sizes), its pace, then the Planner's money buckets to confirm
// Every amount shown comes from the core. The Planner only drafts the buckets and writes the words; a parent confirms.

const TITLES = ["Who is in the family", "Money coming in", "Big regular bills", "First budgets", "Your first plan"];
const AGE_BAND: Record<string, string> = { CHILD_6_9: "6 to 9", PRETEEN_10_12: "10 to 12", TEEN_13_17: "13 to 17" };

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

// ---------------------------------------------------------------- 2. income

function IncomeStep({ onNext, onBack }: { onNext: () => void; onBack: () => void }) {
  const [incomes, setIncomes] = useState<IncomeView[]>([]);
  const [parents, setParents] = useState<MemberView[]>([]);
  const load = useCallback(() => setupApi.incomes().then(setIncomes).catch(() => {}), []);
  useEffect(() => {
    load();
    api.members().then((m) => setParents(m.filter((x) => x.role === "PARENT"))).catch(() => {});
  }, [load]);

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
              <b className="num">{aed(i.plannedMonthly.minor)}</b>
              <span className="muted">{i.kind === "VARIABLE" ? "Commission or sales, planned on a careful month" : "Fixed, every month"} · next on {day(i.nextOn)}</span>
            </div>
          ))}
        </section>
      )}
      <IncomeAddForm parents={parents} title={incomes.length ? "Add more income" : "Add income"} quiet={incomes.length > 0} onAdded={load} />
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
            Typed by hand, never linked to your bank. Without it, Today can only show the month, not what is free until pay day.
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

function BillsStep({ onNext, onBack }: { onNext: () => void; onBack: () => void }) {
  const [bills, setBills] = useState<CommitmentView[]>([]);
  const load = useCallback(() => setupApi.commitments().then(setBills).catch(() => {}), []);
  useEffect(() => {
    load();
  }, [load]);

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
              <span className="muted">{HOW_OFTEN[b.frequency] ?? b.frequency} · next on {day(b.nextDueOn)}</span>
            </div>
          ))}
        </section>
      )}
      <BillAddForm onAdded={load} />
      <div className="actions">
        <button type="button" className="text" onClick={onBack}>Back</button>
        <button type="button" onClick={onNext}>{bills.length ? "Next: first budgets" : "Skip for now"}</button>
      </div>
    </>
  );
}

// ---------------------------------------------------------------- 4. first budgets

function BudgetsStep({ onNext, onBack }: { onNext: () => void; onBack: () => void }) {
  return (
    <BudgetsForm load={setupApi.starter} saveLabel="Confirm budgets" onSaved={onNext} onBack={onBack}
      lede="The app's starting budgets for a family like yours. Change any of them, then confirm. Nothing counts until you do." />
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
            <b className="num">{aed(b.amount.minor)}</b>
            {b.items.length > 0 && <span className="muted">{b.items.map((i) => `${i.label} ${aed(i.amount.minor)}`).join(" · ")}</span>}
          </div>
        ))}
      </div>
      {buckets.warnings.map((w) => <p key={w} className="warn-line" style={{ margin: 0 }}>{w}</p>)}
    </div>
  );
}
