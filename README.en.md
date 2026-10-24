<div align="center">
  <br/>
  <img src="assets/icon.png" width="140" alt="LSP Store app icon" />

# LSP Store

</div>

<p align="center">
An updater and browser for LSPosed modules, inspired by
<a href="https://github.com/komi-store/komi-store">Komi Store</a>.
</p>

<p align="center">
  <a href="README.md">中文</a> | <a href="README.en.md">English</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/version-1.0.0-00A9A5?style=flat-square" alt="Version 1.0.0"/>
  <img src="https://img.shields.io/badge/Android-8.1%2B-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android 8.1+"/>
  <img src="https://img.shields.io/badge/languages-14-6C5CE7?style=flat-square" alt="14 languages"/>
  <img src="https://img.shields.io/badge/license-Apache--2.0-blue?style=flat-square" alt="Apache 2.0"/>
</p>

---

### Download

<p align="center">
  <a href="https://github.com/tophtab/lsp-store/releases/latest">
    <img src="https://img.shields.io/badge/Download-GitHub%20Releases-00A9A5?style=for-the-badge&logo=github" alt="Download from GitHub Releases" />
  </a>
</p>

Release APKs are signed by this project, and every release ships a `SHA256SUMS`
file, so you can verify a download with:

```bash
apksigner verify --print-certs LSP-Store-1.0.0.apk
sha256sum -c SHA256SUMS
```

Signing certificate SHA-256 fingerprint (identical for every release):

```
b733f85831f27656fbbf0c424b0a76ceef74dd7ac3261423b0311dc6c8804fcb
```

You can also install and update it straight from the LSPosed Manager
[module repository](https://modules.lsposed.org/module/io.github.tophtab.lspstore/).
**No module activation is needed and no app is hooked**: LSP Store declares itself
as an empty module scoped to itself only, so that it can be listed in the official
index and take part in Manager's install and update flow. Requires Android 8.1+
with LSPosed installed.

> LSP Store is a third-party project, not affiliated with the LSPosed team.

---

### Features

- Browse and search the official LSPosed module repository, with recently updated
  and most starred tabs.
- See installed modules grouped into updates / up to date / not in repository; update
  one or all of them, or install a module in place.
- Module pages with rendered readme, hook scope, release notes, contributors,
  release history, a stable / pre-release / all channel switch and per-APK download.
- Upgrade checks follow stable releases by default (same rule as LSPosed Manager);
  switching the channel to pre-releases also opts that module's update checks into
  its pre-release builds, and the newer of the two streams always wins.
- The install button doubles as the progress bar and as the cancel button; a
  foreground service keeps downloads alive in the background, and leaving the
  module page no longer interrupts an install.
- Works with any registered APK installer (stock installer, InstallerX Revived,
  Dhizuku, AppManager, ...), or silently via root.
- Update checks happen inside the app (no background polling), with optional
  silent root installs.
- Mirror picker with latency test (each host is probed on a path it actually
  serves), and GitHub fallback for APK downloads.
- Favourites, recently viewed, ignored updates, and linking repo modules to
  installed apps that aren't in the repository.
- Module description translation (Google, LibreTranslate, DeepL, Microsoft).
- 14 languages, light / dark / AMOLED themes and Material You colours.
- Onboarding, diagnostics, cache controls and open-source licenses.

---

### Credits

LSP Store builds on the ideas and work of these projects, with thanks:

- [Komi Store](https://github.com/komi-store/komi-store) — the inspiration and prototype reference; the design language, layout and interaction patterns are borrowed from it
- [LSPosed](https://github.com/LSPosed/LSPosed) — the module framework and the public repository behind every module in the store
- [ScopeView](https://github.com/Xposed-Modules-Repo/io.github.zhongjitianqianguai.scopeview) — a similar project; iron sharpens iron
- [LINUX DO](https://linux.do/)
