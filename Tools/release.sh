#!/usr/bin/env bash
# Legacy publication is disabled in this fork. Stable delivery has one validated workflow.
set -euo pipefail
printf '%s\n' 'Legacy publisher disabled for troop. Use the manually authorized fork-release.yml workflow after the setup in docs/INSTALL.md.' >&2
exit 1
