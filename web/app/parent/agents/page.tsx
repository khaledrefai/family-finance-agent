"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { agentsApi, AgentRun } from "@/lib/api";
import { MoneyCheck } from "../../money-check";
import { Loading, Masthead } from "../../ui";
import { useSession } from "../../use-session";

const AGENT_NAMES: Record<string, string> = {
  ORCHESTRATOR: "Coach (plans the steps)",
  BOOKKEEPER: "Bookkeeper",
  GATEKEEPER: "Gatekeeper",
  SHOPPER: "Shopper",
  PLANNER: "Planner",
  COACH: "Coach",
};

const WHEN = new Intl.DateTimeFormat("en-GB", {
  day: "numeric", month: "short", hour: "2-digit", minute: "2-digit", timeZone: "Asia/Dubai",
});

// "What the agents did": every agent run for this family, newest first, with each call it made to the money engine.
// The core keeps this trail; the agents can't change it. Parents only.
export default function AgentsPage() {
  const { me } = useSession("PARENT");
  const [runs, setRuns] = useState<AgentRun[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  function load() {
    agentsApi.runs(40).then(setRuns).catch((err) => setError((err as Error).message));
  }

  useEffect(() => {
    if (me) load();
  }, [me]);

  if (!me) return <Loading />;

  return (
    <div className="stack">
      <Masthead title="What the agents did" sub="Every step, as the app recorded it" />

      <MoneyCheck />

      <section style={{ display: "grid", gap: 10 }}>
        <div className="row" style={{ padding: 0 }}>
          <h2 className="first section-head" style={{ margin: 0 }}>Recent agent runs</h2>
          <button type="button" className="text" onClick={load}>Refresh</button>
        </div>
        <p className="muted" style={{ margin: 0, fontSize: 14 }}>
          Each run is one agent doing one job. The calls under it are what it asked the money engine. Anything that
          changes money is saved as a draft for a parent to confirm.
        </p>
        {error && <p className="error" role="alert">{error}</p>}
        {runs === null && !error && <p className="muted">Loading…</p>}
        {runs?.length === 0 && <p className="empty">No agent runs yet. Ask something in <Link href="/parent/ask" className="link">Ask</Link>.</p>}
        {runs?.map((r) => <RunCard key={r.id} run={r} />)}
      </section>
    </div>
  );
}

function RunCard({ run: r }: { run: AgentRun }) {
  const how = r.llmUsed
    ? `AI (${r.model ?? "Claude"})`
    : `Simple rules${r.fallbackReason && r.fallbackReason !== "rule-based" ? ` (${r.fallbackReason})` : ""}`;
  const tokens = (r.inputTokens ?? 0) + (r.outputTokens ?? 0);
  return (
    <details className="card run-card">
      <summary>
        <span className="run-head">
          <b>{AGENT_NAMES[r.agent] ?? r.agent}</b>
          <span className={`run-status ${r.status.toLowerCase()}`}>{r.status === "SUCCEEDED" ? "done" : r.status.toLowerCase()}</span>
        </span>
        <span className="muted" style={{ display: "block", fontSize: 13 }}>
          {WHEN.format(new Date(r.startedAt))} · {r.inputSummary === "Weekly money check" && r.triggeredBy ? "money check" : `asked by ${r.triggeredBy ?? "the app"}`}
          {" · "}{r.calls.length} call{r.calls.length === 1 ? "" : "s"}
        </span>
        {r.inputSummary && <span className="run-input">&ldquo;{r.inputSummary}&rdquo;</span>}
      </summary>
      <div style={{ display: "grid", gap: 6, marginTop: 8 }}>
        {r.outputSummary && <p className="step-result" style={{ margin: 0 }}>{r.outputSummary}</p>}
        <p className="muted" style={{ margin: 0, fontSize: 13 }}>
          {how}
          {tokens > 0 && ` · ${tokens.toLocaleString("en-US")} tokens`}
          {r.latencyMs != null && ` · ${(r.latencyMs / 1000).toFixed(1)} s`}
        </p>
        {r.calls.length > 0 && (
          <ol className="run-calls">
            {r.calls.map((c, i) => (
              <li key={i}>
                <code>{c.tool}</code>
                {c.changesState && <span className="step-tag">saved a draft</span>}
                {c.status !== "OK" && <span className="muted"> · {c.status.toLowerCase()} ({c.httpStatus})</span>}
              </li>
            ))}
          </ol>
        )}
      </div>
    </details>
  );
}
