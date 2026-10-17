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

- Home: updates banner plus horizontal shelves (recently updated, most starred,
  beta builds, favourites, recently viewed).
- Search: live filtering, sort (updated / stars / name), channel filter,
  favourites-only filter, recent-search history.
- Installed: every installed LSPosed module grouped into updates / up to date /
  not in repository, with single and batch update.
- Module detail: readme (WebView), release list with per-asset download, links,
  install / update / reinstall, share, hide, skip this version, open app,
  app info, uninstall.
- Library: favourites, recently viewed, hidden modules, skipped updates.
- Background checks: a WorkManager periodic job (default 12h, configurable) refreshes
  the index, finds updates and posts a notification; with root it can update silently.
  Rescheduled on boot via `BootReceiver`.
- APK signature verification: every downloaded APK is checked before install. The
  signer must match the installed module's signing history; mismatches are blocked
  with a dialog that shows both fingerprints and allows an explicit override.
- Download mirror: falls back from assets.lsposed.org to the original GitHub asset
  URL if the CDN fails.
- Install: system `PackageInstaller` by default, optional silent install via `su`.
- Appearance: system / light / dark, AMOLED black, Material You dynamic color.
- Settings: appearance, update channel, sort order, background-check toggle and
  interval, auto-update, root install, library shortcuts, cache, about.

## Architecture

Single Gradle module (`:app`), package `org.lspstore`, Clean-Architecture-shaped
packages. Manual DI through `AppContainer`, exposed to Compose via
`LocalAppContainer`. No Hilt/Room to keep the surface small.

```
app/src/main/java/org/lspstore/
  data/
    remote/        LsposedRepoApi, Ktor client factory, DTOs, mappers
    repository/    ModuleRepositoryImpl (index cache), InstalledModulesRepositoryImpl,
                   SettingsRepositoryImpl, LibraryRepositoryImpl
    install/       ApkInstaller (download, mirror fallback, system installer, su),
                   ApkVerifier (signatures)
    work/          UpdateCheckWorker, UpdateScheduler, UpdateNotifier, BootReceiver
  domain/
    model/         Module, ModuleDetail, Release, ModuleVersion, InstalledModule,
                   UpdateInfo, channels, sort, theme, ApkVerification
    usecase/       UpdateChecker (channel + skipped + hidden aware)
  ui/
    components/    shared Compose components
    navigation/    route constants
    screens/       home, search, installed, detail, library, settings, about
  di/              AppContainer
```

The index is fetched once (39 pages, six concurrent requests), cached in memory
and on disk for six hours, then filtered and sorted client-side.

## Build

Requires JDK 21 and the Android SDK (compileSdk 35, build-tools 35.0.0).

```bash
./gradlew :app:assembleDebug      # APK at app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest  # unit tests + live repository test
```

`local.properties` must point at the SDK (`sdk.dir=...`).

## Tests

- `VersionLogicTest` - tag parsing, the LSPosed upgrade rule, channel selection.
- `LiveRepoTest` - fetches the real index and a real module detail; requires network.

## Signature verification

Before any install, `ApkVerifier` reads the APK's signers with
`PackageManager.getPackageArchiveInfo` and compares them against the installed
module's `signingCertificateHistory` (falling back to `apkContentsSigners`).
APKs that are unsigned or unparseable are rejected. A signer that differs from
the installed app returns `ApkVerification.Mismatch`, which the UI surfaces as a
dialog; the user can still choose to install after uninstalling the old app.

## Known gaps

- Batch update launches one system-installer dialog per module when not rooted.
- Background auto-update requires root; without it the worker only notifies.
- English only; strings are not yet externalised for translation.
- No onboarding, curated collections or announcements feed.
