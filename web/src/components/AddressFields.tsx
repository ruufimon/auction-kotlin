"use client";
import { useId } from "react";
import { Field } from "./Field";
import { ADDRESS_FIELDS, COUNTRIES } from "@/lib/api/settings";

export type AddressValue = Record<"line1" | "city" | "region" | "postalCode" | "country", string>;
export const EMPTY_ADDRESS: AddressValue = { line1: "", city: "", region: "", postalCode: "", country: "" };

export function toAddressValue(source: Partial<AddressValue> | Record<string, string> | null | undefined): AddressValue {
  const s = source as Partial<Record<string, string>> | null | undefined;
  return { line1: s?.line1 ?? "", city: s?.city ?? "", region: s?.region ?? "", postalCode: s?.postalCode ?? "", country: s?.country ?? "" };
}

/** Street, city, region, postal code and country; `errorPrefix` maps API details such as "originAddress.city". */
export function AddressFields({ value, onChange, errors, errorPrefix = "", disabled }: {
  value: AddressValue; onChange: (v: AddressValue) => void; errors: Record<string, string>; errorPrefix?: string; disabled?: boolean;
}) {
  const countryId = useId();
  const countryError = errors[`${errorPrefix}country`];
  const known = COUNTRIES.some(([code]) => code === value.country);
  return (
    <div className="stack">
      {ADDRESS_FIELDS.slice(0, 1).map((f) => (
        <Field key={f.key} label={f.label} autoComplete={f.autoComplete} value={value[f.key]} disabled={disabled}
          error={errors[`${errorPrefix}${f.key}`]} onChange={(e) => onChange({ ...value, [f.key]: e.target.value })} />
      ))}
      <div className="grid-2">
        {ADDRESS_FIELDS.slice(1).map((f) => (
          <Field key={f.key} label={f.label} autoComplete={f.autoComplete} value={value[f.key]} disabled={disabled}
            error={errors[`${errorPrefix}${f.key}`]} onChange={(e) => onChange({ ...value, [f.key]: e.target.value })} />
        ))}
        <div className="field">
          <label htmlFor={countryId}>Country</label>
          <select id={countryId} className="input" autoComplete="country" value={value.country} disabled={disabled}
            aria-invalid={countryError ? true : undefined} aria-describedby={countryError ? `${countryId}-error` : undefined}
            onChange={(e) => onChange({ ...value, country: e.target.value })}>
            <option value="">Select a country</option>
            {!known && value.country && <option value={value.country}>{value.country}</option>}
            {COUNTRIES.map(([code, name]) => <option key={code} value={code}>{name}</option>)}
          </select>
          {countryError && <p id={`${countryId}-error`} className="error" role="alert">{countryError}</p>}
        </div>
      </div>
    </div>
  );
}
