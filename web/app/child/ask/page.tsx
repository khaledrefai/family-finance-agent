"use client";

import { useEffect, useRef, useState } from "react";
import { api } from "@/lib/api";
import { Comparison, findPrices, Offer } from "@/lib/prices";
import { AgentChat } from "../../agent-chat";
import { PriceResults, useSearchStep } from "../../price-results";
import { Loading } from "../../ui";
import { useSession } from "../../use-session";
import { BuddySays, CamelSearching } from "../kid-ui";

// Kid Ask: tell the agent what I bought, find the best price for something I want and ask a parent for it,
// or ask for something directly. Nobody pastes links: the kid says what it is and the app searches the shops
// (the core's price comparison). Picking an offer sends a request with the core's price, not a typed one.
// While a request is on its way the buttons wait, and the answer shows right under the button that was pressed,
// so nobody taps again thinking nothing happened. The core also treats a repeat of the same ask as the same request.
// Two big choices first, "I bought something" and "I want something", so a 9-year-old only sees one job at a time.
// Asking for something the shops don't have is folded under the search.
const IDEAS = ["Sony WH-CH720N headphones", "wireless headphones", "LEGO building set", "Nintendo Switch game"];

export default function ChildAsk() {
  const { me, error, setError, reload } = useSession("CHILD");
  const [want, setWant] = useState("");
  const [finding, setFinding] = useState(false);
  const [found, setFound] = useState<{ comparison: Comparison | null; reply: string } | null>(null);
  const [title, setTitle] = useState("");
  const [amount, setAmount] = useState("");
  // What happened to the last send, shown next to where it was sent from: an offer or the "something else" form
  const [sent, setSent] = useState<{ text: string; ok: boolean; at: "offer" | "form" } | null>(null);
  const [sending, setSending] = useState(false);
  const [sentOffers, setSentOffers] = useState<Record<string, string>>({});
  const notice = useRef<HTMLDivElement>(null);
  const step = useSearchStep(finding);
  const [mode, setMode] = useState<"bought" | "want">("bought");
  const [own, setOwn] = useState(false);
  // Home's "Tell me in Ask" opens on what I bought; ?do=want opens on what I want
  useEffect(() => {
    if (new URLSearchParams(window.location.search).get("do") === "want") setMode("want");
  }, []);

  // Bring the answer into view on a phone, where it could be below the fold
  useEffect(() => {
    if (sent) notice.current?.scrollIntoView({ behavior: "smooth", block: "center" });
  }, [sent]);

  if (!me) return <Loading />;
  const young = me.ageBand === "CHILD_6_9";

  function told(saved: { status: string; agentSummary?: string }, what: string, at: "offer" | "form") {
    // A family rule can stop a request before it reaches a parent; the core says which rule and why
    const blocked = saved.status === "BLOCKED";
    setSent({
      at, ok: !blocked,
      text: blocked
        ? `Not sent: ${saved.agentSummary ?? "it is against a family rule."} Ask a parent if you think that's wrong.`
        : `Sent to your parents! They can see "${what}" now, and their answer will show on Home.`,
    });
  }

  async function find(text: string) {
    if (!text.trim() || finding) return;
    setWant(text);
    setFinding(true);
    setSent(null);
    setFound(null);
    setSentOffers({});
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
    if (!found?.comparison || sending) return;
    setSending(true);
    setSent(null);
    try {
      const saved = await api.requestOffer(found.comparison.id, o.key, o.title);
      setError(null);
      setSentOffers((m) => ({ ...m, [o.key]: saved.status === "BLOCKED" ? "Not sent" : "Sent ✓" }));
      told(saved, saved.title, "offer");
      reload();
    } catch (err) {
      setSent({ at: "offer", ok: false, text: `That didn't go through: ${(err as Error).message} Try again.` });
    } finally {
      setSending(false);
    }
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (sending) return;
    setSent(null);
    // Convert "300" or "12.50" to fils without floating-point maths.
    const match = amount.trim().match(/^(\d+)(?:\.(\d{1,2}))?$/);
    if (!match) {
      setSent({ at: "form", ok: false, text: "Enter the price as a number, like 300 or 12.50." });
      return;
    }
    const fils = Number(match[1]) * 100 + Number((match[2] ?? "").padEnd(2, "0"));
    setSending(true);
    try {
      const saved = await api.createRequest(title, fils);
      setError(null);
      told(saved, title, "form");
      setTitle("");
      setAmount("");
      reload();
    } catch (err) {
      setSent({ at: "form", ok: false, text: `That didn't go through: ${(err as Error).message} Try again.` });
    } finally {
      setSending(false);
    }
  }

  function Notice({ at }: { at: "offer" | "form" }) {
    if (sent?.at !== at) return null;
    return (
      <div ref={notice} className={sent.ok ? "sent-note" : "error"} role={sent.ok ? "status" : "alert"}>
        {sent.text}
      </div>
    );
  }

  const c = found?.comparison;
  const blocked = c?.assessment?.blocked;
  const wallet = c?.assessment?.kid;

  return (
    <div className={`stack${young ? " young" : ""}`}>
      <header className="kid-head">
        <h1>Ask</h1>
      </header>
      <div className="kid-choice" role="group" aria-label="What do you want to tell me?">
        <button type="button" className={mode === "bought" ? "on" : undefined} aria-pressed={mode === "bought"} onClick={() => setMode("bought")}>
          I bought something
        </button>
        <button type="button" className={mode === "want" ? "on" : undefined} aria-pressed={mode === "want"} onClick={() => setMode("want")}>
          I want something
        </button>
      </div>
      <BuddySays>
        <p>{mode === "bought"
          ? "Tell me what you bought and how much. I'll keep your picture right, and you get a star."
          : "Tell me what you want. I'll find the best price, then you can ask a parent for it."}</p>
      </BuddySays>

      {mode === "bought" && <>
      <AgentChat
        title="What did you buy?"
        placeholder="12 dirhams shawarma"
        suggestions={["12 dirhams shawarma", "8 dirhams chicken wrap", "5 dirhams juice"]}
      />

      </>}

      {mode === "want" && <>
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
      </section>

      {finding && <CamelSearching step={step} />}

      {found && !c && <p className="turn-agent">{found.reply}</p>}
      {c && (
        <section className="stack" style={{ gap: 10 }}>
          {blocked ? (
            <p className="turn-agent">
              {c.assessment!.facts.find((f) => f.startsWith("Family rule")) ?? "That one is against a family rule."} I won&apos;t send it to a parent.
              {c.assessment!.facts.some((f) => f.includes("you must not have")) &&
                " How about some fruit, or a cheese sandwich from home? A grown-up can check the label with you."}
            </p>
          ) : c.best ? (
            <p className="turn-agent">
              The best price I found is <b>{c.best.total.text}</b> at {c.best.store}.
              {wallet && (wallet.jar && wallet.jarName
                ? ` Your ${wallet.jarName.toLowerCase()} jar has ${wallet.jar.text} and your wallet ${wallet.wallet.text}${wallet.walletCovers ? ": together enough, and the jar is used first." : ", so you'd need a bit more saving."}`
                : wallet.walletCovers ? ` Your wallet has ${wallet.wallet.text}, enough for this.` : ` Your wallet has ${wallet.wallet.text}, so you'd need some help or a bit more saving.`)}
              {c.assessment?.needsParent ? " A parent says yes first." : ""}
            </p>
          ) : null}
          <Notice at="offer" />
          {!blocked && <PriceResults comparison={c} action={sending ? "Sending…" : "Ask for this one"} onPick={ask} busy={sending} done={sentOffers} />}
          <p className="trust">Prices and totals are worked out by the app from the shops&apos; own prices. Your parent sees the same comparison.</p>
        </section>
      )}

      {own ? (
      <form className="panel" onSubmit={submit}>
        <strong>Ask a parent for it yourself</strong>
        <label>
          What is it?
          <input value={title} onChange={(e) => setTitle(e.target.value)} required />
        </label>
        <label>
          Price in AED
          <input inputMode="decimal" placeholder="300 or 12.50" value={amount} onChange={(e) => setAmount(e.target.value)} required />
        </label>
        <button type="submit" disabled={sending}>{sending ? "Sending…" : "Send to a parent"}</button>
        <Notice at="form" />
      </form>
      ) : (
        <button type="button" className="text" style={{ justifySelf: "start" }} onClick={() => setOwn(true)}>
          Not in the shops? Ask a parent for it yourself
        </button>
      )}
      </>}
      {error && <p className="error" role="alert">{error}</p>}
    </div>
  );
}
