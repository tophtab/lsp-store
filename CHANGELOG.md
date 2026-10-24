# Changelog

## 1.0.4

### Fixed

- The "updated" time on module cards, the detail header and the "recently
  updated" sort no longer comes from GitHub's repo-level `updated_at`, which a
  star, a README edit or a metadata push refreshes. It now uses the publication
  time of the newest release (stable, pre-release or snapshot); modules that
  never published a release show no time and sort after the ones that did.
  HyperClipFix, for example, showed "1 hour ago" because someone starred its
  module repo; its last release was two days old.

## 1.0.3

### Changed

- Settings -> About lost the "not affiliated with the LSPosed project" card and
  its two buttons. The module is distributed through the module index itself, so
  the disclaimer was noise; the slot now holds a clickable card that opens
  https://github.com/tophtab/lsp-store and asks for a star.
- The About and Diagnostics screens show the commit the APK was built from:
  `BuildConfig.BUILD_SHA`, e.g. `1.0.3 · 3f2a91c`. The version name no longer
  carries a `.dev.<sha>` suffix, because a commit's APK is now published as-is
  when that commit is tagged - its `versionName` has to stay the plain release
  version for the tag check and for the module index. The stamped sha is the
  on-device identifier in its place, and it is also written into the `dev`
  pre-release notes.

### Release tooling

- CI builds the app once per push. Previously the gate job produced an unsigned
  release APK that nothing consumed and the snapshot/release job rebuilt it from
  a second cold Gradle User Home, so R8 and lint ran twice: 10m22s of wall clock
  for a commit that was also tagged. A push now takes ~3m30s.
- A master push uploads the signed APK as a workflow artifact. A tag pointing at
  a commit that already has one downloads and publishes it instead of compiling,
  which makes a release take about a minute and makes the released file exactly
  the build that was tested. Without an artifact (tag pushed together with
  master, or expired after 30 days) it falls back to a full build.
- The full lint no longer runs on pushes: `assembleRelease` already runs
  `lintVital`, and `MissingTranslation`/`ExtraTranslation` are now promoted to
  fatal checks so the published APK is gated by the project's error policy in
  ~40s instead of ~150s. Full lint, warnings included, stays on pull requests.
- The guard against a second release on the same tag reads the release list
  instead of `/releases/tags/<tag>`, which returns 404 for drafts - the state a
  failed run leaves behind. It fails closed when the list cannot be read.
- The Gradle configuration cache is enabled, docs-only commits no longer trigger
  a build, and `android-actions/setup-android` installs the SDK packages in one
  step (the runner image no longer puts `sdkmanager` on `PATH`).

## 1.0.2

### Changed

- A finished refresh now returns the Repository and Installed lists to the top.
  Both view models bump a `refreshTick` once a load lands, and the screens answer
  it with `animateScrollToItem(0)`. This covers pull-to-refresh, re-tapping the
  current bottom tab (which already scrolled) and the silent startup refresh, so
  a cold start ends on the newest modules instead of wherever the list was left.
  The tick is only honoured on success: a failed refresh keeps the scroll
  position and the cached content.

### Release tooling

- The Xposed-Modules-Repo mirror step now creates the index release as a draft,
  uploads the APK and `SHA256SUMS`, and only then publishes it with the release
  notes as the body. The index bot validates a release once, when its content is
  published: 1.0.1 and 1.0.2 were published first and given their APK afterwards,
  so the index never saw them and both stayed drafts (invisible at
  modules.lsposed.org, so nobody was offered the update). 1.0.2 was republished
  that way by hand.

## 1.0.1

### Packaging

- Store listing only: `fastlane/metadata/android/en-US/images/phoneScreenshots/` now
  holds the current three tabs (installed, repository, settings) named `1.png`–`3.png`
  as F-Droid expects, replacing the five screenshots from the old four-tab UI. No app
  code changed; the version was bumped because F-Droid and IzzyOnDroid only read
  `fastlane/` from the latest release.
