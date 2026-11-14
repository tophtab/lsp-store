# Code hotspot refactor validation

Validated on 2026-11-14 against `master` commit `4426261` (1.7.1), on branch
`refactor/code-hotspots`. The implementation uses the existing dependencies and
keeps the installation, update, persistence and backup formats compatible.

## Automated checks

The final production tree passed:

```sh
./gradlew :app:lintDebug :app:testDebugUnitTest --stacktrace
git diff --check
```

| Result | Baseline | Refactor |
| --- | ---: | ---: |
| Passing unit tests | 451 | 484 |
| Skipped unit tests | 4 | 4 |
| Failures / errors | 0 / 0 | 0 / 0 |
| Lint warnings | 22 | 22 |

Lint issues were compared as a multiset of `(id, severity, message)`, rather
than by their line numbers after files moved. All 22 match the baseline:
8 `NewerVersionAvailable`, 8 `GradleDependency`, 2 `UseKtx`,
2 `AndroidGradlePluginVersion`, 1 `NotShrinkingResources`, and 1 `OldTargetApi`.

The regression coverage includes:

- Direct installations, renamed packages, several installed copies, fallback
  after the confirmed copy is uninstalled, legacy preference keys, same-code
  reuploads, ignored/skipped updates, pre-releases and pending classification.
  Pending installed copies retain the baseline's known idle classification;
  pending records also have their separate creation-time order.
- Raw-list traversal counts and index reuse across successive snapshots. The
  assertions use call counts, with no machine-dependent timing thresholds.
- Obsolete snapshot cancellation; byte-only page-state deduplication; stage,
  cancellation, log and completion updates; reopening the same picker;
  switching modules; refreshing the same release tag; and stale failures.
- Synchronous candidate/decision consistency, the single-APK path, probe
  refinement and probe-failure fallback. Cancellation continues to propagate.
- Nested folds, duplicate summaries, trailing text, nested lists/tables,
  large tables, images/captions, relative links, theme images, opaque code,
  injected elements and incomplete HTML. Document-cache tests cover reentry,
  recolouring, cancellation and older parses finishing after newer ones.
- Immediate settings writes, duplicate suppression, rapid changes back to
  the original value and independent settings proceeding during a slow write.
- All original 33 resolver test bodies are byte-for-byte preserved in four groups:
  corpus (7), ranking (10), device matching (6), and probe rules (10). The shared
  corpus fixture also has a concurrent-access regression.

## Emulator fixture and measurement method

Both debug APKs were built from their respective source trees and run on the
same temporary copy of `lsp82-qa`: Android 15 / API 35, x86_64, software
rendering, 1080×2340 at 440 dpi, and 1536 MB configured RAM. The original AVD
was preserved. Fixtures and instrumentation lived in isolated temporary
worktrees; production source contains no QA receiver, fixture repository or
composition logger.

The fixture contains 400 repository modules, 400 known installed modules,
800 ordinary installed packages and 40 unknown modules. `qa.module0000` has
two releases, two APKs, an author, nested folds with repeated titles and a
600-body-row README table plus its header and trailing paragraph.

Composition counters use `SideEffect` at the page, Hero, install-control,
installed-row and table-row boundaries. A separate counter records installed
search filtering. A temporary debug receiver drives the real install registry:
200 ms preparing, then 100 byte updates at 80 ms intervals, held at the end
until cancellation. Installed measurements run two packages concurrently;
detail measurements run one. Counts include stage transitions. Compose can
coalesce emissions, so a counter measures completed compositions.

Refresh measurements use eight Installed-tab taps, with 0.4-second pauses between commands.
The timed span is baseline `InstalledViewModel.recompute()` or the refactor's
catalog/snapshot calculation. Repository/copy index preparation is outside
the refactor's timed span and is covered separately by traversal-count tests.
README entry measurements start before opening the detail page and use two
matching 800 ms swipes to bring the table into view. Gradle builds and tests
were stopped during the timing runs.

