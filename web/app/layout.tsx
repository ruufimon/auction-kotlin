import type { Metadata } from 'next';

export const metadata: Metadata = {
  title: 'Gavel Auction Marketplace',
  description: 'Experimental Agent Friendly Architecture implementation',
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
