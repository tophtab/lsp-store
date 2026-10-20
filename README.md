# LSPosed Store

An Android app store and updater for LSPosed modules, modelled on
[komi-store](https://github.com/komi-store/komi-store) but scoped to the LSPosed
module ecosystem instead of all GitHub releases.

It browses and installs modules from the LSPosed module repository, and it checks
the modules already installed on the device for updates and updates them in place.

## Data source

Everything comes from `modules.lsposed.org`, the same backend the LSPosed Manager
app uses for its "Repository" tab. There is no scraping: the site exposes JSON.

| Purpose | Endpoint |
| --- | --- |
| Paginated module index (30 per page, ~39 pages) | `GET /module-list/{page}.json` |
| Single module with all releases and readme | `GET /module/{packageName}.json` |
| Full index (used by the official app) | `GET /modules.json` |
| Mirrors (fallback, same paths) | `modules-blogcdn.lsposed.org`, `modules-cloudflare.lsposed.org` |

`LsposedRepoApi` tries the origin first and rotates through the mirrors on failure,
mirroring `RepoLoader.java` in the LSPosed source.

### Release channels

LSPosed has one global "update channel" preference with three values. Each module
can publish releases into three streams and the repository computes a latest tag
for each. The channel selects which stream an installed module is compared against.

| Channel | Field compared |
| --- | --- |
| Stable | `latestRelease` |
| Beta | `latestBetaRelease` (falls back to stable) |
| Nightly | `latestSnapshotRelease` (falls back to beta, then stable) |

Tags are `versionCode-versionName`, e.g. `4019-3.4.3`. A module is upgradable when
the remote `versionCode` is higher, or equal with a different `versionName`
(`ModuleVersion.isNewerThan`, matching `RepoLoader.ModuleVersion.upgradable`).

## Features

- Repository: an inline search field over the full module index, an updates
  banner plus tabs for recently updated and most starred modules.
- Installed: the app opens here by default, and the bottom bar is now
  Installed / Repository / Settings. Every installed LSPosed module (legacy
  `xposedmodule` and modern libxposed `XposedProvider`) is grouped into updates /
  up to date / not in repository, with single and serialized batch update.
  Up-to-date rows expose komi-style open and more-actions controls, with app info
  and uninstall in the overflow sheet.
- Module detail: readme rendered as Compose markdown blocks (headings, code,
  lists, tables, collapsible details, images), release / APK pickers with
  per-asset download, install / update / reinstall, share, skip this
  version, open app, app info, uninstall.
- Library: favourites, recently viewed, ignored update modules.
- Onboarding: first-run welcome, appearance and permission setup (notifications and
  install-unknown-apps), with a persisted completion flag.
- Automatic Android backup excludes translation API keys.
- Localization: 14 languages (English, Arabic, Bangla, Spanish, French, Hindi,
  Italian, Japanese, Korean, Polish, Russian, Turkish, Simplified and Traditional
  Chinese), plus Android 13+ per-app language selection.
- Mirror picker: choose a preferred repository mirror or keep it automatic, with a
  latency test for each host; failures still fall back to the other mirrors.
- Translation: translate a module description with Google (keyless), LibreTranslate,
  DeepL or Microsoft, with a small in-memory cache.
- External import: installed LSPosed modules that are not in the repository can be
  linked to a repository module so update checks still work; the APK must still
  match the installed package for an in-place update.
- Author pages: tap the contributor on a detail screen to see every module they
  publish.
- Variant picker: when a release ships more than one APK, the app asks which one to
  install instead of guessing. Batch and background updates pick the asset that
  matches the device ABI.
- Download controls: streamed downloads with progress, pause, resume (HTTP Range)
  and cancel. Cache entries are keyed by asset identity, not by file name, so
  modules that both ship `app-release.apk` cannot overwrite each other. A
  foreground service keeps downloads alive in the background and shows progress
  with a cancel-all action in the notification shade.
- Diagnostics: app/device info, module counts, cache sizes, current settings, plus
  copy and share for bug reports; an open-source licenses screen.
- Storage: the index cache and downloaded APKs can be cleared separately.
- Background checks: a WorkManager periodic job (default 12h, configurable) refreshes
  the index, finds updates and posts a notification; with root it can update silently.
  Rescheduled on boot via `BootReceiver`. Settings has a "Check for updates now"
  button that runs the same worker once and reports the result.
- Install checks: the APK is only inspected for things that mean it cannot
  work *at all* on this device (minSdk, native ABI, LSPosed API level, and
  whether the archive is an APK). Replacing an installed app — signature
  changes, downgrades, installer policy — is left to the installer and
  PackageManager, so a patched installer or a rooted setup can do things the
  stock installer would refuse.
- Download mirror: falls back from assets.lsposed.org to the original GitHub asset
  URL if the CDN fails.
- Install: the APK is handed to whatever installer the device has registered for
  APKs (InstallerX Revived, Dhizuku, AppManager, the stock installer, ...) through
  a `VIEW` intent, so third-party installers work without any special support.
  Delegated installs are serialized: one at a time, released by the package
  broadcast or a 15 s timeout. Only the foreground download notification is
  shown; it disappears as soon as the download finishes, and handing the APK to
  the installer does not leave an extra notification behind. The hand-off is
  persisted as a pending-install state instead (its own Installed section, a
  chip + disabled button in Details), cleared by the package broadcast.
  Optional silent install via `su` still completes in-process.
- Detail extras: LSPosed hook scope, release notes and additional contributors are
  shown alongside the readme and release list.
- Offline / startup: the last saved module index and installed-module scan are
  painted immediately, then refreshed in the background. Refresh first tries the
  single-file `/modules.json` endpoint used by LSPosed Manager and falls back to
  the paginated index. A failed refresh leaves the cached content visible; the
  repository index cache is also used offline.
- Appearance: cyan theme, system / light / dark, AMOLED black plus optional
  Material You dynamic color.
- Settings: appearance, update channel, background-check toggle and interval,
  auto-update, root install, translation, library shortcuts, cache,
  about.

## Look and feel

The UI follows komi-store's classic look with a Material 3 "icon cyan" scheme:

- All tonal palettes are anchored to the launcher icon's cyan (HCT hue 194.8)
  using Material Color Utilities. Unlike the stock TonalSpot output, the
  neutral palettes keep that hue at low chroma, so backgrounds and containers
  stay visibly cyan (`surface #F1FBFA`, `surfaceContainer #E6F0EF`) instead of
  near-neutral off-white.
- The brand colour is the icon background itself: `primary #CDF2F0` in both
  light and dark mode, paired with `onPrimary #00363A` so buttons, segmented
  controls, switches and the bottom-bar indicator pill use the exact icon
  cyan while text stays readable. Foreground accents (links, badges, progress,
  focused fields) use `onPrimaryContainer #00363A`.
- Geist / Geist Mono, 16dp outlined panels, 10dp controls, a standard top bar
  with a lowercase subtitle and a 72dp icon-over-label bottom bar. AMOLED and
  Material You are available in Appearance.
- Layout: width-capped staggered feed (one column on phones, more on tablets),
  full-width module cards, section-head + grouped-row settings, and a search
  field above the installed list.

All fonts are bundled under `res/font` (SIL OFL 1.1). Compose building blocks
live in `ui/design` (`KomiSurface`, `KomiText`, `KomiTopBar`, `KomiBottomBar`,
`KomiSegmented`, `KomiChip`, `KomiButton`, `KomiSettings*`, ...), so every screen
shares one visual language.

## Architecture

Single Gradle module (`:app`), package `org.lspstore`, Clean-Architecture-shaped
packages. Manual DI through `AppContainer`, exposed to Compose via
`LocalAppContainer`. No Hilt/Room to keep the surface small.

```
app/src/main/java/org/lspstore/
  data/
    remote/        LsposedRepoApi, Ktor client factory, DTOs, mappers
    repository/    ModuleRepositoryImpl (index cache), InstalledModulesRepositoryImpl,
                   SettingsRepositoryImpl, LibraryRepositoryImpl,
                   PendingInstallRepositoryImpl (delegated installs)
    install/       ApkInstaller (download, mirror fallback, delegation, su),
                   ApkGate (minSdk/ABI/API/package sanity), PackageEventReceiver,
                   SystemInstallSerializer, DownloadService
    translation/   TranslationRepository (Google / LibreTranslate / DeepL / Microsoft)
    work/          UpdateCheckWorker, UpdateScheduler, UpdateNotifier, BootReceiver
  domain/
    model/         Module, ModuleDetail, Release, ModuleVersion, InstalledModule,
                   UpdateInfo, channels, sort, theme
    usecase/       UpdateChecker (channel + ignored updates aware)
  ui/
    ErrorMessages  localized network error mapping
    components/    shared Compose components
    navigation/    route constants
    screens/       home, search, installed, detail, author, library, imports,
                   settings, onboarding, diagnostics, licenses, mirrors, about
  di/              AppContainer

Strings live in `res/values/strings.xml` (English) with translations under
`res/values-<locale>/`. Enum labels are mapped to resources in `ui/Labels.kt`.
```

The index is fetched once (39 pages, six concurrent requests), cached in memory
and on disk for six hours, then filtered and sorted client-side.

## Build

Requires JDK 21 and the Android SDK (compileSdk 36, build-tools 36.0.0).

```bash
./gradlew :app:assembleDebug      # APK at app/build/outputs/apk/debug/
./gradlew :app:assembleRelease    # minified, unsigned (stores sign it)
./gradlew :app:testDebugUnitTest  # unit tests
LIVE_REPO_TEST=1 ./gradlew :app:testDebugUnitTest  # also hit the live repository
./gradlew :app:lintDebug
```

`local.properties` must point at the SDK (`sdk.dir=...`).

There is deliberately **no `signingConfigs` block**: like komi-store, release
APKs are built unsigned and signed by the distribution channel (for example
F-Droid). F-Droid listing text, icon, feature graphic and phone screenshots live in
`fastlane/metadata/android/en-US/`.

Cleartext HTTP is disabled (`network_security_config.xml`), matching komi-store;
repository, download and translation endpoints must use HTTPS.

## Tests

- `VersionLogicTest` - tag parsing, the LSPosed upgrade rule, channel selection
  and linked-module keys.
- `DownloadKeyTest` - assets that share a file name get distinct cache keys.
- `AbiSelectionTest` - ABI-aware APK selection for batch/background updates.
- `MirrorStateTest` - mirror preference ordering and fallback.
- `BatchInstallUseCaseTest` - the "update all" queue: serialized installs, a
  delegated install that never settles skips the rest and releases the slot,
  one failure does not stop the queue, ambiguous variants are skipped.
- `InstallSettlementTest` - when a handed-off install counts as landed (polling
  with an injected clock, so the 180 s timeout runs in microseconds).
- `PendingPruneTest` - version-gated landing, abandonment after the grace period,
  and keeping entries that have not landed yet.
- `ScannedPackageTest` - which packages count as Xposed modules (legacy
  meta-data vs libxposed provider) and the primaryCpuAbi fallback rules.
- `LiveRepoTest` - fetches the real index and a real module detail; skipped unless
  `LIVE_REPO_TEST=1` is set.

## Install checks

Nothing in the store pre-judges whether an APK may replace what is installed.
A different signing key, a lower versionCode or an installer that wants to ask
the user are all the installer's business: the APK is handed over through a
`VIEW` intent (or `su pm install -r` when root install is on) and whatever the
installer/PackageManager answers is the answer. On a rooted device that means a
patched installer can accept an APK the stock one would refuse.

What is still checked before the hand-off is `ApkGate`, which only rejects
archives that cannot run here at all: `minSdk` above the device, no matching
native ABI, a `minApiVersion` above the LSPosed API, or an archive that is not
an APK. A package name that differs from the expected one is a warning (renamed
modules are legitimate) and is turned into a module link after install.

## Known gaps

- Batch update hands modules to the system installer one at a time; when a
  third-party installer asks for input, the queue waits for it (up to 15 s per
  module) instead of stacking dialogs. Root install is still fully silent.
- Background auto-update requires root; without it the worker only notifies.
- Translation only covers the module description; the readme and release notes are
  not translated.
- The interactive variant picker still asks the user; batch/background updates use
  an ABI heuristic instead of showing a picker.
- Dependency versions are pinned to the newest releases that build with
  AGP 8.10.1 + compileSdk 36. The next releases (lifecycle 2.11, navigation 2.10,
  Compose 2026.x, core-ktx 1.19) ship AAR metadata with `minCompileSdk=37` and
  `minAndroidGradlePluginVersion=9.1.0`, so they require Android 17 SDK and a
  migration to AGP 9. Lint therefore reports 7 "a newer version is available"
  hints; they are informational, not defects.
- No curated collections, announcements feed or release-notes history (explicitly
  out of scope).
- Signature checks are the installer's; the store does not warn about a changed
  signing key, so a refusal shows up as the installer's own message (and, for a
  batch, as "installer still open" when the dialog is left hanging).

## License

Apache License 2.0. See [LICENSE](LICENSE).
