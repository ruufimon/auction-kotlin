'use client';

import { useEffect, useMemo, useState } from 'react';
import { zodResolver } from '@hookform/resolvers/zod';
import Link from 'next/link';
import {
  ArrowLeft,
  ArrowRight,
  BadgeCheck,
  Banknote,
  Box,
  Check,
  CheckCircle2,
  Clock3,
  ExternalLink,
  Gavel,
  ImagePlus,
  Info,
  MapPin,
  PackageCheck,
  ShieldCheck,
  Store,
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
import { Field, FieldDescription, FieldError, FieldGroup, FieldLabel } from '@/components/ui/field';
import { Input } from '@/components/ui/input';
import { Progress } from '@/components/ui/progress';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Textarea } from '@/components/ui/textarea';
import { cn } from '@/lib/utils';
import {
  sellerOnboardingDefaults as defaults,
  sellerOnboardingFieldsByStep as fieldsByStep,
  sellerOnboardingSchema as schema,
  type SellerOnboardingForm as SellerForm,
} from '../model/seller-onboarding';
import type { BidderProfile } from '../model/bidder-onboarding';

const steps = [
  { label: 'Welcome', icon: Store },
  { label: 'Seller profile', icon: UserRound },
  { label: 'Shipping origin', icon: MapPin },
  { label: 'Payouts', icon: Banknote },
  { label: 'Item details', icon: Box },
  { label: 'Auction setup', icon: Clock3 },
  { label: 'Review', icon: PackageCheck },
];


