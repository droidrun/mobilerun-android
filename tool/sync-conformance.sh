#!/usr/bin/env bash
# Copyright 2026 Mobilerun
# SPDX-License-Identifier: Apache-2.0

# Maintainer step: refresh conformance/fixtures from a pinned tag of the shared conformance
# repository, then regenerate the constants from the new copy.
#
# Usage: tool/sync-conformance.sh TAG CONFORMANCE_REPO_PATH
#   TAG                    git tag to vendor, for example v0.2.0
#   CONFORMANCE_REPO_PATH  local clone of the shared fixture source
#                          (or set MOBILERUN_CONFORMANCE_REPO)
set -euo pipefail

if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
  echo "usage: $0 TAG [CONFORMANCE_REPO_PATH]" >&2
  exit 2
fi

tag="$1"
repo="${2:-${MOBILERUN_CONFORMANCE_REPO:-}}"
if [ -z "$repo" ]; then
  echo "error: pass the conformance clone as the second argument or set MOBILERUN_CONFORMANCE_REPO" >&2
  exit 2
fi

root="$(cd "$(dirname "$0")/.." && pwd)"

git -C "$repo" fetch --tags
git -C "$repo" rev-parse --verify --quiet "refs/tags/$tag" >/dev/null || {
  echo "error: tag $tag not found in $repo" >&2
  exit 1
}

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
git -C "$repo" archive "$tag" fixtures | tar -x -C "$tmp"

rm -rf "$root/conformance/fixtures"
mkdir -p "$root/conformance"
mv "$tmp/fixtures" "$root/conformance/fixtures"
printf '%s\n' "$tag" > "$root/conformance/VERSION"

python3 "$root/tool/generate-constants.py"
echo "conformance fixtures synced to $tag"
