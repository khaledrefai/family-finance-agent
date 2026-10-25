import type { Metadata, Viewport } from "next";
// The fonts come from npm packages and are served by the app itself, so a build never has to download them from
// Google Fonts (a failed download there broke a deploy). Each covers Latin and Arabic.
import "@fontsource-variable/readex-pro/index.css";
import "@fontsource-variable/baloo-bhaijaan-2/index.css";
import "./globals.css";
import { ServiceWorker } from "./sw-register";

export const metadata: Metadata = {
  title: "Family Finance Coach",
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
    <html lang="en">
      <body>
        <ServiceWorker />
        {children}
      </body>
    </html>
  );
}
