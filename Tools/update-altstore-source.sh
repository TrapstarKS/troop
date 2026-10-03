#!/usr/bin/env bash
# Local-only manifest generation; no network, commit, or publication.
# --repo owner/repo --source manifest.json --asset-url https://... --version X.Y.Z --ipa file.ipa
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
exec python3 "$HERE/verify-release-artifacts.py" manifest "$@"
