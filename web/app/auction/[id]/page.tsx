import type { Metadata } from 'next';
import { notFound } from 'next/navigation';
import { AuctionDetailPage, auctions, getAuction } from '@/features/marketplace';

type AuctionPageProps = { params: Promise<{ id: string }> };

export function generateStaticParams() {
  return auctions.map((auction) => ({ id: String(auction.id) }));
}

export async function generateMetadata({ params }: AuctionPageProps): Promise<Metadata> {
  const auction = getAuction((await params).id);
  if (!auction) return { title: 'Auction not found — Gavel' };
  const description = `${auction.condition} ${auction.category.toLowerCase()} lot from ${auction.seller}. Current bid: ${formatMoney(auction.currentBid)}.`;
  return { title: `${auction.title} — Gavel`, description, openGraph: { title: `${auction.title} — Gavel`, description, images: [{ url: auction.image, alt: auction.title }] }, twitter: { card: 'summary_large_image', title: `${auction.title} — Gavel`, description, images: [auction.image] } };
}

export default async function AuctionPage({ params }: AuctionPageProps) {
  const auction = getAuction((await params).id);
  if (!auction) notFound();
  return <AuctionDetailPage auction={auction} />;
}

function formatMoney(value: number) {
  return new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 }).format(value);
}
