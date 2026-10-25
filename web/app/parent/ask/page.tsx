"use client";

import { useEffect, useState } from "react";
import { api, MemberView } from "@/lib/api";
import { AgentChat } from "../../agent-chat";
import { Loading, Masthead, SampleNote } from "../../ui";
import { useSession } from "../../use-session";

// The accountant chat. The live agent service comes first, so the Ask tab opens on a question box.
// Below it, setting up walks through income, then commitments, then the plan, and everything it fills in
// arrives as a draft card that counts only after Confirm. The setup steps are sample content until the
// income and commitment endpoints exist.
const DRAFTS = [
  {
    id: "omar",
    title: "Omar's salary",
    rows: [["Kind", "Fixed"], ["Amount", "AED 28,000 a month"], ["Arrives", "On the 25th"]],
  },
  {
    id: "sara",
    title: "Sara's commission",
    rows: [["Kind", "Variable"], ["Plan on", "AED 6,500 a month"], ["From", "The last 6 months"]],
    note: "Months above AED 6,500 go to the buffer first, then the family goal. You can change both.",
  },
];

export default function AskPage() {
  const { me } = useSession("PARENT");
  const [members, setMembers] = useState<MemberView[]>([]);
  const [confirmed, setConfirmed] = useState<Record<string, boolean>>({ omar: true });

  useEffect(() => {
    if (me) api.members().then(setMembers).catch(() => {});
  }, [me]);

  if (!me) return <Loading />;
  const step = DRAFTS.every((d) => confirmed[d.id]) ? 2 : 1;

  return (
    <div className="stack">
      <Masthead title="Ask the accountant" sub="About the family's money, in your own words" />

      <AgentChat
        title="Ask anything"
        placeholder="Where did Layla's money go this month?"
        source="Totals are worked out exactly by the app, not guessed by the AI."
        suggestions={[
          ...members.filter((m) => m.role === "CHILD").map((m) => `Where did ${m.displayName}'s money go this month?`),
          "Anything over AED 100 needs me",
          "I got a AED 5,000 bonus",
        ]}
      />

      <section className="stack" style={{ gap: 12 }}>
        <div>
          <h2 className="first">Setting up your money</h2>
          <p className="muted">Step {step} of 3: income, then commitments, then the plan</p>
        </div>
        <div style={{ display: "grid", gridTemplateColumns: "repeat(3, minmax(0, 1fr))", gap: 4 }} aria-hidden="true">
          {[1, 2, 3].map((n) => (
            <span key={n} style={{ height: 4, borderRadius: 99, background: n <= step ? "var(--ink)" : "var(--rule)" }} />
          ))}
        </div>

        <div className="convo">
          <p className="turn-agent">Let's start with what comes in. What is your take-home pay each month, and which day does it arrive? Sara's too, if you share the family money.</p>
          <p className="turn-me">28,000 on the 25th. Sara is on commission, somewhere between 5 and 9 thousand.</p>
          <p className="turn-agent">Thanks. I'll plan Sara's pay on a cautious amount, the average of the three lowest of the last six months, so a weak month never breaks the plan. Please check these two.</p>

          {DRAFTS.map((d) => (
            <div key={d.id} className={`draft${confirmed[d.id] ? " done" : ""}`}>
              <div className="row" style={{ padding: 0 }}>
                <b style={{ fontWeight: 600 }}>{d.title}</b>
                {confirmed[d.id] && <span className="confirmed">Confirmed</span>}
              </div>
              {d.rows.map(([k, v]) => (
                <div className="row" key={k}>
                  <span>{k}</span>
                  <b>{v}</b>
                </div>
              ))}
              {d.note && <p className="muted">{d.note}</p>}
              {!confirmed[d.id] && (
                <div style={{ display: "flex", gap: 8, marginTop: 6 }}>
                  <button onClick={() => setConfirmed((c) => ({ ...c, [d.id]: true }))}>Confirm</button>
                  <button className="quiet">Edit</button>
                </div>
              )}
            </div>
          ))}

          {step === 2 && (
            <p className="turn-agent">
              Next, rent. Upload your tenancy contract and I'll read the cheque dates, or just tell me the yearly rent and how many cheques.
            </p>
          )}
        </div>
      </section>
      <SampleNote />
    </div>
  );
}
