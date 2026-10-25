"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { api, setToken } from "@/lib/api";

// The seeded demo family. Tapping a name fills in the form, which keeps the demo video short.
const DEMO_FAMILY = [
  { username: "omar", name: "Omar", who: "Parent" },
  { username: "sara", name: "Sara", who: "Parent" },
  { username: "layla", name: "Layla", who: "13 years old" },
  { username: "adam", name: "Adam", who: "9 years old" },
];
const DEMO_PASSWORD = "demo1234";

// Login page. Parents go to /parent, children go to /child.
export default function LoginPage() {
  const router = useRouter();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      const res = await api.login(username, password);
      setToken(res.accessToken);
      router.push(res.role === "PARENT" ? "/parent" : "/child");
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

      <h2>Try it as the demo family</h2>
      <div className="people">
        {DEMO_FAMILY.map((p) => (
          <button
            key={p.username}
            type="button"
            className="quiet"
            onClick={() => {
              setUsername(p.username);
              setPassword(DEMO_PASSWORD);
            }}
          >
            {p.name}
            <span>{p.who}</span>
          </button>
        ))}
      </div>
    </>
  );
}
