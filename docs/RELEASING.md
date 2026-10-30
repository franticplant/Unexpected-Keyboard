# Releasing

Releases are built by `.github/workflows/release.yml` from a tag, signed with
the release keystore, checksummed, SBOM-attested and published as a GitHub
release.

## Version scheme

The fork stays close to the upstream version it is based on and adds a fork
suffix:

```text
2.1.0-fork.1
```

- `2.1.0` is the upstream base version at the time of the fork.
- `fork.N` increments for fork releases on that base.
- A tag is the same string with a `v`: `v2.1.0-fork.1`.
- `versionCode` is computed from the version: `major * 1_000_000 +
  minor * 10_000 + patch * 100 + fork`. For `2.1.0-fork.1` that is `2010001`.
  This stays above upstream's versionCode and leaves room for patches.

Tags containing `-` (including every `-fork.N`) are published as pre-releases.

## One-time setup

Create the release keystore outside the repository:

```sh
keytool -genkeypair -v \
  -keystore unexpected-release.jks \
  -alias unexpected \
  -keyalg RSA -keysize 4096 -validity 10000
```

Base64-encode it for GitHub:

```sh
base64 -w0 unexpected-release.jks > unexpected-release.jks.b64
```

Configure the `release` environment in the repository settings, then add:

| Kind | Name | Value |
| --- | --- | --- |
| Secret | `RELEASE_KEYSTORE_BASE64` | contents of `unexpected-release.jks.b64` |
| Secret | `RELEASE_KEYSTORE_PASSWORD` | keystore password |
| Secret | `RELEASE_KEY_PASSWORD` | key password (optional) |
| Variable | `RELEASE_KEY_ALIAS` | key alias (default `unexpected`) |

Protect the `release` environment with required reviewers or a tag rule. The
keystore is never committed; `.gitignore` already excludes `*.keystore` and
`*.jks` should be added if it is not covered.

Back up the keystore and passwords somewhere safe. Losing them means users
cannot upgrade in place.

## Cutting a release

1. Update `versionName`/`versionCode` defaults in `build.gradle.kts` if the
   base version changed (the workflow passes the tag-derived values).
2. Write `docs/release-notes/<tag>.md`. Without it, GitHub's generated notes
   are used.
3. Run locally:
   ```sh
   ./gradlew testDebugUnitTest lintDebug assembleDebug
   ```
4. Commit, push `main`, and wait for CI.
5. Tag and push:
   ```sh
   git tag -s v2.1.0-fork.1 -m "v2.1.0-fork.1"
   git push origin v2.1.0-fork.1
   ```
   The tag must be signed if branch protection requires it. If signing is not
   available, use an annotated tag and document the exception.
6. The workflow builds, attests and publishes. A manual run is possible with
   `workflow_dispatch` and an existing tag.

## Artifacts

- `unexpected-keyboard-<tag>.apk` — signed release APK.
- `unexpected-keyboard-<tag>.spdx.json` — SPDX SBOM.
- `SHA256SUMS` — checksums for both.

## Verifying a download

```sh
gh attestation verify unexpected-keyboard-v2.1.0-fork.1.apk \
  -R franticplant/Unexpected-Keyboard

sha256sum -c SHA256SUMS
```

The workflow verifies the checksums and attestations again in the publish job
after the artifact round-trip.

## Development APKs

`main` also produces a `debug` build signed with the release key when the
secrets are configured. It uses the `.debug` application id, so it installs
alongside a release build and can be updated without uninstalling. Pull
requests never receive the signing secrets.

## Reproducibility

Android/Gradle builds are not guaranteed reproducible because of timestamps,
dependency resolution and signing. The release workflow does not claim
byte-for-byte reproducibility. A future script can compare `assembleRelease`
outputs with the same inputs and document the remaining nondeterminism.

## Rollback

Releases are immutable. If a release is broken:

1. Delete or mark the GitHub release as a pre-release if it has not been
   widely distributed.
2. Publish a new patch tag; never move an existing tag.
3. If the signing key is compromised, rotate it, publish a security advisory,
   and document the new certificate fingerprint in the release notes.
