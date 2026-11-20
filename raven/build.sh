#!/usr/bin/env bash
# Build Raven in a prepared checkout.
#
#   raven/build.sh release|debug      (run raven/prep.sh once first)
#
# release: app:assembleForkRelease, signed when signing material is given:
#   CI:    RAVEN_KEYSTORE_B64, RAVEN_STORE_PASS, RAVEN_KEY_ALIAS, RAVEN_KEY_PASS
#   local: RAVEN_KEYSTORE_PROPS=<properties file with storeFile/storePassword/keyAlias/keyPassword>
#   (no signing material -> unsigned APKs, a warning is printed)
# debug:   app:assembleForkDebug; RAVEN_ABI=x86_64 (etc.) limits it to one ABI (never used for release).
#
# Other env: RAVEN_VERSION (default raven-<tag>-r<GITHUB_RUN_NUMBER or 0>), RAVEN_XMX (default 6g),
#   RAVEN_WORKERS (default 2), RAVEN_MEM_MAX (systemd memory cap for local runs, default 13G),
#   JAVA_HOME / ANDROID_HOME as usual.
# Result: out/*.apk plus .sha256 files and SHA256SUMS.
set -euo pipefail

WORK=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
MODE=${1:?usage: build.sh release|debug}
cd "$WORK"
[ -e .raven-prepped ] || { echo "run raven/prep.sh first" >&2; exit 1; }
TAG=$(cat .raven-tag 2>/dev/null || echo unknown)
VERSION=${RAVEN_VERSION:-raven-$TAG-r${GITHUB_RUN_NUMBER:-0}}
export ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
[ -z "${ANDROID_HOME:-}" ] || export ANDROID_HOME

if [ -f venv/bin/activate ]; then set +u; . venv/bin/activate; set -u; fi

args=(-PversionName="$VERSION"
  "-Dorg.gradle.jvmargs=-Xmx${RAVEN_XMX:-6g} -Xms512m -XX:MaxMetaspaceSize=1536m -XX:+UseParallelGC"
  -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.parallel=false
  "--max-workers=${RAVEN_WORKERS:-2}" --no-daemon --stacktrace)

TMPSIGN=""
cleanup() { [ -z "$TMPSIGN" ] || rm -rf "$TMPSIGN"; }
trap cleanup EXIT

case "$MODE" in
  release)
    task=app:assembleForkRelease
    outdir=gradle/build/app/outputs/apk/forkRelease
    if [ -n "${RAVEN_KEYSTORE_B64:-}" ]; then
      TMPSIGN=$(mktemp -d); chmod 700 "$TMPSIGN"
      printf '%s' "$RAVEN_KEYSTORE_B64" | base64 -d > "$TMPSIGN/ks.jks"
      esc() { printf '%s' "$1" | sed 's/\\/\\\\/g'; }
      {
        echo "storeFile=$TMPSIGN/ks.jks"
        echo "storePassword=$(esc "${RAVEN_STORE_PASS:?}")"
        echo "keyAlias=$(esc "${RAVEN_KEY_ALIAS:?}")"
        echo "keyPassword=$(esc "${RAVEN_KEY_PASS:?}")"
      } > "$TMPSIGN/signing.properties"
      args+=("-PravenKeystoreProps=$TMPSIGN/signing.properties")
    elif [ -n "${RAVEN_KEYSTORE_PROPS:-}" ]; then
      args+=("-PravenKeystoreProps=$RAVEN_KEYSTORE_PROPS")
    else
      echo "WARNING: no signing material given, APKs will be unsigned" >&2
    fi
    # Release must not be limited to one ABI.
    ;;
  debug)
    task=app:assembleForkDebug
    outdir=gradle/build/app/outputs/apk/forkDebug
    if [ -n "${RAVEN_ABI:-}" ]; then
      args+=("-Pandroid.injected.build.abi=$RAVEN_ABI")
      # With an injected ABI the APK is left under intermediates/ (and marked test-only).
      outdir=gradle/build/app/intermediates/apk/forkDebug
    fi
    ;;
  *) echo "mode must be release or debug" >&2; exit 1 ;;
esac

cmd=(./gradlew "$task" "${args[@]}")
if [ -z "${CI:-}" ] && command -v systemd-run >/dev/null 2>&1; then
  cmd=(systemd-run --user --scope --quiet -p "MemoryMax=${RAVEN_MEM_MAX:-13G}" -p MemorySwapMax=2G "${cmd[@]}")
fi
"${cmd[@]}"

rm -rf out; mkdir -p out
found=0
for apk in "$outdir"/*.apk; do
  [ -e "$apk" ] || continue
  found=1
  b=$(basename "$apk" .apk)             # app-arm64-v8a-forkRelease
  abi=${b#app-}; abi=${abi%-fork*}
  dest="out/$VERSION-$abi.apk"
  [ "$MODE" = debug ] && dest="out/$VERSION-$abi-debug.apk"
  cp "$apk" "$dest"
  (cd out && sha256sum "$(basename "$dest")" | tee "$(basename "$dest").sha256")
done
[ "$found" = 1 ] || { echo "no APKs found in $outdir" >&2; exit 1; }
(cd out && cat ./*.sha256 > SHA256SUMS)
echo "APKs in $WORK/out"
