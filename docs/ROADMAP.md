# Roadmap

Milestones are ordered by user value and by how much they de-risk the next one.
Each milestone ends with a buildable APK and a green CI run.

## M0 — Fork hygiene

- [x] Local clone with `upstream` and fork remotes.
- [x] Fork-specific docs: architecture, macros, clipboard, autocorrect.
- [x] Unit tests wired into Gradle.
- [ ] CI: build, unit tests, lint, generated-file check.
- [ ] Release workflow: signed APK, checksums, SBOM, attestation.
- [ ] Dependabot for Gradle and GitHub Actions.
- [ ] Issue/PR templates and security policy.
- [ ] `main` branch protection: required checks, no force-push.

**Done when:** a pull request runs build + tests + lint + generated-file
checks, and a tag produces a signed APK with a SHA-256 file and an attestation.

## M1 — Macros and snippets

- [x] `macros.json` store with atomic writes and validation.
- [x] Longest-trigger matching, per-app globs, exclusions.
- [x] Text variables: date/time/datetime/clipboard/app/newline/tab.
- [x] Named key macros using the existing key vocabulary.
- [x] In-app JSON editor with example and reset.
- [x] Password-field exclusion.
- [x] Unit tests for matching and app patterns.
- [ ] Macro picker key and searchable macro list.
- [ ] Per-app profile UI (not raw JSON).
- [ ] Import/export via the system file picker.

**Done when:** a user can define a snippet for one app, trigger it with a
delimiter, and undo/disable it without editing code.

## M2 — Clipboard overhaul

- [ ] `ClipEntry`/`ClipboardStore` with persistent index.
- [ ] Text history with search, pin, delete, clear.
- [ ] Image capture, file storage, thumbnails, dimensions/size.
- [ ] `FileProvider` + `commitContent` image paste.
- [ ] Clipboard + paste fallback and share fallback.
- [ ] RecyclerView UI with tabs and image rows.
- [ ] Migration from the old pinned-text preference.
- [ ] Backup exclusion and privacy tests.
- [ ] Manual matrix on WhatsApp, Telegram, Signal, Gmail, Chrome, Termux.

**Done when:** copying an image in Photos and tapping it in the keyboard pastes
it into at least one major chat app via `commitContent`, and the fallback is
clear when an app does not support it.

## M3 — Personal autocorrect

- [ ] Previous-word tracker and observation hooks.
- [ ] Unigram/bigram personal model with bounded storage.
- [ ] Reranking of `cdict` candidates; personal prefix vocabulary.
- [ ] Accepted/rejected correction map with undo handling.
- [ ] Per-app weights and opt-out.
- [ ] Batched atomic persistence, decay, caps.
- [ ] Debug metrics: acceptance rate, undo rate, ranking latency.
- [ ] Export/delete model.

**Done when:** after a week of use, the undo rate is lower than baseline and
top-1 acceptance is higher, with no measurable typing latency regression.

## M4 — Optional micro-LLM assist

- [ ] Model manager: explicit download, size/thermal warnings, delete.
- [ ] LiteRT-LM integration behind a feature flag.
- [ ] Explicit actions: rewrite, shorten, fix grammar, continue.
- [ ] No per-keystroke inference; classical path remains default.
- [ ] Measure Pixel 8 latency, memory and battery.

**Done when:** the feature is useful on demand and completely absent from the
typing latency path.

## M5 — Release quality

- [ ] Reproducible build check (best effort; document non-determinism).
- [ ] Signed dev builds on `main` for seamless upgrades.
- [ ] Release notes per tag.
- [ ] Crash/ANR triage guide.
- [ ] F-Droid metadata decision (fork id, description, screenshots).
- [ ] Optional Play internal track.

## Explicit non-goals

- Cloud sync of macros, clipboard or personal model.
- Telemetry or analytics.
- An LLM in the autocorrect hot path.
- Supporting every Android 21 device for rich clipboard features; features
  degrade by API level.