Before each run, log counters and `dumpsys gfxinfo` were reset. Frame duration
is `FrameCompleted - IntendedVsync` from valid `framestats` rows (`Flags == 0`).
Percentiles use sorted samples at `floor(count × percentile)`, clamped to the
last sample. Frame counts reflect the available buffer, rather than an equal
number of rendered frames for every scenario. The complete recorded counters
and percentiles are in [code-hotspots-metrics.json](validation/code-hotspots-metrics.json).

## Calculation and composition results

| Scenario / counter | Baseline | Refactor |
| --- | ---: | ---: |
| Concurrent progress: installed page | 100 | 2 |
| Concurrent progress: installed search filter | 100 | 0 |
| Concurrent progress: active row 0000 | 100 | 101 |
| Concurrent progress: active row 0001 | 100 | 101 |
| Concurrent progress: other installed rows | 0 | 0 |
| Detail progress: detail page | 101 | 2 |
| Detail progress: detail content | 101 | 2 |
| Detail progress: Hero | 101 | 0 |
| Detail progress: install controls | 101 | 101 |
| README entry: table-row compositions | 601 | 19 |
| Eight refresh taps: list calculations | 12 | 8 |
| Eight refresh taps: calculation median (ms) | 171.911 | 11.118 |
| Eight refresh taps: calculation p90 (ms) | 195.936 | 13.428 |
| Eight refresh taps: calculation p95 (ms) | 226.272 | 13.428 |
| Eight refresh taps: installed page compositions | 19 | 32 |
| Eight refresh taps: installed search filter | 19 | 0 |

Byte updates stay within the active rows or install controls. Stage changes
still update the page. The long table composes visible/prefetched rows, and
refresh classification reuses the prepared indexes. Refresh has more page
compositions as loading and completed snapshots arrive separately; unchanged
lists still reuse their search results.

| Frame scenario | Samples before / after | p50 before / after (ms) | p90 before / after (ms) | p95 before / after (ms) |
| --- | ---: | ---: | ---: | ---: |
| Installed progress | 120 / 120 | 66.981 / 67.138 | 74.608 / 85.199 | 83.714 / 100.315 |
| Refresh | 53 / 95 | 67.146 / 67.159 | 207.853 / 85.398 | 261.604 / 94.667 |
| Detail progress | 119 / 120 | 48.620 / 34.700 | 50.572 / 50.389 | 51.243 / 62.117 |
| README entry | 86 / 115 | 34.446 / 21.022 | 267.018 / 33.734 | 343.606 / 126.876 |

These software-rendered debug runs have variable tail timings. Installed and
detail-progress p95 rose, while refresh and README tails fell. The composition
and traversal checks establish the reduced work; the frame table records this
emulator run.

## UI regressions

| Interaction | Observed result |
| --- | --- |
| Search `001`, scroll, open detail and back on both list pages | The query and every visible row coordinate were identical on return, including the installed list near its end. |
| Two concurrent downloads; cancel each progress pill | Cancelling the first restored its Update button while the second stayed active; cancelling the second restored its button too. |
| Detail download; cancel the install button | Byte totals reached 9.5 MB / 9.5 MB, and cancellation immediately restored Update to 2.0. |
| Select the v1.x release and another APK | The picker displayed 1.9 and `module-arm64.apk`; the install button displayed Update to 1.9. |
| Detail → author → back | Release/APK selections and all expanded fold choices were retained. |
| Expand nested and duplicate-title folds | Each repeated title controlled its own body. Collapsing a parent hid descendants; reopening retained the nested expansion. |
| Switch system light → dark → light within detail | The document anchor and expanded nested/duplicate folds survived both theme changes. |
| Scroll the full long README | Row 599 and the following README tail were reachable. |
| Choose app Dark theme with system night mode off, then force-stop and reopen | Dark remained selected; the stored `theme_mode` was `DARK`. |
| Type `qatest` in Microsoft region, then force-stop and reopen | The field retained `qatest` without a Save action. |
| Ignore a repository module → Settings → Ignored repository modules → Unignore | The card disappeared from the ignored list and the module was visible in Repository again. |
| Ignore version 2.0 → Settings → Ignored update modules → Unignore | The version card disappeared and the installed row again offered the 1.0 → 2.0 update. |
| Permanently ignore updates → Ignored update modules → Restore updates | The disabled-update card disappeared and the installed row again offered the update. |
| Favourite a module → Favourites → detail → back → remove | The list opened the correct detail, retained the row on return, then showed its empty state after removal. |
| Recently viewed → detail → back | The viewed module opened the correct detail and remained in Recently viewed on return. |

