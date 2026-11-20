#!/usr/bin/env bash
# End-to-end test of a Raven APK on a device or emulator, driven by adb + uiautomator.
#
#   raven/e2e.sh <apk> <adb-serial>        (serial may also come from $ANDROID_SERIAL / $ADB_SERIAL)
#
# Checks: install+launch (incl. first-run onboarding), create profile "Work", cookie isolation, app-only downloads,
# external-link chooser ("Open link in": Always rule, site-rule removal, setting off), profile UX (toolbar chip,
# chip dialog "Always open <host> in this profile", "New tab in profile…" from the tab counter, tab tray profile filter),
# no crash in logcat.
# Web content comes from a tiny local server (raven/e2e-server.py) that this script starts on the machine running adb and
# kills on exit. It serves raven/e2e-page.html (text/html) and /e2e.txt (attachment). The device reaches it at $RAVEN_E2E_HOST.
# The page mirrors the cookie `who` into document.title, the URL fragment (#who_<v>, shown in the native toolbar) and the body, so
# the value is readable even when GeckoView does not expose page text to uiautomator (true on the CI emulator).
# Every check prints PASS/FAIL; exit status is non-zero if any failed.
# Output (screenshots, UI dumps, logcat) goes to $RAVEN_E2E_OUT (default ./e2e-out).
#
# Env:
#   RAVEN_E2E_WIPE=1   uninstall the app first (default in CI) so every run starts from a fresh install.
#                      Without it an existing install is updated in place and keeps its data/profiles.
#   RAVEN_E2E_LINK_CHOOSER=1  also run the external-link chooser checks.
#   RAVEN_E2E_DISABLE_OTHER_BROWSERS=1  `pm disable-user` Chrome etc. so external links cannot land in another browser (CI).
#   RAVEN_E2E_HOST=<host>  address of the machine running this script as seen from the device (default 10.0.2.2, the Android
#                      emulator's alias for the host loopback; use the host's LAN/bridge address for a real device or Waydroid).
#   RAVEN_E2E_SETTLE=<s>  after boot, wait this long for the launcher to settle (CI sets 20; avoids "isn't responding" ANRs).
#   RAVEN_E2E_PKG=<id>  application id of the APK under test (default app.ravenprofiles; the debug build uses
#                      io.github.forkmaintainers.iceraven.debug). The intent extras are fixed strings, not package-based.
#   RAVEN_E2E_SCHEME=<s>  deep-link scheme for settings (default iceraven, iceraven-debug when the package ends in .debug).
#   RAVEN_E2E_USE_ROOT=1      on rooted emulators, open Work tabs via a contextId intent instead of the menu.
#
# Tested layouts: any screen size/density; text is matched from `uiautomator dump`, never by coordinates
# (except swipe gestures for scrolling, derived from `wm size`).
set -uo pipefail

APK=${1:?usage: e2e.sh <apk> <adb-serial>}
SERIAL=${2:-${ANDROID_SERIAL:-${ADB_SERIAL:-}}}
[ -n "$SERIAL" ] || { echo "adb serial required (arg 2 or \$ANDROID_SERIAL)" >&2; exit 2; }
PKG=${RAVEN_E2E_PKG:-app.ravenprofiles}   # e.g. io.github.forkmaintainers.iceraven.debug for the debug build
HOME_ACT=org.mozilla.fenix.HomeActivity
IRA=org.mozilla.fenix.IntentReceiverActivity
# Deep-link scheme of the build: the debug build answers to iceraven-debug (so it can sit next to the release app).
case "$PKG" in *.debug) SCHEME=${RAVEN_E2E_SCHEME:-iceraven-debug} ;; *) SCHEME=${RAVEN_E2E_SCHEME:-iceraven} ;; esac
EX_CTX=app.ravenprofiles.extra.CONTEXT_ID
EX_DESK=app.ravenprofiles.extra.DESKTOP
EX_SKIP=app.ravenprofiles.extra.SKIP_ASK
OUT=${RAVEN_E2E_OUT:-e2e-out}
mkdir -p "$OUT"; OUT=$(cd "$OUT" && pwd)
UI="$OUT/ui.xml"
FAILS=0; STEP=0

A() { adb -s "$SERIAL" "$@"; }
sh_() { A shell "$@"; }
log() { echo "[e2e] $*"; }

# ---- local test server ---------------------------------------------------------------------------
HOST=${RAVEN_E2E_HOST:-10.0.2.2}
SRV_PID=""
cleanup() { [ -n "$SRV_PID" ] && kill "$SRV_PID" 2>/dev/null; rm -f "$PORT_FILE"; }
PORT_FILE=$(mktemp)
trap cleanup EXIT
trap 'exit 130' INT TERM
python3 -I "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/e2e-server.py" "$PORT_FILE" &
SRV_PID=$!
for _ in $(seq 50); do [ -s "$PORT_FILE" ] && break; sleep 0.1; done
PORT=$(cat "$PORT_FILE")
[ -n "$PORT" ] || { echo "test server did not start" >&2; exit 2; }
BASE_URL="http://$HOST:$PORT"

