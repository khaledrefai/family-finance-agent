import type { Metadata, Viewport } from "next";
import "./globals.css";
import { ServiceWorker } from "./sw-register";

export const metadata: Metadata = {
  title: "Family Finance",
  description: "Family accountant and money coach (prototype)",
  manifest: "/manifest.webmanifest",
  icons: { icon: "/icon.svg" },
};

export const viewport: Viewport = { themeColor: "#0f766e", width: "device-width", initialScale: 1 };

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>
        <ServiceWorker />
        <main>
          {children}
          <p className="demo">Prototype. Demo money only, no real payments.</p>
        </main>
      </body>
    </html>
  );
}
