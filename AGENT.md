# Agent Friendly Architecture (AFA)

This document describes the architectural rules and conventions that enable agents (both human and AI) to navigate this codebase efficiently. The design ensures that locally obvious changes are globally correct, and that unsafe shortcuts are caught by the build system.

## The Six Rules

### 1. The conventional path needs fewer decisions than a shortcut
**Copy an existing feature folder; you do not need to touch anything else.**

Add a new feature by copying an existing feature template:
- Source: `features/product-catalog/`, `features/settlement/`, or `features/bidding/`
- Rename the package, class names, and folder to match your feature
- Return your event types and handlers from the feature
- Run `./gradlew build` to validate

You do **not** edit `settings.gradle.kts`, root `build.gradle.kts`, or any other feature's code.

### 2. Forbidden dependencies fail mechanically
**The build stops with clear error messages and names what to do instead.**

The Gradle build enforces these boundaries:

| Forbidden | Error | Fix |
|-----------|-------|-----|
| Feature A imports Feature B | `✗ :features:a depends on :features:b` | Share through `kernel/` or domain events |
| Kernel imports features | `✗ :kernel depends on :features:…` | Move to a feature |
| Feature imports external server | `✗ :features:x depends on :host-server` | Reach server via events/HTTP |
| Circular event dependencies | `✗ :features:a → :features:b → :features:a` | Redesign event flow (A never listens to B's response events) |

If your change breaks the build, the error message tells you the supported path.

### 3. Every durable value has one obvious writer
**Shared state (event stream, audit log) is written only through the kernel; features own their tables and listen to events.**

Single writer ensures:
- No race conditions or inconsistent views
- Audit trail is unambiguous
- Replays/recovery are deterministic

| State | Writer | Readers |
|-------|--------|---------|
| User account | `features/user-onboarding/` | All features (via events) |
| Auction state | `features/product-catalog/` | Settlement, Bidding (via events) |
| Order | `features/settlement/` | Catalog, Fulfillment (via events) |
| Event stream, audit log | `kernel/` (EventPublisher, AuditLog) | All features (EventHandler) |

Events are published synchronously within the same transaction as the state change, ensuring atomicity.

### 4. New product work adds a folder under features/
**Features are discovered automatically; don't edit shared lists.**

Feature structure:
```
features/my-feature/
  src/main/kotlin/com/gavel/my_feature/
    api/
      MyFeatureEvents.kt          # Event types published by this feature
    commands/
      MyFeatureCommands.kt        # (Optional) Commands this feature handles
    subscribers/
      MyEventHandlers.kt          # Event handlers from other features
    service/
      MyFeatureService.kt         # Business logic
  src/test/kotlin/...             # Tests
  DomainEvents.md                 # Event documentation for this feature
```

**Key files:**
- `api/MyFeatureEvents.kt` — Define event constants and serializable payloads
- `subscribers/` — Implement EventHandler interface for consumed events
- `DomainEvents.md` — Document what this feature publishes and consumes

The feature is automatically discovered by Gradle. No registration needed.

### 5. Exceptions are narrow, explicit, and reviewed as architecture changes
**Changing the kernel, Gradle boundaries, or event definitions requires a PR discussion.**

Rule 5 changes:
- Adding to `kernel/` (shared interfaces: EventPublisher, EventHandler, AuditLog, ApiError, token validation)
- Modifying Gradle build rules or adding new module boundaries
- Introducing new event types or changing event structure
- Adding forbidden dependencies (with explicit exceptions in build.gradle)

These must be documented in your PR as architectural decisions.

### 6. Event-Driven Feature Communication
**Features communicate via immutable, versioned domain events, not direct imports.**

Events are the only safe inter-feature communication mechanism:

#### How it works
1. Feature A publishes an event: `publisher.publish(type, payloadVersion, aggregateType, aggregateId, payload)`
2. Feature B implements an EventHandler to consume it
3. Event has a name and version for idempotency and schema evolution
4. Handlers run synchronously within the same transaction
5. Event published → handlers execute → transaction commits (all or nothing)

#### Event Structure
```kotlin
// kernel/src/main/kotlin/com/gavel/kernel/events/Events.kt (single writer)
interface EventPublisher {
    fun publish(type: String, payloadVersion: Int, aggregateType: String, aggregateId: UUID, payload: String, correlationId: UUID? = null)
}

interface EventHandler {
    val name: String              // Idempotency key (must be unique)
    val eventType: String         // Which event this handles
    val payloadVersion: Int       // Which schema version
    fun handle(event: EventEnvelope)
}

// Every event wrapped in an envelope
data class EventEnvelope(
    val eventId: UUID,
    val type: String,            // e.g., "catalog.auction-sold"
    val payloadVersion: Int,
    val aggregateType: String,   // e.g., "account", "listing"
    val aggregateId: UUID,       // the row's internal id; public ids travel in the payload
    val occurredAt: Instant,
    val correlationId: UUID?,
    val payload: String          // JSON
)
```

#### Event Naming Convention
- **Constants (SCREAMING_SNAKE_CASE):** `AUCTION_SOLD`, `BID_PLACED`, `ORDER_PAID`
- **Event names (past tense):** `AuctionSoldV1`, not `AuctionSell`
- **Event types (feature.verb-noun):** `"catalog.auction-sold"`, `"bidding.bid-placed"`
- **Payload class (PascalCase with version):** `AuctionSoldV1`, `OrderPaidV2`

#### Allowed Event Flows
```
✓ Catalog publishes AUCTION_SOLD
  ↓
  Settlement consumes (creates order)
  Catalog publishes ORDER_PAID
  
✓ User-Onboarding publishes EMAIL_VERIFIED
  ↓
  Bidding consumes (enables bidding)
  Catalog consumes (optional)
  Settlement consumes (optional)

✓ Bidding publishes BID_PLACED
  ↓
  Catalog consumes (updates display, may close auction)
```

#### Forbidden Event Flows
```
✗ Catalog → Settlement → Catalog (circular)
✗ Bidding → Settlement → Bidding (circular)
✗ Any feature directly imports another feature's code
✗ Async fire-and-forget (handlers must run synchronously)
```

#### Versioning for Breaking Changes
- Define new version class (`AuctionSoldV2` alongside `AuctionSoldV1`)
- Update EventPublisher to send v2
- Old handlers stay on v1 (they ignore v2 events)
- Migrate handlers to v2 independently
- After all consumers upgraded, remove v1

#### Idempotency & Replay Safety
Handlers are idempotent by design:
```kotlin
class OrderCreationSubscriber : EventHandler {
    override val name = "settlement.order-creation"  // ← Idempotency key
    
    override fun handle(event: EventEnvelope) {
        // Safe to call multiple times
        // Implementation checks if order exists first
    }
}
```

Same event replayed twice = same result (no duplicate work).

#### See Also
- [DOMAIN_EVENTS.md](DOMAIN_EVENTS.md) — Complete event architecture guide
- `features/*/DomainEvents.md` — Per-feature event documentation

## Where things go

| You want to… | Do this | Do not |
|---|---|---|
| Create a new auction | Feature in `features/product-catalog/` | Edit other features |
| Handle user registration | Feature in `features/user-onboarding/` | Keep own user copy |
| Process a payment | Feature in `features/settlement/` | Direct database access |
| Publish an event | `publisher.publish(type, version, aggregateType, id, json)` from `kernel` inside the state-changing transaction | Hardcoded event dispatch |
| Consume an event | Implement `EventHandler` in `subscribers/` | Direct feature import |
| Share code between features | Add to `kernel/` (Rule 5) | Depend on another feature |
| Store event log | Database outbox table (transactional outbox pattern) | In-memory queue |

## Recipe: Add a Feature (No Other File Changes)

1. **Copy a template:**
   ```bash
   cp -r features/product-catalog features/my-feature
   ```

2. **Rename the package:**
   ```bash
   # Rename folder: features/my-feature/src/main/kotlin/com/gavel/my_feature/
   # Update package names in all .kt files
   ```

3. **Define events (if needed):**
   ```kotlin
   // features/my-feature/src/main/kotlin/com/gavel/my_feature/api/MyFeatureEvents.kt
   object MyFeatureEvents {
       const val MY_EVENT = "my-feature.my-event"
   }
   
   @Serializable data class MyEventV1(val id: String, ...)
   ```

4. **Implement event handlers (if consuming events):**
   ```kotlin
   // features/my-feature/src/main/kotlin/com/gavel/my_feature/subscribers/MyEventHandlers.kt
   class MyEventHandlerImpl(private val service: MyFeatureService) : EventHandler {
       override val name = "my-feature.my-event-handler"
       override val eventType = "other-feature.some-event"
       override val payloadVersion = 1
       
       override fun handle(event: EventEnvelope) {
           val payload = json.decodeFromString<SomeEventV1>(event.payload)
           service.handleEvent(payload)
       }
   }
   ```

5. **Document events:**
   ```markdown
   # features/my-feature/DomainEvents.md
   
   ## Events Published
   - MY_EVENT: Triggered when...
   
   ## Events Consumed
   - OTHER_FEATURE.EVENT: Used to...
   ```

6. **Run the build:**
   ```bash
   ./gradlew build
   ```

If you see a forbidden dependency error, that's a signal to use events instead.

## Database Migrations (Flyway)

Each feature owns its database migrations, packaged in its Gradle module. Migrations are applied in deterministic order by Flyway on application startup.

### Migration Ownership

| Feature | Migration Location |
|---------|-------------------|
| Shared (outbox, audit log) | `kernel/src/main/resources/db/migration/shared/` |
| User-Onboarding | `features/user-onboarding/src/main/resources/db/migration/onboarding/` |
| Product-Catalog | `features/product-catalog/src/main/resources/db/migration/catalog/` |
| Bidding | `features/bidding/src/main/resources/db/migration/bidding/` |
| Settlement | `features/settlement/src/main/resources/db/migration/settlement/` |

Ownership is expressed by **folder**, not by file prefix. The locations are listed in `FlywayConfig.LOCATIONS`.

### Naming Convention

**Format:** `V{yyyyMMddHHmm}__{description}.sql` (capital `V`, **two** underscores)

- The version is a UTC timestamp of when the migration was written. Versions are global across all folders, so every new file must use a timestamp later than any migration already applied.
- Timestamps avoid collisions when features add migrations in parallel branches.
- Flyway runs with `validateMigrationNaming(true)`: a misnamed file fails startup instead of being silently skipped.

**Examples:**
- `V202611060001__create_event_outbox_table.sql` — Shared
- `V202611060101__create_users_table.sql` — User-Onboarding
- `V202611071430__add_users_display_name.sql` — a later User-Onboarding change

### Application Order

Flyway sorts all migrations from all locations by version and applies them in that order. When a migration depends on another feature's table (e.g. a foreign key to `users`), its timestamp must be later.

Never edit a migration that has been applied to a shared environment (staging/production); add a new one instead.
`V202611071300__accounts_keycloak_catchup.sql` drops the M1 credential and token tables: no environment had
applied them (every production deploy before it failed at startup), so a drop was safe. After the first
successful production deploy, that option is gone.

### Adding a New Migration

**Step 1: Create the file in your feature's migration folder**
```bash
touch features/user-onboarding/src/main/resources/db/migration/onboarding/V$(date -u +%Y%m%d%H%M)__add_email_index.sql
```

**Step 2: Write the SQL** (PostgreSQL 16)
```sql
CREATE INDEX idx_users_email_lower ON users (lower(email));
```

**Step 3: Verify**
```bash
./gradlew :app:test --tests '*MigrationTest*'   # applies all migrations to a Testcontainers PostgreSQL
```
Update the expected migration count and tables in `MigrationTest` when you add files.

**Step 4: Run locally** (migrations apply on startup)
```bash
docker compose -f docker-compose.dev.yml up -d postgres keycloak mailpit
./gradlew :app:run   # local Keycloak realm and seeded users: deploy/keycloak/README.md
psql -h localhost -U auction -d auction_db -c 'SELECT version, description FROM flyway_schema_history;'
```

### Within Migrations

- Use `CASCADE` only for test/dev schemas; production must be explicit about deletion
- Always include indexes on foreign keys and frequently queried fields
- Document constraints: use `CONSTRAINT fk_...` names so they're visible in `\d`
- Store money as `BIGINT` integer minor units (e.g. `amount_minor`), never `DECIMAL`/floating point
- Partial unique constraints must be `CREATE UNIQUE INDEX ... WHERE ...`, not inline in `CREATE TABLE`
- Add comments for business rules:
  ```sql
  COMMENT ON COLUMN users.email_verified IS 'Email must be verified before bidding (USR-004)';
  ```

### Troubleshooting Migrations

| Problem | Cause | Fix |
|---------|-------|-----|
| `Flyway migration failed: file not found` | Typo in location path | Check `FlywayConfig.kt` locations array matches folder names |
| `Duplicate version: U1` | Two `U1_*.sql` files | Rename one to `U2_*.sql`, update version sequence |
| `Migration error: column already exists` | Idempotent migration ran twice | Wrap in `IF NOT EXISTS` (Postgres doesn't support; use conditional logic or check first) |
| `Migration blocked by dependent FK` | Can't alter table due to foreign key | Defer constraint check or migrate the dependent table first |

### See Also
- [DOMAIN_EVENTS.md](DOMAIN_EVENTS.md) — Outbox table schema and event persistence
- [requirement.md](requirement.md) — Section 4.1: Flyway for versioned migrations
- `FlywayConfig.kt` — Flyway initialization (runs on app startup before Ktor routing)

## Errors You May See

| Message | Meaning | Fix |
|---------|---------|-----|
| `✗ :features:a depends on :features:b` | Features never import each other | Use domain events for communication |
| `:features:x depends on :kernel` | OK: features depend on the kernel for events, audit, errors and token validation | This is allowed |
| `✗ Circular dependency: :features:a → :features:b → :features:a` | Event loop created | Redesign: don't have A listen to B's response events |
| `✗ :app depends on :features:…` | Framework code importing application | Move logic to a feature |
| `✗ No EventHandler registered for type X` | Event type not consumed | Implement handler or check event type name |
| `✗ Event payload deserialization failed` | Version mismatch or schema incompatibility | Verify @Serializable class matches event schema |

## Commands

```bash
./gradlew build                    # Compile, run tests, check boundaries
./gradlew :app:build              # Build backend application
./gradlew :web:build              # Build Next.js frontend
./gradlew test                    # Run all tests
./gradlew :features:product-catalog:test  # Test one feature

# Acceptance scenarios (Cucumber, Testcontainers PostgreSQL, no Keycloak)
./gradlew :features:user-onboarding:test --tests '*AcceptanceSuite*'
./gradlew :features:user-onboarding:test --tests '*AcceptanceSuite*' \
    -Dcucumber.filter.name="Reading your own profile" -Dcucumber.plugin=pretty
```

### Acceptance-test driven development (ATDD)

Feature files under `features/<feature>/src/test/resources/acceptance/` are the executable
specification. The loop: untag (`@wip`) a scenario, run it red, TDD the aggregate, repository and
route until it passes, commit. Tags `@provider` (needs the real Keycloak realm), `@cross-feature`
(needs another milestone's endpoints) and `@ui` (browser suite) are excluded from `./gradlew test`.
Step definitions drive the HTTP API only; nothing reaches into the domain to set up state.

## Key Files

| File | Purpose |
|------|---------|
| [DOMAIN_EVENTS.md](DOMAIN_EVENTS.md) | Complete event architecture guide, patterns, and conventions |
| [RESTRUCTURING.md](RESTRUCTURING.md) | Backend restructuring for AFA compliance |
| [web/RESTRUCTURING.md](web/RESTRUCTURING.md) | Frontend restructuring for AFA compliance |
| `requirements.md` | Business requirements and compliance |
| `features/*/DomainEvents.md` | Event documentation per feature |
| `kernel/src/main/kotlin/com/gavel/kernel/` | Shared events, audit, API errors, Keycloak token validation (single writer) |

## AFA Design Principles

**Navigability:** Every dependency is visible in code; agents can trace the flow
- ✓ Events are explicit: `publisher.publish(...)` is obvious
- ✓ Handlers are explicit: `class MyHandler : EventHandler { ... }`
- ✗ Hidden orchestration via config or reflection

**Single Writer:** Each data entity has one feature that writes it
- ✓ Catalog writes listings; others read via events
- ✓ Settlement writes orders; catalog reads via ORDER_PAID event
- ✗ Multiple features modifying same entity

**Conventional Paths:** Obvious changes follow the template
- ✓ New feature: copy features/template/
- ✓ New event: define in feature/api/Events.kt
- ✓ Consume event: implement in feature/subscribers/
- ✗ Bespoke structures or registration lists

**Type-Safe Boundaries:** Build enforces rules mechanically
- ✓ Gradle forbids cross-feature imports
- ✓ Event handlers validated at compile time
- ✗ String-based dependency injection or reflection tricks

---

**Last updated:** November 2024  
**Version:** 1.0  
**See also:** [DOMAIN_EVENTS.md](DOMAIN_EVENTS.md), [RESTRUCTURING.md](RESTRUCTURING.md)
