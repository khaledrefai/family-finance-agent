"use client";

import { useState } from "react";
import { setupApi } from "@/lib/setup";

// Adding the other parent or a child, each with their own login. Used in the first-run setup and on the Family tab.
// A child needs a parent's consent tick (their own account, and the AI reading what they type).
const AGES = Array.from({ length: 12 }, (_, k) => k + 6);

export function MemberForm({ role, onCancel, onAdded }: { role: "PARENT" | "CHILD"; onCancel: () => void; onAdded: () => void }) {
  const kid = role === "CHILD";
  const [f, setF] = useState({ displayName: "", age: "", username: "", password: "", email: "", goalName: "", goalPrice: "" });
  const [consent, setConsent] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => setF({ ...f, [k]: e.target.value });

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await setupApi.addMember({
        role, displayName: f.displayName, username: f.username, password: f.password,
        ...(kid
          ? { age: Number(f.age), consent, goalName: f.goalName || undefined, goalPrice: f.goalPrice || undefined }
          : { email: f.email || undefined }),
      });
      onAdded();
    } catch (err) {
      setError((err as Error).message);
      setBusy(false);
    }
  }

  return (
    <form className="panel setup-form" onSubmit={submit}>
      <strong>{kid ? "Add a child" : "Add the other parent"}</strong>
      <div className={kid ? "two" : undefined}>
        <label>
          First name
          <input value={f.displayName} onChange={set("displayName")} maxLength={80} required />
        </label>
        {kid && (
          <label>
            Age
            <select value={f.age} onChange={set("age")} required>
              <option value="" disabled>Pick</option>
              {AGES.map((a) => <option key={a} value={a}>{a}</option>)}
            </select>
          </label>
        )}
      </div>
      <div className="two">
        <label>
          Username
          <input value={f.username} onChange={set("username")} autoCapitalize="none" maxLength={30} required
            placeholder={kid ? "mariam.n" : "khalid.n"} />
        </label>
        <label>
          Password
          <input type="password" value={f.password} onChange={set("password")} autoComplete="new-password"
            minLength={kid ? 6 : 8} required />
        </label>
      </div>
      <span className="muted" style={{ fontSize: 13, marginTop: -6 }}>
        {kid ? "At least 6 characters. Give them the username and password yourself." : "At least 8 characters. They can log in straight away."}
      </span>
      {!kid && (
        <label>
          Their email (optional)
          <input type="email" value={f.email} onChange={set("email")} maxLength={160} />
        </label>
      )}
      {kid && (
        <>
          <div className="two">
            <label>
              Saving for (optional)
              <input value={f.goalName} onChange={set("goalName")} placeholder="A bike" maxLength={120} />
            </label>
            <label>
              It costs (AED)
              <input inputMode="decimal" value={f.goalPrice} onChange={set("goalPrice")} placeholder="450" maxLength={12}
                required={!!f.goalName} />
            </label>
          </div>
          <label className="check">
            <input type="checkbox" checked={consent} onChange={(e) => setConsent(e.target.checked)} required />
            <span>
              I am this child&apos;s parent. I agree to them having their own account, and to the app&apos;s AI reading what
              they type so it can answer them. We keep only their first name, age group and login.
            </span>
          </label>
        </>
      )}
      <div className="actions">
        <button type="submit" disabled={busy}>{busy ? "Adding…" : "Add"}</button>
        <button type="button" className="text" onClick={onCancel}>Cancel</button>
      </div>
      {error && <p className="error" role="alert">{error}</p>}
    </form>
  );
}
