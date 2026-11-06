'use client';

import { useMemo, useState } from 'react';
import Image from 'next/image';
import Link from 'next/link';
import { ArrowRight, Bell, ChevronDown, Clock3, Eye, Gavel, Heart, Search, UserRound } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { cn } from '@/lib/utils';
import { auctionCategories, auctions, formatAuctionMoney } from '../model/auction';

export default function MarketplacePage() {
  const [category, setCategory] = useState('All lots');
  const [query, setQuery] = useState('');
  const [favorites, setFavorites] = useState<number[]>([2]);

  const visibleAuctions = useMemo(() => auctions.filter((auction) =>
    (category === 'All lots' || auction.category === category) && auction.title.toLowerCase().includes(query.toLowerCase())), [category, query]);

  function toggleFavorite(id: number) {
    setFavorites((current) => current.includes(id) ? current.filter((favorite) => favorite !== id) : [...current, id]);
  }

  function requireBidderForSeller(event: React.MouseEvent<HTMLAnchorElement>) {
    if (window.sessionStorage.getItem('gavel-bidder-profile')) return;
    event.preventDefault();
    window.location.href = '/onboarding?returnTo=%2Fsell%2Fonboarding';
  }

  return (
    <main className="min-h-screen bg-background text-foreground">
      <header className="sticky top-0 z-30 border-b border-border/80 bg-background/90 backdrop-blur-xl">
        <div className="mx-auto flex h-20 max-w-[1440px] items-center gap-5 px-5 lg:px-10">
          <Link href="/" className="flex shrink-0 items-center gap-2.5" aria-label="Gavel home"><span className="grid size-10 place-items-center rounded-full bg-primary text-primary-foreground"><Gavel className="size-5" strokeWidth={2.2} /></span><span className="font-heading text-xl font-bold tracking-[-0.04em]">Gavel</span></Link>
          <nav className="ml-6 hidden items-center gap-7 text-sm font-medium text-muted-foreground lg:flex"><a className="text-foreground" href="#live">Live auctions</a><a className="transition-colors hover:text-foreground" href="#live">Ending soon</a><Link className="transition-colors hover:text-foreground" href="/sell/onboarding" onClick={requireBidderForSeller}>Sell an item</Link></nav>
          <div className="relative ml-auto hidden w-full max-w-sm md:block"><Search className="absolute left-3.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" /><Input value={query} onChange={(event) => setQuery(event.target.value)} className="h-11 rounded-full border-transparent bg-secondary pl-10 pr-4 shadow-none focus-visible:bg-card" placeholder="Search rare finds" aria-label="Search auctions" /></div>
          <Button variant="ghost" size="icon-lg" aria-label="Notifications" className="rounded-full"><Bell /></Button>
          <Button nativeButton={false} render={<Link href="/onboarding" />} variant="outline" className="h-10 rounded-full px-4"><UserRound data-icon="inline-start" /><span className="hidden sm:inline">My account</span></Button>
        </div>
      </header>

      <section id="live" className="mx-auto max-w-[1440px] px-5 pb-12 pt-8 lg:px-10 lg:pt-12" aria-labelledby="live-heading">
        <div className="mb-8 flex flex-col gap-5 border-b pb-7 sm:flex-row sm:items-end sm:justify-between">
          <div><p className="mb-2 text-xs font-semibold uppercase tracking-[0.16em] text-[#b34a36]">Curated this week</p><h1 id="live-heading" className="font-heading text-4xl font-bold tracking-[-0.05em] sm:text-5xl">Live auctions</h1><p className="mt-3 text-sm text-muted-foreground">Select a lot to view its full auction details.</p></div>
          <div className="relative w-full sm:max-w-sm md:hidden"><Search className="absolute left-3.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" /><Input value={query} onChange={(event) => setQuery(event.target.value)} className="h-11 rounded-full bg-card pl-10" placeholder="Search rare finds" aria-label="Search auctions" /></div>
        </div>

        <div className="mb-7 flex items-center justify-between gap-4">
          <div className="flex gap-2 overflow-x-auto pb-1" aria-label="Auction categories">{auctionCategories.map((item) => <button key={item} onClick={() => setCategory(item)} className={cn('whitespace-nowrap rounded-full border px-4 py-2 text-sm font-medium transition-colors', category === item ? 'border-primary bg-primary text-primary-foreground' : 'border-border bg-card text-muted-foreground hover:border-foreground/30 hover:text-foreground')}>{item}</button>)}</div>
          <button className="hidden shrink-0 items-center gap-1 text-sm font-semibold sm:flex">Ending first <ChevronDown className="size-4" /></button>
        </div>

        <div className="mb-4 flex items-center gap-3"><h2 className="font-heading text-xl font-bold tracking-tight">All lots</h2><Badge variant="secondary" className="bg-[#e7f1ea] text-[#26623a]">{visibleAuctions.length} live</Badge></div>
        {visibleAuctions.length === 0 ? <div className="grid min-h-80 place-items-center rounded-3xl border border-dashed bg-card p-8 text-center"><div><Search className="mx-auto mb-3 size-7 text-muted-foreground" /><h3 className="font-heading text-lg font-semibold">No matching lots</h3><p className="mt-1 text-sm text-muted-foreground">Try another category or search term.</p></div></div> : (
          <div className="grid gap-5 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
            {visibleAuctions.map((auction) => <article key={auction.id} className="group overflow-hidden rounded-3xl border bg-card p-2 transition-all hover:-translate-y-0.5 hover:shadow-xl hover:shadow-[#2f362f]/8">
              <div className="relative aspect-[4/3] overflow-hidden rounded-[20px] bg-secondary"><Image src={auction.image} alt={auction.title} fill sizes="(min-width: 1280px) 25vw, (min-width: 1024px) 33vw, (min-width: 640px) 50vw, 100vw" unoptimized className="object-cover transition-transform duration-500 group-hover:scale-[1.03]" /><Link href={`/auction/${auction.id}`} className="absolute inset-0" aria-label={`View ${auction.title}`} /><div className="pointer-events-none absolute left-3 top-3 flex items-center gap-1.5 rounded-full bg-white/90 px-2.5 py-1 text-xs font-semibold text-[#253027] backdrop-blur">{auction.biddingOpen ? <><Gavel className="size-3.5 text-[#b34a36]" /> Open for bids</> : <><Eye className="size-3.5" /> Browse only</>}</div><button type="button" onClick={() => toggleFavorite(auction.id)} className="absolute right-3 top-3 grid size-9 place-items-center rounded-full bg-white/90 text-[#253027] backdrop-blur transition-transform hover:scale-105" aria-label={`${favorites.includes(auction.id) ? 'Remove' : 'Add'} ${auction.title} ${favorites.includes(auction.id) ? 'from' : 'to'} favorites`}><Heart className={cn('size-4', favorites.includes(auction.id) && 'fill-[#d85f4a] text-[#d85f4a]')} /></button></div>
              <Link href={`/auction/${auction.id}`} className="block px-3 pb-3 pt-4"><div className="mb-2 flex items-center justify-between text-xs font-medium text-muted-foreground"><span>{auction.category} · {auction.condition}</span><span className="flex items-center gap-1 text-[#b34a36]"><Clock3 className="size-3.5" /> {auction.timeLeft}</span></div><h3 className="font-heading text-lg font-semibold tracking-[-0.025em]">{auction.title}</h3><div className="mt-4 flex items-end justify-between"><div><p className="text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">Current bid</p><p className="mt-0.5 font-heading text-xl font-bold">{formatAuctionMoney.format(auction.currentBid)}</p></div><span className="flex items-center gap-1 text-xs font-semibold text-primary">View lot <ArrowRight className="size-3.5" /></span></div></Link>
            </article>)}
          </div>
        )}
      </section>
      <footer className="border-t bg-card"><div className="mx-auto flex max-w-[1440px] flex-col gap-3 px-5 py-8 text-sm text-muted-foreground sm:flex-row sm:items-center sm:justify-between lg:px-10"><span>© 2026 Gavel Marketplace</span><div className="flex gap-5"><a href="#live">How it works</a><Link href="/sell/onboarding">Trust & safety</Link><Link href="/sell/onboarding">Terms</Link></div></div></footer>
    </main>
  );
}
