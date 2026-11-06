import { z } from 'zod';

export const bidderCategories = ['Cameras', 'Watches', 'Design', 'Records', 'Art'] as const;

export const bidderOnboardingSchema = z.object({
  displayName: z.string().trim().min(2, 'Enter the name other members will see.'),
  email: z.email('Enter a valid email address.'),
  password: z.string().min(8, 'Use at least 8 characters.').regex(/[A-Z]/, 'Add one uppercase letter.').regex(/[0-9]/, 'Add one number.'),
  passwordConfirmation: z.string(),
  phone: z.string().trim().regex(/^[+\d][\d\s()-]{7,}$/, 'Enter a valid phone number.'),
  emailVerified: z.boolean().refine(Boolean, 'Verify your email to continue.'),
  address: z.string().trim().min(5, 'Enter your street address.'),
  country: z.string().min(1, 'Choose a country.'),
  city: z.string().trim().min(2, 'Enter your city.'),
  region: z.string().trim().min(2, 'Enter your state or region.'),
  postal: z.string().trim().min(3, 'Enter your postal code.'),
  favoriteCategories: z.array(z.string()).min(1, 'Choose at least one category.'),
  outbidAlerts: z.boolean(),
  endingSoonAlerts: z.boolean(),
  acceptedTerms: z.boolean().refine(Boolean, 'Accept the marketplace terms to continue.'),
}).refine((data) => data.password === data.passwordConfirmation, {
  message: 'Passwords do not match.',
  path: ['passwordConfirmation'],
});

export type BidderOnboardingForm = z.infer<typeof bidderOnboardingSchema>;

export const bidderOnboardingFieldsByStep: Record<number, (keyof BidderOnboardingForm)[]> = {
  0: [],
  1: ['displayName', 'email', 'password', 'passwordConfirmation', 'phone'],
  2: ['emailVerified'],
  3: ['address', 'country', 'city', 'region', 'postal'],
  4: ['favoriteCategories', 'acceptedTerms'],
  5: [],
};

export const bidderOnboardingDefaults: BidderOnboardingForm = {
  displayName: '',
  email: '',
  password: '',
  passwordConfirmation: '',
  phone: '',
  emailVerified: false,
  address: '',
  country: 'United States',
  city: '',
  region: '',
  postal: '',
  favoriteCategories: [],
  outbidAlerts: true,
  endingSoonAlerts: true,
  acceptedTerms: false,
};

export type BidderDraft = Omit<BidderOnboardingForm, 'password' | 'passwordConfirmation'>;

export type BidderProfile = BidderDraft & {
  status: 'ACTIVE';
  completedAt: string;
};

export function toBidderDraft(values: BidderOnboardingForm): BidderDraft {
  const { password: _password, passwordConfirmation: _passwordConfirmation, ...draft } = values;
  return draft;
}
