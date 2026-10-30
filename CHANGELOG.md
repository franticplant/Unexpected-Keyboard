# Changelog

All notable changes to this fork. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
the upstream base plus `-fork.N` (see [docs/RELEASING.md](docs/RELEASING.md)).

## [Unreleased]

### Added

- User-defined snippets and named key macros with per-app package globs.
- Snippet variables: date, time, datetime, clipboard, app, newline, tab.
- In-app `macros.json` editor with validation, examples and reset.
- Per-app exclusion of snippets in password fields.
- Unit tests for trigger matching and package patterns.
- Clipboard v2: in-memory text/image history, pinned persistence, image
  capture and thumbnails, filters, pin/delete/clear actions.
- Image paste through `InputConnection.commitContent`, with clipboard + paste
  and share fallbacks.
- FileProvider for clipboard content, backup exclusion, password-field capture
  exclusion, migration from the old pinned-text preference.
- Clipboard store and formatting unit tests.
- Personal autocorrect v1: local unigram/bigram model, personal prefix
  vocabulary, accepted/rejected correction memory, candidate reranking,
  bounded atomic persistence, decay and caps.
- **Learn my typing** setting; password-field exclusion and backup exclusion
  for the model file.
- Personal model unit tests for learning, reranking, corrections, persistence
  and backspace.
- Architecture, macro, clipboard, autocorrect, repository and release docs.
- CI: unit tests, lint baseline, debug APK, generated-file check, signed dev APK.
- Release workflow: signed APK, checksums, SPDX SBOM, Sigstore attestations.

### Changed

- Build now pins the NDK and derives the fork version from Gradle properties.
- Fork version starts at `2.1.0-fork.1` (`versionCode` 2010001).
- The clipboard pane replaces the two-list text-only UI with filters, image
  rows and row actions.

### Fixed

- Nothing yet.

## [2.1.0-fork.1] — unreleased

First fork release. Based on upstream Unexpected Keyboard 2.1.0 (commit
`dcda68e`).

### Added

- Macros and snippets as described above.

[Unreleased]: https://github.com/franticplant/Unexpected-Keyboard/compare/v2.1.0-fork.1...HEAD
[2.1.0-fork.1]: https://github.com/franticplant/Unexpected-Keyboard/releases/tag/v2.1.0-fork.1
