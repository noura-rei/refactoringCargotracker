# Baseline — Build Failure Report

## Environment

| Item | Value |
|---|---|
| OS | Windows 11 (10.0, amd64) |
| Shell | Windows PowerShell 5.1 |
| Java version | 27, Oracle Corporation (`C:\Users\pc\.jdks\openjdk-27`) |
| Maven | Apache Maven 3.9.16 |
| Locale | fr_FR |
| Platform encoding | UTF-8 |
| Project | `org.eclipse.ee4j:cargo-tracker` 3.1-SNAPSHOT (packaging: `war`) |
| Working directory | `C:\Users\pc\IdeaProjects\refactoringCargotracker` |
| Git branch | `refactoring` (created from `master`; `main` does not exist) |

## Commands executed and outcomes

| # | Command | Result |
|---|---|---|
| 1 | `git checkout main` | `error: pathspec 'main' did not match any file(s) known to git` |
| 2 | `git branch` | Only `* master` exists (no `main`) |
| 3 | `git checkout master` | `Already on 'master'` / up to date with `origin/master` |
| 4 | `git pull origin master` | `Already up to date.` |
| 5 | `git checkout -b main origin/main` | `fatal: 'origin/main' is not a commit and a branch 'main' cannot be created from it` |
| 6 | `git checkout -b refactoring` | `Switched to a new branch 'refactoring'` |
| 7 | `./mvnw clean test` | **BUILD FAILURE** (20.058 s) — Spotless `apply` fails |
| 8 | `.:mvnw .cmd -version` | `.:mvnw : Lecteur introuvable. Il n'existe aucun lecteur nommé « . ».` (DriveNotFound) |
| 9 | `./mvnw .cmd -version` | Maven 3.9.16 / Java 27 reported OK |
| 10 | `.\\mvnw .cmd -version` | Maven 3.9.16 / Java 27 reported OK |
| 11 | `./mvnw clean test` | **BUILD FAILURE** (4.506 s) — same Spotless error |
| 12 | `.\\mvnw.cmd clean package` | **BUILD FAILURE** (3.438 s) — same Spotless error |
| 13 | `.\\mvnw.cmd splotless:check` | **BUILD FAILURE** (4.518 s) — `No plugin found for prefix 'splotless'` |
| 14 | `.\\mvnw.cmd spotless:check` | **BUILD FAILURE** (1.912 s) — same Spotless error |

**No build ever reached the test phase.** Tests executed: **0** (zero). The `clean test` / `clean package` lifecycle stops during the `spotless:apply` goal, bound to the default lifecycle, before `compile`, `test`, or `package`.

## Primary error (blocks all builds)


Cause: `splotless:check` is a typo; the correct prefix `spotless:check` was used next and reproduced the primary error.

## Warnings observed (not fatal)

## Error tally

| Category | Count |
|---|---|
| Build failures | 5 (`clean test` ×2, `clean package` ×1, `spotless:check` ×1, `splotless:check` ×1) |
| Distinct root-cause errors | 3: (1) JDK 27 vs google-java-format `NoSuchMethodError`; (2) `splotless` unknown plugin prefix; (3) `.:mvnw` PowerShell DriveNotFound (shell typo) |
| Git errors | 2 (`pathspec 'main'`, `fatal: 'origin/main' is not a commit`) |
| Tests executed | **0** |
| Tests passed / failed / skipped | 0 / 0 / 0 |
| Files reported failing formatting | 1 (`src\main\java\org\eclipse\cargotracker\application\ApplicationEvents.java`) — first file hit; the run aborts immediately |

## Notes / observations

- The repository default branch is `master`, not `main`. Both attempts to work with `main` failed; the `refactoring` branch was successfully created from `master`.
- The build never compiled or tested any code. It is **not possible to state a test count** from this log because the Spotless goal (bound early in the default lifecycle) aborts the build before the `test` phase. Baseline test count = 0 executed.
- The failure is reproducible and deterministic: identical `NoSuchMethodError` on every Maven invocation touching Spotless.
- Likely remediation directions (for later, not part of this baseline): run Maven on a JDK supported by google-java-format 1.18.1 (e.g., JDK 17/21), or upgrade Spotless/google-java-format to a version compatible with JDK 27, or skip Spotless (`-Dspotless.check.skip=true` / `-Dspotless.apply.skip=true`) to obtain a real compile/test baseline.****