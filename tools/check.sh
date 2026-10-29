#!/usr/bin/env bash
# Everything that has to be true before any of this is published.
#
#   tools/check.sh            web, server and desktop
#
# Nothing here writes to web/dist, data/ or any live directory: the web build
# goes to a scratch directory, because web/dist is what nexa.fish serves
# and building into it *is* the deploy.
set -uo pipefail
cd "$(dirname "$0")/.."
[ -f "$HOME/.local/toolchain/env.sh" ] && . "$HOME/.local/toolchain/env.sh"

fails=()
run() {
  local name="$1"; shift
  printf '\n\033[1m── %s\033[0m\n' "$name"
  if "$@"; then
    printf '\033[32m   ok\033[0m\n'
  else
    printf '\033[31m   FAILED\033[0m\n'
    fails+=("$name")
  fi
}

run "go build"   go build ./...
run "repository privacy" python3 tools/privacy_check.py
run "go vet"     go vet ./...
run "go test"    go test ./internal/...

run "web typecheck" bash -c 'cd web && npx tsc --noEmit'
run "text layout fixture typecheck" bash -c 'cd web && npx tsc --noEmit -p tests'
run "web tests"     bash -c 'cd web && npx vitest run --reporter dot'
run "web build"     bash -c 'cd web && npx vite build --outDir "${TMPDIR:-/tmp}/dist-check" --emptyOutDir >/dev/null'
# the front page says how much the app is to download; this keeps that true
run "app size"      python3 tools/app_size.py "${TMPDIR:-/tmp}/dist-check"

if command -v cargo >/dev/null 2>&1 && [ -d desktop/src-tauri ]; then
  run "desktop format" bash -c 'cd desktop/src-tauri && cargo fmt --check'
  run "desktop check"  bash -c 'cd desktop/src-tauri && cargo check'
  run "desktop tests"  bash -c 'cd desktop/src-tauri && cargo test --quiet'
else
  printf '\n\033[1m── desktop check\033[0m\n   skipped (no Rust toolchain)\n'
fi

# Every visible string has to exist in the catalogues, in all eleven languages.
run "i18n keys" bash -c 'python3 tools/i18n_keys_used.py | tee /dev/stderr | grep -q "0 missing"'

printf '\n'
if [ ${#fails[@]} -eq 0 ]; then
  printf '\033[32mall checks passed\033[0m\n'
  exit 0
fi
printf '\033[31mfailed: %s\033[0m\n' "${fails[*]}"
exit 1
