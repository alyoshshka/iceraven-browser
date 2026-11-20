#!/usr/bin/env bash
# Prepare this checkout for building: exactly Iceraven's own prep from .github/workflows/release.yml
# (SDK pieces, patch android-components on the fly, python venv, branding sed), run in place.
#
#   raven/prep.sh
#
# RUN ONCE on a clean checkout (with the android-components submodule initialised). The prep edits tracked files
# (android-components patching, strings.xml), so a second run would patch twice: the script refuses and tells
# you how to reset (`git checkout -- . && git -C android-components checkout -- . && git clean -fdx`; this discards
# local edits, so commit or stash first).
#
# Env: ANDROID_HOME  local SDK; install-sdk.sh runs only in CI, with RAVEN_INSTALL_SDK=1, or when the NDK is missing.
set -euo pipefail

cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
step() { echo; echo "=== $* ==="; }

[ ! -e .raven-prepped ] || { echo "already prepared (.raven-prepped exists); see the header of raven/prep.sh to reset" >&2; exit 1; }
[ -e android-components/components ] || { echo "run: git submodule update --init --depth 1 android-components" >&2; exit 1; }

step "Upstream release tag"
tag=$(git describe --tags --abbrev=0 --match 'iceraven-*' 2>/dev/null || true)
if [ -z "$tag" ]; then
  git fetch --quiet --tags --no-recurse-submodules https://github.com/fork-maintainers/iceraven-browser
  tag=$(git describe --tags --abbrev=0 --match 'iceraven-*')
fi
echo "$tag" > .raven-tag
echo "base: $tag"

step "Android SDK"
NDK_VER=$(grep -oP 'ndk;\K[0-9.]+' automation/iceraven/install-sdk.sh | head -1 || true)
if [ "${GITHUB_ACTIONS:-}" = true ] || [ "${RAVEN_INSTALL_SDK:-0}" = 1 ] \
   || [ -z "${ANDROID_HOME:-}" ] || { [ -n "$NDK_VER" ] && [ ! -d "${ANDROID_HOME:-/nonexistent}/ndk/$NDK_VER" ]; }; then
  ./automation/iceraven/install-sdk.sh
else
  echo "using ANDROID_HOME (NDK ${NDK_VER:-?} present)"
  grep -qs '^sdk.dir=' local.properties || echo "sdk.dir=$ANDROID_HOME" >> local.properties
fi

step "Iceraven prep (as in upstream release.yml)"
# Iceraven's scripts call `python`; where only python3 exists, provide it through a shim that stays inside the
# checkout (the venv records its interpreter home, so the shim must outlive this script).
if ! command -v python >/dev/null 2>&1; then
  mkdir -p .pyshim && ln -sf "$(command -v python3)" .pyshim/python && export PATH="$PWD/.pyshim:$PATH"
fi
./automation/iceraven/patch_android_components.sh
./automation/iceraven/setup_venv.sh
sed -i 's/Firefox/Iceraven/g' app/src/*/res/values*/*strings.xml
sed -i '/about_content/s/Mozilla/@forkmaintainers/' app/src/*/res/values*/*strings.xml
sed -i '/<string name="trackers_blocked_panel_categorical_num_trackers_blocked">/s/%s/%1$s/g; /<string name="trackers_blocked_panel_categorical_num_trackers_blocked">/s/%d/%2$d/g' app/src/*/res/values*/*strings.xml

touch .raven-prepped
echo; echo "prepared on $tag"
