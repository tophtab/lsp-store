# Agent instructions

## Issue-linked changes: branch + PR with a closing keyword

Every change that resolves a GitHub issue goes through a pull request. Do not push
it straight to `master`.

1. Branch from `master`, e.g. `fix/1-updated-time`.
2. In the PR body (not just the commit message), reference the issue with a
   closing keyword so GitHub closes it on merge and links the PR in the issue
   timeline:

   ```
   Fixes #1
   ```

   `Fixes`, `Closes` and `Resolves` all work. One keyword per issue; the merge
   commit then shows "closed this as completed in #<pr>".
3. Let the `checks` workflow pass: full `lintDebug` (warnings included) and unit
   tests. A PR builds no APK; the master push that follows builds and signs the
   release, with `lintVital` as its only lint gate.
4. Merge with **Squash and merge**. The PR body keeps the keyword even when the
   squash commit message is rewritten.
5. If the issue needs a released build before it can be verified, still merge the
   PR first, then follow `docs/release-process.md`.

Direct pushes to `master` stay acceptable for repository housekeeping that has no
issue (dependencies, docs, this file).

## Releases

Follow `docs/release-process.md`: bump `versionName`/`versionCode`, write
`release-notes/<version>.md`, add a `CHANGELOG.md` section, push the merge commit,
wait for the `build` artifact, then tag `v<version>` and push the tag.
