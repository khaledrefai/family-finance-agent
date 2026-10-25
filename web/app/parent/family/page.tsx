"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { aed, api, MemberView, plain } from "@/lib/api";
import { FamilyRule, rulesApi } from "@/lib/live";
import { kids } from "@/lib/sample";
import { AgentChat } from "../../agent-chat";
import { Masthead, Progress, SampleNote } from "../../ui";
import { useSession } from "../../use-session";

// Family: who is in it, the rules the app checks on every request, and food profiles.
// Members and rules are live. A parent writes a rule in their own words; the agent drafts it, and it only
// counts once a parent presses Turn on here. Kids' goals are still sample content.
const AGE: Record<string, string> = { CHILD_6_9: "6 to 9", PRETEEN_10_12: "10 to 12", TEEN_13_17: "13 to 17" };

export default function FamilyPage() {
  const { me, requests } = useSession("PARENT");
  const [members, setMembers] = useState<MemberView[]>([]);
  const [rules, setRules] = useState<FamilyRule[] | null>(null);
  const [ruleNote, setRuleNote] = useState<string | null>(null);

  const loadRules = () => rulesApi.list().then(setRules).catch(() => setRules([]));
  useEffect(() => {
    if (!me) return;
    api.members().then(setMembers).catch(() => {});
    loadRules();
  }, [me]);

  async function turnOn(r: FamilyRule) {
    try {
      const on = await rulesApi.confirm(r.id);
      setRuleNote(on.replaces.length ? `On. It replaces "${on.replaces.join('", "')}".` : `On. "${on.label}" is checked from now on.`);
      await loadRules();
    } catch (err) {
      setRuleNote((err as Error).message);
    }
  }

  async function turnOff(r: FamilyRule) {
    try {
      await rulesApi.off(r.id);
      setRuleNote(r.status === "DRAFT" ? "Draft removed." : `Off. "${r.label}" is no longer checked.`);
      await loadRules();
    } catch (err) {
      setRuleNote((err as Error).message);
    }
  }

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
        {rules === null && <p className="muted">Loading the rules…</p>}
        {rules?.length === 0 && <p className="empty">No rules yet. Write one below, like "no peanuts for Adam".</p>}
        {rules?.map((r) => {
          const on = r.status === "ACTIVE";
          return (
            <div key={r.id} className={`draft${on ? " done" : ""}`}>
              {!on && <p className="muted">Drafted from what you wrote. Not checked until you turn it on.</p>}
              <div className="row" style={{ padding: 0 }}>
                <span>&ldquo;{r.label}&rdquo;</span>
                {on && <span className="confirmed">On</span>}
              </div>
              <div className="typed">
                {r.chips.map((t) => (
                  <span key={t}>{t}</span>
                ))}
              </div>
              <div style={{ display: "flex", gap: 8, marginTop: 4 }}>
                {!on && <button onClick={() => turnOn(r)}>Turn on</button>}
                <button className={on ? "text" : "quiet"} onClick={() => turnOff(r)}>{on ? "Turn off" : "Remove"}</button>
              </div>
            </div>
          );
        })}
        {ruleNote && <p className="muted" role="status">{ruleNote}</p>}
        <AgentChat
          title="Write a rule in your own words"
          placeholder="No energy drinks for the kids"
          suggestions={["No energy drinks for the kids", "Anything over AED 100 needs me", "Layla: snacks up to AED 50 a month"]}
          source="The app checks the typed rule on every request. The AI only reads your words."
          onReply={loadRules}
        />
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
