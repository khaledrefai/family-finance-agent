"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect, useState } from "react";
import { api, MemberView } from "@/lib/api";
import { headphones } from "@/lib/sample";
import { Chit } from "../../chit";
import { Back, SampleNote, WhySheet } from "../../ui";
import { useSession } from "../../use-session";

// One request with the "Why this?" sheet open: the figures the agent used, the kid's side and the family's side.
// The request is live; the assessment rows are sample figures until POST /api/requests/assess exists.
function Why() {
  const router = useRouter();
  const id = useSearchParams().get("id");
  const { me, requests, error, setError, reload } = useSession("PARENT");
  const [members, setMembers] = useState<MemberView[]>([]);
  const [open, setOpen] = useState(true);

  useEffect(() => {
    if (me) api.members().then(setMembers).catch(() => {});
  }, [me]);

  if (!me) return <p className="muted">Loading…</p>;
  const r = requests.find((x) => x.id === id);
  if (!r) {
    return (
      <div className="stack">
        <Back href="/parent" label="Today" />
        <p className="empty">This request is no longer here. It may have been decided already.</p>
      </div>
    );
  }
  const name = members.find((m) => m.id === r.requesterId)?.displayName ?? "Someone";

  async function decide(decision: "approve" | "reject") {
    try {
      await api.decide(r!.id, decision);
      await reload();
      router.push("/parent");
    } catch (err) {
      setError((err as Error).message);
    }
  }

  const buttons = r.status === "PENDING" && r.requesterId !== me.memberId && (
    <div className="actions">
      <button onClick={() => decide("approve")}>Approve</button>
      <button className="decline" onClick={() => decide("reject")}>Decline</button>
    </div>
  );

  return (
    <div className="stack">
      <Back href="/parent" label="Today" />
      {error && <p className="error" role="alert">{error}</p>}
      <Chit request={r} from={`${name} asks`} stamp={r.status !== "PENDING"}>
        {buttons}
      </Chit>
      {!open && (
        <button type="button" className="quiet" onClick={() => setOpen(true)}>
          Why this?
        </button>
      )}
      <SampleNote />
      {open && (
        <WhySheet
          title="Why I suggested a yes"
          groups={[
            { heading: `${name}'s side`, rows: headphones.kidSide },
            { heading: "The family's side", rows: headphones.familySide },
            { heading: "Family rules checked", rows: headphones.rules.map((x) => ({ ...x, good: x.value === "Not relevant" })) },
          ]}
          footer={`${name} saw only the first list, never the family figures. Worked out exactly by the app, not guessed by the AI.`}
          onClose={() => setOpen(false)}
        >
          {buttons}
        </WhySheet>
      )}
    </div>
  );
}

export default function WhyPage() {
  return (
    <Suspense fallback={<p className="muted">Loading…</p>}>
      <Why />
    </Suspense>
  );
}
