# Profiles

Profiles let you keep separate browsing identities in one browser: each profile has its own cookies, site storage,
logins and (optionally) downloads. Profiles are built on Gecko containers (`contextId`), which Iceraven already
carries; this feature adds the UI and the plumbing around them.

## What it does

- Create, rename and delete profiles in Settings. Cookies and site data are isolated per profile and cleared when a
  profile is deleted.
- Tabs show a coloured dot for their profile. Popups and new windows inherit the profile of the opener.
- "Open in profile" in the main menu reopens the current page in another profile.
- Home screen shortcuts can target a profile (and desktop mode); they work on cold start.
- Downloads can be stored privately per profile in app-only storage, with a file manager in the Downloads screen
  (profile filter, move, export, import) and an in-app picker for uploads.
- Links opened from other apps can ask which profile to use ("Open link in", with an "always" rule per site).

## Where the code is

- App: `app/src/main/java/org/mozilla/fenix/`, mainly the `profiles` package (profile model, settings screens, shortcuts),
  Downloads (`downloads/listscreen`), tab tray markers and the intent receiver.
- android-components: five small patches in `automation/iceraven/patches/profiles/`, applied by
  `automation/iceraven/patch_android_components.sh` together with Iceraven's other patches. They cover contextId
  inheritance in popups, re-adding an existing container, a per-context downloads directory hook, optional extras
  on home screen shortcuts and an optional file source chooser before the system picker.

## Building

Nothing changes: run Iceraven's usual prep (`patch_android_components.sh`, `setup_venv.sh`) and build
`app:assembleForkRelease` as before.