- README: a screenshot tour above the download link, and the feature list trimmed to
  names only.

## 1.0.0

### Packaging

- Build toolchain moved to AGP 9.4.1 / Gradle 9.8.2 / Kotlin 2.4.21 and
  `compileSdk 37` (Android 17), which is what the libxposed 102 artifacts require.
  `targetSdk` stays at 36 and `minSdk` at 27, so the runtime support matrix does not
  change. The `org.jetbrains.kotlin.android` plugin is gone: AGP 9 compiles Kotlin
  itself, and the `kotlin { compilerOptions { jvmTarget } }` block went with it
  (`jvmTarget` now defaults to `compileOptions.targetCompatibility`).
- `io.github.libxposed:service` is now 102.0.0, matching the API and `module.prop`
  versions; the earlier 101.0.0 pin only existed because 102 required `compileSdk 37`.
- First public release, version 1.0.0 (versionCode 10000). The version code is
  `major * 10000 + minor * 100 + patch`, so minor and patch must stay below 100.
- The application id is now `io.github.tophtab.lspstore`, and the Kotlin namespace
  follows it. The old `org.lspstore` id was never published, so no installed copy
  is orphaned.
- `minSdk` is 27 (Android 8.1), the floor LSPosed itself supports; the README no
  longer claims 8.0.
- Release APKs are self-signed from CI instead of being left unsigned for a store
  to sign, because the module repository distributes them as-is.

### Release tooling

- A minimal libxposed declaration (`META-INF/xposed/module.prop`, `java_init.list`,
  `scope.list`) plus an empty `LspStoreModule` entry class, so the app can be listed
  in the official module index and installed or updated from LSPosed Manager. The
  scope is the app's own package and the entry class hooks nothing; every feature
  works without the module being activated.
- `io.github.libxposed:service` ships its `XposedProvider` into the manifest. That provider
  is how a modern module is recognised without opening its APK, so the store now
  detects itself as an installed module and updates itself through the same
  installed-module flow as everything else, instead of needing a separate
  self-update path.
- `release-notes/` holds the text published as GitHub release notes, which the
  module index renders as the release description.
- The release job refuses to publish an unsigned APK, checks that the tag matches
  the APK's `versionName`, and uploads `SHA256SUMS` next to the APK.

### Added

- Per-module pre-release tracking, komi style. The More sheet on an installed
  module (above "Ignore this version") and a chip on the module page toggle
  **Pre-releases** for that one module, with a checkmark showing the current
  state; it only appears for modules that actually published a pre-release.
  While it is on, that module's update checks follow its pre-release stream
  instead of only its stable releases. The two streams are still compared and
  the newer one wins, so an older beta can never hide a newer stable build (24
  of the 29 modules with pre-releases ship exactly that), modules that only ever
  published a pre-release finally show up as updatable, and same-code
  re-uploads still count when the chosen stream was published later. The choice
  is stored per repository package (`pre_release_modules`) and defaults to off.
  The module page lists the pre-release builds too, so the update it proposes can
  actually be installed.

### Removed

- The proxy feature is gone. Settings no longer offers an HTTP/SOCKS proxy with
  credentials or a connection test, and the OkHttp clients used for repository
  requests, APK probes and downloads no longer install a proxy selector or
  authenticator.
- APK signature verification before install. The store no longer compares the
  downloaded APK's signer with the installed module and no longer blocks the
  install on a mismatch: replacing a package is the installer's and
  PackageManager's call, and on a rooted device a patched installer (or root
  install) may legitimately accept an APK the stock installer would refuse. The
  mismatched-signature dialog, its uninstall-and-install shortcut and the
  `ApkVerifier` / `ApkVerification` code are gone. `ApkGate` still rejects APKs
  that cannot run on this device at all (minSdk, ABI, LSPosed API, not an APK).
