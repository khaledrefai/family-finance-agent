"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { setToken } from "@/lib/api";
import { setupApi } from "@/lib/setup";

// Sign up: a parent makes a new family and goes straight to the setup steps (/setup).
// The other parent and the kids are added there, each with their own login.
export default function SignupPage() {
  const router = useRouter();
  const [f, setF] = useState({ familyName: "", displayName: "", username: "", password: "", email: "" });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement>) => setF({ ...f, [k]: e.target.value });

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      setToken(null);
      const res = await setupApi.signup({ ...f, email: f.email || undefined });
      setToken(res.accessToken);
      router.push("/setup");
    } catch (err) {
      setError((err as Error).message);
      setBusy(false);
    }
  }

  return (
    <>
      <h1>Start your family</h1>
      <p className="lede">You set it up as a parent. The other parent and the kids get their own logins in the next step.</p>

      <form className="panel setup-form" onSubmit={submit}>
        <label>
          Family name
          <input value={f.familyName} onChange={set("familyName")} placeholder="The Nasser family" maxLength={120} />
        </label>
        <label>
          Your first name
          <input value={f.displayName} onChange={set("displayName")} autoComplete="given-name" maxLength={80} required />
        </label>
        <label>
          Username
          <input value={f.username} onChange={set("username")} autoCapitalize="none" autoComplete="username"
            placeholder="huda.nasser" maxLength={30} required />
        </label>
        <label>
          Password
          <input type="password" value={f.password} onChange={set("password")} autoComplete="new-password" minLength={8} required />
          <span className="muted" style={{ fontWeight: 400 }}>At least 8 characters.</span>
        </label>
        <label>
          Email (optional)
          <input type="email" value={f.email} onChange={set("email")} autoComplete="email" maxLength={160} />
          <span className="muted" style={{ fontWeight: 400 }}>Only for alerts about the family&apos;s money. You can add it later.</span>
        </label>
        <button type="submit" disabled={busy}>{busy ? "Making your family…" : "Make our family"}</button>
        {error && <p className="error" role="alert">{error}</p>}
      </form>

      <p className="muted" style={{ fontSize: 13 }}>
        No real money moves in this app and it never links to a bank. The kids&apos; wallets hold demo money only.
      </p>
      <p style={{ marginTop: 16 }}>
        Already have a family here? <Link href="/login" className="link">Log in</Link>
      </p>
    </>
  );
}
