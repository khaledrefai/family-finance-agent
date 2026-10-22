"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { api, setToken } from "@/lib/api";

// Login page. Parents go to /parent, children go to /child.
export default function LoginPage() {
  const router = useRouter();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    try {
      const res = await api.login(username, password);
      setToken(res.accessToken);
      router.push(res.role === "PARENT" ? "/parent" : "/child");
    } catch (err) {
      setError((err as Error).message);
    }
  }

  return (
    <>
      <h1>Family Finance</h1>
      <form className="card" onSubmit={submit}>
        <label>
          Username
          <input value={username} onChange={(e) => setUsername(e.target.value)} autoCapitalize="none" />
        </label>
        <label>
          Password
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} />
        </label>
        <button type="submit">Log in</button>
        {error && <p className="error">{error}</p>}
      </form>
      <p className="muted">Demo logins: sara, omar (parents), layla (13), adam (9). Password: demo1234</p>
    </>
  );
}
