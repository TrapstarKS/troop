#!/usr/bin/env bash
# Package a Release device build with an ad-hoc capability template, not device authorization.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="${1:?usage: $0 app.app output.ipa bundle.id X.Y.Z}"
OUT="${2:?output IPA required}"
IDENTITY="${3:?expected bundle identity required}"
VERSION="${4:?expected version required}"
[ -d "$APP" ] || { echo "App bundle missing: $APP" >&2; exit 1; }
[ ! -e "$OUT" ] || { echo "Refusing to replace IPA: $OUT" >&2; exit 1; }
OUT_DIR="$(cd "$(dirname "$OUT")" && pwd)"
OUT="$OUT_DIR/$(basename "$OUT")"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir "$TMP/Payload"
ditto "$APP" "$TMP/Payload/$(basename "$APP")"
COPY="$TMP/Payload/$(basename "$APP")"
rm -rf "$COPY/Watch"
find "$COPY" -name embedded.mobileprovision -delete
find "$COPY" -name _CodeSignature -type d -prune -exec rm -rf {} +
find "$COPY" -type f -name '*.debug.dylib' -delete
find "$COPY" -type f -name '__preview.dylib' -delete
"$HERE/anonymize-ios-app.sh" "$COPY"
"$HERE/prepare-ios-sideload-app.sh" "$COPY"
(cd "$TMP" && zip -qry packaged.ipa Payload)
python3 "$HERE/verify-release-artifacts.py" ipa "$TMP/packaged.ipa" \
  --bundle-id "$IDENTITY" --version "$VERSION"
mv "$TMP/packaged.ipa" "$OUT"
echo "Packaged sideload IPA: $OUT (user provisioning still required)"
