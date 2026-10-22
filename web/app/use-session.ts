"use client";

import { useRouter } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { api, ApiError, Me, Role, setToken, SpendingRequest } from "@/lib/api";

// Shared by the parent and child pages: loads who I am and my visible requests,
// and sends me back to login if the token is missing, expired, or the wrong role.
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

  const logout = () => {
    setToken(null);
    router.replace("/");
  };

  return { me, requests, error, setError, reload, logout };
}
