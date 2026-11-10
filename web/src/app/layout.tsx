import type { Metadata, Viewport } from "next";
import { cookies } from "next/headers";
import { Header } from "@/components/Header";
import { sessionUser } from "@/lib/server/session";
import "./globals.css";

export const metadata: Metadata = {
  title: { default: "Gavel", template: "%s · Gavel" },
  description: "Online auctions: one account to bid and sell.",
  icons: { icon: "/favicon.svg" },
};

export const viewport: Viewport = { width: "device-width", initialScale: 1 };

export default async function RootLayout({ children }: { children: React.ReactNode }) {
  const user = sessionUser(await cookies());
  return (
    <html lang="en">
      <head>
        <link rel="preconnect" href="https://fonts.googleapis.com" />
        <link rel="preconnect" href="https://fonts.gstatic.com" crossOrigin="" />
        {/* eslint-disable-next-line @next/next/no-page-custom-font */}
        <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600&family=Space+Grotesk:wght@500;600&display=swap" rel="stylesheet" />
      </head>
      <body>
        <a href="#main" className="skip-link">Skip to content</a>
        <div className="shell">
          <Header user={user} />
          <main id="main" className="main">
            <div className="container">{children}</div>
          </main>
          <footer className="footer">
            <div className="container footer-inner">
              <span>© Gavel</span>
              <span>Bids are binding commitments.</span>
            </div>
          </footer>
        </div>
      </body>
    </html>
  );
}
