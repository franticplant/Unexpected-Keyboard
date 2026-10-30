# Unexpected Keyboard — custom fork

[![CI](https://github.com/franticplant/Unexpected-Keyboard/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/franticplant/Unexpected-Keyboard/actions/workflows/ci.yml)
[![Release](https://github.com/franticplant/Unexpected-Keyboard/actions/workflows/release.yml/badge.svg)](https://github.com/franticplant/Unexpected-Keyboard/actions/workflows/release.yml)

A personal fork of [Julow/Unexpected-Keyboard](https://github.com/Julow/Unexpected-Keyboard)
focused on three things:

1. **User-defined macros and snippets** that work per app.
2. **A real clipboard**: text and images, persistent, searchable, pinnable.
3. **Self-learning autocorrect** that runs entirely on the device, with a
   micro-LLM only as an optional, explicit assistant.

The upstream keyboard engine — swipe-to-type symbols, programmable layouts,
compose sequences, dictionaries — stays intact.

> Status: early. The macro engine is implemented and tested. The clipboard and
> personal autocorrect are designed and being built. See
> [docs/ROADMAP.md](docs/ROADMAP.md).

## What works now

### Macros and snippets

Type a trigger, followed by space, enter or tab:

```json
{
  "snippets": [
    {"trigger": ";date", "text": "{date}"},
    {"trigger": ";sig", "text": "Best,\nEnes", "apps": ["com.whatsapp", "org.telegram.*"]}
  ],
  "macros": [
    {"trigger": ";selall", "keys": "ctrl,a"}
  ]
}
```

- Longest-trigger matching, text variables, key sequences.
- Per-app allow-lists and exclusions using package-name globs.
- Snippets are disabled in password fields.
- In-app editor under **Settings → Macros & snippets**.
- Full schema and examples: [docs/MACROS.md](docs/MACROS.md).

### Clipboard (in progress)

The design replaces the six-item text-only list with:

- Persistent text and image history.
- Thumbnails, dimensions, size and age.
- Search, pin, delete, clear-all.
- Image paste through `InputConnection.commitContent`, with a clipboard +
  paste-key fallback and a share-sheet fallback.
- Storage limits, deduplication and backup exclusion.

See [docs/CLIPBOARD.md](docs/CLIPBOARD.md).

### Personal autocorrect (in progress)

`cdict` remains the fast candidate generator. A personal model reranks its
candidates and learns from accepted/rejected corrections:

- Unigram/bigram counts, personal vocabulary, typo map.
- Per-app weights, bounded storage, decay.
- No LLM, no network, no disk read on the keystroke path.
- Optional micro-LLM for explicit rewrite actions only.

See [docs/AUTOCORRECT.md](docs/AUTOCORRECT.md).

## Install

Download the latest signed APK from
[Releases](https://github.com/franticplant/Unexpected-Keyboard/releases), or
build it locally.

The application id is still `juloo.keyboard2` (debug: `juloo.keyboard2.debug`),
so a debug build installs alongside the upstream app. The fork does not share
the upstream release signing key; a release APK cannot update the upstream app
in place.

## Build

Requirements:

- JDK 21
- Android SDK platform 36 and NDK `27.0.12077973`
- Python 3 for generated files
- Git submodules

```sh
git submodule update --init --recursive
export ANDROID_HOME="$HOME/Android/Sdk"
./gradlew assembleDebug
```

The APK is written to `build/outputs/apk/debug/`. To install on a connected
device:

```sh
./gradlew installDebug
# or
adb install -r build/outputs/apk/debug/Unexpected-Keyboard-debug.apk
```

Checks:

```sh
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
```

Lint uses `lint-baseline.xml` for pre-existing upstream issues. New issues fail
the build.

## Documentation

| Document | Contents |
| --- | --- |
| [Architecture](docs/ARCHITECTURE.md) | Modules, data flow, threading and budgets. |
| [Macros](docs/MACROS.md) | JSON schema, variables, per-app profiles, limitations. |
| [Clipboard](docs/CLIPBOARD.md) | Entry model, storage, image capture and paste, UI, test matrix. |
| [Autocorrect](docs/AUTOCORRECT.md) | Personal model, ranking, learning signals, micro-LLM policy. |
| [Roadmap](docs/ROADMAP.md) | Milestones and acceptance criteria. |
| [Repository](docs/REPO.md) | Branches, commits, CI, signing, settings. |
| [Releasing](docs/RELEASING.md) | Versioning, secrets, tags, attestations, rollback. |
| [Security](SECURITY.md) | Threat model and private reporting. |

## Privacy

- Macros, clipboard history and the personal model are local files.
- There is no telemetry.
- The only network use is the upstream dictionary download feature.
- Clipboard files and the personal model are designed to be excluded from
  backup.
- Password fields are excluded from snippets, suggestions and learning.

## Relationship to upstream

This is an independent fork, not affiliated with or endorsed by Julow. The
keyboard engine and layouts come from upstream under GPL-3.0. Fork-specific
code is also GPL-3.0-only. Please send upstream bug reports to
[Julow/Unexpected-Keyboard](https://github.com/Julow/Unexpected-Keyboard); send
fork-specific issues here.

## License

GPL-3.0. See [LICENSE](LICENSE).
