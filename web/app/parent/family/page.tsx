"use client";

import { useEffect, useState } from "react";
import { aed, api, MemberView, plain } from "@/lib/api";
import { FamilyRule, rulesApi } from "@/lib/live";
import { FoodProfile, mealsApi } from "@/lib/meals";
import { kids } from "@/lib/sample";
import { AgentChat } from "../../agent-chat";
import { NotifyCard } from "../../live-ui";
import { Loading, Masthead, Progress, SampleNote } from "../../ui";
import { useSession } from "../../use-session";

// Family: who is in it, the rules the app checks on every request, food profiles, and notifications.
// Members and rules are live. A parent writes a rule in their own words; the agent drafts it, and it only
// counts once a parent presses Turn on here. Kids' goals are still sample content.
const AGE: Record<string, string> = { CHILD_6_9: "6 to 9", PRETEEN_10_12: "10 to 12", TEEN_13_17: "13 to 17" };

export default function FamilyPage() {
  const { me } = useSession("PARENT");
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

  if (!me) return <Loading />;

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

      <NotifyCard parent />

      <FoodProfiles />

      <SampleNote />
    </div>
  );
}

// Who eats what: halal, vegetarian, vegan and foods someone doesn't like, used by the weekly dinners. Allergies are
// family rules above, so one "no peanuts for Adam" covers ask-to-buy and dinners.
function FoodProfiles() {
  const [profiles, setProfiles] = useState<FoodProfile[] | null>(null);
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState<Record<string, { halal: boolean; vegetarian: boolean; vegan: boolean; dislikes: string }>>({});
  const [note, setNote] = useState<string | null>(null);

  useEffect(() => {
    mealsApi.board().then((b) => setProfiles(b.profiles)).catch(() => setProfiles([]));
  }, []);

  function edit() {
    const d: typeof draft = {};
    profiles?.forEach((p) => { d[p.memberId] = { halal: p.halal, vegetarian: p.vegetarian, vegan: p.vegan, dislikes: p.dislikes.join(", ") }; });
    setDraft(d);
    setEditing(true);
    setNote(null);
  }

  async function save() {
    try {
      const next = await mealsApi.profiles(Object.entries(draft).map(([memberId, v]) => ({ memberId, ...v })));
      setProfiles(next);
      setEditing(false);
      setNote("Saved. The weekly dinners follow it from now on.");
    } catch (e) {
      setNote((e as Error).message);
    }
  }

  const set = (id: string, change: Partial<(typeof draft)[string]>) => setDraft((d) => ({ ...d, [id]: { ...d[id], ...change } }));

  return (
    <section className="card" id="food" style={{ display: "grid", gap: 8 }}>
      <div className="row" style={{ padding: 0 }}>
        <span className="card-title">Food profiles</span>
        {!editing && profiles && profiles.length > 0 && <button type="button" className="text" onClick={edit}>Change</button>}
      </div>
      {profiles === null && <p className="muted">Loading…</p>}
      {!editing && profiles?.map((p) => (
        <p key={p.memberId} style={{ fontSize: 14, margin: 0 }}>
          <b>{p.name}</b>: {describe(p)}
        </p>
      ))}
      {editing && profiles?.map((p) => (
        <fieldset key={p.memberId} className="food-edit">
          <legend><b>{p.name}</b></legend>
          <div className="chips" style={{ marginBottom: 0 }}>
            {(["halal", "vegetarian", "vegan"] as const).map((k) => (
              <button key={k} type="button" className={`chip${draft[p.memberId]?.[k] ? " on" : ""}`} aria-pressed={!!draft[p.memberId]?.[k]}
                onClick={() => set(p.memberId, k === "vegan" && !draft[p.memberId]?.vegan ? { vegan: true, vegetarian: true } : { [k]: !draft[p.memberId]?.[k] })}>
                {k[0].toUpperCase() + k.slice(1)}
              </button>
            ))}
          </div>
          <label style={{ fontSize: 14 }}>
            Doesn&apos;t like
            <input value={draft[p.memberId]?.dislikes ?? ""} placeholder="mushrooms, olives" maxLength={200}
              onChange={(e) => set(p.memberId, { dislikes: e.target.value })} />
          </label>
          {p.allergies.length > 0 && <span className="muted" style={{ fontSize: 13 }}>Allergy rule: no {p.allergies.join(", ")}</span>}
        </fieldset>
      ))}
      {editing && (
        <div className="actions">
          <button type="button" onClick={save}>Save food profiles</button>
          <button type="button" className="text" onClick={() => setEditing(false)}>Cancel</button>
        </div>
      )}
      {note && <p className="muted" role="status" style={{ margin: 0 }}>{note}</p>}
      <p className="muted" style={{ margin: 0 }}>
        Used for the weekly dinners; allergies are family rules, used for ask-to-buy too. The app flags food but never certifies it:
        always check the halal mark and the pack label.
      </p>
    </section>
  );
}

function describe(p: FoodProfile): string {
  const parts: string[] = [];
  if (p.halal) parts.push("halal");
  if (p.vegan) parts.push("vegan");
  else if (p.vegetarian) parts.push("vegetarian");
  p.allergies.forEach((a) => parts.push(`no ${a} (allergy rule)`));
  if (p.dislikes.length) parts.push(`doesn't like ${p.dislikes.join(", ")}`);
  return parts.length ? parts.join(", ") : "eats anything";
}
