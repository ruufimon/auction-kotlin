'use client';

import { useState } from 'react';
import Image from 'next/image';
import Link from 'next/link';
import { ArrowLeft, ArrowRight, BadgeCheck, Clock3, Gavel, Heart, LockKeyhole, MapPin, PackageCheck, ShieldCheck, Star } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { cn } from '@/lib/utils';
import { bidEnabledAuction, formatAuctionMoney, type Auction } from '../model/auction';

export default function AuctionDetailPage({ auction }: { auction: Auction }) {
  const [favorite, setFavorite] = useState(false);
  const [bid, setBid] = useState((auction.currentBid + 50).toLocaleString('en-US'));
  const [notice, setNotice] = useState('');

  function placeBid() {
    if (!auction.biddingOpen) return;
    if (!window.sessionStorage.getItem('gavel-bidder-profile')) {
      window.location.href = `/onboarding?returnTo=${encodeURIComponent(`/auction/${auction.id}`)}`;
      return;
    }
    const amount = Number(bid.replaceAll(',', ''));
    const minimum = auction.currentBid + 50;
    setNotice(Number.isFinite(amount) && amount >= minimum ? `Bid of ${formatAuctionMoney.format(amount)} is ready for confirmation.` : `Enter at least ${formatAuctionMoney.format(minimum)}.`);
  }

  return <main className="min-h-screen bg-background text-foreground">
    <header className="border-b border-border/80 bg-background/90"><div className="mx-auto flex h-20 max-w-[1280px] items-center justify-between px-5 lg:px-10"><Link href="/" className="flex items-center gap-2.5" aria-label="Gavel home"><span className="grid size-10 place-items-center rounded-full bg-primary text-primary-foreground"><Gavel className="size-5" /></span><span className="font-heading text-xl font-bold tracking-[-0.04em]">Gavel</span></Link><Button nativeButton={false} render={<Link href="/" />} variant="outline" className="rounded-full"><ArrowLeft data-icon="inline-start" /> All auctions</Button></div></header>

    <div className="mx-auto max-w-[1280px] px-5 py-7 lg:px-10 lg:py-10">
      <Link href="/" className="mb-6 inline-flex items-center gap-2 text-sm font-semibold text-muted-foreground transition-colors hover:text-foreground"><ArrowLeft className="size-4" /> Back to auction list</Link>
      <div className="grid gap-7 lg:grid-cols-[minmax(0,1.35fr)_minmax(340px,0.65fr)]">
        <section><div className="relative aspect-[4/3] overflow-hidden rounded-3xl bg-secondary"><Image src={auction.image} alt={auction.title} fill priority sizes="(min-width: 1024px) 65vw, 100vw" unoptimized className="object-cover" /><div className="absolute left-4 top-4"><Badge className={auction.biddingOpen ? 'bg-[#d85f4a] text-white' : 'bg-white/90 text-[#253027]'}>{auction.biddingOpen ? <><Gavel /> Open for bids</> : <><LockKeyhole /> Browse only</>}</Badge></div><button onClick={() => setFavorite((value) => !value)} className="absolute right-4 top-4 grid size-11 place-items-center rounded-full bg-white/90 text-[#253027] shadow-sm backdrop-blur" aria-label={`${favorite ? 'Remove from' : 'Add to'} favorites`}><Heart className={cn('size-5', favorite && 'fill-[#d85f4a] text-[#d85f4a]')} /></button></div></section>

        <aside className="lg:sticky lg:top-8 lg:self-start"><div className="overflow-hidden rounded-3xl bg-[#263129] text-[#f8f4eb] shadow-2xl shadow-[#263129]/15"><div className="border-b border-white/10 p-6 sm:p-7"><div className="flex items-center justify-between text-xs text-white/55"><span>{auction.category} · Lot #{String(auction.id).padStart(3, '0')}</span><span className="flex items-center gap-1.5 text-[#ffd2c8]"><Clock3 className="size-3.5" /> {auction.timeLeft} left</span></div><h1 className="mt-3 font-heading text-3xl font-bold tracking-[-0.045em]">{auction.title}</h1><p className="mt-2 text-sm text-white/55">{auction.condition} condition</p><div className="mt-7 grid grid-cols-2 gap-4"><div><p className="text-xs text-white/50">Current bid</p><p className="mt-1 font-heading text-3xl font-bold">{formatAuctionMoney.format(auction.currentBid)}</p></div><div className="border-l border-white/10 pl-4"><p className="text-xs text-white/50">Bid activity</p><p className="mt-1 font-heading text-3xl font-bold">{auction.bids}</p></div></div></div>
          <div className="p-6 sm:p-7">{auction.biddingOpen ? <><label htmlFor="bid" className="text-sm font-semibold">Your maximum bid</label><div className="mt-2 flex rounded-xl bg-white p-1.5"><span className="grid w-10 place-items-center font-semibold text-[#263129]">$</span><Input id="bid" inputMode="numeric" value={bid} onChange={(event) => setBid(event.target.value)} className="h-11 border-0 bg-transparent px-0 text-base font-bold text-[#263129] shadow-none focus-visible:ring-0" /><Button onClick={placeBid} className="h-11 rounded-lg bg-[#d85f4a] px-5 text-white hover:bg-[#c44f3b]">Place bid <ArrowRight data-icon="inline-end" /></Button></div><p aria-live="polite" className={cn('mt-3 min-h-5 text-xs', notice ? 'text-[#ffd2c8]' : 'text-white/50')}>{notice || `Minimum next bid: ${formatAuctionMoney.format(auction.currentBid + 50)}`}</p><div className="mt-5 flex items-start gap-3 border-t border-white/10 pt-5 text-xs leading-5 text-white/55"><ShieldCheck className="mt-0.5 size-4 shrink-0 text-[#bdd6c2]" />Your bid is only charged if you win. Payments are securely settled after the auction closes.</div></> : <div><div className="flex gap-3 rounded-2xl border border-white/10 bg-white/5 p-4"><LockKeyhole className="mt-0.5 size-5 shrink-0 text-white/70" /><div><p className="text-sm font-semibold">This lot is available to browse</p><p className="mt-1 text-xs leading-5 text-white/55">Bidding is currently limited to the spotlight auction.</p></div></div><Button nativeButton={false} render={<Link href={`/auction/${bidEnabledAuction.id}`} />} className="mt-4 h-11 w-full rounded-xl bg-[#d85f4a] text-white hover:bg-[#c44f3b]">View spotlight auction <ArrowRight data-icon="inline-end" /></Button></div>}</div></div></aside>
      </div>

      <div className="mt-8 grid gap-5 lg:grid-cols-[1.35fr_0.65fr]"><section className="rounded-3xl border bg-card p-6 sm:p-8"><p className="text-xs font-semibold uppercase tracking-[0.14em] text-[#b34a36]">About this lot</p><h2 className="mt-3 font-heading text-2xl font-bold tracking-tight">Details and condition</h2><p className="mt-4 max-w-3xl leading-7 text-muted-foreground">{auction.description}</p><div className="mt-7 grid gap-4 border-t pt-6 sm:grid-cols-2"><div className="flex gap-3"><PackageCheck className="mt-0.5 size-5 text-primary" /><div><p className="text-sm font-semibold">Delivery</p><p className="mt-1 text-sm text-muted-foreground">{auction.shipping}</p></div></div><div className="flex gap-3"><MapPin className="mt-0.5 size-5 text-primary" /><div><p className="text-sm font-semibold">Ships from</p><p className="mt-1 text-sm text-muted-foreground">{auction.location}</p></div></div></div></section><section className="rounded-3xl border bg-card p-6 sm:p-8"><p className="text-xs font-semibold uppercase tracking-[0.14em] text-muted-foreground">Seller</p><h2 className="sr-only">Seller information</h2><div className="mt-3 flex gap-3"><BadgeCheck className="size-5 text-primary" /><div><p className="font-semibold">{auction.seller}</p><p className="mt-1 flex items-center gap-1 text-sm text-muted-foreground"><Star className="size-4 fill-[#d7a446] text-[#d7a446]" /> {auction.sellerRating}</p></div></div></section></div>
    </div>
  </main>;
}
