/** Currency the assessment amounts are entered in (ISO 4217); the API stores integer minor units. */
export const CURRENCY = process.env.NEXT_PUBLIC_CURRENCY || "USD";

/** Mirrors the API's default SellerRequirements; the API stays the authority via SELLER_PROFILE_INCOMPLETE. */
export const SELLER_FIELDS = [
  { key: "legalName", label: "Legal name", hint: "As it appears on your ID. Not shown publicly.", autoComplete: "name" },
  { key: "storeName", label: "Store name", hint: "Shown on your listings.", autoComplete: "organization" },
  { key: "contactPhone", label: "Contact phone", hint: "For buyers and support about orders.", autoComplete: "tel" },
  { key: "payoutAccount", label: "Payout account", hint: "Account that receives your sale proceeds.", autoComplete: "off" },
] as const;

export const ADDRESS_FIELDS = [
  { key: "line1", label: "Street address", autoComplete: "address-line1" },
  { key: "city", label: "City", autoComplete: "address-level2" },
  { key: "region", label: "State / region", autoComplete: "address-level1" },
  { key: "postalCode", label: "Postal code", autoComplete: "postal-code" },
] as const;

export const COUNTRIES: [string, string][] = [
  ["US", "United States"], ["CA", "Canada"], ["GB", "United Kingdom"], ["IE", "Ireland"], ["DE", "Germany"], ["FR", "France"],
  ["NL", "Netherlands"], ["ES", "Spain"], ["IT", "Italy"], ["AU", "Australia"], ["NZ", "New Zealand"], ["JP", "Japan"],
  ["SG", "Singapore"], ["TH", "Thailand"], ["VN", "Vietnam"], ["MY", "Malaysia"], ["ID", "Indonesia"], ["PH", "Philippines"], ["IN", "India"],
];
