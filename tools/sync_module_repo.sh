#!/usr/bin/env bash
#
# Mirror the module metadata this repository owns into
# Xposed-Modules-Repo/io.github.tophtab.lspstore, which is the repository the
# LSPosed index (modules.lsposed.org) reads. That repository holds metadata only,
# never source: the README, the images the README references, and the
# SUMMARY/SCOPE/SOURCE_URL files.
#
# Everything is copied verbatim, which is why the README references images as
# `assets/...`: the same relative path has to resolve in both repositories.
#
# Usage:
#   GH_TOKEN=<pat with contents:write on the module repository> tools/sync_module_repo.sh
#
# Run by .github/workflows/module-repo.yml on every push that touches the files
# below; safe to run by hand as well (it skips files that are already identical).

set -euo pipefail

REPO="Xposed-Modules-Repo/io.github.tophtab.lspstore"

cd "$(dirname "$0")/.."

# local path -> path in the module repository
PAIRS=(
  "README.md:README.md"
  "README.en.md:README.en.md"
  "assets/icon.png:assets/icon.png"
  "assets/screenshots/installed.png:assets/screenshots/installed.png"
  "assets/screenshots/repository.png:assets/screenshots/repository.png"
  "assets/screenshots/settings.png:assets/screenshots/settings.png"
  "module-repo/SUMMARY:SUMMARY"
  "module-repo/SCOPE:SCOPE"
  "module-repo/SOURCE_URL:SOURCE_URL"
)

body_file="$(mktemp)"
trap 'rm -f "$body_file"' EXIT

for pair in "${PAIRS[@]}"; do
  src="${pair%%:*}"
  dst="${pair#*:}"

  test -f "$src" || { echo "error: $src is missing" >&2; exit 1; }

  # The contents API exposes the git blob id, which is exactly what
  # `git hash-object` computes locally, so identical files need no upload.
  local_sha="$(git hash-object "$src")"
  if remote="$(gh api "repos/$REPO/contents/$dst" 2>/dev/null)"; then
    remote_sha="$(printf '%s' "$remote" | jq -r '.sha')"
  else
    remote_sha=""
  fi

  if [ "$local_sha" = "$remote_sha" ]; then
    echo "up to date: $dst"
    continue
  fi

  # The body has to go through a file: the screenshots' base64 is far past the
  # per-argument limit of exec(2).
  base64 -w0 "$src" > "$body_file"
  jq -n --rawfile content "$body_file" \
    --arg message "Sync $dst from tophtab/lsp-store" \
    --arg sha "$remote_sha" \
    '{message: $message, content: $content}
     + (if $sha == "" then {} else {sha: $sha} end)' \
    | gh api --method PUT "repos/$REPO/contents/$dst" --input - > /dev/null
  echo "updated: $dst"
done

# The repository description is deliberately left alone: the index and LSPosed
# Manager show it as the module's *name*, so it has to stay the app name
# ("LSP Store"). It is also admin-only, which this workflow's token is not.
