'use client';

import { useEffect, useState } from 'react';
import { zodResolver } from '@hookform/resolvers/zod';
import Link from 'next/link';
import {
  ArrowLeft,
  ArrowRight,
  BadgeCheck,
  BellRing,
  Check,
  CheckCircle2,
  Gavel,
  Heart,
  KeyRound,
  MailCheck,
  MapPin,
  ShieldCheck,
  Sparkles,
  Trash2,
  UserRound,
} from 'lucide-react';
import { Controller, type FieldPath, useForm } from 'react-hook-form';

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogMedia,
  AlertDialogTitle,
  AlertDialogTrigger,
} from '@/components/ui/alert-dialog';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { Field, FieldDescription, FieldError, FieldGroup, FieldLabel } from '@/components/ui/field';
import { Input } from '@/components/ui/input';
import { Progress } from '@/components/ui/progress';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { cn } from '@/lib/utils';
import {
  bidderCategories,
  bidderOnboardingDefaults as defaults,
  bidderOnboardingFieldsByStep as fieldsByStep,
  bidderOnboardingSchema as schema,
  toBidderDraft,
  type BidderOnboardingForm as BidderForm,
  type BidderProfile,
} from '../model/bidder-onboarding';

const steps = [
  { label: 'Welcome', icon: Sparkles },
  { label: 'Your account', icon: UserRound },
  { label: 'Verify email', icon: MailCheck },
  { label: 'Delivery address', icon: MapPin },
  { label: 'Bid preferences', icon: Heart },
  { label: 'Review', icon: ShieldCheck },
];

