"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { LiveNote, useLiveEvents, useLiveStatus } from "@/lib/live-stream";
import { notifyApi, pushBlocker, pushIsOn, PushSettings, turnOffPush, turnOnPush } from "@/lib/push";
import { Icon } from "./ui";

// Week 5 pieces shared by the parent and kid screens: the toast that slides in when something happens while
// the app is open, the "Live" dot, the bell with its count, and the Notifications card (this phone, email, test).

/** Shows what the core just told me (the same words as the phone notification) for a few seconds. */
export function LiveToast() {
  const [note, setNote] = useState<LiveNote | null>(null);
  useLiveEvents("all", (e) => {
    if (e.notification) setNote(e.notification);
  });
  useEffect(() => {
    if (!note) return;
    const t = setTimeout(() => setNote(null), 7000);
    return () => clearTimeout(t);
  }, [note]);
  if (!note) return null;
  return (
    <div className="toast" role="status" aria-live="polite">
      <Link href={note.link} onClick={() => setNote(null)}>
        <Icon name="bell" size={20} />
        <span style={{ flex: 1 }}>
          <b>{note.title}</b>
          <br />
          {note.body}
        </span>
      </Link>
      <button type="button" className="text" aria-label="Close" onClick={() => setNote(null)}>×</button>
    </div>
  );
}

/** A small dot that says the screen updates by itself. */
export function LiveDot() {
  const on = useLiveStatus();
  return (
    <span className={`live-dot${on ? " on" : ""}`} title={on ? "Updates by itself" : "Reconnecting"}>
      {on ? "Live" : "Offline"}
    </span>
  );
}

/** The bell in the parent's masthead: how many notifications are unread, refreshed live. */
export function BellLink({ href = "/parent/alerts" }: { href?: string }) {
  const [unread, setUnread] = useState(0);
  const load = useCallback(() => {
    notifyApi.bell().then((b) => setUnread(b.unread)).catch(() => {});
  }, []);
  useEffect(load, [load]);
  useLiveEvents("all", (e) => {
    if (e.kind === "hello" || e.notification) load();
  });
  return (
    <Link href={href} className="bell" aria-label={unread ? `Notifications, ${unread} new` : "Notifications"}>
      <Icon name="bell" size={22} />
      {unread > 0 && <span className="bell-count">{unread > 9 ? "9+" : unread}</span>}
    </Link>
  );
}

/**
 * Notifications for this phone (web push), plus for parents the email address for alerts and a
 * "Check now" that runs the bill and budget rules without waiting for the 15-minute check.
 */
export function NotifyCard({ parent, kid = false }: { parent: boolean; kid?: boolean }) {
  const [settings, setSettings] = useState<PushSettings | null>(null);
  const [on, setOn] = useState(false);
  const [blocker, setBlocker] = useState<string | null>(null);
  const [email, setEmail] = useState("");
  const [note, setNote] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    setBlocker(pushBlocker());
    pushIsOn().then(setOn).catch(() => {});
    notifyApi.settings().then((s) => {
      setSettings(s);
      setEmail(s.email && !s.email.endsWith(".local") ? s.email : "");
    }).catch(() => {});
  }, []);

  async function act(work: () => Promise<string>) {
    setBusy(true);
    setNote(null);
    try {
      setNote(await work());
    } catch (err) {
      setNote((err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  const toggle = () => act(async () => {
    if (on) {
      const s = await turnOffPush();
      if (s) setSettings(s);
      setOn(false);
      return "Off for this phone.";
    }
    setSettings(await turnOnPush());
    setOn(true);
    return kid ? "On. You'll hear when a parent answers." : "On. This phone gets requests, bills and budget alerts.";
  });

  return (
    <section className="card" style={{ display: "grid", gap: 10 }}>
      <div className="row" style={{ padding: 0 }}>
        <span className="card-title">{kid ? "Tell me when a parent answers" : "Notifications"}</span>
        <LiveDot />
      </div>
      {!kid && (
        <p style={{ fontSize: 14 }}>
          Requests and answers show up live while the app is open. Phone notifications also reach you when it is closed.
          Bills, card bills, budgets and Sunday&apos;s plan for the week come from fixed rules, not from the AI.
        </p>
      )}
      {blocker ? (
        <p className="muted">{blocker}</p>
      ) : (
        <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
          <button type="button" onClick={toggle} disabled={busy} className={on ? "quiet" : undefined}>
            {on ? "Turn off on this phone" : "Turn on for this phone"}
          </button>
          {on && (
            <button type="button" className="text" disabled={busy} onClick={() => act(async () => (await notifyApi.test()).status)}>
              Send me a test
            </button>
          )}
        </div>
      )}
      {settings && !kid && (
        <p className="muted">{settings.phones === 1 ? "1 phone gets your notifications." : `${settings.phones} phones get your notifications.`}</p>
      )}

      {parent && (
        <>
          <form
            style={{ display: "grid", gap: 6 }}
            onSubmit={(e) => {
              e.preventDefault();
              act(async () => {
                const s = await notifyApi.email(email);
                setSettings(s);
                if (!s.email) return "Email alerts are off.";
                return s.emailReady ? `Saved. Alerts also go to ${s.email}.` : `Saved. Email starts once the server's email is set up.`;
              });
            }}
          >
            <label htmlFor="alert-email" className="label">Email for bill and budget alerts</label>
            <div style={{ display: "flex", gap: 8 }}>
              <input id="alert-email" type="email" inputMode="email" autoComplete="email" placeholder="you@example.com"
                value={email} onChange={(e) => setEmail(e.target.value)} style={{ flex: 1, minWidth: 0 }} />
              <button type="submit" className="quiet" disabled={busy}>Save</button>
            </div>
            {settings && !settings.emailReady && (
              <p className="muted">Email isn&apos;t set up on this server yet, so alerts go to the app and phones only.</p>
            )}
          </form>
          <button type="button" className="text" style={{ justifySelf: "start", minHeight: 44 }} disabled={busy}
            onClick={() => act(async () => {
              const r = await notifyApi.checkNow();
              return r.sent.length ? `Sent: ${r.sent.join("; ")}.` : "Checked. Nothing new since the last alerts.";
            })}>
            Check bills and budgets now
          </button>
        </>
      )}
      {note && <p className="muted" role="status">{note}</p>}
    </section>
  );
}