Temporary settings were restored to System theme and Google translation, with
the test region cleared. The ignored, skipped and favourite entries created by
these checks were restored or removed. The machine-readable record includes
the search, README, detail, settings and library regression observations.

The baseline also reproduced a displaced scroll anchor when returning near
the end of an installed search list. Root destinations now own their bottom
navigation scaffold, so the bar and list enter with their final viewport in
the same layout. The saved-state and tab-navigation callbacks are retained.

## Kotlin file sizes

Counts include every `*.kt` file under `app/src`, using physical source lines
(`splitlines()`), including blank/comment lines. The baseline is read directly
from git commit `4426261`; the refactor is counted from the production tree,
including added test files and excluding the temporary QA worktrees.

| Scope | Baseline | Refactor | Change |
| --- | ---: | ---: | ---: |
| Kotlin files | 228 | 250 | +22 |
| Main source lines | 24,394 | 24,983 | +589 |
| Test source lines | 9,559 | 10,217 | +658 |
| Total Kotlin lines | 33,953 | 35,200 | +1,247 |
| Largest Kotlin file | 756 | 523 | −233 |

The total grows with the indexes, structure model and regression coverage,
while the largest file shrinks. Paths below use `main/` for
`app/src/main/java/io/github/tophtab/lspstore/` and `test/` for
`app/src/test/java/io/github/tophtab/lspstore/`.

### Baseline top ten

| Rank | File | Lines |
| --- | --- | ---: |
| 1 | `test/VariantResolverTest.kt` | 756 |
| 2 | `main/ui/screens/detail/DetailSections.kt` | 669 |
| 3 | `main/ui/screens/detail/VersionPickerParts.kt` | 573 |
| 4 | `main/data/repository/LibraryRepositoryImpl.kt` | 521 |
| 5 | `main/domain/variant/VariantResolver.kt` | 519 |
| 6 | `main/MainActivity.kt` | 506 |
| 7 | `test/VersionLogicTest.kt` | 504 |
| 8 | `main/ui/screens/library/LibraryScreens.kt` | 504 |
| 9 | `test/BatchInstallUseCaseTest.kt` | 492 |
| 10 | `main/ui/screens/installed/InstalledViewModel.kt` | 488 |

### Refactor top ten

| Rank | File | Lines |
| --- | --- | ---: |
| 1 | `main/domain/variant/VariantResolver.kt` | 523 |
| 2 | `main/data/repository/LibraryRepositoryImpl.kt` | 521 |
| 3 | `test/VersionLogicTest.kt` | 504 |
| 4 | `main/MainActivity.kt` | 502 |
| 5 | `test/BatchInstallUseCaseTest.kt` | 492 |
| 6 | `test/ResumableFetchTest.kt` | 481 |
| 7 | `main/ui/screens/onboarding/OnboardingScreen.kt` | 481 |
| 8 | `test/ResolveVariantUseCaseTest.kt` | 479 |
| 9 | `main/ui/screens/detail/ModuleDetailViewModel.kt` | 471 |
| 10 | `main/ui/components/ModuleCards.kt` | 457 |
