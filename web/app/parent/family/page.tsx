"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { aed, api, MemberView, plain } from "@/lib/api";
import { kids, rules } from "@/lib/sample";
import { Masthead, Progress, SampleNote } from "../../ui";
import { useSession } from "../../use-session";

// Family: who is in it, the rules the app checks on every request, and food profiles.
// Members are live; goals and rules are sample content until the rules and goals endpoints exist.
const AGE: Record<string, string> = { CHILD_6_9: "6 to 9", PRETEEN_10_12: "10 to 12", TEEN_13_17: "13 to 17" };

export default function FamilyPage() {
  const { me, requests } = useSession("PARENT");
  const [members, setMembers] = useState<MemberView[]>([]);
  const [ruleOn, setRuleOn] = useState(rules.map((r) => r.on));

  useEffect(() => {
    if (me) api.members().then(setMembers).catch(() => {});
  }, [me]);

  if (!me) return <p className="muted">Loading…</p>;
  const decidedCount = requests.filter((r) => r.status !== "PENDING").length;

  return (
    <div className="stack">
      <Masthead title="Family" sub={`${me.familyName} · ${members.length || "…"} members`} />

      <section className="card" style={{ paddingTop: 4, paddingBottom: 4 }}>
        <div className="rows">
          {members.map((m) => {
            const kid = kids[m.displayName];
            return (
              <div className="row" key={m.id} style={{ alignItems: "center", gap: 12, padding: "10px 0" }}>
                <span aria-hidden="true" style={{ flex: "none", width: 40, height: 40, borderRadius: 99, display: "grid", placeItems: "center", background: "var(--rule-soft)", fontWeight: 600 }}>
                  {m.displayName[0]}
                </span>
                <span style={{ flex: 1, fontSize: 15 }}>
                  <b>{m.displayName}</b> · {m.role === "PARENT" ? "parent" : `${AGE[m.ageBand] ?? "child"}${kid ? ` · ${kid.weekly}` : ""}`}
                  <br />
                  <span className="muted">
                    {m.id === me.memberId
                      ? "You"
                      : m.role === "PARENT"
                        ? "Decides anything you ask for"
                        : kid
                          ? `${kid.goal[0].toUpperCase()}${kid.goal.slice(1)}: ${aed(kid.savedMinor)} of ${plain(kid.goalMinor)}, ${kid.percent}%`
                          : "No goal yet"}
                  </span>
                  {m.role === "CHILD" && kid && (
                    <span style={{ display: "block", marginTop: 4 }}>
                      <Progress percent={kid.percent} label={`${kid.percent} percent of the goal`} thin />
                    </span>
                  )}
                </span>
              </div>
            );
          })}
        </div>
      </section>

      <section className="stack" style={{ gap: 10 }}>
        <div className="row" style={{ padding: 0, alignItems: "baseline" }}>
          <h2 className="first">Family rules</h2>
          <span className="muted">Checked by the app on every request</span>
        </div>
        {rules.map((r, i) => (
          <div key={r.said} className={`draft${ruleOn[i] ? " done" : ""}`}>
            {!ruleOn[i] && <p className="muted">Drafted from what you wrote</p>}
            <div className="row" style={{ padding: 0 }}>
              <span>"{r.said}"</span>
              {ruleOn[i] && <span className="confirmed">On</span>}
            </div>
            <div className="typed">
              {r.typed.map((t) => (
                <span key={t}>{t}</span>
              ))}
            </div>
            {!ruleOn[i] && (
              <div style={{ display: "flex", gap: 8, marginTop: 4 }}>
                <button onClick={() => setRuleOn((on) => on.map((v, j) => (j === i ? true : v)))}>Turn on</button>
                <button className="quiet">Edit</button>
              </div>
            )}
          </div>
        ))}
        <Link href="/parent/ask" className="link" style={{ fontSize: 14 }}>Write a new rule in Ask</Link>
      </section>

      <section className="card" style={{ display: "grid", gap: 6 }}>
        <p className="card-title">Food profiles</p>
        <p style={{ fontSize: 14 }}>Whole family: halal only</p>
        <p style={{ fontSize: 14 }}>Adam: peanut allergy</p>
        <p style={{ fontSize: 14 }}>Weekly meal plan: on (optional)</p>
        <p className="muted" style={{ marginTop: 4 }}>
          Used for ask-to-buy. The app flags products but never certifies them: always check the halal mark and the pack label.
        </p>
      </section>

      <Link href="/parent" className="askbar" style={{ justifyContent: "space-between", color: "var(--ink)", borderColor: "var(--rule)" }}>
        <span>Decided requests</span>
        <span className="muted">{decidedCount} ›</span>
      </Link>
      <SampleNote />
    </div>
  );
}