- The global update-channel picker. Settings no longer offers Stable/Beta
  (Nightly was already hidden), so a beta preference written by an older build
  no longer applies; pre-releases are now chosen per module instead (see
  "Per-module pre-release tracking"). `UpdateChannel` is gone from the
  domain, the stored `update_channel` preference is neither read nor written,
  and the diagnostics page no longer lists a channel it can no longer display.
- Automatic update checks in the background, and with them the whole
  background pipeline: the periodic WorkManager job, the "check in the
  background" switch, the interval picker, the "check now" button, `BootReceiver`
  and its boot permission, the "auto-update when rooted" switch, the
  `auto_update` preference, the update notification (channel, permission use and
  the deep link that opened the Installed tab). `UpdateCheckWorker`,
  `UpdateScheduler` and `UpdateNotifier` are gone. The app no longer polls the
  repository on its own: updates are computed from the index it already
  refreshes while you browse, and installing stays manual — silently via root
  when "Root 静默安装" is on, otherwise through the system installer. The
  settings group that used to hold all of this is now a single "Root" switch.

### Fixed

- A resumed APK download no longer fails with a bare `HTTP 416`. The downloader
  only sends `Range` when the partial file can actually be continued: a file
  that already covers the whole asset (a pause that landed after the last write,
  or an asset re-uploaded smaller under the same URL) is fetched again, and a
  server that refuses or misplaces the range now gets one fresh request from
  byte 0 instead of the error being reported. The completion size check also
  prefers the total the server reports over the repository index's size, so a
  stale index entry cannot turn a finished download into a failure.
- "Update all" no longer ends on a bare "Failures: 1". The batch queue now
  keeps the reason for every module it could not install — download/resolve
  failure, or the installer's own message — and the summary toast lists them
  one per line (`Failures: 2` / `MapAdKiller: unknown failure code 7` /
  `Foo: no APK asset`), up to three names, with the count always giving the
  real total. It stays a transient message: no new screen, no dialog.
- Modules whose updates were ignored from the Installed tab now appear in the
  settings list next to per-version skips, so the list reflects everything that
  is currently being suppressed.
- Screens no longer get the status-bar inset twice. The root scaffold passes no
  insets to the nav host (the bottom bar owns the navigation-bar inset) and each
  screen's own scaffold + top bar applies the status bar exactly once, so the
  empty band above every page is gone.

### Changed

- The Repository screen no longer truncates its shelves. "Recently updated"
  and "Most starred" each list every module in the index instead of the first
  15, and "Most starred" also keeps modules with no stars (at the end) rather
  than filtering them out. There is no other module-browsing screen, so the
  cut-off made the rest of the repository unreachable.
- The Installed row's overflow now offers both update-suppression actions:
  "Skip this version" and "Permanently ignore updates" (the latter restores
  update checks when tapped again). Skips are stored as the repository tag
  instead of the numeric versionCode, so modules that reuse `versionCode` 1 for
  every release only hide the version the user actually saw. Old numeric skips
  keep their previous code-wide meaning. The Ignored updates list now shows the
  author's version name (e.g. `3.0`), not the internal versionCode or the raw
  `code-versionName` tag.
- The Nightly update channel is no longer offered. The repository currently
  publishes no snapshot releases, so the old preference is read as Beta.
- A downloaded APK is only installed when its release matches the version the
  update list promised. A stale mirror detail (which can lag days behind the
  index) now fails with "repository is still serving an older release" instead
  of installing the old build and prompting again.
- PackageManager scans are reused for 30 seconds by callers that do not force a
  refresh; installs, package broadcasts and pull-to-refresh still rescan.

- Removed the manga theme style. The app now always uses the classic komi
  layout with a Material 3 "icon cyan" scheme: the launcher icon's background
  (`#CDF2F0`) is the `primary` colour, paired with a dark teal `onPrimary`,
  while supporting roles and cyan-tinted surfaces come from tonal palettes
  anchored to the same hue. The bottom bar got an icon-cyan indicator pill,
  and the personality switch is gone from Settings and onboarding.
