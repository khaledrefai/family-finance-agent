"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { api, getToken, setToken } from "@/lib/api";

// The seeded demo family. Tapping a name fills in the form, which keeps the demo video short. Each one says the first
// thing to try, so a judge knows where to start without a walkthrough.
const DEMO_FAMILY = [
  { username: "omar", name: "Omar", who: "Parent", kid: false, tryThis: "Approve Adam's football" },
  { username: "sara", name: "Sara", who: "Parent", kid: false, tryThis: "Ask where Layla's money went" },
  { username: "layla", name: "Layla", who: "13 years old", kid: true, tryThis: "Find headphones and ask" },
  { username: "adam", name: "Adam", who: "9 years old", kid: true, tryThis: "Tell the camel a snack" },
];
const DEMO_PASSWORD = "demo1234";

// Login page. Parents go to /parent, children go to /child.
// /login?as=layla comes from the landing page's demo family and fills the form for that person.
// Someone who is still signed in (a notification tap, the Home Screen app) goes straight in.
export default function LoginPage() {
  const router = useRouter();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // Read the address after the page loads, so the page can be built ahead of time
  useEffect(() => {
    const as = new URLSearchParams(window.location.search).get("as");
    if (as && DEMO_FAMILY.some((p) => p.username === as)) {
      setUsername(as);
      setPassword(DEMO_PASSWORD);
    }
    if (getToken()) api.me().then((me) => router.replace(me.role === "PARENT" ? "/parent" : "/child")).catch(() => setToken(null));
  }, [router]);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      setToken(null); // an old token (expired, or from before a redeploy) must not ride along with the login
      const res = await api.login(username, password);
      setToken(res.accessToken);
      router.push(res.role === "PARENT" ? "/parent" : "/child"); // a family still setting up is sent on to /setup
    } catch (err) {
      setError((err as Error).message);
      setBusy(false);
    }
  }

  return (
    <>
      <div className="wordmark">
        <svg width="44" height="44" viewBox="0 0 512 512" aria-hidden="true">
          <rect width="512" height="512" rx="96" fill="var(--ink)" />
          <path d="M136 150h240v228a20 20 0 0 1-20 20H156a20 20 0 0 1-20-20z" fill="var(--chit)" />
          <g transform="rotate(-8 300 300)">
            <rect x="222" y="262" width="150" height="72" rx="12" fill="none" stroke="var(--approved)" strokeWidth="12" />
            <path d="M262 298l20 20 40-40" fill="none" stroke="var(--approved)" strokeWidth="14" strokeLinecap="round" strokeLinejoin="round" />
          </g>
        </svg>
        <h1>Family Finance Coach</h1>
      </div>
      <p className="lede">Where the family's money goes, and who said yes to what.</p>

      <form className="panel" onSubmit={submit}>
        <label>
          Username
          <input value={username} onChange={(e) => setUsername(e.target.value)} autoCapitalize="none" autoComplete="username" required />
        </label>
        <label>
          Password
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" required />
        </label>
        <button type="submit" disabled={busy}>{busy ? "Logging in…" : "Log in"}</button>
        {error && <p className="error" role="alert">{error}</p>}
      </form>

      <p style={{ marginTop: 4 }}>
        New here? <Link href="/signup" className="link">Start your family</Link>
      </p>

      <h2>Try it as the demo family</h2>
      <p className="muted" style={{ marginTop: -6, marginBottom: 12 }}>Pick someone, then log in.</p>
      <div className="people">
        {DEMO_FAMILY.map((p) => (
          <button
            key={p.username}
            type="button"
            aria-pressed={username === p.username}
            onClick={() => {
              setUsername(p.username);
              setPassword(DEMO_PASSWORD);
            }}
          >
            <span className={`initial${p.kid ? " kid" : ""}`} aria-hidden="true">{p.name[0]}</span>
            <span className="who">
              {p.name}
              <span>{p.who}</span>
              <span className="try">Try: {p.tryThis}</span>
            </span>
          </button>
        ))}
      </div>
      <p style={{ marginTop: 24 }}>
        <Link href="/" className="link">What Family Finance Coach does</Link>
      </p>
    </>
  );
}
