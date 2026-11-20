# Raven

Raven is a fork of [Iceraven](https://github.com/fork-maintainers/iceraven-browser) (a Firefox for Android fork)
that adds **profiles**:

- per-profile cookies and site storage, profile dots on tabs
- "Open in profile" from the main menu
- profile shortcuts on the home screen (with desktop mode)
- app-only, per-profile downloads with a file manager, and an in-app upload picker
- ask which profile to use for links from other apps

It is not affiliated with Mozilla or with the Iceraven maintainers. The feature itself is described in
[docs/profiles.md](docs/profiles.md).

## Branches

| Branch | What |
|---|---|
| `iceraven` | upstream, untouched |
| `feat/profiles` | upstream tag plus the profiles feature only (the branch to propose upstream) |
| `raven` | `feat/profiles` plus the Raven identity (app id `app.ravenprofiles`, label "Raven", optional release signing) and our tooling: `raven/` and `.github/workflows/raven.yml` |

How to propose `feat/profiles` upstream and keep it rebased: [raven/UPSTREAM.md](raven/UPSTREAM.md).

## Tooling (`raven/`)

| Script | What |
|---|---|
| `prep.sh` | Iceraven's own prep, in place: SDK pieces, android-components patching, venv, branding. Run once on a clean checkout |
| `build.sh` | gradle build, APKs and SHA-256 sums in `out/` |
| `e2e.sh` | adb + uiautomator end-to-end test |
| `check-public.sh` | scan our changes (not upstream's) for personal data |

## Build locally

Needs JDK 17+ (`JAVA_HOME`), the Android SDK (`ANDROID_HOME`; the NDK is installed by Iceraven's `install-sdk.sh` if
missing), python3, git, about 15 GB disk and 8+ GB RAM.

```
git submodule update --init --depth 1 android-components
git fetch --tags https://github.com/fork-maintainers/iceraven-browser   # only if the iceraven-* tags are missing
raven/prep.sh                                   # once, on a clean checkout
RAVEN_KEYSTORE_PROPS=/path/to/signing.properties raven/build.sh release
raven/build.sh debug                            # RAVEN_ABI=x86_64 to build one ABI
raven/e2e.sh out/<apk> <adb-serial>
```

`signing.properties` has `storeFile`, `storePassword`, `keyAlias`, `keyPassword`. Without it, release APKs are unsigned.
`e2e.sh` keeps existing app data unless `RAVEN_E2E_WIPE=1` (the default in CI). Before publishing, run
`RAVEN_DENYLIST=/path/outside/repo/denylist.txt raven/check-public.sh`.

## CI (`.github/workflows/raven.yml`)

- **build**: `raven/prep.sh`, then a signed `assembleForkRelease` (all ABIs).
- **e2e**: x86_64 APK on an API 34 emulator (KVM); screenshots and logcat are uploaded as `e2e-out`.
- **public-check**: `raven/check-public.sh`, generic checks over our changes and commit identities.
- **release** (push to `raven`, after the above): GitHub release `raven-<iceraven-tag>-r<run>` with APKs and SHA-256 sums.
- **sync-upstream** (daily and manual): when upstream has a newer `iceraven-*` tag, merges it into `sync/<tag>` (from
  `feat/profiles`) and opens a PR into `feat/profiles` in this repository; on a conflict it opens an issue. It
  never pushes to upstream. It also opens a PR `feat/profiles` into `raven` when `raven` is behind.
- Secrets: `RAVEN_KEYSTORE_B64`, `RAVEN_STORE_PASS`, `RAVEN_KEY_ALIAS`, `RAVEN_KEY_PASS`.
- Repository settings: Actions enabled, "Allow GitHub Actions to create pull requests" on. Scheduled workflows run
  only from the default branch, so the daily sync needs `raven.yml` there (or `raven` as the default branch).
  PRs opened with the default token do not trigger workflows; close and reopen them to run CI.

## Signing

Release APKs are signed with a key that lives only in the CI secrets. Installing over a build signed with another key
is rejected: back up and reinstall once.

## License

Mozilla Public License 2.0, like upstream.
