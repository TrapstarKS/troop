#!/usr/bin/env bash
# A lookup failure is not evidence that a release is absent. Accept only a confirmed HTTP 404.
set -euo pipefail
REPO="${1:?repository owner/name required}"
TAG="${2:?release tag required}"
RESPONSE="$(mktemp)"
trap 'rm -f "$RESPONSE"' EXIT
if gh api --include "repos/$REPO/releases/tags/$TAG" > "$RESPONSE"; then
  echo "Release already exists; use a new version." >&2
  exit 1
fi
grep -Eq '^HTTP/[0-9.]+[[:space:]]+404([[:space:]]|$)' "$RESPONSE" || {
  echo "Cannot confirm that the release is absent; refusing source mutation." >&2
  exit 1
}
