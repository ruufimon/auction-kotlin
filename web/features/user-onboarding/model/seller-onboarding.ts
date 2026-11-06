import { z } from 'zod';

export const sellerOnboardingSchema = z.object({
  displayName: z.string().trim().min(2, 'Enter the name buyers will see.'),
  phone: z.string().trim().regex(/^[+\d][\d\s()-]{7,}$/, 'Enter a valid phone number.'),
  bio: z.string().trim().min(20, 'Tell buyers a little more about you.').max(180, 'Keep your bio under 180 characters.'),
  address: z.string().trim().min(5, 'Enter your street address.'),
  country: z.string().min(1, 'Choose a country.'),
  city: z.string().trim().min(2, 'Enter your city.'),
  region: z.string().trim().min(2, 'Enter your state or region.'),
  postal: z.string().trim().min(3, 'Enter your postal code.'),
  handlingTime: z.string().min(1, 'Choose a handling time.'),
  payoutConnected: z.boolean().refine((connected) => connected, 'Connect a payout account to continue.'),
  title: z.string().trim().min(5, 'Use at least 5 characters for the title.').max(80, 'Keep the title under 80 characters.'),
  category: z.string().min(1, 'Choose a category.'),
  condition: z.string().min(1, 'Choose a condition.'),
  description: z.string().trim().min(30, 'Add at least 30 characters of useful detail.'),
  photos: z.array(z.string()).min(1, 'Add at least one product photo.'),
  startingPrice: z.number().positive('Starting price must be greater than zero.'),
  freeShipping: z.boolean(),
  shippingPrice: z.number().min(0, 'Shipping price cannot be negative.'),
  startAt: z.string().min(1, 'Choose a start date and time.'),
  endAt: z.string().min(1, 'Choose an end date and time.'),
});

export type SellerOnboardingForm = z.infer<typeof sellerOnboardingSchema>;

export const sellerOnboardingFieldsByStep: Record<number, (keyof SellerOnboardingForm)[]> = {
  0: [],
  1: ['displayName', 'phone', 'bio'],
  2: ['address', 'country', 'city', 'region', 'postal', 'handlingTime'],
  3: ['payoutConnected'],
  4: ['title', 'category', 'condition', 'description', 'photos'],
  5: ['startingPrice', 'shippingPrice', 'startAt', 'endAt'],
  6: [],
};

export const sellerOnboardingDefaults: SellerOnboardingForm = {
  displayName: 'Alex Morgan',
  phone: '',
  bio: '',
  address: '',
  country: 'United States',
  city: '',
  region: '',
  postal: '',
  handlingTime: '2 business days',
  payoutConnected: false,
  title: '',
  category: '',
  condition: '',
  description: '',
  photos: [],
  startingPrice: 100,
  freeShipping: false,
  shippingPrice: 18,
  startAt: '',
  endAt: '',
};
