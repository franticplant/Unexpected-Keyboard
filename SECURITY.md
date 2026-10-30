# Security policy

## Supported versions

Security fixes are applied to `main` and to the latest release tag. Older
release tags are not maintained.

## Reporting a vulnerability

Please use GitHub's private vulnerability reporting:

https://github.com/franticplant/Unexpected-Keyboard/security/advisories/new

Do not open a public issue for a vulnerability. If private reporting is not
available, open a minimal issue asking for a private channel; do not include
exploit details.

Include:

- Affected version/tag and Android version.
- A reproduction that does not contain your real messages, passwords or
  clipboard contents.
- Impact: what can an attacker read, write or trigger?
- Whether the issue also affects upstream `Julow/Unexpected-Keyboard`.

## Scope

This is an input method, so the security boundary is unusually sensitive:

- It can read text around the cursor and the clipboard while focused.
- It stores user-defined macros, clipboard history and a personal language
  model in app-private storage.
- It has the `INTERNET` permission for dictionary downloads. The macro,
  clipboard and personal-model features must not use the network.
- Release APKs are signed; the signing key is kept out of the repository and
  exposed only to the `release` GitHub environment.

In scope: data exposure, path traversal in the clipboard content provider,
signature/update-chain issues, injection through `macros.json`, denial of
service in the IME process, and privacy leaks in logs or backups.

Out of scope: issues that require a rooted device or a malicious app with
equivalent privileges, and upstream-only issues that are not reachable through
this fork's changes (still report them upstream).

## Disclosure

We aim to acknowledge reports within 72 hours and to publish a fix and advisory
once a release is available. Please coordinate disclosure timing with the
maintainer.
