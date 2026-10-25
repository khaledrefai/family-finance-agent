"use client";

import { useState } from "react";
import { api } from "@/lib/api";
import { Comparison, findPrices, Offer } from "@/lib/prices";
import { AgentChat } from "../../agent-chat";
import { PriceResults } from "../../price-results";
import { Loading } from "../../ui";
import { useSession } from "../../use-session";
import { BuddySays } from "../kid-ui";

// Kid Ask: tell the agent what I bought, find the best price for something I want and ask a parent for it,
// or ask for something directly. Nobody pastes links: the kid says what it is and the app searches the shops
// (the core's price comparison). Picking an offer sends a request with the core's price, not a typed one.
const IDEAS = ["Sony WH-CH720N headphones", "wireless headphones", "LEGO building set", "Nintendo Switch game"];

export default function ChildAsk() {
  const { me, error, setError, reload } = useSession("CHILD");
  const [want, setWant] = useState("");
  const [finding, setFinding] = useState(false);
  const [found, setFound] = useState<{ comparison: Comparison | null; reply: string } | null>(null);
  const [title, setTitle] = useState("");
  const [amount, setAmount] = useState("");
  const [sent, setSent] = useState<string | null>(null);

  if (!me) return <Loading />;
  const young = me.ageBand === "CHILD_6_9";

  function told(saved: { status: string; agentSummary?: string }, what: string) {
    // A family rule can stop a request before it reaches a parent; the core says which rule and why
    setSent(saved.status === "BLOCKED"
      ? `Not sent: ${saved.agentSummary ?? "it is against a family rule."} Ask a parent if you think that's wrong.`
      : `Sent. Your parents can see "${what}" now, and their answer will show on Home.`);
  }

  async function find(text: string) {
    if (!text.trim() || finding) return;
    setWant(text);
    setFinding(true);
    setSent(null);
    setFound(null);
    try {
      setFound(await findPrices(text));
      setError(null);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setFinding(false);
    }
  }

  async function ask(o: Offer) {
    if (!found?.comparison) return;
    try {
      const saved = await api.requestOffer(found.comparison.id, o.key, o.title);
      setError(null);
      told(saved, saved.title);
      await reload();
    } catch (err) {
      setError((err as Error).message);
    }
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setSent(null);
    // Convert "300" or "12.50" to fils without floating-point maths.
    const match = amount.trim().match(/^(\d+)(?:\.(\d{1,2}))?$/);
    if (!match) {
      setError("Enter the price as a number, like 300 or 12.50.");
      return;
    }
    const fils = Number(match[1]) * 100 + Number((match[2] ?? "").padEnd(2, "0"));
    try {
      const saved = await api.createRequest(title, fils);
      setError(null);
      told(saved, title);
      await reload();
    } catch (err) {
      setError((err as Error).message);
      return;
    }
    setTitle("");
    setAmount("");
  }

  const c = found?.comparison;
  const blocked = c?.assessment?.blocked;
  const wallet = c?.assessment?.kid;

  return (
    <div className={`stack${young ? " young" : ""}`}>
      <header className="kid-head">
        <h1>Ask</h1>
      </header>
      <BuddySays>
        <p>Tell me what you bought and I&apos;ll keep your picture right. Or tell me what you want, and I&apos;ll find the best price.</p>
      </BuddySays>

      <AgentChat
        title="What did you buy?"
        placeholder="12 dirhams shawarma"
        suggestions={["12 dirhams shawarma", "8 dirhams chicken wrap", "5 dirhams juice"]}
      />

      <section className="panel" aria-label="Something you want">
        <strong>Something you want</strong>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            find(want);
          }}
          style={{ display: "flex", gap: 8 }}
        >
          <input aria-label="What do you want?" placeholder={young ? "A LEGO set" : "Wireless headphones"} value={want} onChange={(e) => setWant(e.target.value)} />
          <button type="submit" disabled={finding}>Find it</button>
        </form>
        <div className="chips" style={{ marginBottom: 0 }}>
          {IDEAS.map((s) => (
            <button key={s} type="button" className="chip" onClick={() => find(s)} disabled={finding}>
              {s}
            </button>
          ))}
        </div>
        {finding && <p className="thinking">Looking in the shops…</p>}
      </section>

      {found && !c && <p className="turn-agent">{found.reply}</p>}
      {c && (
        <section className="stack" style={{ gap: 10 }}>
          {blocked ? (
            <p className="turn-agent">
              {c.assessment!.facts.find((f) => f.startsWith("Family rule")) ?? "That one is against a family rule."} I won&apos;t send it to a parent.
            </p>
          ) : c.best ? (
            <p className="turn-agent">
              The best price I found is <b>{c.best.total.text}</b> at {c.best.store}.
              {wallet && (wallet.walletCovers ? ` Your wallet has ${wallet.wallet.text}, enough for this.` : ` Your wallet has ${wallet.wallet.text}, so you'd need some help or a bit more saving.`)}
              {c.assessment?.needsParent ? " A parent says yes first." : ""}
            </p>
          ) : null}
          {!blocked && <PriceResults comparison={c} action="Ask for this one" onPick={ask} />}
          <p className="trust">Prices and totals are worked out by the app from the shops&apos; own prices. Your parent sees the same comparison.</p>
        </section>
      )}

      <form className="panel" onSubmit={submit}>
        <strong>Ask for something else</strong>
        <label>
          What is it?
          <input value={title} onChange={(e) => setTitle(e.target.value)} required />
        </label>
        <label>
          Price in AED
          <input inputMode="decimal" placeholder="300 or 12.50" value={amount} onChange={(e) => setAmount(e.target.value)} required />
        </label>
        <button type="submit">Send to a parent</button>
      </form>
      {error && <p className="error" role="alert">{error}</p>}
      {sent && <p className="muted" role="status">{sent}</p>}
    </div>
  );
}
