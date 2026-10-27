"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { api, getToken } from "@/lib/api";

// A new family that hasn't finished the setup steps goes to /setup instead of the parent screens.
// The demo family is always set up, so the video never sees this.
export function SetupGuard() {
  const router = useRouter();
  useEffect(() => {
    if (!getToken()) return;
    api.me().then((me) => {
      if (me.role === "PARENT" && me.setupDone === false) router.replace("/setup");
    }).catch(() => {});
  }, [router]);
  return null;
}
