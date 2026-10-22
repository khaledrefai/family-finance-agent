"use client";

import { useEffect } from "react";

// Registers the service worker so phones offer "Add to Home Screen" (PWA).
export function ServiceWorker() {
  useEffect(() => {
    if ("serviceWorker" in navigator) navigator.serviceWorker.register("/sw.js").catch(() => {});
  }, []);
  return null;
}
