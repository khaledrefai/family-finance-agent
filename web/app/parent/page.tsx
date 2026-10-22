"use client";

import { useEffect, useState } from "react";
import { api, formatMoney, MemberView } from "@/lib/api";
import { useSession } from "../use-session";

// Parent view: see every request in the family and approve or reject the pending ones.
export default function ParentPage() {
  const { me, requests, error, setError, reload, logout } = useSession("PARENT");
  const [members, setMembers] = useState<MemberView[]>([]);

  useEffect(() => {
    if (me) api.members().then(setMembers).catch(() => {});
  }, [me]);

  const nameOf = (id: string) => members.find((m) => m.id === id)?.displayName ?? "Someone";

  async function decide(id: string, decision: "approve" | "reject") {
    try {
      await api.decide(id, decision);
      await reload();
    } catch (err) {
      setError((err as Error).message);
    }
  }

  if (!me) return <p className="muted">Loading…</p>;

  return (
    <>
      <div className="row">
        <h1>Hi {me.displayName}</h1>
        <button className="secondary" onClick={logout}>Log out</button>
      </div>
      <p className="muted">{me.familyName} · parent view</p>
      {error && <p className="error">{error}</p>}

      <h2>Requests</h2>
      {requests.length === 0 && <p className="muted">No requests yet.</p>}
      {requests.map((r) => (
        <div className="card" key={r.id}>
          <div className="row">
            <strong>{r.title}</strong>
            <span className="badge">{r.status}</span>
          </div>
          <p className="muted">
            {nameOf(r.requesterId)} · {formatMoney(r.amountMinor, r.currency)}
          </p>
          {r.status === "PENDING" && r.requesterId !== me.memberId && (
            <div className="row">
              <button onClick={() => decide(r.id, "approve")}>Approve</button>
              <button className="danger" onClick={() => decide(r.id, "reject")}>Reject</button>
            </div>
          )}
          {r.status === "PENDING" && r.requesterId === me.memberId && (
            <p className="muted">Waiting for the other parent.</p>
          )}
        </div>
      ))}
    </>
  );
}
