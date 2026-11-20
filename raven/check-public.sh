#!/usr/bin/env bash
# Fail if anything personal could leak from our changes to this (public) fork.
#
# Scope: only what we added on top of upstream Iceraven: lines added in `git diff <base> HEAD`, the names of
# changed files, and commits not reachable from any upstream iceraven-* tag. Upstream's own files and history
# (which contain other people's e-mail addresses) are NOT scanned.
#
# Base: $RAVEN_BASE, else the newest iceraven-* tag reachable from HEAD (tags are fetched from upstream if missing).
#
# Generic checks (always run, safe for CI):
#   - e-mail addresses other than *@users.noreply.github.com / example.* / noreply@*
#   - /home/<user>, /Users/<user> paths
#   - private LAN addresses (192.168.x.x, 10.x.x.x, 172.16-31.x.x)
#   - private key blocks
#   - author/committer identities other than a GitHub noreply identity (except GitHub's own PR merge commit committer); commit messages with local paths / LAN IPs
# Private deny-list (optional): $RAVEN_DENYLIST = path to a file OUTSIDE the repo with one extended-regex per line
#   (names, hostnames, usernames...). Blank lines and lines starting with # are ignored.
set -uo pipefail

cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
hits=0

BASE=${RAVEN_BASE:-$(git describe --tags --abbrev=0 --match 'iceraven-*' 2>/dev/null || true)}
if [ -z "$BASE" ]; then
  git fetch --quiet --tags --no-recurse-submodules https://github.com/fork-maintainers/iceraven-browser 2>/dev/null || true
  BASE=$(git describe --tags --abbrev=0 --match 'iceraven-*' 2>/dev/null || true)
fi
[ -n "$BASE" ] || { echo "cannot determine the upstream base tag (set RAVEN_BASE)" >&2; exit 2; }
echo "base: $BASE"

# added lines, prefixed "path:" so hits are locatable; this script's own patterns are excluded
ADDED=$(git diff --no-color -U0 --diff-filter=d "$BASE" HEAD -- . ':!raven/check-public.sh' | awk '
  /^\+\+\+ b\// { f = substr($0, 7); next }
  /^\+\+\+ /    { next }
  /^\+/         { print f ":" substr($0, 2) }')
NAMES=$(git diff --name-only "$BASE" HEAD)

scan() { # <label> <ere> [filter-out ere] [strip ere: removed from every line before matching]
  local out
  out=$({ printf '%s\n' "$ADDED"; printf '%s\n' "$NAMES"; } | { if [ -n "${4:-}" ]; then sed -E "s/$4//g"; else cat; fi; } | grep -E -- "$2" 2>/dev/null | { if [ -n "${3:-}" ]; then grep -vE -- "$3"; else cat; fi; })
  if [ -n "$out" ]; then
    echo "HIT [$1]:"; echo "$out" | head -20 | sed 's/^/   /'
    hits=$((hits + $(echo "$out" | wc -l)))
  else
    echo "ok  [$1]: 0"
  fi
}

scan "foreign e-mail" '[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}' '@users\.noreply\.github\.com|@example\.|noreply@|@(android|androidx|kotlin|SuppressLint)\b|[A-Za-z]+@[0-9]+\.[0-9]'
scan "home paths" '/home/[A-Za-z0-9_.-]+|/Users/[A-Za-z0-9_.-]+'
# 10.0.2.2 is the Android emulator's fixed alias for the host loopback (used by raven/e2e.sh); it is not a real LAN address.
scan "private IPs" '\b(192\.168\.[0-9]+\.[0-9]+|10\.[0-9]+\.[0-9]+\.[0-9]+|172\.(1[6-9]|2[0-9]|3[01])\.[0-9]+\.[0-9]+)\b' '' '(^|[^0-9.])10\.0\.2\.2($|[^0-9])'
scan "private keys" '-----BEGIN [A-Z ]*PRIVATE KEY-----'

if [ -n "${RAVEN_DENYLIST:-}" ]; then
  if [ -f "$RAVEN_DENYLIST" ]; then
    pat=$(grep -vE '^\s*(#|$)' "$RAVEN_DENYLIST" | paste -sd'|')
    if [ -n "$pat" ]; then scan "private deny-list" "$pat"; fi
  else
    echo "RAVEN_DENYLIST file not found" >&2; hits=$((hits + 1))
  fi
else
  echo "--  private deny-list skipped (set RAVEN_DENYLIST)"
fi

# commits that are not part of upstream releases
UP_TAGS=$(git tag -l 'iceraven-*')
# shellcheck disable=SC2086
OURS=$(git rev-list HEAD --not $UP_TAGS)
echo "our commits: $(printf '%s\n' "$OURS" | grep -c .)"
# The merge commit GitHub creates for a pull request (checked out by CI) is committed by "GitHub <noreply@github.com>";
# that exact committer is accepted on merge commits only. Its author is still checked.
ident() {
  local c=$1 committer
  git log -1 --format='%an <%ae>' "$c"
  committer=$(git log -1 --format='%cn <%ce>' "$c")
  if [ "$committer" = 'GitHub <noreply@github.com>' ] && [ "$(git rev-list --parents -n1 "$c" | wc -w)" -gt 2 ]; then return 0; fi
  echo "$committer"
}
bad=$(for c in $OURS; do ident "$c"; done | sort -u | grep -vE '<[0-9]+\+[A-Za-z0-9-]+(\[bot\])?@users\.noreply\.github\.com>$' || true)
if [ -n "$bad" ]; then echo "HIT [commit identities]:"; echo "$bad" | sed 's/^/   /'; hits=$((hits + 1)); else echo "ok  [commit identities]: 0"; fi
msgs=$(for c in $OURS; do git log -1 --format=%B "$c"; done | grep -nE '/home/|/Users/|192\.168\.' || true)
if [ -n "$msgs" ]; then echo "HIT [commit messages]:"; echo "$msgs" | sed 's/^/   /'; hits=$((hits + 1)); else echo "ok  [commit messages]: 0"; fi

if [ "$hits" -gt 0 ]; then echo "FAILED: $hits hit(s)"; exit 1; fi
echo "public check passed"