# ---- results -------------------------------------------------------------------------------------
pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1${2:+ ($2)}"; FAILS=$((FAILS + 1)); shot "fail-$(echo "$1" | tr -c 'A-Za-z0-9' _)"; }
shot() { STEP=$((STEP + 1)); A exec-out screencap -p > "$OUT/$(printf '%02d' $STEP)-$1.png" 2>/dev/null || true; }

# ---- UI helpers ----------------------------------------------------------------------------------
# Android's "Set <app> as your default browser app?" role dialog (raised by the browser's onboarding) is often
# invisible to `uiautomator dump`, so detect it by window focus and back out of it before every dump.
dismiss_role_dialog() {
  if sh_ dumpsys window 2>/dev/null | grep -m1 -E 'mCurrentFocus|mFocusedWindow' | grep -qE 'RequestRoleActivity|permissioncontroller'; then
    log "default-browser role dialog in focus; pressing Back"; sh_ input keyevent 4; sleep 1
  fi
}

dump() { # refresh $UI, retrying: uiautomator fails while animations/idle state are unsettled
  local i
  dismiss_role_dialog
  for i in 1 2 3 4; do
    if sh_ uiautomator dump /sdcard/raven-ui.xml >/dev/null 2>&1 && A exec-out cat /sdcard/raven-ui.xml > "$UI" 2>/dev/null && [ -s "$UI" ]; then return 0; fi
    sleep 1
  done
  log "uiautomator dump failed 4 times"; : > "$UI"; return 1
}

