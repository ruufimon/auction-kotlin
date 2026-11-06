# Working in this repo

This guide is for coding agents and for people. You will usually arrive with a narrow
prompt and a few nearby files, not a model of the whole system. The repo is designed so
that the locally obvious change is also the globally correct one. If a shortcut is unsafe,
the build or the app refuses it and tells you the supported path.

## The five rules

1. **The conventional path needs fewer decisions than a shortcut.** Copy an existing
   feature folder; you do not need to touch anything else.
2. **Forbidden dependencies fail mechanically.** The build stops with `✗ … → …` and names
   what to do instead.
3. **Every durable value has one obvious writer.** Bank state is written only by
   `app/…/BankClient.kt`. Features read it and send commands.
4. **New product work adds a folder under `features/`.** It never edits a shared list:
   features are discovered from their folder and a reserved file.
5. **Exceptions are narrow, explicit and reviewed as architecture changes.** Changing
   `dune/`, `bank-api/` (including `bank-api/openapi.yaml`) or `BankClient` is one of
   these. Say so in your PR.

Every guardrail behind these rules, and how to see it fire, is listed in
[docs/GUARDRAILS.md](docs/GUARDRAILS.md).

## Where things go

| You want to… | Do this | Do not |
|---|---|---|
| Show something new on a screen | New folder in `features/`, return an `entrypoint(screen, Slot.BANNER / BODY / ACTIONS)` | Edit the feature that owns the screen |
| Add a new screen | New folder in `features/`, return an `entrypoint(name, Slot.SCREEN)` | Register it in a list |
| Read bank state | `ctx.client.read(BankState.Balance)` | Keep your own copy of the balance |
| Change bank state | `ctx.client.send(Transfer(...))` | Write state anywhere else |
| Call the server | `ctx.host<BankHost>().fraudScore(...)` | Build your own HTTP client |
| Use another feature's code | Share it through `bank-api` (rule 5) | Depend on another feature |

## Recipe: add a feature (no other file changes)

1. Copy `features/fraud-banner/` to `features/<your-feature>/`.
2. Rename the package folder, package name, class name and `id` (use the folder name).
3. Put the new fully qualified class name in
   `src/main/resources/META-INF/services/dev.sand.dune.Feature`.
4. Return your entrypoints from `entrypoints()`.
5. Run `./gradlew build`.

You do **not** edit `settings.gradle.kts`, `app/build.gradle.kts` or any other feature.
A feature folder needs no build file; the root build gives it `:dune` and `:bank-api`.

## Errors you may see, and what they mean

| Message starts with | Meaning |
|---|---|
| `✗ :features:a depends on :features:b` | Features never import each other. Share through `bank-api`. |
| `✗ :dune depends on …` | The framework never imports application code. |
| `✗ … depends on :host-server` | The server is a separate deployable. Reach it over HTTP through `BankHost`. |
| `✗ Screen 'x' is provided by a and b` | A screen has one owner. Add to it through a slot instead. |
| `✗ Feature 'x' adds to screen 'y', which no feature provides` | Check the screen name; the message lists the ones that exist. |
| `✗ No handler for command …` | Only `BankClient` handles commands. A new one is a rule 5 change. |
| `✗ No BankHost is wired` | Hosts are provided by `:app`. Use an existing contract. |

## Commands

```
./gradlew build        # compile, run tests, check boundaries
./gradlew :app:run     # print the screens and run one transfer
./gradlew :host-server:run   # serve bank-api/openapi.yaml on localhost:8080
```

## Changing the server contract

`bank-api/openapi.yaml`, `BankHost` and `host-server` change together, in one reviewed PR
(rule 5). Every new error must return Problem Details with a `supportedPath`.
