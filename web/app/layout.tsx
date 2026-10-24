import type { Metadata, Viewport } from "next";
import { Baloo_Bhaijaan_2, Readex_Pro } from "next/font/google";
import "./globals.css";
import { ServiceWorker } from "./sw-register";

// Readex Pro covers Latin and Arabic, so an Arabic version later keeps the same voice.
const readex = Readex_Pro({ subsets: ["latin", "arabic"], weight: ["300", "400", "500", "600", "700"], variable: "--font-readex" });
// The kids' screens use a rounder, friendlier face. It also covers Arabic.
const baloo = Baloo_Bhaijaan_2({ subsets: ["latin", "arabic"], weight: ["500", "600", "700"], variable: "--font-kid" });

export const metadata: Metadata = {
  title: "Family Finance",
  description: "Family accountant and money coach (prototype)",
  manifest: "/manifest.webmanifest",
  icons: { icon: "/icon.svg" },
};

export const viewport: Viewport = {
  themeColor: [
    { media: "(prefers-color-scheme: light)", color: "#edf0f3" },
    { media: "(prefers-color-scheme: dark)", color: "#0f1320" },
  ],
  width: "device-width",
  initialScale: 1,
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" className={`${readex.variable} ${baloo.variable}`}>
      <body>
        <ServiceWorker />
        <main>
          {children}
          <p className="fine">Prototype. Demo money only, no real payments.</p>
        </main>
      </body>
    </html>
  );
}
