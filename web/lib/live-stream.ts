"use client";

// Live updates from the core (week 5). One stream per open tab: GET /api/live, Server-Sent Events.
// It is read with fetch, not EventSource, so the login token travels in the Authorization header and never in the
// address. A ping only says what changed ("REQUEST_DECIDED"); the screen then reloads that part through the normal
// API. When the stream drops it reconnects by itself, and every (re)connect starts with "hello", on which screens
// reload once, so nothing missed while offline stays missed.

import { useEffect, useRef, useState } from "react";
import { getToken } from "./api";

export type LiveKind =
  | "hello"
  | "REQUEST_WAITING"
  | "REQUEST_BLOCKED"
  | "REQUEST_DECIDED"
  | "WALLET_CHANGED"
  | "BILL_DUE"
  | "CARD_DUE"
  | "OVERSPEND"
  | "WEEKLY_PLAN"
  | "OUTINGS_PLANNED"
  | "TEST";

/** What the core says to me about it, when there is something to say (the same words as the phone notification). */
export interface LiveNote {
  id: string;
  title: string;
  body: string;
  link: string;
}

export interface LiveEvent {
  kind: LiveKind;
  notification?: LiveNote;
}

type Listener = (e: LiveEvent) => void;

const listeners = new Set<Listener>();
const statusListeners = new Set<(on: boolean) => void>();
let running = false;
let connected = false;
let abort: AbortController | null = null;

function setConnected(on: boolean) {
  connected = on;
  statusListeners.forEach((l) => l(on));
}

function emit(e: LiveEvent) {
  listeners.forEach((l) => {
    try {
      l(e);
    } catch {
      /* one screen's bug must not stop the others */
    }
  });
}

async function run() {
  let wait = 2000;
  while (running) {
    const token = getToken();
    if (!token) break; // logged out
    abort = new AbortController();
    try {
      const res = await fetch("/api/live", {
        headers: { Authorization: `Bearer ${token}`, Accept: "text/event-stream" },
        cache: "no-store",
        signal: abort.signal,
      });
      if (res.status === 401 || res.status === 403) break;
      if (!res.ok || !res.body) throw new Error(`live ${res.status}`);
      const reader = res.body.pipeThrough(new TextDecoderStream()).getReader();
      let buffer = "";
      wait = 2000;
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        buffer += value;
        // Events are separated by a blank line
        let cut: number;
        while ((cut = buffer.search(/\r?\n\r?\n/)) >= 0) {
          const block = buffer.slice(0, cut);
          buffer = buffer.slice(cut).replace(/^\r?\n\r?\n/, "");
          handle(block);
        }
      }
    } catch {
      /* network blip, server restart or tab going to sleep: try again below */
    }
    setConnected(false);
    if (!running) break;
    await new Promise((r) => setTimeout(r, wait));
    wait = Math.min(wait * 2, 30000);
  }
  setConnected(false);
  running = false;
}

function handle(block: string) {
  let name = "message";
  const data: string[] = [];
  for (const line of block.split(/\r?\n/)) {
    if (line.startsWith(":")) continue; // heartbeat comment
    if (line.startsWith("event:")) name = line.slice(6).trim();
    else if (line.startsWith("data:")) data.push(line.slice(5).replace(/^ /, ""));
  }
  if (name === "message" && data.length === 0) return;
  let parsed: { notification?: LiveNote } = {};
  try {
    parsed = data.length ? JSON.parse(data.join("\n")) : {};
  } catch {
    /* not JSON: treat as a bare ping */
  }
  if (name === "hello") setConnected(true);
  emit({ kind: name as LiveKind, notification: parsed.notification });
}

function start() {
  if (running || typeof window === "undefined") return;
  running = true;
  run();
}

/** Stop the stream (on log out). The next useLive call starts it again. */
export function stopLive() {
  running = false;
  abort?.abort();
}

/**
 * Calls {@code onEvent} for each live event of the given kinds ("hello" included when listed).
 * The latest callback is always used, so it can read fresh state without re-subscribing.
 */
export function useLiveEvents(kinds: LiveKind[] | "all", onEvent: (e: LiveEvent) => void, enabled = true) {
  const ref = useRef(onEvent);
  ref.current = onEvent;
  const key = kinds === "all" ? "all" : kinds.join(",");
  useEffect(() => {
    if (!enabled) return;
    const wanted = key === "all" ? null : new Set(key.split(","));
    const l: Listener = (e) => {
      if (!wanted || wanted.has(e.kind)) ref.current(e);
    };
    listeners.add(l);
    start();
    return () => {
      listeners.delete(l);
    };
  }, [key, enabled]);
}

/** True while the stream is open (the small "Live" dot). */
export function useLiveStatus() {
  const [on, setOn] = useState(connected);
  useEffect(() => {
    statusListeners.add(setOn);
    setOn(connected);
    return () => {
      statusListeners.delete(setOn);
    };
  }, []);
  return on;
}
