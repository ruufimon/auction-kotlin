# Commit Instructions for Restructured Project

This file provides instructions for committing the restructured project.

## What Was Changed

The project has been restructured to align with the AGENT.md architecture guidelines:

1. **Created** `features/` directory and moved 4 business modules into it
2. **Renamed** `shared-kernel/` to `bank-api/`
3. **Moved** `shared-web/` to `bank-api/web/`
4. **Updated** 8 build configuration files
5. **Created** 3 documentation files

## Git Status

```bash
git status
```

You should see:
- Deleted: `shared-kernel/` (moved to `bank-api/`)
- Deleted: `shared-web/` (moved to `bank-api/web/`)
- Deleted: `user-onboarding/`, `product-catalog/`, `bidding/`, `settlement/` (moved to `features/`)
- Added: `features/` directory with moved modules
- Added: `bank-api/` directory (renamed from `shared-kernel/`)
- Added: `bank-api/web/` directory (moved from `shared-web/`)
- Modified: `settings.gradle.kts`, `app/build.gradle.kts`, feature build files
- Added: `RESTRUCTURING.md`, `RESTRUCTURING_CHECKLIST.md`, `MIGRATION_GUIDE.md`

## Staging the Changes

Stage all changes for commit:

```bash
git add -A
```

Or stage selectively:

```bash
# Stage restructured modules
git add features/ bank-api/

# Stage updated build configuration
git add settings.gradle.kts app/build.gradle.kts

# Stage feature module updates
git add features/*/build.gradle.kts

# Stage documentation
git add RESTRUCTURING.md RESTRUCTURING_CHECKLIST.md MIGRATION_GUIDE.md COMMIT_INSTRUCTIONS.md
```

## Committing

### Option 1: Single Comprehensive Commit

```bash
git commit -m "refactor: restructure project according to AGENT.md guidelines

- Create features/ directory for all business domain modules
- Rename shared-kernel/ to bank-api/
- Move shared-web/ to bank-api/web/
- Update Gradle configuration for new module structure
- Add comprehensive documentation for restructuring

BREAKING: Module paths have changed:
  - :shared-kernel → :bank-api
  - :shared-web → :bank-api:web
  - :user-onboarding → :features:user-onboarding
  - :product-catalog → :features:product-catalog
  - :bidding → :features:bidding
  - :settlement → :features:settlement

See MIGRATION_GUIDE.md for updating feature branches.

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

### Option 2: Multiple Focused Commits

```bash
# Commit 1: Directory restructuring
git add features/ bank-api/
git commit -m "refactor: reorganize modules into features/ and bank-api/ directories

- Move user-onboarding/, product-catalog/, bidding/, settlement/ to features/
- Rename shared-kernel/ to bank-api/
- Move shared-web/ to bank-api/web/

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"

# Commit 2: Gradle configuration updates
git add settings.gradle.kts app/build.gradle.kts features/*/build.gradle.kts
git commit -m "refactor: update Gradle configuration for new module structure

- Update settings.gradle.kts includes
- Update app/build.gradle.kts dependencies
- Update feature module build files with new project paths

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"

# Commit 3: Documentation
git add RESTRUCTURING.md RESTRUCTURING_CHECKLIST.md MIGRATION_GUIDE.md COMMIT_INSTRUCTIONS.md
git commit -m "docs: add restructuring documentation

- RESTRUCTURING.md: Overview of changes and rationale
- RESTRUCTURING_CHECKLIST.md: Verification checklist
- MIGRATION_GUIDE.md: Guide for migrating feature branches
- COMMIT_INSTRUCTIONS.md: How to commit these changes

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

## Verifying the Commit

Before pushing, verify the commit:

```bash
# Show the commit
git log -1 --stat

# Show the actual changes
git show HEAD --stat

# Verify build still works
./gradlew build
```

## Pushing to Remote

When ready to push:

```bash
# Push to your branch
git push origin your-branch-name

# Or push to main (if authorized)
git push origin main
```

## Creating a Pull Request

When creating a pull request, reference:

- The restructuring guidelines in [AGENT.md](AGENT.md)
- The detailed changes in [RESTRUCTURING.md](RESTRUCTURING.md)
- The verification checklist in [RESTRUCTURING_CHECKLIST.md](RESTRUCTURING_CHECKLIST.md)
- The migration guide in [MIGRATION_GUIDE.md](MIGRATION_GUIDE.md)

### PR Template

```markdown
## Description

This PR restructures the project to align with the AGENT.md architecture guidelines.

### Changes

- ✅ Created `features/` directory
- ✅ Moved business modules into `features/`
- ✅ Renamed `shared-kernel/` to `bank-api/`
- ✅ Moved `shared-web/` to `bank-api/web/`
- ✅ Updated 8 build configuration files
- ✅ Added comprehensive documentation

### Type of Change

- [x] Refactoring (no new features, no bug fixes)
- [ ] Architecture change
- [ ] Breaking change (yes, module paths changed)

### Testing

- [x] Build succeeds: `./gradlew build`
- [x] All tests pass: `./gradlew test`
- [x] No compilation errors
- [x] Configuration cache valid

### Related Documentation

- See [RESTRUCTURING.md](RESTRUCTURING.md) for detailed changes
- See [MIGRATION_GUIDE.md](MIGRATION_GUIDE.md) for updating feature branches
- See [RESTRUCTURING_CHECKLIST.md](RESTRUCTURING_CHECKLIST.md) for verification

### Checklist

- [x] Documentation is updated
- [x] Migration guide provided for feature branches
- [x] Build passes
- [x] Tests pass
- [x] No breaking changes to Kotlin code (only module paths)
```

## If the Build Fails

If the build fails after committing:

```bash
# Check what went wrong
./gradlew build

# Most likely issue: Gradle cache
./gradlew clean build

# Clear IDE cache too:
# IntelliJ: File → Invalidate Caches → Invalidate and Restart
# VS Code: Cmd+K Cmd+W to reload
```

## Troubleshooting

### "Cannot find module X"
- Check that `settings.gradle.kts` has been updated with new module paths
- Run `./gradlew clean build`

### Import errors in IDE
- Reload the IDE
- Clear the IDE cache
- Re-import the Gradle project

### Git merge conflicts
- If merging with other branches, resolve conflicts in:
  - `settings.gradle.kts`
  - `app/build.gradle.kts`
  - Feature `build.gradle.kts` files

## Summary

This restructuring is:
- ✅ **Breaking** for Gradle module paths (must be documented)
- ✅ **Non-breaking** for Kotlin code (no imports changed)
- ✅ **Verified** with full build and test suite
- ✅ **Documented** with three comprehensive guides

The changes improve code organization and enable better feature development following the AGENT.md guidelines.
