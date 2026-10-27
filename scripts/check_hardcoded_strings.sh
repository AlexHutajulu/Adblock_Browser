#!/usr/bin/env bash
set -euo pipefail
HITS=$(grep -rn --include="*.kt" \
  -E 'Text\(\s*"[A-Za-z]' app/src/main/java \
  | grep -v -E 'https?://|^[0-9]+%|AlexShield|EasyList|Peter Lowe' || true)
if [ -n "$HITS" ]; then
  echo "MASIH ADA STRING HARDCODED:"
  echo "$HITS"
  exit 1
fi
echo "OK: tidak ada Text(\"...\") hardcoded."
