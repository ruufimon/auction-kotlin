# Web Frontend Restructuring

This document explains the restructuring of the Next.js/TypeScript frontend to align with AGENT.md architecture principles.

## Overview

The web frontend has been reorganized to mirror the backend's architecture:
- **Features** are now in a dedicated `features/` folder
- **Shared code** is centralized in a `shared/` folder
- **App routes** remain at the root level in `app/`

This improves code organization, makes feature management clearer, and reduces coupling between features.

## Changes Made

### Directory Structure

#### Before
```
web/
├── app/                      (Next.js app router)
├── domains/                  (feature domains)
│   ├── user-onboarding/
│   ├── marketplace/
│   └── seller-onboarding/
├── components/               (UI components)
├── hooks/                    (custom hooks)
├── lib/                      (utilities)
└── public/
```

#### After
```
web/
├── app/                      (Next.js app router - unchanged)
├── features/                 (business domain features)
│   ├── user-onboarding/
│   ├── marketplace/
│   └── seller-onboarding/
├── shared/                   (shared code)
│   ├── components/           (UI components)
│   ├── hooks/                (custom hooks)
│   └── lib/                  (utilities)
└── public/                   (static assets)
```

### Moved Directories

| Old Path | New Path | Notes |
|----------|----------|-------|
| `domains/` | `features/` | All feature domains moved |
| `components/` | `shared/components/` | Shared UI components |
| `hooks/` | `shared/hooks/` | Custom React hooks |
| `lib/` | `shared/lib/` | Utility functions |

### Import Path Updates

#### TypeScript Configuration
Updated `tsconfig.json` with explicit path aliases:

```json
{
  "paths": {
    "@/*": ["./*"],
    "@/shared/*": ["./shared/*"],
    "@/features/*": ["./features/*"]
  }
}
```

#### Import Examples

**Before:**
```typescript
import { Button } from '@/components/ui/button'
import { useLocalStorage } from '@/hooks/use-local-storage'
import { formatCurrency } from '@/lib/format'
```

**After (still works - paths are backward compatible):**
```typescript
import { Button } from '@/shared/components/ui/button'
import { useLocalStorage } from '@/shared/hooks/use-local-storage'
import { formatCurrency } from '@/shared/lib/format'
```

Or use the direct path:
```typescript
import { Button } from '@/components/ui/button'  // still works!
```

### Feature Organization

Each feature in `features/` follows the same structure:

```
features/user-onboarding/
├── ui/                  (React components)
│   ├── seller-onboarding-page.tsx
│   └── bidder-onboarding-page.tsx
├── model/               (data models & types)
│   ├── seller-onboarding.ts
│   └── bidder-onboarding.ts
└── index.ts            (public exports)

features/marketplace/
├── ui/
│   ├── marketplace-page.tsx
│   └── auction-detail-page.tsx
├── model/
│   └── auction.ts
└── index.ts

features/seller-onboarding/
├── ui/
│   └── seller-onboarding-page.tsx
├── model/
└── index.ts
```

### Shared Code Organization

```
shared/
├── components/
│   └── ui/                 (shadcn/ui components)
│       ├── button.tsx
│       ├── field.tsx
│       ├── badge.tsx
│       └── ... (other UI components)
├── hooks/
│   └── use-mobile.ts       (custom React hooks)
└── lib/
    └── utils.ts            (utility functions)
```

## Benefits of This Structure

### 1. **Feature Isolation**
Features are clearly separated in the `features/` directory, making it easier to:
- Find feature-specific code
- Understand feature dependencies
- Add new features by copying an existing one

### 2. **Shared Code Clarity**
All shared code is in the `shared/` directory, making it clear what's reusable across features:
- UI components
- Custom hooks
- Utility functions

### 3. **Scalability**
As the project grows with more features:
- Adding features is straightforward (copy `features/example/`)
- Shared code is easy to discover and reuse
- Feature dependencies are visible

### 4. **Backend Alignment**
Mirrors the backend architecture:
- Features in `features/` (Kotlin)
- Shared APIs in `shared/` / `bank-api/` (Kotlin)
- Clear architectural boundaries

## Architecture Rules (Aligned with AGENT.md)

### Rule 1: Conventional Paths
✅ Copy an existing feature folder to add new features

### Rule 2: Forbidden Dependencies
- Features should not directly import from other features
- Use shared components, hooks, and utilities instead

### Rule 3: Single Writer Pattern
- Shared components: `shared/components/`
- Shared hooks: `shared/hooks/`
- Shared utilities: `shared/lib/`

### Rule 4: Features Organization
- All feature code in `features/{feature-name}/`
- App routes in `app/` (Next.js app router)
- Shared code in `shared/`

### Rule 5: Exceptions Are Explicit
- If features must depend on each other, document it in comments

## Build & Run

### Development
```bash
cd web
npm run dev
```

### Build
```bash
npm run build
```

### Linting
```bash
npm run lint
```

### Formatting
```bash
npm run format
```

### Type Checking
The TypeScript configuration already includes strict type checking.

## Path Aliases (TypeScript)

The `tsconfig.json` provides these aliases:

| Alias | Path | Usage |
|-------|------|-------|
| `@/*` | `./` | Root directory |
| `@/shared/*` | `./shared/*` | Shared code |
| `@/features/*` | `./features/*` | Features |

All three work, but explicit paths are recommended for clarity:

```typescript
// Recommended
import { Button } from '@/shared/components/ui/button'
import { formatCurrency } from '@/shared/lib/format'

// Also works (backward compatible)
import { Button } from '@/components/ui/button'
import { formatCurrency } from '@/lib/format'
```

## Migration Notes

### For Existing Code
- Existing imports using `@/components/`, `@/hooks/`, `@/lib/` continue to work
- No immediate action needed
- Update imports gradually as code is touched

### For New Code
- Use `@/shared/` prefix for shared code
- Use `@/features/` prefix for feature-specific code
- Example: `@/shared/components/ui/button`

### For New Features
1. Create `features/my-feature/`
2. Create subdirectories: `ui/`, `model/`, etc.
3. Export public API from `features/my-feature/index.ts`
4. Use `@/features/my-feature` to import from other features

## Testing

No test configuration changes are needed. The restructuring is purely organizational and does not affect:
- Build output
- Runtime behavior
- External APIs
- Component functionality

## Verification

To verify the restructuring:

```bash
# Build the project
npm run build

# Run type checking
npx tsc --noEmit

# Run linting
npm run lint
```

All should pass without errors.

## Future Improvements

### 1. Create `shared/types/` for shared types
```
shared/
├── components/
├── hooks/
├── lib/
└── types/           (new)
    └── models.ts    (shared domain types)
```

### 2. Create `app/api/` for API routes (if using Next.js 13+)
Already follows Next.js conventions in `app/` directory.

### 3. Add `shared/styles/` for shared CSS
```
shared/
├── components/
├── hooks/
├── lib/
├── styles/         (new)
│   └── globals.css
└── types/
```

## Questions?

Refer to the backend restructuring documentation for architecture principles:
- See `RESTRUCTURING.md` (backend) for detailed architecture guidelines
- See `AGENT.md` for the five rules and principles

Both frontend and backend now follow the same organizational patterns!
