# Contributing

## Prerequisites

- JDK 17+ on `PATH`. Unit tests run on a JDK 24 toolchain Gradle provisions itself;
  [Requirements](docs/TURNKEY_SUPPORT.md#requirements) in docs/TURNKEY_SUPPORT.md has the reason.
- Android SDK: point `sdk.dir` at it in `local.properties`, or set `ANDROID_HOME`.

## Build & test

```bash
./gradlew build                                                # everything
./gradlew testDebugUnitTest checkNoSkippedUnitTests            # unit tests + skipped-test gate (the Test check)
./gradlew detektDebug detektDebugUnitTest -PdetektAutoCorrect  # the Analysis check's detekt step, auto-fixing formatting in place
./gradlew lint dokkaGenerate                                   # its other two steps
```

Android Studio's *Reformat Code* matches the style (`ktlint.code_style` in `config/detekt/detekt.yml`,
mirrored in `.editorconfig`). A non-formatting finding is fixed in the code, or suppressed with
`@Suppress("RuleName")` at the smallest scope with a reason; `detekt-baseline.xml` files only
shrink, and the Analysis check fails if one grows.

## What CI runs

Every PR gets five checks, all required for a merge into `main`:

| Check | What it does | Locally |
|---|---|---|
| Test | Unit tests for every module, the skipped-test gate and the vendor-free classpath check. | `./gradlew testDebugUnitTest checkNoSkippedUnitTests checkVendorFreeClasspaths` |
| Analysis | detekt with type resolution (ktlint rules included), Android Lint and Dokka; the red step names the tool. | `./gradlew detektDebug detektDebugUnitTest lint dokkaGenerate` |
| R8 | A minified release build of the sample against the SDK modules, with no missing classes, and the Kotlin floor check. | `./gradlew :app:assembleRelease checkKotlinFloor`; `app/build/outputs/mapping/release/missing_rules.txt` must be empty |
| Release | Lints the workflows, checks that every check name the ruleset requires exists as a job, and refuses path filters on the triggers. On a version bump it also checks the release files: a dated `CHANGELOG.md` heading with content, the install lines, and that the version is unpublished. | Nothing to run |
| Changelog | A change under a module's `src/main` needs an entry under `## [Unreleased]` in `CHANGELOG.md`, or the `skip-changelog` label. | Nothing to run |

## Before opening a PR

- Everything above green locally.
- Commits must be **signed** and authored with an email linked to your GitHub account: the branch
  ruleset refuses to merge unsigned or unattributed commits.
- If you changed the public API, update `docs/METHODS.md` to match, and the module's README too
  when the contract is specific to that module.
- `RAIN_105`, `RAIN_303` and `RAIN_304` are unassigned and stay so: a new 1xx code starts at
  `RAIN_106` and a new 3xx code at `RAIN_306`.
- A change under a module's `src/main` needs a line under `## [Unreleased]` in `CHANGELOG.md` (its
  header has the format), or the `skip-changelog` label when no host can notice the change.
- A new member on `WalletProvider`, `ProviderDescriptor` or `RainClient` ships with a default body
  wherever one makes sense; an abstract member breaks every host implementation at compile time.

## Pull requests from outside Rain

Rain develops the SDK in a private repository and publishes each release here as a single commit,
so a PR on this repository is never merged as it is. A maintainer applies your change in the private
repository, credits you with a `Co-authored-by:` trailer, and closes your PR when the release that
carries it is published. The checklist above still applies, except the `CHANGELOG.md` line, which
the maintainer adds.