- An `ApkGate` rejection (minSdk above the device, unmatched ABI, LSPosed API
  too new, not an APK) now only records one failed attempt. It no longer flips
  the module's variant state to `NOT_APPLICABLE`, which used to drop that module
  out of the Installed update list and out of background checks after a single
  mismatch.
- "Update all" in Installed now downloads up to three module APKs concurrently
  and installs each one as soon as its download finishes. Installs are still
  serialized so installer dialogs never stack, but a slow download no longer
  blocks modules whose APK is already ready. Each update row shows its own
  download / queued / installing progress.
- Renamed "Skipped updates" to "Ignored update modules". The Hidden modules
  feature has been removed entirely, and the Repository page no longer shows
  an available-updates banner.
- Installed and Repository are cache-first. The last saved module index
  (`module_index.json`) and the last installed-module scan
  (`installed_modules.json`) are painted immediately, then PackageManager is
  rescanned and the repository is refreshed in the background. The first screen
  of a session performs the network refresh; later screens reuse the in-memory
  index. Pull-to-refresh still forces a fetch, and a failed refresh keeps the
  cached content instead of replacing it with an error.
- Repository refresh now requests the single-file `/modules.json` index (the same
  endpoint the official LSPosed Manager uses) before falling back to the
  paginated `module-list/{page}.json` endpoints. A rejected single-file request
  is remembered for the process so later refreshes go straight to pagination.
- The bottom bar is now Installed / Repository / Settings and the app opens on
  Installed. The separate Search tab and screen are gone: the Repository page
  has an inline search field that filters the full module index. The Home and
  Installed refresh icons were removed; pull-to-refresh remains.
- Up-to-date installed rows now have komi-style open and more-actions controls.
  The overflow sheet offers open, app info and uninstall.

- Repository and author module cards no longer swap in the installed APK's
  launcher icon. An installed module is flagged by the `Installed` chip only, so
  every card in a feed looks the same; the Installed tab still shows the real
  launcher icon.
- The detail hero no longer repeats version information. The installed / beta /
  nightly version chips and the `Installed <version>` chip are gone, and the
  bordered installed-status pill only remains for "update available" and
  "pending install" (plain "installed" was redundant next to the Reinstall
  button). The Stats section now only shows stars / releases / scope, like komi's
  repo stats; the version picker above the install button still shows the
  release being installed.
- The Source / Website / Repo text buttons were removed from the detail hero;
  the repository still opens from the top-bar browser action.
- The detail hero is now a plain tinted card: the diagonal accent stripes and
  the 100dp module avatar (which also carried the circular download spinner)
  are gone, and the author handle and title sit on a normal 20dp margin. Download
  progress still shows in the install controls below the picker.
- Readme badges (shields.io and friends) render at 18dp instead of up to 40dp,
  so a row of badges no longer dominates the About this app section. Badge cells
  inside readme tables shrank the same way.
- The readme now renders like komi's markdown: tables become bordered panels
  with a header row, ordered lists are numbered, `<details>` blocks are
  collapsible, horizontal rules are kept, links are accent-coloured and
  underlined, inline code gets a tinted background and theme-aware
  (`#gh-light-mode-only` / `#gh-dark-mode-only`) images respect the active theme.
- The installed list (up to date, not in repository and pending rows) now shows
  the real launcher icon from `PackageManager` instead of a generic extension
  glyph, matching the update rows.
- Search no longer lists the whole repository, or a "modules found" count, while
  the query is empty. The empty state is just the recent-search history; results
  and the count only appear once something is typed.

- Module details now use komi's single-scroll layout: an AppHeader-style hero
  card with the accent stripe and a 100dp avatar, a release-channel selector,
  What's new, About this app and the full release history. GitHub-only stats
  (forks, issues, license) are omitted because the LSPosed repository has no
  such fields.