export default function SellerOnboardingPage() {
  const [step, setStep] = useState(0);
  const [completed, setCompleted] = useState(false);
  const [scheduleError, setScheduleError] = useState('');
  const [saveNotice, setSaveNotice] = useState('');
  const [bidderEmail, setBidderEmail] = useState('Verified bidder');
  const form = useForm<SellerForm>({ resolver: zodResolver(schema), defaultValues: defaults, mode: 'onTouched' });
  const { control, formState: { errors }, getValues, handleSubmit, register, reset, setValue, trigger, watch } = form;
  const values = watch();

  useEffect(() => {
    const bidderValue = window.sessionStorage.getItem('gavel-bidder-profile');
    if (!bidderValue) {
      window.location.replace('/onboarding?returnTo=%2Fsell%2Fonboarding');
      return;
    }
    let bidder: BidderProfile;
    try {
      bidder = JSON.parse(bidderValue) as BidderProfile;
      setBidderEmail(bidder.email);
    } catch {
      window.sessionStorage.removeItem('gavel-bidder-profile');
      window.location.replace('/onboarding?returnTo=%2Fsell%2Fonboarding');
      return;
    }
    const saved = window.sessionStorage.getItem('gavel-seller-onboarding');
    if (saved) {
      try { reset({ ...defaults, ...bidder, ...JSON.parse(saved) }); } catch { window.sessionStorage.removeItem('gavel-seller-onboarding'); }
    } else {
      reset({ ...defaults, displayName: bidder.displayName, phone: bidder.phone, address: bidder.address, country: bidder.country, city: bidder.city, region: bidder.region, postal: bidder.postal });
    }
  }, [reset]);

  useEffect(() => {
    const subscription = watch((next) => window.sessionStorage.setItem('gavel-seller-onboarding', JSON.stringify(next)));
    return () => subscription.unsubscribe();
  }, [watch]);

  const progress = ((step + 1) / steps.length) * 100;
  const minimumDate = useMemo(() => new Date(Date.now() - new Date().getTimezoneOffset() * 60_000).toISOString().slice(0, 16), []);

  async function continueFlow() {
    const valid = await trigger(fieldsByStep[step]);
    if (!valid) return;
    if (step === 5) {
      const { startAt, endAt, freeShipping, shippingPrice } = getValues();
      if (new Date(endAt) <= new Date(startAt)) {
        setScheduleError('The auction must end after it starts.');
        return;
      }
      if (!freeShipping && shippingPrice < 0) return;
      setScheduleError('');
    }
    setStep((current) => Math.min(current + 1, steps.length - 1));
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  function saveAndExit() {
    window.sessionStorage.setItem('gavel-seller-onboarding', JSON.stringify(getValues()));
    setSaveNotice('Progress saved for this browser session. Returning to the marketplace…');
    window.setTimeout(() => { window.location.href = '/'; }, 700);
  }

  function discard() {
    window.sessionStorage.removeItem('gavel-seller-onboarding');
    window.location.href = '/';
  }

  function finish(data: SellerForm) {
    window.sessionStorage.setItem('gavel-listing-draft', JSON.stringify({ ...data, status: 'DRAFT' }));
    window.sessionStorage.removeItem('gavel-seller-onboarding');
    setCompleted(true);
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  if (completed) return <SuccessState values={values} onEdit={() => { setCompleted(false); setStep(4); }} />;

  return (
    <main className="min-h-screen bg-background text-foreground">
      <header className="border-b bg-background/90 backdrop-blur-xl">
        <div className="mx-auto flex h-20 max-w-[1280px] items-center justify-between px-5 lg:px-10">
          <Link href="/" className="flex items-center gap-2.5" aria-label="Gavel home"><span className="grid size-10 place-items-center rounded-full bg-primary text-primary-foreground"><Gavel className="size-5" /></span><span className="font-heading text-xl font-bold tracking-[-0.04em]">Gavel</span></Link>
          <div className="hidden items-center gap-2 text-sm text-muted-foreground sm:flex"><BadgeCheck className="size-4 text-[#39704b]" /> {bidderEmail}</div>
          <AlertDialog>
            <AlertDialogTrigger render={<Button variant="ghost" />}>Discard</AlertDialogTrigger>
            <AlertDialogContent>
              <AlertDialogHeader><AlertDialogMedia><Trash2 /></AlertDialogMedia><AlertDialogTitle>Discard your setup?</AlertDialogTitle><AlertDialogDescription>Your seller details and first listing progress will be removed from this browser session.</AlertDialogDescription></AlertDialogHeader>
              <AlertDialogFooter><AlertDialogCancel>Keep working</AlertDialogCancel><AlertDialogAction variant="destructive" onClick={discard}>Discard progress</AlertDialogAction></AlertDialogFooter>
            </AlertDialogContent>
          </AlertDialog>
        </div>
      </header>

      <div className="mx-auto grid max-w-[1280px] gap-8 px-5 py-8 lg:grid-cols-[260px_minmax(0,720px)_1fr] lg:px-10 lg:py-12">
        <aside className="hidden lg:block">
          <p className="mb-5 text-xs font-semibold uppercase tracking-[0.15em] text-muted-foreground">Seller setup</p>
          <ol className="space-y-1">
            {steps.map((item, index) => {
              const Icon = item.icon;
              return <li key={item.label} className={cn('flex items-center gap-3 rounded-xl px-3 py-3 text-sm', index === step && 'bg-card font-semibold shadow-sm', index < step ? 'text-foreground' : 'text-muted-foreground')}><span className={cn('grid size-8 place-items-center rounded-full border', index < step && 'border-primary bg-primary text-primary-foreground', index === step && 'border-primary text-primary')} >{index < step ? <Check className="size-4" /> : <Icon className="size-4" />}</span>{item.label}</li>;
            })}
          </ol>
          <div className="mt-7 rounded-2xl border bg-card p-4 text-sm"><div className="flex items-center gap-2 font-semibold"><ShieldCheck className="size-4 text-primary" /> Your data is protected</div><p className="mt-2 leading-5 text-muted-foreground">Private contact and payout details are never shown on your listings.</p></div>
        </aside>

        <section>
          <div className="mb-7 lg:hidden"><div className="mb-2 flex items-center justify-between text-sm"><span className="font-semibold">{steps[step].label}</span><span className="text-muted-foreground">{step + 1} of {steps.length}</span></div><Progress value={progress} /></div>
          <form onSubmit={handleSubmit(finish)}>
            <div className="rounded-3xl border bg-card p-5 shadow-sm sm:p-8 lg:p-10">
              <StepContent step={step} control={control} errors={errors} register={register} setValue={setValue} values={values} minimumDate={minimumDate} goToStep={setStep} />
              {scheduleError && <p role="alert" className="mt-4 rounded-xl bg-destructive/10 p-3 text-sm text-destructive">{scheduleError}</p>}
            </div>

            <div className="mt-5 flex flex-col-reverse gap-3 sm:flex-row sm:items-center sm:justify-between">
              <div className="flex gap-2"><Button type="button" variant="outline" onClick={() => setStep((current) => Math.max(0, current - 1))} disabled={step === 0}><ArrowLeft data-icon="inline-start" /> Back</Button><Button type="button" variant="ghost" onClick={saveAndExit}>Save & exit</Button></div>
              {step < steps.length - 1 ? <Button type="button" onClick={continueFlow} className="bg-[#d85f4a] text-white hover:bg-[#c44f3b]">Continue <ArrowRight data-icon="inline-end" /></Button> : <Button type="submit" className="bg-[#d85f4a] text-white hover:bg-[#c44f3b]">Complete setup <Check data-icon="inline-end" /></Button>}
            </div>
            <p aria-live="polite" className="mt-3 min-h-5 text-sm text-muted-foreground">{saveNotice}</p>
          </form>
        </section>

        <aside className="hidden xl:block"><div className="sticky top-8 rounded-2xl border bg-card p-5"><p className="text-xs font-semibold uppercase tracking-[0.14em] text-muted-foreground">Progress</p><p className="mt-3 font-heading text-3xl font-bold tracking-tight">{Math.round(progress)}%</p><Progress value={progress} className="mt-3" /><p className="mt-4 text-sm leading-6 text-muted-foreground">Complete each section once. You can move back without losing the information entered in this session.</p></div></aside>
      </div>
    </main>
  );
}

type StepProps = {
  step: number;
  control: ReturnType<typeof useForm<SellerForm>>['control'];
  errors: ReturnType<typeof useForm<SellerForm>>['formState']['errors'];
  register: ReturnType<typeof useForm<SellerForm>>['register'];
  setValue: ReturnType<typeof useForm<SellerForm>>['setValue'];
  values: SellerForm;
  minimumDate: string;
  goToStep: (step: number) => void;
};

function StepContent({ step, control, errors, register, setValue, values, minimumDate, goToStep }: StepProps) {
  const error = (name: FieldPath<SellerForm>) => (errors as unknown as Record<string, { message?: string }>)[name]?.message;

  if (step === 0) return <div><Badge className="bg-[#e7f1ea] text-[#26623a]"><BadgeCheck /> Email verified</Badge><h1 className="mt-5 font-heading text-4xl font-bold tracking-[-0.05em] sm:text-5xl">Turn remarkable things into remarkable sales.</h1><p className="mt-5 max-w-xl text-base leading-7 text-muted-foreground">Set up your seller profile, connect payouts, and create your first auction draft. Most sellers finish in about 6 minutes.</p><div className="mt-8 grid gap-3 sm:grid-cols-3">{[['Your profile', 'Build trust with buyers.'], ['Secure payouts', 'Connect through our provider.'], ['First listing', 'Prepare your item for auction.']].map(([title, copy], index) => <div key={title} className="rounded-2xl bg-secondary/70 p-4"><span className="grid size-8 place-items-center rounded-full bg-primary text-sm font-bold text-primary-foreground">{index + 1}</span><p className="mt-4 font-semibold">{title}</p><p className="mt-1 text-sm text-muted-foreground">{copy}</p></div>)}</div></div>;

  if (step === 1) return <StepShell eyebrow="About you" title="Create your seller profile" copy="This is how buyers will recognize and trust you."><FieldGroup><div className="grid gap-5 sm:grid-cols-2"><Field data-invalid={Boolean(error('displayName'))}><FieldLabel htmlFor="displayName">Public display name</FieldLabel><Input id="displayName" className="h-11" aria-invalid={Boolean(error('displayName'))} {...register('displayName')} /><FieldDescription>Shown on listings and order updates.</FieldDescription><FieldError>{error('displayName')}</FieldError></Field><Field data-invalid={Boolean(error('phone'))}><FieldLabel htmlFor="phone">Phone number</FieldLabel><Input id="phone" type="tel" placeholder="+1 555 0142" className="h-11" aria-invalid={Boolean(error('phone'))} {...register('phone')} /><FieldDescription>Private; used for fulfillment issues.</FieldDescription><FieldError>{error('phone')}</FieldError></Field></div><Field data-invalid={Boolean(error('bio'))}><FieldLabel htmlFor="bio">Seller bio</FieldLabel><Textarea id="bio" rows={4} placeholder="Share what you collect and what buyers can expect…" aria-invalid={Boolean(error('bio'))} {...register('bio')} /><div className="flex justify-between"><FieldError>{error('bio')}</FieldError><FieldDescription>{values.bio.length}/180</FieldDescription></div></Field></FieldGroup></StepShell>;

  if (step === 2) return <StepShell eyebrow="Fulfillment" title="Where will you ship from?" copy="Your exact address stays private. Buyers only see the shipping country."><FieldGroup><Field data-invalid={Boolean(error('address'))}><FieldLabel htmlFor="address">Street address</FieldLabel><Input id="address" placeholder="42 Mercer Street" className="h-11" aria-invalid={Boolean(error('address'))} {...register('address')} /><FieldError>{error('address')}</FieldError></Field><div className="grid gap-5 sm:grid-cols-2"><SelectField name="country" label="Country" options={['United States', 'Canada', 'United Kingdom', 'Thailand']} control={control} error={error('country')} /><Field data-invalid={Boolean(error('city'))}><FieldLabel htmlFor="city">City</FieldLabel><Input id="city" className="h-11" aria-invalid={Boolean(error('city'))} {...register('city')} /><FieldError>{error('city')}</FieldError></Field><Field data-invalid={Boolean(error('region'))}><FieldLabel htmlFor="region">State or region</FieldLabel><Input id="region" className="h-11" aria-invalid={Boolean(error('region'))} {...register('region')} /><FieldError>{error('region')}</FieldError></Field><Field data-invalid={Boolean(error('postal'))}><FieldLabel htmlFor="postal">Postal code</FieldLabel><Input id="postal" className="h-11" aria-invalid={Boolean(error('postal'))} {...register('postal')} /><FieldError>{error('postal')}</FieldError></Field></div><SelectField name="handlingTime" label="Typical handling time" options={['1 business day', '2 business days', '3 business days', '5 business days']} control={control} error={error('handlingTime')} /></FieldGroup></StepShell>;

  if (step === 3) return <StepShell eyebrow="Payments" title="Connect your payouts" copy="Gavel uses a secure payment partner to verify your identity and send sale proceeds."><div className={cn('rounded-2xl border p-5', values.payoutConnected ? 'border-[#4f815f]/40 bg-[#edf5ef]' : 'bg-secondary/50')}><div className="flex flex-col gap-5 sm:flex-row sm:items-center sm:justify-between"><div className="flex items-start gap-4"><span className={cn('grid size-11 shrink-0 place-items-center rounded-xl', values.payoutConnected ? 'bg-[#39704b] text-white' : 'bg-primary text-primary-foreground')} >{values.payoutConnected ? <CheckCircle2 /> : <Banknote />}</span><div><p className="font-semibold">{values.payoutConnected ? 'Payout account connected' : 'Secure payout account'}</p><p className="mt-1 text-sm leading-5 text-muted-foreground">{values.payoutConnected ? 'Identity confirmed · USD payouts enabled' : 'You’ll return here automatically after connecting.'}</p></div></div><Button type="button" variant={values.payoutConnected ? 'outline' : 'default'} onClick={() => setValue('payoutConnected', true, { shouldValidate: true })}>{values.payoutConnected ? 'Connected' : 'Connect securely'} {!values.payoutConnected && <ExternalLink data-icon="inline-end" />}</Button></div></div><div className="mt-5 flex gap-3 rounded-xl border p-4 text-sm text-muted-foreground"><ShieldCheck className="mt-0.5 size-5 shrink-0 text-primary" /><p>Bank details are entered with the payment provider, never inside Gavel. In this prototype, connection is simulated.</p></div><FieldError className="mt-3">{error('payoutConnected')}</FieldError></StepShell>;

  if (step === 4) return <StepShell eyebrow="Your first lot" title="Describe the item" copy="Specific, honest details help buyers bid with confidence."><FieldGroup><Field data-invalid={Boolean(error('title'))}><FieldLabel htmlFor="title">Listing title</FieldLabel><Input id="title" placeholder="e.g. Leica M6 Classic 0.72" className="h-11" aria-invalid={Boolean(error('title'))} {...register('title')} /><div className="flex justify-between"><FieldError>{error('title')}</FieldError><FieldDescription>{values.title.length}/80</FieldDescription></div></Field><div className="grid gap-5 sm:grid-cols-2"><SelectField name="category" label="Category" options={['Cameras', 'Watches', 'Design', 'Records', 'Art']} control={control} error={error('category')} placeholder="Choose category" /><SelectField name="condition" label="Condition" options={['New', 'Excellent', 'Very good', 'Good', 'Fair', 'Restored']} control={control} error={error('condition')} placeholder="Choose condition" /></div><Field data-invalid={Boolean(error('description'))}><FieldLabel htmlFor="description">Description</FieldLabel><Textarea id="description" rows={5} placeholder="Describe the item’s history, condition, included accessories, and any flaws…" aria-invalid={Boolean(error('description'))} {...register('description')} /><FieldError>{error('description')}</FieldError></Field><Field data-invalid={Boolean(error('photos'))}><FieldLabel htmlFor="photos">Product photos</FieldLabel><label htmlFor="photos" className="grid min-h-36 cursor-pointer place-items-center rounded-2xl border border-dashed bg-secondary/40 p-5 text-center transition-colors hover:bg-secondary"><div><ImagePlus className="mx-auto size-7 text-primary" /><p className="mt-3 text-sm font-semibold">Choose photos</p><p className="mt-1 text-xs text-muted-foreground">JPG, PNG, or WebP · up to 8 photos</p></div></label><input id="photos" type="file" accept="image/png,image/jpeg,image/webp" multiple className="sr-only" onChange={(event) => setValue('photos', Array.from(event.target.files ?? []).slice(0, 8).map((file) => file.name), { shouldValidate: true })} />{values.photos.length > 0 && <div className="flex flex-wrap gap-2">{values.photos.map((photo) => <Badge key={photo} variant="secondary" className="max-w-full"><ImagePlus /> <span className="truncate">{photo}</span></Badge>)}</div>}<FieldError>{error('photos')}</FieldError></Field></FieldGroup></StepShell>;

  if (step === 5) return <StepShell eyebrow="Auction details" title="Set your price and timing" copy="Bidding starts at your chosen price and closes at the fixed end time."><FieldGroup><div className="grid gap-5 sm:grid-cols-2"><Field data-invalid={Boolean(error('startingPrice'))}><FieldLabel htmlFor="startingPrice">Starting price</FieldLabel><div className="relative"><span className="absolute left-3 top-1/2 -translate-y-1/2 text-sm font-semibold text-muted-foreground">$</span><Input id="startingPrice" type="number" min="1" className="h-11 pl-7" aria-invalid={Boolean(error('startingPrice'))} {...register('startingPrice', { valueAsNumber: true })} /></div><FieldError>{error('startingPrice')}</FieldError></Field><Field data-invalid={Boolean(error('shippingPrice'))}><FieldLabel htmlFor="shippingPrice">Shipping price</FieldLabel><div className="relative"><span className="absolute left-3 top-1/2 -translate-y-1/2 text-sm font-semibold text-muted-foreground">$</span><Input id="shippingPrice" type="number" min="0" disabled={values.freeShipping} className="h-11 pl-7" aria-invalid={Boolean(error('shippingPrice'))} {...register('shippingPrice', { valueAsNumber: true })} /></div><label className="flex items-center gap-2 text-sm text-muted-foreground"><input type="checkbox" className="size-4 accent-[#263129]" {...register('freeShipping')} /> Offer free shipping</label><FieldError>{error('shippingPrice')}</FieldError></Field></div><div className="grid gap-5 sm:grid-cols-2"><Field data-invalid={Boolean(error('startAt'))}><FieldLabel htmlFor="startAt">Starts</FieldLabel><Input id="startAt" type="datetime-local" min={minimumDate} className="h-11" aria-invalid={Boolean(error('startAt'))} {...register('startAt')} /><FieldError>{error('startAt')}</FieldError></Field><Field data-invalid={Boolean(error('endAt'))}><FieldLabel htmlFor="endAt">Ends</FieldLabel><Input id="endAt" type="datetime-local" min={values.startAt || minimumDate} className="h-11" aria-invalid={Boolean(error('endAt'))} {...register('endAt')} /><FieldError>{error('endAt')}</FieldError></Field></div><div className="flex gap-3 rounded-xl bg-secondary/60 p-4 text-sm text-muted-foreground"><Info className="mt-0.5 size-4 shrink-0 text-primary" /><p>Auctions close exactly at the selected time. Once the first bid arrives, pricing and timing can no longer be changed.</p></div></FieldGroup></StepShell>;

  return <StepShell eyebrow="Final check" title="Review your seller setup" copy="Confirm the details below. Your listing will be saved as a draft, not published."><div className="space-y-3"><ReviewCard title="Seller profile" action={() => goToStep(1)} rows={[['Display name', values.displayName], ['Phone', values.phone], ['Bio', values.bio]]} /><ReviewCard title="Shipping & payouts" action={() => goToStep(2)} rows={[['Ships from', `${values.city}, ${values.region}, ${values.country}`], ['Handling time', values.handlingTime], ['Payouts', 'Connected securely']]} /><ReviewCard title="First auction draft" action={() => goToStep(4)} rows={[['Item', values.title], ['Category', `${values.category} · ${values.condition}`], ['Starting price', `$${values.startingPrice.toLocaleString()}`], ['Shipping', values.freeShipping ? 'Free' : `$${values.shippingPrice.toLocaleString()}`], ['Schedule', `${formatDate(values.startAt)} → ${formatDate(values.endAt)}`]]} /></div><div className="mt-5 flex gap-3 rounded-xl border border-[#4f815f]/30 bg-[#edf5ef] p-4 text-sm text-[#315b3e]"><CheckCircle2 className="mt-0.5 size-5 shrink-0" /><p><strong>Nothing goes live yet.</strong> Completing setup creates an editable draft. You’ll review publication rules before buyers can see it.</p></div></StepShell>;
}

function StepShell({ eyebrow, title, copy, children }: { eyebrow: string; title: string; copy: string; children: React.ReactNode }) {
  return <div><p className="text-xs font-semibold uppercase tracking-[0.15em] text-[#b34a36]">{eyebrow}</p><h1 className="mt-3 font-heading text-3xl font-bold tracking-[-0.045em] sm:text-4xl">{title}</h1><p className="mb-8 mt-3 max-w-xl leading-6 text-muted-foreground">{copy}</p>{children}</div>;
}

function SelectField({ name, label, options, control, error, placeholder }: { name: FieldPath<SellerForm>; label: string; options: string[]; control: StepProps['control']; error?: string; placeholder?: string }) {
  return <Field data-invalid={Boolean(error)}><FieldLabel>{label}</FieldLabel><Controller name={name} control={control} render={({ field }) => <Select value={field.value as string} onValueChange={field.onChange}><SelectTrigger className="h-11 w-full bg-card" aria-invalid={Boolean(error)}><SelectValue placeholder={placeholder ?? label} /></SelectTrigger><SelectContent>{options.map((option) => <SelectItem key={option} value={option}>{option}</SelectItem>)}</SelectContent></Select>} /><FieldError>{error}</FieldError></Field>;
}

function ReviewCard({ title, rows, action }: { title: string; action: () => void; rows: [string, string][] }) {
  return <section className="rounded-2xl border p-5"><div className="flex items-center justify-between"><h2 className="font-heading text-lg font-bold">{title}</h2><Button type="button" variant="ghost" size="sm" onClick={action}>Edit</Button></div><dl className="mt-4 space-y-3">{rows.map(([label, value]) => <div key={label} className="grid gap-1 text-sm sm:grid-cols-[130px_1fr]"><dt className="text-muted-foreground">{label}</dt><dd className="break-words font-medium">{value}</dd></div>)}</dl></section>;
}

function formatDate(value: string) {
  if (!value) return 'Not set';
  return new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' }).format(new Date(value));
}

function SuccessState({ values, onEdit }: { values: SellerForm; onEdit: () => void }) {
  return <main className="grid min-h-screen place-items-center bg-[#263129] px-5 py-12 text-[#f8f4eb]"><div className="w-full max-w-2xl text-center"><span className="mx-auto grid size-16 place-items-center rounded-full bg-[#d85f4a]"><Check className="size-8" /></span><Badge className="mt-7 bg-white/10 text-white">Seller setup complete</Badge><h1 className="mt-5 font-heading text-4xl font-bold tracking-[-0.05em] sm:text-6xl">Your first draft is ready.</h1><p className="mx-auto mt-5 max-w-lg text-base leading-7 text-white/65">Welcome to selling on Gavel. <strong className="text-white">{values.title}</strong> has been saved privately and is ready for your final edits.</p><div className="mx-auto mt-8 max-w-lg rounded-2xl border border-white/10 bg-white/5 p-5 text-left"><div className="flex items-center gap-4"><span className="grid size-12 place-items-center rounded-xl bg-white/10"><Box /></span><div><p className="text-sm text-white/50">Draft listing</p><p className="font-semibold">{values.title}</p></div><Badge className="ml-auto bg-[#e7f1ea] text-[#26623a]">DRAFT</Badge></div></div><div className="mt-8 flex flex-col justify-center gap-3 sm:flex-row"><Button onClick={onEdit} variant="outline" className="border-white/20 bg-transparent text-white hover:bg-white/10 hover:text-white">Continue editing</Button><Button nativeButton={false} render={<Link href="/" aria-label="View marketplace" />} className="bg-[#d85f4a] text-white hover:bg-[#c44f3b]">View marketplace <ArrowRight data-icon="inline-end" /></Button></div></div></main>;
}
