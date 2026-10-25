"use client";

// Phone notifications (web push) and the bell. The core keeps the subscription and sends the pushes;
// this file only asks the browser for permission and hands the core what the browser gives back.

import { call } from "./api";

export interface PushSettings {
  publicKey: string;
  phones: number;
  email: string | null;
  emailReady: boolean;
}

export interface BellItem {
  id: string;
  kind: string;
  title: string;
  body: string;
  link: string;
  createdAt: string;
  read: boolean;
}

export interface Bell {
  unread: number;
  items: BellItem[];
}

export interface WeekPlan {
  label: string;
  freeThisWeek: { minor: number; text: string };
  nextIncomeOn: string | null;
  daysToIncome: number;
  headline: string;
  bills: { label: string; amount: { text: string }; note: string }[];
  slowDown: { label: string; amount: { minor: number; text: string }; note: string }[];
  body: string;
}

export interface Wallet {
  memberId: string;
  name: string;
  balance: { minor: number; text: string };
  demoMoney: boolean;
  recent: { kind: string; amount: { minor: number; text: string }; description: string; at: string }[];
}

export const notifyApi = {
  settings: () => call<PushSettings>("/api/push/settings"),
  bell: () => call<Bell>("/api/notifications"),
  readAll: () => call<Bell>("/api/notifications/read", { method: "POST" }),
  test: () => call<{ status: string }>("/api/push/test", { method: "POST" }),
  email: (email: string) => call<PushSettings>("/api/me/email", { method: "PUT", body: JSON.stringify({ email }) }),
  checkNow: () => call<{ sent: string[] }>("/api/alerts/check?weekly=true", { method: "POST" }),
  week: () => call<WeekPlan>("/api/plan/week"),
  wallet: (memberId?: string) => call<Wallet>(`/api/wallet${memberId ? `?memberId=${memberId}` : ""}`),
};

/**
 * Why this phone can't take notifications, in a sentence for the person holding it, or null when it can.
 * Browsers only allow push on https (or on localhost while developing); iPhones only from the Home Screen app.
 */
export function pushBlocker(): string | null {
  if (typeof window === "undefined") return "Loading…";
  if (!window.isSecureContext) {
    return "Phone notifications need the app on a secure (https) address. Live updates on this screen still work.";
  }
  const ios = /iPhone|iPad|iPod/.test(navigator.userAgent);
  if (!("serviceWorker" in navigator) || !("PushManager" in window) || !("Notification" in window)) {
    return ios
      ? "On iPhone, add the app to your Home Screen first (Share, Add to Home Screen), then turn this on there."
      : "This browser can't show notifications from the app.";
  }
  if (Notification.permission === "denied") {
    return "Notifications are blocked for this site. Allow them in the browser's site settings, then try again.";
  }
  return null;
}

/** Is this phone already subscribed (in the browser)? */
export async function pushIsOn(): Promise<boolean> {
  if (pushBlocker()) return false;
  const reg = await navigator.serviceWorker.getRegistration();
  return !!(await reg?.pushManager.getSubscription());
}

export async function turnOnPush(): Promise<PushSettings> {
  const blocker = pushBlocker();
  if (blocker) throw new Error(blocker);
  const permission = await Notification.requestPermission();
  if (permission !== "granted") throw new Error("Notifications stay off. You can turn them on any time here.");
  const { publicKey } = await notifyApi.settings();
  const reg = await navigator.serviceWorker.register("/sw.js");
  await navigator.serviceWorker.ready;
  let sub = await reg.pushManager.getSubscription();
  // A subscription made for another server key (an older install) has to be replaced
  if (sub && keyOf(sub) !== publicKey) {
    await sub.unsubscribe();
    sub = null;
  }
  sub ??= await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: fromB64(publicKey) });
  const json = sub.toJSON();
  return call<PushSettings>("/api/push/subscriptions", {
    method: "POST",
    body: JSON.stringify({ endpoint: json.endpoint, keys: { p256dh: json.keys?.p256dh, auth: json.keys?.auth } }),
  });
}

export async function turnOffPush(): Promise<PushSettings | null> {
  const reg = await navigator.serviceWorker.getRegistration();
  const sub = await reg?.pushManager.getSubscription();
  if (!sub) return null;
  const endpoint = sub.endpoint;
  await sub.unsubscribe();
  return call<PushSettings>("/api/push/unsubscribe", { method: "POST", body: JSON.stringify({ endpoint }) });
}

function keyOf(sub: PushSubscription): string | null {
  const key = sub.options.applicationServerKey;
  if (!key) return null;
  const bytes = new Uint8Array(key as ArrayBuffer);
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function fromB64(b64url: string): Uint8Array<ArrayBuffer> {
  const b64 = b64url.replace(/-/g, "+").replace(/_/g, "/").padEnd(Math.ceil(b64url.length / 4) * 4, "=");
  const raw = atob(b64);
  const out = new Uint8Array(new ArrayBuffer(raw.length));
  for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i);
  return out;
}
