export type Auction = {
  id: number;
  title: string;
  category: string;
  condition: string;
  currentBid: number;
  bids: number;
  timeLeft: string;
  image: string;
  biddingOpen: boolean;
  description: string;
  seller: string;
  sellerRating: string;
  shipping: string;
  location: string;
};

export const auctions: Auction[] = [
  { id: 1, title: 'Leica M6 Classic 0.72', category: 'Cameras', condition: 'Excellent', currentBid: 3240, bids: 18, timeLeft: '01h 42m', image: 'https://images.unsplash.com/photo-1590073260031-ca4370c6a35a?auto=format&fit=crop&w=1200&q=85', biddingOpen: true, description: 'A beautifully preserved Leica M6 Classic with the sought-after 0.72 viewfinder. The meter is responsive, shutter speeds are accurate, and the body shows only light signs of careful use.', seller: 'Northlight Cameras', sellerRating: '4.9 · 126 sales', shipping: '$35 insured shipping', location: 'Portland, Oregon' },
  { id: 2, title: 'Omega Seamaster De Ville', category: 'Watches', condition: 'Very good', currentBid: 1850, bids: 27, timeLeft: '03h 08m', image: 'https://images.unsplash.com/photo-1523170335258-f5ed11844a49?auto=format&fit=crop&w=1000&q=85', biddingOpen: false, description: 'A refined vintage Seamaster De Ville with a clean silver dial, applied indices, and its original signed crown. Recently inspected and keeping excellent time.', seller: 'Horology House', sellerRating: '4.8 · 89 sales', shipping: '$28 insured shipping', location: 'Boston, Massachusetts' },
  { id: 3, title: 'Danish Teak Lounge Chair', category: 'Design', condition: 'Restored', currentBid: 920, bids: 11, timeLeft: '05h 21m', image: 'https://images.unsplash.com/photo-1567538096630-e0c55bd6374c?auto=format&fit=crop&w=1000&q=85', biddingOpen: false, description: 'A sculptural mid-century lounge chair in warm teak, professionally restored with fresh webbing and textured neutral upholstery while preserving its original character.', seller: 'Form & Timber', sellerRating: '5.0 · 42 sales', shipping: '$120 white-glove delivery', location: 'Brooklyn, New York' },
  { id: 4, title: 'Blue Note First Pressing', category: 'Records', condition: 'Near mint', currentBid: 680, bids: 14, timeLeft: '08h 56m', image: 'https://images.unsplash.com/photo-1461360228754-6e81c478b882?auto=format&fit=crop&w=1000&q=85', biddingOpen: false, description: 'An early Blue Note pressing with a deep-groove label and crisp sleeve. The vinyl has been play-graded and retains remarkable presence and clarity.', seller: 'After Hours Records', sellerRating: '4.9 · 211 sales', shipping: '$18 archival packaging', location: 'Chicago, Illinois' },
];

export const bidEnabledAuction = auctions.find((auction) => auction.biddingOpen) ?? auctions[0];

export function getAuction(id: string | number) {
  return auctions.find((auction) => auction.id === Number(id));
}

export const auctionCategories = ['All lots', 'Cameras', 'Watches', 'Design', 'Records'];

export const formatAuctionMoney = new Intl.NumberFormat('en-US', {
  style: 'currency',
  currency: 'USD',
  maximumFractionDigits: 0,
});
