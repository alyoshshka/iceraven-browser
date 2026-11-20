#!/usr/bin/env bash
# End-to-end test of a Raven APK on a device or emulator, driven by adb + uiautomator.
#
#   raven/e2e.sh <apk> <adb-serial>        (serial may also come from $ANDROID_SERIAL / $ADB_SERIAL)
#
# Checks: install+launch (incl. first-run onboarding), create profile "Work", cookie isolation (httpbin.org),
# app-only downloads, external-link chooser ("Open link in": Always rule, site-rule removal, setting off),
# no crash in logcat. Page content is read from the uiautomator tree (GeckoView exposes it) via httpbin /cookies. Every check prints PASS/FAIL; exit status is non-zero if any failed.
# Output (screenshots, UI dumps, logcat) goes to $RAVEN_E2E_OUT (default ./e2e-out).
#
# Env:
#   RAVEN_E2E_WIPE=1   uninstall the app first (default in CI) so every run starts from a fresh install.
#                      Without it an existing install is updated in place and keeps its data/profiles.
#   RAVEN_E2E_LINK_CHOOSER=1  also run the external-link chooser checks.
#
# Tested layouts: any screen size/density; text is matched from `uiautomator dump`, never by coordinates
# (except swipe gestures for scrolling, derived from `wm size`).
set -uo pipefail

APK=${1:?usage: e2e.sh <apk> <adb-serial>}
SERIAL=${2:-${ANDROID_SERIAL:-${ADB_SERIAL:-}}}
[ -n "$SERIAL" ] || { echo "adb serial required (arg 2 or \$ANDROID_SERIAL)" >&2; exit 2; }
PKG=app.ravenprofiles
HOME_ACT=org.mozilla.fenix.HomeActivity
IRA=org.mozilla.fenix.IntentReceiverActivity
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

# ---- results -------------------------------------------------------------------------------------
pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1${2:+ ($2)}"; FAILS=$((FAILS + 1)); shot "fail-$(echo "$1" | tr -c 'A-Za-z0-9' _)"; }
shot() { STEP=$((STEP + 1)); A exec-out screencap -p > "$OUT/$(printf '%02d' $STEP)-$1.png" 2>/dev/null || true; }

