"use client";

import { useEffect, useState } from "react";
import { api, MemberView } from "@/lib/api";
import { AgentChat } from "../agent-chat";
import { Chit } from "../chit";
import { useSession } from "../use-session";

// Parent view: decide waiting requests first, ask where the kids' money went,
// then see everything already decided.
export default function ParentPage() {
  const { me, requests, error, setError, reload, logout } = useSession("PARENT");
  const [members, setMembers] = useState<MemberView[]>([]);
  const [justDecided, setJustDecided] = useState<string | null>(null);

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

  return (
    <>
      <header className="masthead">
        <div>
          <h1>Hi {me.displayName}</h1>
          <p className="muted">{me.familyName}</p>
        </div>
        <button className="text" onClick={logout}>Log out</button>
      </header>
      {error && <p className="error" role="alert">{error}</p>}

      {fresh && (
        <>
          <h2 className="first">Just decided</h2>
          <Chit request={fresh} from={`${nameOf(fresh.requesterId)} asked`} landed />
        </>
      )}
      {pending.length > 0 && <h2 className={fresh ? undefined : "first"}>Waiting for a decision</h2>}
      {pending.map((r) => (
        <Chit key={r.id} request={r} from={`${nameOf(r.requesterId)} asks`} stamp={false}>
          {r.requesterId !== me.memberId ? (
            <div className="chit-actions">
              <button onClick={() => decide(r.id, "approve")}>Approve</button>
              <button className="decline" onClick={() => decide(r.id, "reject")}>Decline</button>
            </div>
          ) : (
            <p className="chit-note">You asked for this, so the other parent decides.</p>
          )}
        </Chit>
      ))}

      <AgentChat
        title="Ask about the kids' money"
        placeholder="Where did Layla's money go this month?"
        source="Numbers from the money engine, not the AI."
        suggestions={members
          .filter((m) => m.role === "CHILD")
          .map((m) => `Where did ${m.displayName}'s money go this month?`)}
      />

      <h2>Decided</h2>
      {decided.length === 0 && <p className="empty">Approved and declined requests will collect here.</p>}
      {decided.map((r) => (
        <Chit key={r.id} request={r} from={nameOf(r.requesterId)} small />
      ))}
    </>
  );
}
