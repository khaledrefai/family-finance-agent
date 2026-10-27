"use client";

import { useEffect, useState } from "react";
import { api, MemberView } from "@/lib/api";
import { AgentChat } from "../../agent-chat";
import { Loading, Masthead } from "../../ui";
import { useSession } from "../../use-session";

// The accountant chat. The live agent service answers questions, logs spending said in words, and turns a rule said
// in words into a draft the parent confirms. A new family's income, bills, budgets and plan are set up at /setup, so
// this tab is only the chat. The suggestions use the family's own kids.
export default function AskPage() {
  const { me } = useSession("PARENT");
  const [members, setMembers] = useState<MemberView[]>([]);

  useEffect(() => {
    if (me) api.members().then(setMembers).catch(() => {});
  }, [me]);

  if (!me) return <Loading />;
  const kids = members.filter((m) => m.role === "CHILD");

  return (
    <div className="stack">
      <Masthead title="Ask the accountant" sub="About the family's money, in your own words" />

      <AgentChat
        title="Ask anything"
        placeholder={kids[0] ? `Where did ${kids[0].displayName}'s money go this month?` : "Can we afford a AED 1,200 weekend away?"}
        source="Totals are worked out exactly by the app, not guessed by the AI."
        suggestions={[
          ...members.filter((m) => m.role === "CHILD").map((m) => `Where did ${m.displayName}'s money go this month?`),
          "Anything over AED 100 needs me",
          "I got a AED 5,000 bonus",
        ]}
      />

      <section className="card" style={{ display: "grid", gap: 6 }}>
        <p className="card-title">What you can say here</p>
        <ul className="plain-list">
          <li><b>Ask a question:</b> &quot;Can we afford a AED 1,200 weekend away?&quot;</li>
          <li><b>Log a payment:</b> &quot;taxi to the airport 85 dirhams&quot;</li>
          <li><b>Make a family rule:</b> &quot;{kids[0] ? `${kids[0].displayName}: snacks up to AED 50 a month` : "anything over AED 100 needs me"}&quot;</li>
        </ul>
        <p className="muted" style={{ fontSize: 13, margin: 0 }}>Anything that changes money comes back as a draft. Nothing counts until you confirm it.</p>
      </section>
    </div>
  );
}
