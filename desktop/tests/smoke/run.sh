#!/usr/bin/env bash
# Compatibility entry point; run.py also runs natively on Windows and macOS.
# Usage: desktop/tests/smoke/run.sh /absolute/path/to/nexa-desktop
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
exec python3 "$here/run.py" "${1:?usage: run.sh /path/to/nexa-desktop}"
