"use client";

import { useRouter } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { api, ApiError, Me, Role, setToken, SpendingRequest } from "@/lib/api";
import { stopLive, useLiveEvents } from "@/lib/live-stream";

// Shared by the parent and child pages: loads who I am and my visible requests,
// and sends me back to login if the token is missing, expired, or the wrong role.
// The requests reload by themselves when one is asked for, blocked or decided (the live stream, week 5).
export function useSession(expectedRole: Role) {
  const router = useRouter();
  const [me, setMe] = useState<Me | null>(null);
  const [requests, setRequests] = useState<SpendingRequest[]>([]);
  const [error, setError] = useState<string | null>(null);

  const reload = useCallback(async () => {
    try {
      const who = await api.me();
      if (who.role !== expectedRole) {
        router.replace(who.role === "PARENT" ? "/parent" : "/child");
        return;
      }
      setMe(who);
      setRequests(await api.requests());
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) router.replace("/");
      else setError((err as Error).message);
    }
  }, [expectedRole, router]);

  useEffect(() => {
    reload();
  }, [reload]);

  useLiveEvents(["hello", "REQUEST_WAITING", "REQUEST_BLOCKED", "REQUEST_DECIDED"], () => {
    reload();
  }, !!me);

  const logout = () => {
    stopLive();
    setToken(null);
    router.replace("/");
  };

  return { me, requests, error, setError, reload, logout };
}