- The detail page now follows komi's block order block for block: hero, install
  controls, a new Stats section (stars / releases / scope, then installed / latest
  versions), What's new and About this app. The hero gained komi's bordered
  installed / update-available status pill and drops the `@` before the author
  handle.
- The stable / beta / nightly selector was removed from the detail page. The
  repository has 1162 modules but only 32 publish a beta and none publish
  nightly builds, so the row was almost always a single useless choice; the
  global update channel in Settings still drives the default version.
- The detail release history list was replaced with komi's version and asset
  dropdowns: a labelled picker row above the install button opens bottom sheets
  that list releases (with Latest / beta badges and a selected check) and the
  APKs of the chosen release (name, size, downloads). The install button now
  installs the selected APK.
- Further komi detail alignment: the download / verify / install progress ring
  is drawn around the 100dp hero avatar, the top-bar overflow is a bottom action
  sheet instead of a dropdown, the hero title auto-sizes from 18 to 30sp, the
  detail page supports pull-to-refresh, What's new collapses behind a gradient
  with a Read more / Show less pill, the readme renders headings / code blocks /
  quotes / lists, dialogs use the shared `KomiDialog` panel, the install button
  shows the live download percentage, the version sheet filters by release line
  and the asset sheet marks the ABI match, and an install log block records the
  download / install steps.
- Module tiles only show an icon for installed modules, using the real APK
  icon from `PackageManager`. The author's GitHub avatar is no longer shown as a
  stand-in for modules that are not installed.
- Feed and search cards no longer show the stable/beta version chips; the
  installed badge is the only chip left.
- The detail readme is rendered as Compose text and image blocks instead of a
  WebView. A WebView nested in the scrolling column swallowed vertical drags, so
  only the readme itself could scroll; the whole page now scrolls as one column.
- Readme images now decode SVG too (Coil `coil-svg`), request with a browser
  User-Agent so CDN-hotlinked shields.io badges load, and flow consecutive
  badges inline at a capped size.
- Search dropped the favourites-only filter and the per-card favourite button.

- Search no longer shows stable/beta/nightly chips; results now include every
  release stream. Sort stays available from the search top bar.
- Home only keeps the recently updated and most starred tabs. The beta builds
  and library tabs were removed; library shortcuts live in Settings.
- Settings now follows komi's grouped-row layout: compact segmented controls in
  the row's trailing slot, expandable proxy and translation sections, and drill
  rows for library, mirrors, diagnostics and about. The duplicate sort control
  was dropped from Settings and remains on the search screen.

- The frontend now mirrors komi-store's personality system and defaults to its
  manga theme: cream paper / ink palette with a cobalt accent, Anton display +
  JetBrains Mono, uppercase labels, 3dp ink outlines, offset hard shadows,
  screentone card texture, an ink masthead and a 66dp ink-pill bottom bar. The
  classic personality (Geist, 16dp outlined panels, 72dp icon-over-label bar) is
  selectable in Appearance and during onboarding, alongside AMOLED and Material
  You. Both share komi's layout: width-capped staggered feed, full-width module
  cards, section-head + grouped-row settings, and a search field above the
  installed list.
- Installs are delegated to the device's APK installer through a `VIEW` intent,
  mirroring komi-store: InstallerX Revived, Dhizuku, AppManager and the stock
  installer are all picked up through the normal APK handler. The in-app
  `PackageInstaller` session path was removed - it could only ever reach the
  system installer, and on devices where that confirmation never surfaced the
  install died with `INSTALL_FAILED_ABORTED: User rejected permissions`.
- Delegated installs go through a single install slot (komi's
  `SystemInstallSerializer`): released by `PACKAGE_REPLACED`/`PACKAGE_ADDED` or a
  15 s timeout, so batch updates no longer stack installer dialogs. The installed
  list refreshes from the package broadcast. Like komi-store, the only install
  notification is the foreground download one, which is removed when the
  download completes; no "waiting for the installer" notification is posted.
  Instead, the hand-off is persisted as a pending-install state
  (`PendingInstallRepositoryImpl`): pending entries get their own section in
  Installed and a chip plus a disabled install button in Details, and are
  released by the package broadcast, by the target version appearing on the
  device, or manually via the pending row's cancel action.
