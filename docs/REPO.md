# Repository workflow

This is a fork of [Julow/Unexpected-Keyboard](https://github.com/Julow/Unexpected-Keyboard).
The fork is the product; upstream is the source of the keyboard engine.

## Remotes and branches

```text
origin    https://github.com/franticplant/Unexpected-Keyboard.git
upstream  https://github.com/Julow/Unexpected-Keyboard.git
```

- `main` is the fork's integration branch and the default branch.
- `upstream/master` is read-only. Never commit to it.
- Feature work happens on short-lived branches such as `macros/picker` or
  `clipboard/images`, then merges into `main`.
- Release tags are cut from `main`: `v2.1.0-fork.1`.

## Syncing upstream

```sh
git fetch upstream
git checkout main
git merge upstream/master
```

Resolve conflicts in favor of the fork's features, keep upstream behaviour
otherwise, and run the full test/lint/build loop. Generated files must be
regenerated, not hand-merged.

## Commit style

Use a short prefix and an imperative subject:

```text
macros: add per-app profile matching
clipboard: persist image entries and thumbnails
autocorrect: rank candidates with a bigram model
ci: pin actions and add release attestations
docs: document clipboard fallbacks
```

Keep commits focused. A commit should build and pass tests on its own unless it
is explicitly a work-in-progress commit on a topic branch (do not merge those
without squashing).

## Pull requests

Every PR must:

- Explain the user-visible change and the risk.
- Include tests for new logic (JVM tests where possible).
- Keep generated files current (`check-generated` CI job).
- Pass `testDebugUnitTest`, `lintDebug` and `assembleDebug`.
- Update docs when behaviour or configuration changes.
- Avoid new work on the per-keystroke path without measurements.
- Avoid secrets, keystores, personal clipboard content and real message text.

UI changes need a before/after screenshot or a short screen recording.

## CI

| Workflow | Runs | Purpose |
| --- | --- | --- |
| `ci.yml` | push/PR | tests, lint, debug APK, signed dev APK on `main` |
| `check-generated.yml` | push/PR | regenerates layouts/compose/method XML and fails on drift |
| `release.yml` | `v*` tags | signed release, checksums, SBOM, attestations, GitHub release |

Actions are pinned by commit SHA. Dependabot updates them weekly, grouped.

## Repository settings checklist

Enable and keep:

- [x] Default branch `main`.
- [x] Branch protection on `main`: require `Build and test` and
      `Regenerate and compare`; disallow force-push and deletion.
- [ ] Require signed commits (after the signing key is registered).
- [x] Secret scanning and push protection.
- [x] Dependabot alerts and security updates.
- [x] Private vulnerability reporting (see `SECURITY.md`).
- [ ] `release` environment with required reviewers and the signing secrets.
- [x] Actions permissions: read-only by default; workflows request what they
      need explicitly.
- [ ] Immutable releases, if available for the repository.

## Signing

Commits are signed with an SSH key:

```sh
git config gpg.format ssh
git config user.signingkey ~/.ssh/franticplant_signing.pub
git config commit.gpgsign true
```

The public key must be registered on GitHub as a **signing key** (not only as
an authentication key), otherwise GitHub reports `unknown_key`. The key is
passphrase-protected; unlock it with an agent before committing:

```sh
eval "$(ssh-agent -s)"
ssh-add ~/.ssh/franticplant_signing
```

Release APK signing is separate; see `docs/RELEASING.md`.

## Fork identity

The application id is still `juloo.keyboard2` (debug builds use
`juloo.keyboard2.debug`). This keeps the fork drop-in compatible with upstream
settings and dictionaries. A future release may switch to a fork-specific id;
that requires a migration plan and a fresh signing identity, so it is a
deliberate release decision, not a drive-by change.
