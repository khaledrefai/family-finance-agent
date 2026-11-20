"use client";

import { useState } from "react";
import { agent, AgentStep } from "@/lib/api";
import { rulesApi } from "@/lib/live";

interface Turn {
  from: "me" | "agent";
  text: string;
  // A rule the agent just drafted: the parent can turn it on right here (the core only takes that from a parent's tap)
  rule?: { id: string; state: "draft" | "on" | "busy"; note?: string };
  steps?: AgentStep[];
  planned?: boolean;
}

// The Gatekeeper's answer after drafting a rule: I've written this down as a rule: "No energy drinks for Layla" (...)
const DRAFTED = /I've written this down as a rule: "([^"]+)"/;

// A small chat box that talks to the agent service. Used by both the child and parent pages.
// `source` is a line shown under each agent answer, e.g. where its numbers came from.
export function AgentChat({
  title,
  placeholder,
  suggestions = [],
  intro,
  source,
  onReply,
}: {
  title: string;
  placeholder: string;
  suggestions?: string[];
  intro?: string | null;
  source?: string;
  onReply?: () => void;
}) {
  const [text, setText] = useState("");
  const [turns, setTurns] = useState<Turn[]>([]);
  const [busy, setBusy] = useState(false);

  async function send(message: string) {
    if (!message.trim() || busy) return;
    setBusy(true);
    setTurns((t) => [...t, { from: "me", text: message }]);
    setText("");
    try {
      const res = await agent.chat(message);
      // A rule the Gatekeeper drafted, from the answer or from the step that drafted it
      const label = [res.reply, ...(res.steps ?? []).map((s) => s.result)].map((t) => t.match(DRAFTED)?.[1]).find(Boolean);
      const draft = label ? (await rulesApi.list().catch(() => [])).find((r) => r.status === "DRAFT" && r.label === label) : undefined;
      setTurns((t) => [...t, {
        from: "agent", text: res.reply, rule: draft && { id: draft.id, state: "draft" }, steps: res.steps, planned: res.planned,
      }]);
      onReply?.();
    } catch (err) {
      setTurns((t) => [...t, { from: "agent", text: `That didn't send. ${(err as Error).message}` }]);
    } finally {
      setBusy(false);
    }
  }

  async function turnOn(i: number, id: string) {
    const set = (rule: Turn["rule"]) => setTurns((t) => t.map((x, j) => (j === i ? { ...x, rule } : x)));
    set({ id, state: "busy" });
    try {
      const on = await rulesApi.confirm(id);
      set({ id, state: "on", note: on.replaces.length ? `On. It replaces "${on.replaces.join('", "')}".` : `On. "${on.label}" is checked from now on.` });
      onReply?.();
    } catch (err) {
      set({ id, state: "draft", note: (err as Error).message });
    }
  }

  return (
    <section className="panel chat" aria-label={title}>
      <strong>{title}</strong>
      <div className="turns" aria-live="polite">
        {intro && turns.length === 0 && <p className="turn-agent">{intro}</p>}
        {turns.map((t, i) =>
          t.from === "me" ? (
            <p key={i} className="turn-me">{t.text}</p>
          ) : (
            <div key={i} style={{ display: "contents" }}>
              <p className="turn-agent">{t.text}</p>
              {t.rule && t.rule.state !== "on" && (
                <button type="button" style={{ justifySelf: "start" }} disabled={t.rule.state === "busy"} onClick={() => turnOn(i, t.rule!.id)}>
                  {t.rule.state === "busy" ? "Turning on…" : "Turn on"}
                </button>
              )}
              {t.rule?.note && <p className="muted" role="status">{t.rule.note}</p>}
              {t.steps && t.steps.length > 0 && <Steps steps={t.steps} planned={!!t.planned} />}
              {source && <p className="turn-source">{source}</p>}
            </div>
          ),
        )}
        {busy && <p className="thinking">Working it out…</p>}
      </div>
      {suggestions.length > 0 && (
        <div className="chips">
          {suggestions.map((s) => (
            <button key={s} type="button" className="chip" onClick={() => send(s)} disabled={busy}>
              {s}
            </button>
          ))}
        </div>
      )}
      <form
        onSubmit={(e) => {
          e.preventDefault();
          send(text);
        }}
      >
        <input aria-label={title} placeholder={placeholder} value={text} onChange={(e) => setText(e.target.value)} />
        <button type="submit" disabled={busy}>Send</button>
      </form>
    </section>
  );
}

// What each tool is, in words a parent understands
const TOOL_WORDS: Record<string, string> = {
  spending_story: "looked at the spending",
  log_what_i_bought: "logged what was bought",
  can_we_afford: "checked the money and the family rules",
  find_best_price: "searched the shops",
  draft_family_rule: "wrote a rule draft",
  plan_this_month: "made this month's plan",
  plan_dinners: "planned the dinners",
  plan_outings: "planned outings",
  savings_what_if: "ran the savings calculator",
  money_this_month: "read this month's money",
  budgets_this_month: "read the budgets",
  family_goals: "read the goals",
  family_rules: "read the family rules",
  odd_charges: "looked for odd charges",
};

// "How I worked this out": every step the agent took for this answer, in order
function Steps({ steps, planned }: { steps: AgentStep[]; planned: boolean }) {
  return (
    <details className="agent-steps">
      <summary>
        How I worked this out · {planned ? `${steps.length} step${steps.length === 1 ? "" : "s"}, chosen by the AI` : "AI off, fixed path"}
      </summary>
      <ol>
        {steps.map((s, i) => (
          <li key={i} className={s.ok ? undefined : "failed"}>
            <b>{s.agent === "Core" ? "Money engine" : s.agent}</b> {TOOL_WORDS[s.tool] ?? s.tool}
            {s.said && <span className="muted"> · asked: &ldquo;{s.said}&rdquo;</span>}
            {s.changes && <span className="step-tag">{s.tool === "log_what_i_bought" ? "logged" : "draft, needs a parent's tap"}</span>}
            <span className="step-result">{s.result.length > 240 ? `${s.result.slice(0, 240)}…` : s.result}</span>
          </li>
        ))}
      </ol>
    </details>
  );
}
