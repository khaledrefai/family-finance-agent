"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect, useState } from "react";
import { aed, api, MemberView } from "@/lib/api";
import { Assessment, core, dayLongMonth, dayMonth, useAccounts } from "@/lib/live";
import { cardCheck, headphones } from "@/lib/sample";
import { Chit } from "../../chit";
import { AccountPicker, Back, CardWarning, SampleNote, WhySheet } from "../../ui";
import { useSession } from "../../use-session";

// One request with the "Why this?" sheet open: the figures the agent used, the kid's side and the family's side.
// The request and its assessment are live (POST /api/requests/assess, worked out by the money engine).
function Why() {
  const router = useRouter();
  const id = useSearchParams().get("id");
  const { me, requests, error, setError, reload } = useSession("PARENT");
  const [members, setMembers] = useState<MemberView[]>([]);
  const [open, setOpen] = useState(true);
  const [payFrom, setPayFrom] = useState<string | null>(null);
  const { accounts, live } = useAccounts(!!me);
  const [assessment, setAssessment] = useState<Assessment | null>(null);
  const r = requests.find((x) => x.id === id);
  const account = payFrom ?? accounts.find((a) => a.kind === "CREDIT_CARD")?.id ?? "visa";

  useEffect(() => {
    if (me) api.members().then(setMembers).catch(() => {});
  }, [me]);

  useEffect(() => {
    if (!live || !r) return;
    core.assess((r.amountMinor / 100).toFixed(2), r.requesterId, account, r.title, r.category).then(setAssessment).catch(() => setAssessment(null));
  }, [live, r, account]);

  if (!me) return <p className="muted">Loading…</p>;
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

  const acct = accounts.find((a) => a.id === account);
  const sampleCheck = cardCheck[account];
  const a = live ? assessment : null;
  const check = a ? (a.card && { due: dayLongMonth(a.card.dueOn), withMinor: a.card.billWith.minor, freeByDueMinor: a.card.freeByDue.minor, shortMinor: a.card.shortBy.minor, fits: a.card.fits })
    : sampleCheck && { ...sampleCheck, fits: false };
  const paying = r.status === "PENDING" && r.requesterId !== me.memberId && (
    <div style={{ display: "grid", gap: 8 }}>
      <AccountPicker label="Pay from" value={account} onChange={setPayFrom} accounts={accounts} />
      {a ? <CardWarning accountId={account} live={a.card && { card: a.card.card, ...check!, fits: a.card.fits }} /> : <CardWarning accountId={account} />}
    </div>
  );
  const kidSide = a?.kid
    ? [
        { label: "Price asked", value: a.amount.text },
        { label: "In the demo wallet", value: a.kid.wallet.text },
        { label: "Wallet after this", value: a.kid.walletAfter.text, over: !a.kid.walletCovers },
      ]
    : a ? [{ label: "Price asked", value: a.amount.text }] : headphones.kidSide;
  const familySide = a
    ? [
        ...(a.freeUntilPay ? [{ label: `Free until pay day${a.nextIncomeOn ? ` (${dayMonth(a.nextIncomeOn)})` : ""}`, value: a.freeUntilPay.text }] : []),
        ...(a.freeAfter ? [{ label: "Free after this", value: a.freeAfter.text, over: a.freeAfter.minor < 0 }] : []),
        ...(a.nextCommitment ? [{ label: "Next bill", value: a.nextCommitment }] : []),
      ]
    : headphones.familySide;
  // Every family rule that applies to this child, with what the rule engine found for it
  const ruleWord = { OK: "Fine", NEEDS_PARENT: "Sent to you", BLOCKED: "Blocked" } as const;
  const rules = a
    ? a.rules
      ? a.rules.hits.map((h) => ({ label: h.rule, value: ruleWord[h.outcome], good: h.outcome === "OK", over: h.outcome === "BLOCKED" }))
      : []
    : headphones.rules.map((x) => ({ ...x, good: x.value === "Not relevant" }));
  const ruleNotes = a?.rules?.hits.filter((h) => h.outcome !== "OK").map((h) => h.detail) ?? [];
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
        {paying}
        {buttons}
      </Chit>
      {!open && (
        <button type="button" className="quiet" onClick={() => setOpen(true)}>
          Why this?
        </button>
      )}
      {ruleNotes.map((n) => (
        <p key={n} className="trust">{n}</p>
      ))}
      <SampleNote live={!!a} />
      {open && (
        <WhySheet
          title={a ? (a.verdict === "DOES_NOT_FIT" ? "Why this needs a careful look" : "Why this looks fine") : "Why I suggested a yes"}
          groups={[
            { heading: `${name}'s side`, rows: kidSide },
            { heading: "The family's side", rows: familySide },
            ...(rules.length ? [{ heading: "Family rules checked", rows: rules }] : []),
            ...(check && acct
              ? [{
                  heading: "Card bill check",
                  rows: [
                    { label: "Paid from", value: `${acct.label}${acct.last4 ? ` ···${acct.last4}` : ""}` },
                    { label: `Bill on ${check.due}, with this`, value: aed(check.withMinor) },
                    { label: "Free by then", value: aed(check.freeByDueMinor) },
                    ...(check.fits ? [] : [{ label: "Short by", value: aed(check.shortMinor), over: true }]),
                  ],
                }]
              : []),
          ]}
          footer={`${name} saw only the first list, never the family figures. Worked out exactly by the app, not guessed by the AI.`}
          onClose={() => setOpen(false)}
        >
          {paying}
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