# ---- UI helpers ----------------------------------------------------------------------------------
dump() { # refresh $UI, retrying: uiautomator fails while animations/idle state are unsettled
  local i
  for i in 1 2 3 4; do
    if sh_ uiautomator dump /sdcard/raven-ui.xml >/dev/null 2>&1 && A exec-out cat /sdcard/raven-ui.xml > "$UI" 2>/dev/null && [ -s "$UI" ]; then return 0; fi
    sleep 1
  done
  : > "$UI"; return 1
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

has_text() { dump; ui_query has "$1"; }

wait_for_text() { # <regex> [timeout s]
  local end=$((SECONDS + ${2:-20}))
  while [ $SECONDS -lt "$end" ]; do
    dump && ui_query has "$1" && return 0
    sleep 1
  done
  return 1
}

wait_gone() { # <regex> [timeout s]: wait until no node matches
  local end=$((SECONDS + ${2:-10}))
  while [ $SECONDS -lt "$end" ]; do dump && ! ui_query has "$1" && return 0; sleep 1; done
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

# ---- intents -------------------------------------------------------------------------------------
view_default() { # open URL in a Default-profile tab (never ask which profile)
  sh_ am start -a android.intent.action.VIEW -d "$1" --ez "$EX_SKIP" true -n "$PKG/$IRA" >/dev/null
}
view_ctx() { # <url> <contextId>
  sh_ am start -a android.intent.action.VIEW -d "$1" --es "$EX_CTX" "$2" --ez "$EX_DESK" false --ez "$EX_SKIP" true -n "$PKG/$IRA" >/dev/null
}

# Work profile's contextId, if the device allows reading app data (rooted emulator images, not release builds
# on a normal phone). Prints the id or nothing.
discover_ctx() {
  local xml
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
# GeckoView exposes page text to uiautomator as a node (verified on Waydroid), so we read the JSON that
# https://httpbin.org/cookies returns: {"cookies": {"who": "<value>"}}. READ_URL carries a nonce so every read
# is a fresh navigation. read_page_value prints "who=<value>" or "none" (empty cookie jar).
READ_BASE="https://httpbin.org/cookies"
nonce() { echo "n$RANDOM$RANDOM"; }
read_url() { echo "$READ_BASE?$(nonce)"; }
cookie_in_ui() { # prints who=<v> / none if a /cookies JSON body is on screen
  python3 -I - "$UI" <<'PY'
import re, sys
import xml.etree.ElementTree as ET
try:
    root = ET.parse(sys.argv[1]).getroot()
except Exception:
    sys.exit(1)
for n in root.iter("node"):
    t = n.get("text", "")
    if '"cookies"' in t:
        m = re.search(r'"who":\s*"([^"]*)"', t)
        print("who=" + m.group(1) if m else "none"); sys.exit(0)
sys.exit(1)
PY
}
read_page_value() { # poll up to $1 s for the cookie JSON of the selected tab
  local end=$((SECONDS + ${1:-40})) v
  while [ $SECONDS -lt "$end" ]; do
    dump
    v=$(cookie_in_ui) && { echo "$v"; return 0; }
    sleep 2
  done
  shot page-not-readable
  return 1
}
fire_read() { # <default|ctx-id|-> : open a fresh cookie page, wait for the load, print the value
  local url; url=$(read_url)
  if [ "$1" = default ]; then view_default "$url"; else view_ctx "$url" "$1"; fi
  sleep 4; read_page_value 40
}

open_menu() { tap_text '^(Main )?[Mm]enu$|^Menu$|^More options$' 15; }

open_settings() {
  sh_ am start -a android.intent.action.VIEW -d iceraven://settings -n "$PKG/$IRA" >/dev/null
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
  if ! apk_out=$(A install -r -d -g "$APK" 2>&1); then
    fail "$name" "install: $(echo "$apk_out" | tail -1); signature mismatch with an installed copy? set RAVEN_E2E_WIPE=1"; return 1
  fi
  sh_ pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
  sh_ pm path "$PKG" | grep -q package: || { fail "$name" "package not installed"; return 1; }
  sh_ am force-stop "$PKG"
  sh_ am start -W -n "$PKG/$HOME_ACT" >/dev/null
  local end=$((SECONDS + 40))
  while [ $SECONDS -lt "$end" ]; do
    if sh_ dumpsys window | tr -d '\r' | grep -E 'mCurrentFocus|mFocusedApp' | grep -q "$PKG"; then
      sleep 3; dismiss_noise; shot launched; pass "$name"; return 0
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
  tap_text '^Save$' 10 || { fail "$name" "no Save button"; return 1; }
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
  # 1. Default: set the cookie server-side (the redirect lands on /cookies, which shows it).
  view_default "https://httpbin.org/cookies/set?who=default"; sleep 6
  v=$(read_page_value 45) || { fail "$name" "page content not observable in the a11y tree"; return 1; }
  [ "$v" = 'who=default' ] || { fail "$name" "Default should have who=default, saw $v"; return 1; }
  log "Default sees $v"
  # 2. Work: set who=work. With a known contextId use a shortcut-style intent; otherwise temporarily set it in
  #    Default via a non-redirecting Set-Cookie page, hand that very tab to Work through the menu (Work
  #    re-requests the same URL and gets its own cookie), then restore Default.
  local setwork='https://httpbin.org/response-headers?Set-Cookie=who%3Dwork%3B%20Path%3D%2F'
  if [ -n "$WORK_CTX" ]; then
    log "Work contextId discovered; using shortcut-style intents"
    view_ctx "$setwork" "$WORK_CTX"; sleep 6
  else
    log "contextId not discoverable; using Menu > More > Open in profile"
    view_default "$setwork"; sleep 6
    open_selected_in_work || { fail "$name" "menu path to Work failed"; shot menu-fail; return 1; }
    sleep 6
    view_default "https://httpbin.org/cookies/set?who=default"; sleep 6
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
  local name='downloads stay app-only (not in /sdcard/Download)'
  sh_ 'rm -f /sdcard/Download/e2e.txt*' 2>/dev/null
  view_default 'https://httpbin.org/response-headers?Content-Disposition=attachment%3B%20filename%3De2e.txt'
  if wait_for_text '^Download$' 25; then tap_text '^Download$' 5; else log "no download prompt; assuming it started"; fi
  sleep 10; shot download
  if sh_ 'ls /sdcard/Download 2>/dev/null' | tr -d '\r' | grep -q '^e2e\.txt'; then
    fail "$name" "e2e.txt leaked into /sdcard/Download"; return 1
  fi
  # positive proof that the download happened: it is listed in the in-app Downloads screen
  sh_ am start -n "$PKG/$HOME_ACT" >/dev/null; sleep 2
  if open_menu && tap_text '^Downloads$' 10 && wait_for_text 'e2e\.txt' 20; then
    shot downloads-list; pass "$name"; back
  else
    fail "$name" "download not found in the in-app Downloads list (cannot prove it happened)"
  fi
}

# Fire a VIEW intent like another app would (no component). If it lands in another browser or Android's own
# chooser (other browsers installed), retry targeted at our package.
view_external() {
  local url=$1 i focus
  home
  sh_ am start -a android.intent.action.VIEW -d "$url" >/dev/null 2>&1
  for i in 1 2 3 4 5 6; do sleep 1; focus=$(sh_ dumpsys window | tr -d '\r' | grep -E 'mCurrentFocus' | head -1); echo "$focus" | grep -q "$PKG" && return 0; done
  log "link went elsewhere (another default browser or a system chooser: ${focus##* }); retrying with -p $PKG"
  home
  sh_ am start -a android.intent.action.VIEW -d "$url" -p "$PKG" >/dev/null 2>&1
}

check_link_chooser() {
  if [ "${RAVEN_E2E_LINK_CHOOSER:-0}" != 1 ]; then echo "SKIP: external-link chooser"; return 0; fi
  local name v
  local host=httpbin.org

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

  name='Settings > Profiles lists the site rule; removing it works'
  open_settings && tap_text_scroll '^Profiles$' && wait_for_text '^Site rules$' 15 \
    || { fail "$name" "Profiles screen / Site rules section not shown"; return 1; }
  if ! has_text "^$host$"; then fail "$name" "no rule for $host listed"; return 1; fi
  shot site-rule
  tap_text "^Remove rule for $host$" 10 || { fail "$name" "no remove button"; return 1; }
  sleep 1; dump
  if ui_query has '^(Remove|Delete|OK|Yes)$'; then tap_text '^(Remove|Delete|OK|Yes)$' 5; sleep 1; fi
  if wait_gone "^$host$" 10; then pass "$name"; else fail "$name" "rule still listed after removal"; return 1; fi

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
for k in window_animation_scale transition_animation_scale animator_duration_scale; do sh_ settings put global "$k" 0 >/dev/null 2>&1 || true; done
# Best effort: ask Android to expose accessibility content (helps if GeckoView only builds its tree then).
sh_ settings put secure accessibility_enabled 1 >/dev/null 2>&1 || true
A logcat -c

check_install_launch && {
  check_create_profile
  check_cookie_isolation
  check_downloads_app_only
  check_link_chooser
}
check_no_crash

echo
if [ "$FAILS" -eq 0 ]; then echo "ALL CHECKS PASSED"; else echo "$FAILS CHECK(S) FAILED"; fi
exit $((FAILS > 0))
