# Proposing the profiles feature upstream

`feat/profiles` is the upstreamable change: upstream's release tag plus the profiles commits and
`docs/profiles.md`. It has no Raven identity, no Raven tooling and no CI of ours.

## Opening the pull request

1. Make sure `feat/profiles` is based on the newest upstream release tag (see "Rebasing" below) and builds with
   Iceraven's normal prep and `app:assembleForkRelease`.
2. Open a PR from `<our fork>:feat/profiles` to `fork-maintainers/iceraven-browser:iceraven`.
3. Describe what it contains: the app commits (profiles, downloads, shortcuts, link chooser), and five small
   android-components patches in `automation/iceraven/patches/profiles/`, applied by
   `patch_android_components.sh` in the same way as the existing toolbar and top-sites patches. `docs/profiles.md`
   is the feature description for the PR text.
4. Ask the maintainers whether they prefer the series split into several smaller PRs.

Post only after reviewing the final text.

## Rebasing on a new upstream release

```
git fetch --tags https://github.com/fork-maintainers/iceraven-browser
git checkout feat/profiles
git rebase <new-iceraven-tag>      # or merge it, as the sync-upstream workflow does
```

Conflicts are most likely in `patch_android_components.sh` (keep both sets of lines) and in the AC patches under
`automation/iceraven/patches/profiles/`; regenerate a patch against the new android-components pin when it no longer
applies. Then merge `feat/profiles` into `raven`.
