"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { useLiveEvents } from "@/lib/live-stream";
import { Bell, notifyApi } from "@/lib/push";
import { Back, Loading, Masthead } from "../../ui";
import { useSession } from "../../use-session";

// Notifications: everything the app told me, newest first (the same words as the phone notifications).
// Opening the page marks them read. New ones appear by themselves.
const WHEN = new Intl.DateTimeFormat("en-GB", { weekday: "short", day: "numeric", month: "short", hour: "2-digit", minute: "2-digit", timeZone: "Asia/Dubai" });

export default function AlertsPage() {
  const { me } = useSession("PARENT");
  const [bell, setBell] = useState<Bell | null>(null);
  const load = useCallback(() => {
    notifyApi.readAll().then(setBell).catch(() => setBell({ unread: 0, items: [] }));
  }, []);
  useEffect(() => {
    if (me) load();
  }, [me, load]);
  useLiveEvents("all", (e) => {
    if (e.notification) load();
  }, !!me);

  if (!me) return <Loading />;
  return (
    <div className="stack">
      <Back href="/parent" label="Today" />
      <Masthead title="Notifications" sub="Requests, answers, bills and budgets" />
      {bell === null && <span className="skeleton" aria-label="Loading…" role="status" />}
      {bell?.items.length === 0 && (
        <p className="empty">Nothing yet. When one of the kids asks for something, or a bill is due soon, it shows up here.</p>
      )}
      {bell && bell.items.length > 0 && (
        <section className="card" style={{ paddingTop: 4, paddingBottom: 4 }}>
          <div className="rows">
            {bell.items.map((n) => (
              <Link key={n.id} href={n.link} className="row" style={{ display: "block", padding: "12px 0", color: "inherit", textDecoration: "none" }}>
                <b>{n.title}</b>
                <br />
                <span style={{ fontSize: 14 }}>{n.body}</span>
                <br />
                <span className="muted">{WHEN.format(new Date(n.createdAt))}</span>
              </Link>
            ))}
          </div>
        </section>
      )}
      <Link href="/parent/family" className="link">Notification settings</Link>
    </div>
  );
}