- "Install unknown apps" is checked right before delegating; when it is off the
  user is sent to the toggle with a clear message instead of getting an aborted
  install.
- Root installs still run `pm install` through `su` and complete in-process.
- The module source list now matches what the hosts actually serve, and every
  endpoint has its own host order instead of one shared rotation. The catalogue
  is fetched from the paginated `module-list` endpoint on `modules.lsposed.org`
  (real time), and `modules.json` on `backup.modules.lsposed.org` is the
  one-request fallback; module details still come from `modules.lsposed.org` and
  now fall back to the backup host. `modules-blogcdn.lsposed.org` (answers 418)
  and `modules-cloudflare.lsposed.org` (no DNS record) are gone, and the source
  picker no longer probes every host with `module-list/1.json`, which used to
  report the backup host as unreachable even though it is the only host serving
  `modules.json`. The paginated catalogue stays the default on purpose: update
  checks read the index, and the backup mirror publishes a deployment that is
  several days old. Pinning the backup host in the source picker flips that
  order, because the choice is otherwise meaningless: that host serves no
  paginated catalogue at all, so it can only mean "take the older single file".

## 0.2.0

Correctness and release-readiness pass.

### Fixed

- Download cache keys now include the full asset identity. Modules and releases
  that reuse names such as `app-release.apk` no longer overwrite or reinstall
  each other's APKs.
- Imported/linked modules are verified, installed and managed using the
  installed package name; linked modules no longer disappear from the installed
  screen.
- Android 8.0/8.1 no longer crashes on `PackageInfo.longVersionCode`.
- Module index cache reads/writes moved off the main thread, with a stale-cache
  fallback when the repository is unreachable.
- APK verification rejects an APK whose package name does not match the expected
  package.
- Installs use PackageInstaller sessions with a result callback and show the
  confirmation flow; signature mismatches now offer uninstall-then-install.
- Batch and background updates pick an APK matching the device ABI instead of
  always taking the first asset.
- Search channel chips actually filter results.
- AMOLED black now also applies with dynamic color enabled.
- Proxy "Test" uses the values currently in the form, not the last saved config.
- Author pages work for names containing spaces or slashes.
- Backup import rejects unsupported backup versions.
- Notifications open the Installed screen.

### Added

- Module scope, release notes and additional contributors on the detail screen.
- Offline browsing from the cached index.
- ABI-aware asset selection and unit tests for download keys / ABI matching.
- Backup rules that exclude proxy credentials and translation API keys.
- Per-app language configuration (Android 13+), monochrome launcher icon.
- CI workflow, release minification/shrinking and an unsigned release build.
- Apache-2.0 license, fastlane/F-Droid metadata with icon, feature graphic and
  phone screenshots, translated metadata for the 13 other locales.
- Detect modern libxposed modules via their XposedProvider in addition to the
  legacy xposedmodule meta-data.
- "Check for updates now" action in Settings running the background worker once.
- Foreground download service with progress and a cancel-all notification action.
- Localized network/timeout/server error messages instead of raw exception text.
- Dedicated monochrome notification icon.
- Unknown imported modules can be opened in their linked module detail page.
- Dependency, icon-density and ellipsis polish; lint has 0 errors and no
  actionable warnings.
- Toolchain move to AGP 8.10.1, Gradle 8.14.6, Kotlin 2.2.21, compileSdk/targetSdk
  36, Compose BOM 2025.12.01, lifecycle 2.10.0, activity 1.13.0. Upgrading lifecycle past 2.8.7
  needed AGP 8.10 because newer AndroidX lint checks use a newer lint API.
