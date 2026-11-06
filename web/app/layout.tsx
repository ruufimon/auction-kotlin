import type { Metadata } from 'next';
import { DM_Sans, Manrope } from 'next/font/google';
import './globals.css';

const body = DM_Sans({ variable: '--font-body', subsets: ['latin'] });
const heading = Manrope({ variable: '--font-display', subsets: ['latin'] });

export const metadata: Metadata = {
  metadataBase: new URL(process.env.SITE_URL ?? 'https://gavel-auction-marketplace.famous-rat-1550.chatgpt.site'),
  title: 'Gavel — Curated live auctions',
  description: 'Discover singular objects and bid with confidence on Gavel.',
  openGraph: {
    title: 'Gavel — Curated live auctions',
    description: 'Objects worth waiting for. Discover singular pieces and bid with confidence.',
    images: [{ url: '/og.png', width: 1200, height: 630, alt: 'Gavel — Objects worth waiting for.' }],
  },
  twitter: {
    card: 'summary_large_image',
    title: 'Gavel — Curated live auctions',
    description: 'Objects worth waiting for. Discover singular pieces and bid with confidence.',
    images: ['/og.png'],
  },
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <html lang="en"><body className={`${body.variable} ${heading.variable} antialiased`}>{children}</body></html>;
}