export default function BidderOnboardingPage() {
  const [step, setStep] = useState(0);
  const [completed, setCompleted] = useState(false);
  const [verificationSent, setVerificationSent] = useState(false);
  const [saveNotice, setSaveNotice] = useState('');
  const [returnTo, setReturnTo] = useState('/');
  const form = useForm<BidderForm>({ resolver: zodResolver(schema), defaultValues: defaults, mode: 'onTouched' });
  const { control, formState: { errors }, getValues, handleSubmit, register, reset, setValue, trigger, watch } = form;
  const values = watch();

  useEffect(() => {
    const requestedReturn = new URLSearchParams(window.location.search).get('returnTo');
    if (requestedReturn?.startsWith('/') && !requestedReturn.startsWith('//')) setReturnTo(requestedReturn);

    const profile = readSession<BidderProfile>('gavel-bidder-profile');
    if (profile) {
      reset({ ...defaults, ...profile });
      setCompleted(true);
      return;
    }

    const draft = readSession<Partial<BidderForm>>('gavel-bidder-onboarding');
    if (draft) reset({ ...defaults, ...draft, password: '', passwordConfirmation: '' });
  }, [reset]);

  useEffect(() => {
    const subscription = watch((next) => {
      if (!completed) window.sessionStorage.setItem('gavel-bidder-onboarding', JSON.stringify(toBidderDraft({ ...defaults, ...next } as BidderForm)));
    });
    return () => subscription.unsubscribe();
  }, [completed, watch]);

  const progress = ((step + 1) / steps.length) * 100;

  async function continueFlow() {
    if (!await trigger(fieldsByStep[step])) return;
    setStep((current) => Math.min(current + 1, steps.length - 1));
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  function saveAndExit() {
    window.sessionStorage.setItem('gavel-bidder-onboarding', JSON.stringify(toBidderDraft(getValues())));
    setSaveNotice('Progress saved without your password. Returning to the marketplace…');
    window.setTimeout(() => { window.location.href = '/'; }, 700);
  }

  function discard() {
    window.sessionStorage.removeItem('gavel-bidder-onboarding');
    reset(defaults);
    setStep(0);
    setSaveNotice('');
  }

  function finish(data: BidderForm) {
    const profile: BidderProfile = { ...toBidderDraft(data), status: 'ACTIVE', completedAt: new Date().toISOString() };
    window.sessionStorage.setItem('gavel-bidder-profile', JSON.stringify(profile));
    window.sessionStorage.removeItem('gavel-bidder-onboarding');
    setCompleted(true);
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  function editProfile() {
    setCompleted(false);
    setStep(1);
  }

  if (completed) return <BidderReady values={values} returnTo={returnTo} onEdit={editProfile} />;

  return (
    <main className="min-h-screen bg-background text-foreground">
      <header className="border-b bg-background/90 backdrop-blur-xl">
        <div className="mx-auto flex h-20 max-w-[1280px] items-center justify-between px-5 lg:px-10">
          <Link href="/" className="flex items-center gap-2.5" aria-label="Gavel home"><span className="grid size-10 place-items-center rounded-full bg-primary text-primary-foreground"><Gavel className="size-5" /></span><span className="font-heading text-xl font-bold tracking-[-0.04em]">Gavel</span></Link>
          <div className="hidden items-center gap-2 text-sm text-muted-foreground sm:flex"><ShieldCheck className="size-4 text-[#39704b]" /> Secure bidder setup</div>
          <AlertDialog>
            <AlertDialogTrigger render={<Button variant="ghost" />}>Discard</AlertDialogTrigger>
            <AlertDialogContent>
              <AlertDialogHeader><AlertDialogMedia><Trash2 /></AlertDialogMedia><AlertDialogTitle>Discard your setup?</AlertDialogTitle><AlertDialogDescription>Your saved bidder details will be removed from this browser session. Your password is never saved.</AlertDialogDescription></AlertDialogHeader>
              <AlertDialogFooter><AlertDialogCancel>Keep working</AlertDialogCancel><AlertDialogAction variant="destructive" onClick={discard}>Discard progress</AlertDialogAction></AlertDialogFooter>
            </AlertDialogContent>
          </AlertDialog>
        </div>
      </header>

      <div className="mx-auto grid max-w-[1280px] gap-8 px-5 py-8 lg:grid-cols-[260px_minmax(0,720px)_1fr] lg:px-10 lg:py-12">
        <aside className="hidden lg:block">
          <p className="mb-5 text-xs font-semibold uppercase tracking-[0.15em] text-muted-foreground">Bidder setup</p>
          <ol className="space-y-1">
            {steps.map((item, index) => {
              const Icon = item.icon;
              return <li key={item.label} className={cn('flex items-center gap-3 rounded-xl px-3 py-3 text-sm', index === step && 'bg-card font-semibold shadow-sm', index < step ? 'text-foreground' : 'text-muted-foreground')}><span className={cn('grid size-8 place-items-center rounded-full border', index < step && 'border-primary bg-primary text-primary-foreground', index === step && 'border-primary text-primary')}>{index < step ? <Check className="size-4" /> : <Icon className="size-4" />}</span>{item.label}</li>;
            })}
          </ol>
          <div className="mt-7 rounded-2xl border bg-card p-4 text-sm"><div className="flex items-center gap-2 font-semibold"><KeyRound className="size-4 text-primary" /> Your account stays private</div><p className="mt-2 leading-5 text-muted-foreground">Contact and delivery details are never shown in public bid activity.</p></div>
        </aside>

        <section>
          <div className="mb-7 lg:hidden"><div className="mb-2 flex items-center justify-between text-sm"><span className="font-semibold">{steps[step].label}</span><span className="text-muted-foreground">{step + 1} of {steps.length}</span></div><Progress value={progress} /></div>
          <form onSubmit={handleSubmit(finish)}>
            <div className="rounded-3xl border bg-card p-5 shadow-sm sm:p-8 lg:p-10">
              <BidderStep step={step} control={control} errors={errors} register={register} setValue={setValue} values={values} verificationSent={verificationSent} setVerificationSent={setVerificationSent} goToStep={setStep} />
            </div>
            <div className="mt-5 flex flex-col-reverse gap-3 sm:flex-row sm:items-center sm:justify-between">
              <div className="flex gap-2"><Button type="button" variant="outline" onClick={() => setStep((current) => Math.max(0, current - 1))} disabled={step === 0}><ArrowLeft data-icon="inline-start" /> Back</Button><Button type="button" variant="ghost" onClick={saveAndExit}>Save & exit</Button></div>
              {step < steps.length - 1 ? <Button type="button" onClick={continueFlow} className="bg-[#d85f4a] text-white hover:bg-[#c44f3b]">Continue <ArrowRight data-icon="inline-end" /></Button> : <Button type="submit" className="bg-[#d85f4a] text-white hover:bg-[#c44f3b]">Complete setup <Check data-icon="inline-end" /></Button>}
            </div>
            <p aria-live="polite" className="mt-3 min-h-5 text-sm text-muted-foreground">{saveNotice}</p>
          </form>
        </section>

        <aside className="hidden xl:block"><div className="sticky top-8 rounded-2xl border bg-card p-5"><p className="text-xs font-semibold uppercase tracking-[0.14em] text-muted-foreground">Progress</p><p className="mt-3 font-heading text-3xl font-bold tracking-tight">{Math.round(progress)}%</p><Progress value={progress} className="mt-3" /><p className="mt-4 text-sm leading-6 text-muted-foreground">Complete your bidder profile once, then return anytime to update it.</p></div></aside>
      </div>
    </main>
  );
}

type StepProps = {
  step: number;
  control: ReturnType<typeof useForm<BidderForm>>['control'];
  errors: ReturnType<typeof useForm<BidderForm>>['formState']['errors'];
  register: ReturnType<typeof useForm<BidderForm>>['register'];
  setValue: ReturnType<typeof useForm<BidderForm>>['setValue'];
  values: BidderForm;
  verificationSent: boolean;
  setVerificationSent: (sent: boolean) => void;
  goToStep: (step: number) => void;
};

function BidderStep({ step, control, errors, register, setValue, values, verificationSent, setVerificationSent, goToStep }: StepProps) {
  const error = (name: FieldPath<BidderForm>) => (errors as Record<string, { message?: string }>)[name]?.message;

  if (step === 0) return <div><Badge className="bg-[#e7f1ea] text-[#26623a]"><BadgeCheck /> Bidder first</Badge><h1 className="mt-5 font-heading text-4xl font-bold tracking-[-0.05em] sm:text-5xl">Your seat at the auction starts here.</h1><p className="mt-5 max-w-xl text-base leading-7 text-muted-foreground">Create one trusted Gavel account to bid now and sell later. Setup takes about 3 minutes.</p><div className="mt-8 grid gap-3 sm:grid-cols-3">{[['Verify once', 'Protect every bid you place.'], ['Bid confidently', 'Get alerts on lots you follow.'], ['Sell when ready', 'Upgrade the same account later.']].map(([title, copy], index) => <div key={title} className="rounded-2xl bg-secondary/70 p-4"><span className="grid size-8 place-items-center rounded-full bg-primary text-sm font-bold text-primary-foreground">{index + 1}</span><p className="mt-4 font-semibold">{title}</p><p className="mt-1 text-sm text-muted-foreground">{copy}</p></div>)}</div></div>;

  if (step === 1) return <StepShell eyebrow="Your account" title="Create your bidder profile" copy="These details identify your account and help us protect high-value bids."><FieldGroup><Field data-invalid={Boolean(error('displayName'))}><FieldLabel htmlFor="bidder-display-name">Display name</FieldLabel><Input id="bidder-display-name" autoComplete="name" className="h-11" placeholder="Alex Morgan" aria-invalid={Boolean(error('displayName'))} {...register('displayName')} /><FieldError>{error('displayName')}</FieldError></Field><div className="grid gap-5 sm:grid-cols-2"><Field data-invalid={Boolean(error('email'))}><FieldLabel htmlFor="bidder-email">Email address</FieldLabel><Input id="bidder-email" type="email" autoComplete="email" className="h-11" placeholder="alex@example.com" aria-invalid={Boolean(error('email'))} {...register('email')} /><FieldError>{error('email')}</FieldError></Field><Field data-invalid={Boolean(error('phone'))}><FieldLabel htmlFor="bidder-phone">Phone number</FieldLabel><Input id="bidder-phone" type="tel" autoComplete="tel" className="h-11" placeholder="+1 555 0142" aria-invalid={Boolean(error('phone'))} {...register('phone')} /><FieldError>{error('phone')}</FieldError></Field><Field data-invalid={Boolean(error('password'))}><FieldLabel htmlFor="bidder-password">Password</FieldLabel><Input id="bidder-password" type="password" autoComplete="new-password" className="h-11" aria-invalid={Boolean(error('password'))} {...register('password')} /><FieldDescription>8+ characters, one uppercase letter, and one number.</FieldDescription><FieldError>{error('password')}</FieldError></Field><Field data-invalid={Boolean(error('passwordConfirmation'))}><FieldLabel htmlFor="bidder-password-confirmation">Confirm password</FieldLabel><Input id="bidder-password-confirmation" type="password" autoComplete="new-password" className="h-11" aria-invalid={Boolean(error('passwordConfirmation'))} {...register('passwordConfirmation')} /><FieldError>{error('passwordConfirmation')}</FieldError></Field></div></FieldGroup></StepShell>;

  if (step === 2) return <StepShell eyebrow="Email verification" title="Confirm it’s really you" copy={`We’ll send a single-use verification link to ${values.email || 'your email address'}.`}><div className={cn('rounded-2xl border p-5', values.emailVerified ? 'border-[#4f815f]/40 bg-[#edf5ef]' : 'bg-secondary/50')}><div className="flex flex-col gap-5 sm:flex-row sm:items-center sm:justify-between"><div className="flex items-start gap-4"><span className={cn('grid size-11 shrink-0 place-items-center rounded-xl', values.emailVerified ? 'bg-[#39704b] text-white' : 'bg-primary text-primary-foreground')}>{values.emailVerified ? <CheckCircle2 /> : <MailCheck />}</span><div><p className="font-semibold">{values.emailVerified ? 'Email verified' : verificationSent ? 'Verification email sent' : 'Ready to send'}</p><p className="mt-1 text-sm text-muted-foreground">{values.emailVerified ? values.email : verificationSent ? 'Use the simulated verification button to continue.' : 'The link expires after 15 minutes.'}</p></div></div>{values.emailVerified ? <Badge className="bg-[#d7eadc] text-[#26623a]">Verified</Badge> : verificationSent ? <Button type="button" onClick={() => setValue('emailVerified', true, { shouldValidate: true })}>I’ve verified my email</Button> : <Button type="button" onClick={() => setVerificationSent(true)}>Send verification email</Button>}</div></div><p className="mt-4 text-sm text-muted-foreground">This prototype simulates delivery and verification; no email is sent.</p><FieldError className="mt-3">{error('emailVerified')}</FieldError></StepShell>;

  if (step === 3) return <StepShell eyebrow="Delivery" title="Where should your wins arrive?" copy="We use this address to estimate delivery. Sellers never see it until settlement."><FieldGroup><Field data-invalid={Boolean(error('address'))}><FieldLabel htmlFor="bidder-address">Street address</FieldLabel><Input id="bidder-address" autoComplete="street-address" className="h-11" placeholder="42 Mercer Street" aria-invalid={Boolean(error('address'))} {...register('address')} /><FieldError>{error('address')}</FieldError></Field><div className="grid gap-5 sm:grid-cols-2"><SelectField name="country" label="Country" options={['United States', 'Canada', 'United Kingdom', 'Thailand']} control={control} error={error('country')} /><Field data-invalid={Boolean(error('city'))}><FieldLabel htmlFor="bidder-city">City</FieldLabel><Input id="bidder-city" autoComplete="address-level2" className="h-11" aria-invalid={Boolean(error('city'))} {...register('city')} /><FieldError>{error('city')}</FieldError></Field><Field data-invalid={Boolean(error('region'))}><FieldLabel htmlFor="bidder-region">State or region</FieldLabel><Input id="bidder-region" autoComplete="address-level1" className="h-11" aria-invalid={Boolean(error('region'))} {...register('region')} /><FieldError>{error('region')}</FieldError></Field><Field data-invalid={Boolean(error('postal'))}><FieldLabel htmlFor="bidder-postal">Postal code</FieldLabel><Input id="bidder-postal" autoComplete="postal-code" className="h-11" aria-invalid={Boolean(error('postal'))} {...register('postal')} /><FieldError>{error('postal')}</FieldError></Field></div></FieldGroup></StepShell>;

  if (step === 4) return <StepShell eyebrow="Preferences" title="Tune your bidding experience" copy="Choose what you collect and which auction moments should reach you."><FieldGroup><Field data-invalid={Boolean(error('favoriteCategories'))}><FieldLabel>Favorite categories</FieldLabel><div className="flex flex-wrap gap-2">{bidderCategories.map((category) => { const active = values.favoriteCategories.includes(category); return <button key={category} type="button" aria-pressed={active} onClick={() => setValue('favoriteCategories', active ? values.favoriteCategories.filter((item) => item !== category) : [...values.favoriteCategories, category], { shouldValidate: true })} className={cn('rounded-full border px-4 py-2 text-sm font-medium transition-colors', active ? 'border-primary bg-primary text-primary-foreground' : 'bg-card text-muted-foreground hover:text-foreground')}>{category}</button>; })}</div><FieldError>{error('favoriteCategories')}</FieldError></Field><div className="grid gap-3 sm:grid-cols-2"><PreferenceCheckbox name="outbidAlerts" control={control} icon={<BellRing className="size-5" />} title="Outbid alerts" copy="Know when another bidder takes the lead." /><PreferenceCheckbox name="endingSoonAlerts" control={control} icon={<Gavel className="size-5" />} title="Ending soon" copy="Get a reminder before watched lots close." /></div><Controller name="acceptedTerms" control={control} render={({ field }) => <Field orientation="horizontal" data-invalid={Boolean(error('acceptedTerms'))} className="rounded-xl border p-4"><Checkbox id="accepted-terms" checked={field.value} onCheckedChange={field.onChange} aria-invalid={Boolean(error('acceptedTerms'))} /><div><FieldLabel htmlFor="accepted-terms">I accept the marketplace terms</FieldLabel><FieldDescription>Bids are binding and winning bidders must complete payment.</FieldDescription><FieldError>{error('acceptedTerms')}</FieldError></div></Field>} /></FieldGroup></StepShell>;

  return <StepShell eyebrow="Final check" title="Review your bidder profile" copy="Confirm the details below. You can update these preferences from your account later."><div className="space-y-3"><ReviewCard title="Account" action={() => goToStep(1)} rows={[['Display name', values.displayName], ['Email', `${values.email} · Verified`], ['Phone', values.phone]]} /><ReviewCard title="Delivery" action={() => goToStep(3)} rows={[['Address', values.address], ['Location', `${values.city}, ${values.region}, ${values.country} ${values.postal}`]]} /><ReviewCard title="Preferences" action={() => goToStep(4)} rows={[['Collecting', values.favoriteCategories.join(', ')], ['Outbid alerts', values.outbidAlerts ? 'On' : 'Off'], ['Ending soon', values.endingSoonAlerts ? 'On' : 'Off']]} /></div><div className="mt-5 flex gap-3 rounded-xl border border-[#4f815f]/30 bg-[#edf5ef] p-4 text-sm text-[#315b3e]"><CheckCircle2 className="mt-0.5 size-5 shrink-0" /><p><strong>You’re ready to bid.</strong> Payment details are only requested if you win an auction.</p></div></StepShell>;
}

function StepShell({ eyebrow, title, copy, children }: { eyebrow: string; title: string; copy: string; children: React.ReactNode }) {
  return <div><p className="text-xs font-semibold uppercase tracking-[0.15em] text-[#b34a36]">{eyebrow}</p><h1 className="mt-3 font-heading text-3xl font-bold tracking-[-0.045em] sm:text-4xl">{title}</h1><p className="mb-8 mt-3 max-w-xl leading-6 text-muted-foreground">{copy}</p>{children}</div>;
}

function SelectField({ name, label, options, control, error }: { name: FieldPath<BidderForm>; label: string; options: string[]; control: StepProps['control']; error?: string }) {
  return <Field data-invalid={Boolean(error)}><FieldLabel>{label}</FieldLabel><Controller name={name} control={control} render={({ field }) => <Select value={field.value as string} onValueChange={field.onChange}><SelectTrigger className="h-11 w-full bg-card" aria-invalid={Boolean(error)}><SelectValue placeholder={label} /></SelectTrigger><SelectContent>{options.map((option) => <SelectItem key={option} value={option}>{option}</SelectItem>)}</SelectContent></Select>} /><FieldError>{error}</FieldError></Field>;
}

function PreferenceCheckbox({ name, control, icon, title, copy }: { name: 'outbidAlerts' | 'endingSoonAlerts'; control: StepProps['control']; icon: React.ReactNode; title: string; copy: string }) {
  return <Controller name={name} control={control} render={({ field }) => <label className="flex cursor-pointer gap-3 rounded-2xl border p-4 transition-colors has-[[data-checked]]:border-primary/40 has-[[data-checked]]:bg-primary/5"><Checkbox checked={field.value} onCheckedChange={field.onChange} /><span className="mt-0.5 text-primary">{icon}</span><span><span className="block text-sm font-semibold">{title}</span><span className="mt-1 block text-xs leading-5 text-muted-foreground">{copy}</span></span></label>} />;
}

function ReviewCard({ title, rows, action }: { title: string; rows: [string, string][]; action: () => void }) {
  return <section className="rounded-2xl border p-5"><div className="flex items-center justify-between"><h2 className="font-heading text-lg font-bold">{title}</h2><Button type="button" variant="ghost" size="sm" onClick={action}>Edit</Button></div><dl className="mt-4 space-y-3">{rows.map(([label, value]) => <div key={label} className="grid gap-1 text-sm sm:grid-cols-[120px_1fr]"><dt className="text-muted-foreground">{label}</dt><dd className="break-words font-medium">{value}</dd></div>)}</dl></section>;
}

function BidderReady({ values, returnTo, onEdit }: { values: BidderForm; returnTo: string; onEdit: () => void }) {
  const nextLabel = returnTo.startsWith('/sell') ? 'Continue to seller setup' : 'Explore live auctions';
  return <main className="grid min-h-screen place-items-center bg-[#263129] px-5 py-12 text-[#f8f4eb]"><div className="w-full max-w-2xl text-center"><span className="mx-auto grid size-16 place-items-center rounded-full bg-[#d85f4a]"><Check className="size-8" /></span><Badge className="mt-7 bg-white/10 text-white">Verified bidder</Badge><h1 className="mt-5 font-heading text-4xl font-bold tracking-[-0.05em] sm:text-6xl">Your paddle is ready.</h1><p className="mx-auto mt-5 max-w-lg text-base leading-7 text-white/65">Welcome to Gavel, <strong className="text-white">{values.displayName}</strong>. You can now bid on live lots and enable selling whenever you’re ready.</p><div className="mx-auto mt-8 grid max-w-lg gap-3 text-left sm:grid-cols-2"><div className="rounded-2xl border border-white/10 bg-white/5 p-4"><p className="text-xs text-white/50">Account</p><p className="mt-1 font-semibold">Email verified</p></div><div className="rounded-2xl border border-white/10 bg-white/5 p-4"><p className="text-xs text-white/50">Alerts</p><p className="mt-1 font-semibold">{values.outbidAlerts || values.endingSoonAlerts ? 'Auction updates on' : 'All alerts off'}</p></div></div><div className="mt-8 flex flex-col justify-center gap-3 sm:flex-row"><Button onClick={onEdit} variant="outline" className="border-white/20 bg-transparent text-white hover:bg-white/10 hover:text-white">Edit profile</Button><Button onClick={() => { window.location.href = returnTo; }} className="bg-[#d85f4a] text-white hover:bg-[#c44f3b]">{nextLabel} <ArrowRight data-icon="inline-end" /></Button></div></div></main>;
}

function readSession<T>(key: string): T | null {
  const value = window.sessionStorage.getItem(key);
  if (!value) return null;
  try { return JSON.parse(value) as T; } catch { window.sessionStorage.removeItem(key); return null; }
}
