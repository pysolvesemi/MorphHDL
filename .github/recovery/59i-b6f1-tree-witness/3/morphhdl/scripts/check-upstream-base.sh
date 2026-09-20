#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(git -C "$script_dir" rev-parse --show-toplevel)"
manifest="$repo_root/morphhdl/upstream-base.conf"

read_manifest_value() {
  local key="$1"
  awk -F= -v key="$key" '
    $1 == key {
      sub(/^[^=]*=/, "")
      print
      found = 1
    }
    END { if (!found) exit 1 }
  ' "$manifest"
}

upstream_repository="$(read_manifest_value UPSTREAM_REPOSITORY)"
upstream_branch="$(read_manifest_value UPSTREAM_BRANCH)"
upstream_commit="$(read_manifest_value UPSTREAM_COMMIT)"

if [[ ! "$upstream_commit" =~ ^[0-9a-f]{40}$ ]]; then
  echo "Invalid UPSTREAM_COMMIT in $manifest" >&2
  exit 1
fi

if ! git -C "$repo_root" cat-file -e "${upstream_commit}^{commit}"; then
  echo "Recorded upstream commit is missing from the local history: $upstream_commit" >&2
  exit 1
fi

if ! git -C "$repo_root" merge-base --is-ancestor "$upstream_commit" HEAD; then
  echo "Recorded upstream commit is not an ancestor of HEAD: $upstream_commit" >&2
  exit 1
fi

echo "Upstream repository : $upstream_repository"
echo "Upstream branch     : $upstream_branch"
echo "Recorded base       : $upstream_commit"
echo "Current HEAD        : $(git -C "$repo_root" rev-parse HEAD)"

if [[ "${1:-}" == "--check-remote" ]]; then
  remote_name="${2:-upstream}"
  remote_url="$(git -C "$repo_root" remote get-url "$remote_name")"

  if [[ "$remote_url" != "$upstream_repository" ]]; then
    echo "Remote '$remote_name' points to '$remote_url', expected '$upstream_repository'" >&2
    exit 1
  fi

  git -C "$repo_root" fetch --no-tags "$remote_name" "$upstream_branch"
  remote_head="$(git -C "$repo_root" rev-parse FETCH_HEAD)"

  if ! git -C "$repo_root" merge-base --is-ancestor "$upstream_commit" "$remote_head"; then
    echo "Recorded base is not an ancestor of $remote_name/$upstream_branch" >&2
    exit 1
  fi

  pending_count="$(git -C "$repo_root" rev-list --count "${upstream_commit}..${remote_head}")"
  echo "Remote head         : $remote_head"
  echo "Pending commits     : $pending_count"
fi