# ui_query <mode> <regex>: mode "center" prints "x y" of the first node whose text/content-desc matches,
# "has" exits 0/1. Prefers clickable nodes. mode "edit" finds the first EditText (regex ignored).
ui_query() {
  python3 -I - "$UI" "$1" "$2" <<'PY'
import re, sys
import xml.etree.ElementTree as ET
path, mode, rx = sys.argv[1:4]
try:
    root = ET.parse(path).getroot()
except Exception:
    sys.exit(1)
pat = re.compile(rx)
best = None
for n in root.iter("node"):
    t, d, cls = n.get("text", ""), n.get("content-desc", ""), n.get("class", "")
    if mode == "edit":
        ok = cls.endswith("EditText")
    else:
        ok = bool((t and pat.search(t)) or (d and pat.search(d)))
    if not ok:
        continue
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
    if not m:
        continue
    x1, y1, x2, y2 = map(int, m.groups())
    if x2 <= x1 or y2 <= y1:
        continue
    if mode == "has":
        sys.exit(0)
    cand = ((x1 + x2) // 2, (y1 + y2) // 2, n.get("clickable") == "true")
    if best is None or (cand[2] and not best[2]):
        best = cand
    if best[2]:
        break
if best is None:
    sys.exit(1)
print(best[0], best[1])
PY
}

# ui_grep <regex>: print first regex match found in any text/content-desc
ui_grep() {
  python3 -I - "$UI" "$1" <<'PY'
import re, sys
import xml.etree.ElementTree as ET
try:
    root = ET.parse(sys.argv[1]).getroot()
except Exception:
    sys.exit(1)
pat = re.compile(sys.argv[2])
for n in root.iter("node"):
    for v in (n.get("text", ""), n.get("content-desc", "")):
        m = pat.search(v)
        if m:
            print(m.group(0)); sys.exit(0)
sys.exit(1)
PY
}

# System dialogs ("Pixel Launcher isn't responding", "X keeps stopping") can cover the screen on a slow emulator.
# Looks at the current $UI dump; taps "Wait" on ANR dialogs, else closes the dialog. Returns 0 if one was handled.
dismiss_system_dialogs() {
  local xy
  # First-run onboarding can appear long after launch on a slow emulator; tap through it wherever we are waiting.
  if ui_query has 'Welcome to|default browser|Set as default' \
     && xy=$(ui_query center '^(Continue|Not now|Skip|Maybe later|No thanks|Cancel)$'); then
    log "onboarding on screen; dismissing"; tap_xy ${xy}; sleep 2; return 0
  fi
  ui_query has "isn.t responding|keeps stopping|has stopped" || return 1
  log "system dialog on screen; dismissing"
  if xy=$(ui_query center '^Wait$'); then :; elif xy=$(ui_query center '^(Close app|OK|Close)$'); then :; else return 1; fi
  tap_xy ${xy}; sleep 1; return 0
}

has_text() { dump; ui_query has "$1"; }

wait_for_text() { # <regex> [timeout s]
  local end=$((SECONDS + ${2:-20}))
  while [ $SECONDS -lt "$end" ]; do
    dump && { ui_query has "$1" && return 0; dismiss_system_dialogs && continue; }
    sleep 1
  done
  return 1
}

wait_gone() { # <regex> [timeout s]: wait until no node matches
  local end=$((SECONDS + ${2:-10}))
  while [ $SECONDS -lt "$end" ]; do dump && { dismiss_system_dialogs && continue; ! ui_query has "$1" && return 0; }; sleep 1; done
  return 1
}

switch_xy() { # prints "x y on|off" for the first checkable row of the current dump
  python3 -I - "$UI" <<'PY'
import re, sys
import xml.etree.ElementTree as ET
try:
    root = ET.parse(sys.argv[1]).getroot()
except Exception:
    sys.exit(1)
for n in root.iter("node"):
    if n.get("checkable") == "true":
        x1, y1, x2, y2 = map(int, re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds")).groups())
        print((x1 + x2) // 2, (y1 + y2) // 2, "on" if n.get("checked") == "true" else "off"); sys.exit(0)
sys.exit(1)
PY
}

tap_xy() { sh_ input tap "$1" "$2"; }

tap_text() { # <regex> [timeout s]: wait, then tap the node's center
  local end=$((SECONDS + ${2:-20})) xy
  while [ $SECONDS -lt "$end" ]; do
    dump
    if xy=$(ui_query center "$1"); then tap_xy ${xy}; sleep 1; return 0; fi
    dismiss_system_dialogs && continue
    sleep 1
  done
  log "tap_text: '$1' not found"; return 1
}

SCREEN_W=1080; SCREEN_H=1920
read_size() {
  local s
  s=$(sh_ wm size | tr -d '\r' | grep -oE '[0-9]+x[0-9]+' | tail -1)
  [ -n "$s" ] && { SCREEN_W=${s%x*}; SCREEN_H=${s#*x}; }
}
swipe_up() { sh_ input swipe $((SCREEN_W / 2)) $((SCREEN_H * 3 / 4)) $((SCREEN_W / 2)) $((SCREEN_H / 4)) 300; sleep 1; }

swipe_down() { sh_ input swipe $((SCREEN_W / 2)) $((SCREEN_H / 4)) $((SCREEN_W / 2)) $((SCREEN_H * 3 / 4)) 300; sleep 1; }

tap_text_scroll() { # <regex>: tap, scrolling down up to 6 times to find it
  local i
  for i in 0 1 2 3 4 5 6; do
    tap_text "$1" 3 && return 0
    swipe_up
  done
  return 1
}

back() { sh_ input keyevent KEYCODE_BACK; sleep 1; }
home() { sh_ input keyevent KEYCODE_HOME; sleep 1; }

# Dismiss first-run / permission noise that may sit on top of the browser.
dismiss_noise() {
  local i xy
  # Iceraven onboarding: "Continue" (welcome), then "Not now" on the default-browser page.
  for i in 1 2 3 4 5; do
    dump
    if xy=$(ui_query center '^(Continue|Cancel|Skip|Not now|Maybe later|No thanks|Don.t allow|Allow)$'); then tap_xy ${xy}; sleep 2; else break; fi
  done
}

# Wait (up to $1 s) for the browser UI, tapping through first-run onboarding on the way. On a cold emulator the
# onboarding appears many seconds after launch (the first dump can be a blank window), so one early pass is not enough.
wait_browser_ready() {
  local end=$((SECONDS + ${1:-120})) xy ok=0
  while [ $SECONDS -lt "$end" ]; do
    dump
    dismiss_system_dialogs && continue
    if xy=$(ui_query center '^(Continue|Not now|Skip|Maybe later|No thanks|Cancel)$'); then tap_xy ${xy}; ok=0; sleep 2; continue; fi
    if ui_query has 'Search or enter address|^New tab$|^Menu$|Tabs Open'; then ok=$((ok + 1)); [ "$ok" -ge 2 ] && return 0; else ok=0; fi
    sleep 2
  done
  return 1
}

# ---- intents -------------------------------------------------------------------------------------
view_default() { # open URL in a Default-profile tab (never ask which profile)
  sh_ am start -a android.intent.action.VIEW -d "'$1'" --ez "$EX_SKIP" true -n "$PKG/$IRA" >/dev/null
}
view_ctx() { # <url> <contextId>
  sh_ am start -a android.intent.action.VIEW -d "'$1'" --es "$EX_CTX" "$2" --ez "$EX_DESK" false --ez "$EX_SKIP" true -n "$PKG/$IRA" >/dev/null
}

# Work profile's contextId, if the device allows reading app data (rooted emulator images, not release builds
# on a normal phone). Prints the id or nothing.
discover_ctx() {
  local xml
  # The menu path ("Open in profile") is the one validated on real devices; the contextId shortcut is opt-in.
  [ "${RAVEN_E2E_USE_ROOT:-0}" = 1 ] || return 0
  # `adb root` restarts adbd (and drops the connection on some hosts), so only do it where the build allows it.
  if [ "$(sh_ getprop ro.debuggable | tr -d '\r')" = 1 ] && [ "$(sh_ id -u | tr -d '\r')" != 0 ]; then
    A root >/dev/null 2>&1; A wait-for-device; sleep 2
  fi
  xml=$( { sh_ "cat /data/data/$PKG/shared_prefs/profiles_storage.xml 2>/dev/null || su 0 cat /data/data/$PKG/shared_prefs/profiles_storage.xml 2>/dev/null"; } | tr -d '\r')
  printf '%s' "$xml" | python3 -I -c '
import re, sys
m = re.search(r"name=\"([0-9a-fA-F-]{36})\.name\">Work<", sys.stdin.read())
print(m.group(1) if m else "")'
}

# ---- page content --------------------------------------------------------------------------------
# The test page ($BASE_URL/e2e-page.html) stores ?set=<v> as cookie who=<v> and shows the current value as who_<v> / who_none in
# document.title, the URL fragment (native toolbar) and the body. read_page_value prints "who=<value>" or "none".
# A nonce in the query makes every read a fresh navigation. Sources, in order: URL fragment, tab title, page text.
nonce() { echo "n$RANDOM$RANDOM"; }
read_url() { echo "$BASE_URL/e2e-page.html?n=$(nonce)"; }
set_url() { echo "$BASE_URL/e2e-page.html?set=$1&n=$(nonce)"; } # <value>
cookie_in_ui() { # prints who=<v> / none if the page value is on screen
  python3 -I - "$UI" <<'PY'
import re, sys
import xml.etree.ElementTree as ET
try:
    root = ET.parse(sys.argv[1]).getroot()
except Exception:
    sys.exit(1)
def out(v):
    print("none" if v == "none" else "who=" + v); sys.exit(0)
nodes = list(root.iter("node"))
# 1. URL fragment in the toolbar's URL bar (content-desc "<url>. Search or enter address"); only the selected tab
for n in nodes:
    d = n.get("content-desc", "")
    m = re.search(r'#who_([A-Za-z0-9_%-]+)\.?\s*(?:Search or enter address)?', d) if "Search or enter address" in d else None
    if m: out(m.group(1))
# 2. title of the selected tab (tab strip/tray lists every tab, so only selected="true" nodes count)
for n in nodes:
    if n.get("selected") == "true":
        for t in (n.get("text", ""), n.get("content-desc", "")):
            m = re.search(r'(?:^|\s)who_([A-Za-z0-9_%-]+)\s*$', t.strip())
            if m: out(m.group(1))
# 3. page text (last exact match in document order; the page body is drawn after the toolbar/tab strip)
last = None
for n in nodes:
    m = re.fullmatch(r'who_([A-Za-z0-9_%-]+)', n.get("text", "").strip())
    if m: last = m.group(1)
if last: out(last)
sys.exit(1)
PY
}
# wait_value <who=..|none> [timeout]: poll until the selected tab shows exactly that value (i.e. the set page has really loaded)
wait_value() {
  local end=$((SECONDS + ${2:-40}))
  while [ $SECONDS -lt "$end" ]; do
    dump; [ "$(cookie_in_ui)" = "$1" ] && return 0
    dismiss_system_dialogs && continue
    sleep 2
  done
  return 1
}
read_page_value() { # poll up to $1 s for the page value of the selected tab
  local end=$((SECONDS + ${1:-40})) v xy
  while [ $SECONDS -lt "$end" ]; do
    dump
    v=$(cookie_in_ui) && { echo "$v"; return 0; }
    dismiss_system_dialogs && continue
    # first-run onboarding can still pop up late on a cold emulator
    if xy=$(ui_query center '^(Continue|Not now|Skip)$'); then tap_xy ${xy}; fi
    sleep 2
  done
  shot page-not-readable
  return 1
}
fire_read() { # <default|ctx-id|-> : open a fresh read page, wait for the load, print the value
  local url; url=$(read_url)
  if [ "$1" = default ]; then view_default "$url"; else view_ctx "$url" "$1"; fi
  sleep 4; read_page_value 40
}

open_menu() { tap_text '^(Main )?[Mm]enu$|^Menu$|^More options$' 15; }

open_settings() {
  sh_ am start -a android.intent.action.VIEW -d "$SCHEME://settings" -n "$PKG/$IRA" >/dev/null
  wait_for_text '^Profiles$|^Settings$' 15 && return 0
  # fallback: menu -> Settings
  sh_ am start -n "$PKG/$HOME_ACT" >/dev/null; sleep 2
  open_menu && tap_text '^Settings$' 10
}

# ---- checks --------------------------------------------------------------------------------------
check_install_launch() {
  local name="install as $PKG and launch HomeActivity" apk_out
  if [ "${RAVEN_E2E_WIPE:-${CI:-0}}" != 0 ] && [ "${RAVEN_E2E_WIPE:-${CI:-0}}" != false ]; then
    sh_ pm uninstall "$PKG" >/dev/null 2>&1 || true
  fi
  if ! apk_out=$(A install -r -d -t -g "$APK" 2>&1); then
    fail "$name" "install: $(echo "$apk_out" | tail -1); signature mismatch with an installed copy? set RAVEN_E2E_WIPE=1"; return 1
  fi
  sh_ pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
  sh_ pm path "$PKG" | grep -q package: || { fail "$name" "package not installed"; return 1; }
  sh_ am force-stop "$PKG"
  sh_ am start -W -n "$PKG/$HOME_ACT" >/dev/null
  local end=$((SECONDS + 40))
  while [ $SECONDS -lt "$end" ]; do
    if sh_ dumpsys window | tr -d '\r' | grep -E 'mCurrentFocus|mFocusedApp' | grep -q "$PKG"; then
      sleep 3; dump; dismiss_system_dialogs; dismiss_noise; wait_browser_ready 60 || log "browser UI not confirmed after launch"; shot launched; pass "$name"; return 0
    fi
    sleep 1
  done
  fail "$name" "app did not get focus"; return 1
}

check_create_profile() {
  local name='create profile "Work" in Settings > Profiles'
  open_settings || { fail "$name" "could not open settings"; return 1; }
  tap_text_scroll '^Profiles$' || { fail "$name" "no Profiles entry"; return 1; }
  wait_for_text '^Add profile$' 15 || { fail "$name" "profiles screen not shown"; return 1; }
  if has_text '^Work$'; then pass "$name (already existed)"; shot profiles; return 0; fi
  tap_text '^Add profile$' 10 || { fail "$name" "no Add profile"; return 1; }
  wait_for_text 'Profile name' 10 || { fail "$name" "dialog not shown"; return 1; }
  dump; local xy
  xy=$(ui_query edit '') || xy=$(ui_query center 'Profile name') || { fail "$name" "no name field"; return 1; }
  tap_xy ${xy}; sleep 1
  sh_ input text Work; sleep 1
  # The dialog opens with the soft keyboard up and now has colour and icon pickers, so Save sits behind the keyboard (a tap
  # there would hit a key/suggestion). dumpsys cannot tell reliably whether the keyboard is up, but it always is here:
  # one Back closes just the keyboard. Then scroll to Save in case the dialog is taller than a small screen.
  back; wait_for_text 'Profile name' 10 || { fail "$name" "dialog closed instead of the keyboard"; return 1; }
  tap_text_scroll '^Save$' || { fail "$name" "no Save button"; return 1; }
  if wait_for_text '^Work$' 15; then shot profile-created; pass "$name"; else fail "$name" "Work not listed after save"; fi
}

WORK_CTX=""
# Re-open the selected tab's URL in the Work profile via Menu > More > Open in profile > Work.
open_selected_in_work() {
  open_menu && tap_text '^More( Collapsed)?$' 10 && tap_text '^Open in profile$' 10 && wait_for_text '^Work$' 10 && tap_text '^Work$' 10
}

# Sets who=default in Default and who=work in Work; leaves both readable via read_default / read_work.
read_default() { fire_read default; }
read_work() { # Work's view of the cookie page
  local v
  if [ -n "$WORK_CTX" ]; then fire_read "$WORK_CTX"; return; fi
  view_default "$(read_url)"; sleep 4
  open_selected_in_work || return 1
  sleep 4; read_page_value 40
}

check_cookie_isolation() {
  local name='cookie isolation between Default and Work' v
  sh_ am force-stop "$PKG"
  WORK_CTX=$(discover_ctx)
  # 1. Default: set who=default on the local test page.
  view_default "$(set_url default)"
  wait_value who=default 60 || { fail "$name" "set page did not load / cookie not set"; return 1; }
  v=$(fire_read default) || { fail "$name" "page value not observable (neither page text nor URL marker)"; return 1; }
  [ "$v" = 'who=default' ] || { fail "$name" "Default should have who=default, saw $v"; return 1; }
  log "Default sees $v"
  # 2. Work: set who=work. With a known contextId use a shortcut-style intent; otherwise load ?set=work in Default,
  #    hand that very tab to Work through the menu (Work re-requests the same URL and gets its own cookie),
  #    then restore Default to who=default.
  local setwork; setwork=$(set_url work)
  if [ -n "$WORK_CTX" ]; then
    log "Work contextId discovered; using shortcut-style intents"
    view_ctx "$setwork" "$WORK_CTX"; wait_value who=work 60 || { fail "$name" "Work set page did not load"; return 1; }
  else
    log "contextId not discoverable; using Menu > More > Open in profile"
    view_default "$setwork"; wait_value who=work 60 || { fail "$name" "set page did not load"; return 1; }
    open_selected_in_work || { fail "$name" "menu path to Work failed"; shot menu-fail; return 1; }
    wait_value who=work 60 || { fail "$name" "Work tab did not load"; return 1; }
    view_default "$(set_url default)"; wait_value who=default 60 || { fail "$name" "Default restore did not load"; return 1; }
  fi
  # 3. Both profiles still hold their own value.
  v=$(read_default) || { fail "$name" "Default re-read not observable"; return 1; }
  [ "$v" = 'who=default' ] || { fail "$name" "Default cookie changed to $v"; return 1; }
  v=$(read_work) || { fail "$name" "Work re-read not observable"; return 1; }
  [ "$v" = 'who=work' ] || { fail "$name" "Work cookie changed to $v"; return 1; }
  shot isolation
  pass "$name (Default=default, Work=work)"
}

check_downloads_app_only() {
  local name='downloads stay app-only (not in /sdcard/Download)' i found=0
  sh_ 'rm -f /sdcard/Download/e2e.txt*' 2>/dev/null
  view_default "$BASE_URL/e2e.txt"
  # Fenix asks "Download" / "Cancel" in a bottom sheet; tap it as soon as it shows (up to 25 s).
  if wait_for_text '^Download$' 25; then tap_text '^Download$' 5; else log "no download prompt within 25 s (may have started directly)"; fi
  shot download
  # Wait up to ~90 s for the file to be listed in the in-app Downloads screen (Menu > Downloads), re-checking.
  for i in 1 2 3 4 5 6; do
    sh_ am start -n "$PKG/$HOME_ACT" >/dev/null; sleep 2
    if open_menu && tap_text '^Downloads$' 10 && wait_for_text 'e2e\.txt' 12; then found=1; break; fi
    shot downloads-retry
    back; back; sleep 8
  done
  if sh_ 'ls /sdcard/Download 2>/dev/null' | tr -d '\r' | grep -q '^e2e\.txt'; then
    fail "$name" "e2e.txt leaked into /sdcard/Download"; return 1
  fi
  if [ "$found" = 1 ]; then
    # let it finish: a completed item shows no "Downloading" / progress state
    wait_gone 'Downloading|Paused|Failed' 30 || true
    shot downloads-list; pass "$name"; back
  else
    fail "$name" "download not found in the in-app Downloads list after ~90 s (cannot prove it happened)"
  fi
}

# Fire a VIEW intent like another app would (no component). If it lands in another browser or Android's own
# chooser (other browsers installed), retry targeted at our package.
view_external() {
  local url=$1 i focus
  home
  sh_ am start -a android.intent.action.VIEW -d "'$url'" >/dev/null 2>&1
  for i in 1 2 3 4 5 6; do sleep 1; focus=$(sh_ dumpsys window | tr -d '\r' | grep -E 'mCurrentFocus' | head -1); echo "$focus" | grep -q "$PKG" && return 0; done
  log "link went elsewhere (another default browser or a system chooser: ${focus##* }); retrying with -p $PKG"
  home
  sh_ am start -a android.intent.action.VIEW -d "'$url'" -p "$PKG" >/dev/null 2>&1
}

# Settings > Profiles > Work: opens the profile editor (it opens with the keyboard up, so one Back closes just that).
open_work_editor() {
  if ! has_text '^Add profile$'; then
    open_settings && tap_text_scroll '^Profiles$' && wait_for_text '^Add profile$' 15 || return 1
  fi
  tap_text '^Work$' 10 && wait_for_text '^Edit profile$' 10 || return 1
  back; wait_for_text '^Edit profile$' 10
}

check_link_chooser() {
  if [ "${RAVEN_E2E_LINK_CHOOSER:-0}" != 1 ]; then echo "SKIP: external-link chooser"; return 0; fi
  local name v
  local host=$HOST

  name='external link shows "Open link in" dialog (Default, Work, Always checkbox)'
  view_external "$(read_url)"
  if ! wait_for_text '^Open link in$' 20; then fail "$name" "dialog not shown"; return 1; fi
  if ui_query has '^Default$' && ui_query has '^Work$' && ui_query has "^Always open $host in this profile$"; then
    shot link-chooser; pass "$name"
  else fail "$name" "dialog is missing Default / Work / Always rows"; return 1; fi

  name='chooser: Work + Always + Open loads the page in a Work tab'
  tap_text '^Work$' 5 && tap_text "^Always open $host in this profile$" 5 && tap_text '^Open$' 5 \
    || { fail "$name" "could not operate the dialog"; return 1; }
  sleep 4
  v=$(read_page_value 40) || { fail "$name" "page not readable"; return 1; }
  if [ "$v" = 'who=work' ]; then pass "$name"; else fail "$name" "expected who=work, saw $v"; fi

  name='second link to the same host: no dialog, opens in Work'
  view_external "$(read_url)"
  sleep 5; dump
  if ui_query has '^Open link in$'; then fail "$name" "dialog shown despite the Always rule"; return 1; fi
  v=$(read_page_value 40) || { fail "$name" "page not readable"; return 1; }
  if [ "$v" = 'who=work' ]; then pass "$name"; else fail "$name" "expected who=work, saw $v"; fi
  dump; if ui_query has '^Open link in$'; then fail "$name" "dialog shown late"; return 1; fi

  name='Edit profile "Work" lists the site rule; removing it works'
  open_work_editor || { fail "$name" "could not open the Work profile editor"; return 1; }
  tap_text_scroll '^Sites that always open here$' >/dev/null  # scrolls the dialog to the list; the tap itself is harmless
  if ! has_text "^$host\$"; then fail "$name" "no rule for $host listed"; return 1; fi
  shot site-rule
  tap_text_scroll "^Remove rule for $host\$" || { fail "$name" "no remove button"; return 1; }
  tap_text_scroll '^Save$' || { fail "$name" "no Save button"; return 1; }
  wait_for_text '^Add profile$' 10 || { fail "$name" "editor did not close"; return 1; }
  # reopen: the rule must be gone
  open_work_editor || { fail "$name" "could not reopen the Work profile editor"; return 1; }
  if wait_gone "^$host\$" 10; then pass "$name"; else fail "$name" "rule still listed after removal"; tap_text_scroll '^Cancel$'; return 1; fi
  tap_text_scroll '^Cancel$' || back

  name='"Ask which profile" switch off: link opens in Default without a dialog'
  dump
  local sw state
  sw=$(switch_xy) || { fail "$name" "switch row not found"; return 1; }
  state=${sw##* }
  [ "$state" = on ] && { tap_xy ${sw% *}; sleep 1; dump; sw=$(switch_xy); state=${sw##* }; }
  [ "$state" = off ] || { fail "$name" "could not turn the switch off"; return 1; }
  view_external "$(read_url)"
  sleep 5; dump
  if ui_query has '^Open link in$'; then fail "$name" "dialog shown with the setting off"; return 1; fi
  v=$(read_page_value 40) || { fail "$name" "page not readable"; return 1; }
  if [ "$v" = 'who=default' ]; then pass "$name"; else fail "$name" "expected who=default, saw $v"; fi
}

# ---- profile UX checks (toolbar chip, chip dialog, new tab in profile, tab tray filter) -------------
# Open the read page in a Work tab and wait until it is the selected tab (chip shown). With a known contextId use an
# intent; otherwise load it in Default and hand the tab over through Menu > More > Open in profile.
open_work_tab() {
  if [ -n "$WORK_CTX" ]; then view_ctx "$(read_url)" "$WORK_CTX"
  else view_default "$(read_url)"; sleep 4; open_selected_in_work || return 1; fi
  wait_for_text '^Profile: Work$' 40
}
# On the browser screen: 0 if the toolbar shows the chip of profile <name> (content-desc "Profile: <name>").
chip_is() { wait_for_text "^Profile: $1\$" "${2:-15}"; }
# Chip -> actions dialog -> set the "Always open <host> in this profile" switch to <on|off>, close the dialog.
set_always_open() {
  local want=$1 sw state
  tap_text '^Profile: ' 15 || return 1
  wait_for_text "^Always open $HOST in this profile\$" 15 || return 1
  dump; sw=$(switch_xy) || return 1; state=${sw##* }
  if [ "$state" != "$want" ]; then
    tap_text "^Always open $HOST in this profile\$" 5 || return 1
    sleep 1; dump; sw=$(switch_xy) || return 1; state=${sw##* }
  fi
  tap_text '^Close$' 5 || back
  [ "$state" = "$want" ]
}

check_profile_chip() {
  local name='toolbar chip: shown on a Work tab, absent on a Default tab'
  open_work_tab || { fail "$name" "no 'Profile: Work' chip on a Work tab"; return 1; }
  shot chip-work
  view_default "$(read_url)"
  wait_value who=default 40 || { fail "$name" "Default page did not load"; return 1; }
  dump
  if ui_query has '^Profile: '; then fail "$name" "chip shown on a Default tab"; return 1; fi
  pass "$name"
}

check_new_tab_in_profile() {
  local name='tab counter long-press > "New tab in profile…" > Work: new tab shows the Work chip'
  view_default "$(read_url)"; wait_value who=default 40 || { fail "$name" "Default page did not load"; return 1; }
  dump
  ui_query has '^Profile: ' && { fail "$name" "precondition: Default tab shows a chip"; return 1; }
  local xy
  xy=$(ui_query center 'Tabs [Oo]pen|^Tab counter|tab counter') || { fail "$name" "no tab counter in the toolbar"; return 1; }
  sh_ input swipe ${xy} ${xy} 900; sleep 1
  tap_text '^New tab in profile…$' 10 || { fail "$name" "no 'New tab in profile…' in the long-press menu"; return 1; }
  wait_for_text '^Work$' 10 || { fail "$name" "profile picker did not list Work"; return 1; }
  tap_text '^Work$' 5
  chip_is Work 20 || { fail "$name" "new tab has no Work chip"; return 1; }
  shot new-work-tab
  pass "$name"
}

check_tabs_filter() {
  local name='tab tray profile filter: Work shows only Work tabs, All profiles resets'
  # Precondition: both profiles own a tab titled by their cookie (who_default / who_work).
  view_default "$(read_url)"; wait_value who=default 40 || { fail "$name" "Default page did not load"; return 1; }
  open_work_tab || { fail "$name" "could not open a Work tab"; return 1; }
  view_default "$(read_url)"; wait_value who=default 40 || { fail "$name" "Default page did not load"; return 1; }
  dump
  local xy
  xy=$(ui_query center 'Tabs [Oo]pen|^Tab counter|tab counter') || { fail "$name" "no tab counter"; return 1; }
  tap_xy ${xy}
  # The filter chip is the first row of the list; the tray opens scrolled to the selected tab, so scroll back up to it.
  local k found=0
  for k in 1 2 3 4 5 6 7 8; do
    if wait_for_text '^All profiles$' 3; then found=1; break; fi
    swipe_down
  done
  [ "$found" = 1 ] || { fail "$name" "tab tray shows no profile filter chip"; back; return 1; }
  ui_query has 'who_default' || log "unfiltered tray: Default marker tab not on screen (list is long); continuing"
  tap_text '^All profiles$' 5 || { fail "$name" "cannot open the filter"; back; return 1; }
  wait_for_text '^Work$' 10 || { fail "$name" "filter lists no Work"; back; return 1; }
  tap_text '^Work$' 5
  wait_gone 'who_default' 15 || { fail "$name" "Default tab still listed with the Work filter"; back; return 1; }
  dump; ui_query has 'who_work' || { fail "$name" "no Work tab listed with the Work filter"; back; return 1; }
  shot tabs-filter-work
  tap_text '^Work$' 5 || { fail "$name" "cannot reopen the filter"; back; return 1; }
  wait_for_text '^All profiles$' 10 && tap_text '^All profiles$' 5
  # Reset: the chip reads "All profiles" again and the Default tabs are back (the list may be long: scroll to find one).
  local seen=0
  wait_for_text '^All profiles$' 10 || { fail "$name" "chip did not reset to All profiles"; back; return 1; }
  for k in 1 2 3 4 5 6 7 8; do
    dump; if ui_query has 'who_default'; then seen=1; break; fi
    swipe_up
  done
  back
  if [ "$seen" = 1 ]; then pass "$name"; else fail "$name" "Default tab not back after resetting to All profiles"; fi
}

check_always_open_via_chip() {
  local name='chip dialog: "Always open <host> in this profile" ON routes a Default load to Work, OFF stops it' v
  open_work_tab || { fail "$name" "no Work tab to start from"; return 1; }
  set_always_open on || { fail "$name" "could not switch the rule on"; set_always_open off; return 1; }
  # A Default-profile intent for the same host must end up in a Work tab.
  view_default "$(read_url)"
  v=$(read_page_value 40) || { fail "$name" "page not readable"; set_always_open off; return 1; }
  if [ "$v" != 'who=work' ] || ! chip_is Work 10; then
    fail "$name" "rule ON: expected who=work + Work chip, saw $v"; set_always_open off; return 1
  fi
  shot always-on
  set_always_open off || { fail "$name" "could not switch the rule off"; return 1; }
  view_default "$(read_url)"
  v=$(read_page_value 40) || { fail "$name" "page not readable after OFF"; return 1; }
  dump; if [ "$v" = 'who=default' ] && ! ui_query has '^Profile: '; then pass "$name"; else fail "$name" "rule OFF: expected who=default, saw $v"; fi
}

check_no_crash() {
  local name='no FATAL EXCEPTION in logcat'
  A logcat -d > "$OUT/logcat.txt" 2>&1 || true
  if grep -A3 'FATAL EXCEPTION' "$OUT/logcat.txt" | grep -q "$PKG"; then
    fail "$name" "see logcat.txt"
  else pass "$name"; fi
}

# ---- main ----------------------------------------------------------------------------------------
A wait-for-device
for _ in $(seq 60); do [ "$(sh_ getprop sys.boot_completed | tr -d '\r')" = 1 ] && break; sleep 2; done
read_size
sh_ input keyevent KEYCODE_WAKEUP; sh_ wm dismiss-keyguard >/dev/null 2>&1 || true
sh_ input keyevent 82 >/dev/null 2>&1 || true
if [ "${RAVEN_E2E_SETTLE:-0}" -gt 0 ] 2>/dev/null; then log "settling for ${RAVEN_E2E_SETTLE}s"; sleep "$RAVEN_E2E_SETTLE"; fi
for k in window_animation_scale transition_animation_scale animator_duration_scale; do sh_ settings put global "$k" 0 >/dev/null 2>&1 || true; done
# Best effort: ask Android to expose accessibility content (helps if GeckoView only builds its tree then).
sh_ settings put secure accessibility_enabled 1 >/dev/null 2>&1 || true
if [ "${RAVEN_E2E_DISABLE_OTHER_BROWSERS:-0}" = 1 ]; then
  # CI emulator images ship Chrome, which would catch external VIEW intents (FirstRunActivity).
  for p in com.android.chrome com.google.android.apps.chrome com.chrome.beta; do
    if sh_ pm path "$p" 2>/dev/null | grep -q package:; then log "disabling $p"; sh_ pm disable-user --user 0 "$p" >/dev/null 2>&1 || sh_ pm disable "$p" >/dev/null 2>&1 || true; fi
  done
fi
A logcat -c

check_install_launch && {
  check_create_profile
  check_cookie_isolation
  check_downloads_app_only
  check_link_chooser
  check_profile_chip
  check_new_tab_in_profile
  check_tabs_filter
  check_always_open_via_chip
}
check_no_crash

echo
if [ "$FAILS" -eq 0 ]; then echo "ALL CHECKS PASSED"; else echo "$FAILS CHECK(S) FAILED"; fi
exit $((FAILS > 0))
