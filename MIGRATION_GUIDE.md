# Migration Guide: Old Structure → New Structure

If you have uncommitted changes or branches based on the old project structure, use this guide to migrate them.

## Quick Reference: Path Mappings

| Old Path | New Path | Notes |
|----------|----------|-------|
| `shared-kernel/` | `bank-api/` | Renamed module |
| `shared-web/` | `bank-api/web/` | Moved into bank-api |
| `user-onboarding/` | `features/user-onboarding/` | Moved into features |
| `product-catalog/` | `features/product-catalog/` | Moved into features |
| `bidding/` | `features/bidding/` | Moved into features |
| `settlement/` | `features/settlement/` | Moved into features |

## Updating Source Code Imports

If your code imports from these modules, update the package references:

### Before (Old Structure)
```kotlin
// No package changes needed - only directory structure changed
import com.gavel.onboarding.api.UserRegisteredEvent
import com.gavel.web.ApiError
```

### After (New Structure)
```kotlin
// Same imports - no package changes needed
// Only the physical location changed on disk
import com.gavel.onboarding.api.UserRegisteredEvent
import com.gavel.web.ApiError
```

**Note:** Package names remain the same! Only directory structure changed.

## Updating Gradle Dependencies

### Before (Old Structure)
```kotlin
dependencies {
    implementation(project(":shared-kernel"))
    implementation(project(":shared-web"))
    implementation(project(":user-onboarding"))
    implementation(project(":product-catalog"))
    implementation(project(":bidding"))
    implementation(project(":settlement"))
}
```

### After (New Structure)
```kotlin
dependencies {
    implementation(project(":bank-api"))
    implementation(project(":bank-api:web"))
    implementation(project(":features:user-onboarding"))
    implementation(project(":features:product-catalog"))
    implementation(project(":features:bidding"))
    implementation(project(":features:settlement"))
}
```

## Migrating Feature Branches

If you have a branch with changes to one of the moved modules:

### Option 1: Rebase on Main
```bash
# Make sure you're on the main branch with new structure
git checkout main
git pull

# Rebase your feature branch
git checkout your-feature-branch
git rebase main
```

### Option 2: Manual Migration
1. Create a new branch from current main:
   ```bash
   git checkout main
   git pull
   git checkout -b migrated/your-feature-branch
   ```

2. Find the files you changed in the old structure
3. Apply the same changes to the new locations:
   - Old `bidding/src/...` → New `features/bidding/src/...`
   - Old `shared-kernel/src/...` → New `bank-api/src/...`
   - Old `shared-web/src/...` → New `bank-api/web/src/...`

## File Path Changes

Use this reference when relocating your changes:

```
Old Path                              → New Path
────────────────────────────────────────────────────────────────
user-onboarding/src/main/kotlin       → features/user-onboarding/src/main/kotlin
user-onboarding/src/test/kotlin       → features/user-onboarding/src/test/kotlin
user-onboarding/build.gradle.kts      → features/user-onboarding/build.gradle.kts

product-catalog/src/main/kotlin       → features/product-catalog/src/main/kotlin
product-catalog/src/test/kotlin       → features/product-catalog/src/test/kotlin
product-catalog/build.gradle.kts      → features/product-catalog/build.gradle.kts

bidding/src/main/kotlin               → features/bidding/src/main/kotlin
bidding/src/test/kotlin               → features/bidding/src/test/kotlin
bidding/build.gradle.kts              → features/bidding/build.gradle.kts

settlement/src/main/kotlin            → features/settlement/src/main/kotlin
settlement/src/test/kotlin            → features/settlement/src/test/kotlin
settlement/build.gradle.kts           → features/settlement/build.gradle.kts

shared-kernel/src/main/kotlin         → bank-api/src/main/kotlin
shared-kernel/src/test/kotlin         → bank-api/src/test/kotlin
shared-kernel/build.gradle.kts        → bank-api/build.gradle.kts

shared-web/src/main/kotlin            → bank-api/web/src/main/kotlin
shared-web/src/test/kotlin            → bank-api/web/src/test/kotlin
shared-web/build.gradle.kts           → bank-api/web/build.gradle.kts
```

## Gradle Configuration Changes

### settings.gradle.kts
Update the include statements:

```diff
- include("shared-kernel")
- include("shared-web")
- include("user-onboarding")
- include("product-catalog")
- include("bidding")
- include("settlement")

+ include("bank-api")
+ include("bank-api:web")
+ include("features:user-onboarding")
+ include("features:product-catalog")
+ include("features:bidding")
+ include("features:settlement")
```

### app/build.gradle.kts
Update all project dependencies:

```diff
  dependencies {
-     implementation(project(":shared-kernel"))
-     implementation(project(":shared-web"))
-     implementation(project(":user-onboarding"))
-     implementation(project(":product-catalog"))
-     implementation(project(":bidding"))
-     implementation(project(":settlement"))

+     implementation(project(":bank-api"))
+     implementation(project(":bank-api:web"))
+     implementation(project(":features:user-onboarding"))
+     implementation(project(":features:product-catalog"))
+     implementation(project(":features:bidding"))
+     implementation(project(":features:settlement"))
  }
```

### Feature build.gradle.kts files
Update references to shared modules:

```diff
  dependencies {
-     implementation(project(":shared-web"))
+     implementation(project(":bank-api:web"))

-     implementation(project(":user-onboarding"))
+     implementation(project(":features:user-onboarding"))

-     implementation(project(":bidding"))
+     implementation(project(":features:bidding"))

-     implementation(project(":product-catalog"))
+     implementation(project(":features:product-catalog"))
  }
```

## Verifying Your Migration

After migrating changes to the new structure:

```bash
# Clean build to ensure no caching issues
./gradlew clean build

# Run tests
./gradlew test

# Check for compilation errors
./gradlew compileKotlin
```

## Common Issues & Solutions

### "Project not found: ':shared-kernel'"
**Cause:** Old build file still references old module name
**Solution:** Update to `:bank-api`

### "Project not found: ':user-onboarding'"
**Cause:** Old build file still references old module path
**Solution:** Update to `:features:user-onboarding`

### Compilation errors after migration
**Cause:** Package names haven't changed, but Gradle can't find the modules
**Solution:** Run `./gradlew clean build` to clear cached information

### IDE not recognizing modules
**Cause:** IDE cache is stale
**Solution:** 
- IntelliJ: File → Invalidate Caches → Invalidate and Restart
- VS Code: Reload Window (Cmd+K Cmd+W)

## Questions or Issues?

If you encounter problems during migration:

1. Check that all `:shared-kernel` references are updated to `:bank-api`
2. Check that all `:shared-web` references are updated to `:bank-api:web`
3. Check that all feature module references use `:features:*` format
4. Run `./gradlew clean build --refresh-dependencies` to clear caches
5. Refer to [RESTRUCTURING.md](RESTRUCTURING.md) for the complete restructuring details

## Summary

The migration is straightforward:
- **Package names stay the same** (no code changes needed)
- **Only Gradle module paths change** in `build.gradle.kts` files
- **File paths change on disk** but content is identical
- **All tests pass** after migration
