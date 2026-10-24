"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { aed, api, MemberView } from "@/lib/api";
import { accounts, advice, bills, familyGoal, kids, monthly } from "@/lib/sample";
import { Chit } from "../chit";
import { AccountPicker, BigAmount, CardWarning, Icon, Masthead, Progress, SampleNote, TRUST, WhySheet } from "../ui";
import { useSession } from "../use-session";

// Today: only what needs a parent, in this order: bills due, ask, waiting requests,
// what is free this month, the family goal, one piece of advice, then what was decided.
// Requests are live from the core; the money cards use sample figures until the plan endpoints exist.
export default function TodayPage() {
  const { me, requests, error, setError, reload, logout } = useSession("PARENT");
  const [members, setMembers] = useState<MemberView[]>([]);
  const [justDecided, setJustDecided] = useState<string | null>(null);
  const [adviceState, setAdviceState] = useState<"open" | "accepted" | "later">("open");
  const [why, setWhy] = useState(false);
  // Which account a parent would pay a request from. Sample default until requests carry it.
  const [payFrom, setPayFrom] = useState<Record<string, string>>({});

  useEffect(() => {
    if (me) api.members().then(setMembers).catch(() => {});
  }, [me]);

  const nameOf = (id: string) => members.find((m) => m.id === id)?.displayName ?? "Someone";

  async function decide(id: string, decision: "approve" | "reject") {
    try {
      await api.decide(id, decision);
      setJustDecided(id);
      await reload();
    } catch (err) {
      setError((err as Error).message);
    }
  }

  if (!me) return <p className="muted">Loading…</p>;

  const pending = requests.filter((r) => r.status === "PENDING");
  const decided = requests.filter((r) => r.status !== "PENDING" && r.id !== justDecided);
  const fresh = requests.find((r) => r.id === justDecided);
  const nextBill = bills[0];
  const tightCard = accounts.find((a) => a.bill && !a.bill.ok);

  return (
    <div className="stack">
      <Masthead
        title={`Hi ${me.displayName}`}
        sub={me.familyName}
        action={<button className="text" onClick={logout}>Log out</button>}
      />
      {error && <p className="error" role="alert">{error}</p>}

      <Link href="/parent/money" className="alert">
        <Icon name="bell" size={20} />
        <span style={{ flex: 1 }}>
          <b>{nextBill.name} due in {nextBill.daysLeft} days.</b> AED 850 reserved, enough to cover it.
        </span>
        <span aria-hidden="true">›</span>
      </Link>

      {tightCard?.bill && (
        <Link href="/parent/money/accounts" className="alert short">
          <Icon name="warn" size={20} />
          <span style={{ flex: 1 }}>
            <b>{tightCard.label} bill due {tightCard.bill.due}.</b> {aed(tightCard.bill.soFarMinor)} so far, {aed(tightCard.bill.freeByDueMinor)} free by then.
          </span>
          <span aria-hidden="true">›</span>
        </Link>
      )}

      <Link href="/parent/ask" className="askbar">
        <Icon name="ask" size={20} />
        Ask the accountant anything
      </Link>

      {fresh && (
        <section>
          <h2 className="first">Just decided</h2>
          <Chit request={fresh} from={`${nameOf(fresh.requesterId)} asked`} landed />
        </section>
      )}

      <section>
        <h2 className="first">Waiting for you</h2>
        {pending.length === 0 && <p className="empty">Nothing waiting. When one of the kids asks for something, it lands here.</p>}
        {pending.map((r) => (
          <Chit key={r.id} request={r} from={`${nameOf(r.requesterId)} asks`} stamp={false}>
            {r.requesterId !== me.memberId && (
              <div style={{ display: "grid", gap: 8, padding: "10px 0 8px" }}>
                <AccountPicker label="Pay from" value={payFrom[r.id] ?? "visa"} onChange={(id) => setPayFrom((p) => ({ ...p, [r.id]: id }))} />
                <CardWarning accountId={payFrom[r.id] ?? "visa"} />
              </div>
            )}
            {r.requesterId !== me.memberId ? (
              <div className="chit-actions">
                <button onClick={() => decide(r.id, "approve")}>Approve</button>
                <button className="decline" onClick={() => decide(r.id, "reject")}>Decline</button>
              </div>
            ) : (
              <p className="chit-note">You asked for this, so the other parent decides.</p>
            )}
            <Link href={`/parent/why?id=${r.id}`} className="link">Why this?</Link>
          </Chit>
        ))}
      </section>

      <section className="card">
        <p className="muted">Free this month, after commitments</p>
        <BigAmount minor={monthly.freeMinor} />
        <p style={{ fontSize: 14 }}>AED 2,140 free until pay day on {monthly.payday}.</p>
        <p className="trust" style={{ marginTop: 4 }}>{TRUST}</p>
      </section>

      <section className="card" style={{ display: "grid", gap: 8 }}>
        <div className="row" style={{ padding: 0 }}>
          <span className="card-title">{familyGoal.name}</span>
          <span className="muted">Family goal</span>
        </div>
        <Progress percent={familyGoal.percent} label={`${familyGoal.percent} percent saved`} />
        <p style={{ fontSize: 14 }}>
          AED 3,600 of 12,000 saved · {familyGoal.percent}% · on track for {familyGoal.onTrackFor}
        </p>
        <p className="muted">
          Own goals: Layla {kids.Layla.percent}% · Adam {kids.Adam.percent}%
        </p>
      </section>

      {adviceState !== "later" && (
        <section className="card strong" style={{ display: "grid", gap: 10 }}>
          <p className="muted">{advice.title}</p>
          {adviceState === "accepted" ? (
            <p role="status">Done. Entertainment is AED 600 a month for November and December. You can change it in Money, Budgets.</p>
          ) : (
            <>
              <p>{advice.text}</p>
              <div className="actions" style={{ marginTop: 0 }}>
                <button onClick={() => setAdviceState("accepted")}>Accept</button>
                <button className="quiet" onClick={() => setAdviceState("later")}>Not now</button>
              </div>
            </>
          )}
          <button type="button" className="text" style={{ justifySelf: "start", minHeight: 44 }} onClick={() => setWhy(true)}>
            Why this?
          </button>
        </section>
      )}

      <Link href="/parent/summary" className="link">See October's family summary</Link>
      <SampleNote />

      <section>
        <h2 className="first">Decided</h2>
        {decided.length === 0 && <p className="empty">Approved and declined requests will collect here.</p>}
        {decided.map((r) => (
          <Chit key={r.id} request={r} from={nameOf(r.requesterId)} small />
        ))}
      </section>

      {why && (
        <WhySheet
          title="Why I suggested this"
          groups={[{ heading: "The numbers behind it", rows: advice.why }]}
          onClose={() => setWhy(false)}
        />
      )}
    </div>
  );
}
