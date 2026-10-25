// Service worker: makes the app installable and shows web push notifications (week 5).
// A push carries its own words (title, body, link), written by the core, so nothing is fetched here
// and no login token is needed. Offline caching is not part of the prototype.
self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", (event) => event.waitUntil(self.clients.claim()));
self.addEventListener("fetch", () => {});

self.addEventListener("push", (event) => {
  let note = { title: "Family Finance Coach", body: "Something new is waiting for you.", link: "/", id: undefined };
  try {
    note = { ...note, ...event.data.json() };
  } catch {
    /* no or unreadable payload: show the plain line */
  }
  event.waitUntil(
    self.registration.showNotification(note.title, {
      body: note.body,
      icon: "/icon.svg",
      badge: "/icon.svg",
      tag: note.id,
      data: { link: note.link },
    }),
  );
});

// Tapping a notification opens the app on its page, reusing an open tab when there is one.
self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  const link = (event.notification.data && event.notification.data.link) || "/";
  event.waitUntil(
    self.clients.matchAll({ type: "window", includeUncontrolled: true }).then((tabs) => {
      for (const tab of tabs) {
        if ("focus" in tab) {
          tab.navigate(link).catch(() => {});
          return tab.focus();
        }
      }
      return self.clients.openWindow(link);
    }),
  );
});
