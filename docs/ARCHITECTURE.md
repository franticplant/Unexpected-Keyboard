# Architecture

This document describes the fork's additions around the upstream keyboard. It
is intentionally written for someone who has to debug the IME on a device, not
as a tour of every upstream file.

## Upstream core (unchanged unless noted)

| File | Responsibility |
| --- | --- |
| `Keyboard2.java` | InputMethodService; lifecycle, input view, editor actions. |
| `Keyboard2View.java` | Keyboard rendering and touch dispatch. |
| `Pointers.java` | Multi-touch, swipe directions, modifiers. |
| `KeyValue.java` | Key kinds and the key vocabulary. |
| `KeyValueParser.java` | Parses custom-layout key definitions and macros. |
| `KeyEventHandler.java` | Turns key events into text, key events, edits. |
| `CurrentlyTypedWord.java` | Tracks the word under the cursor with minimal IPC. |
| `Suggestions.java` | Candidate generation from the `cdict` dictionary. |
| `Autocapitalisation.java` | Sentence/word capitalisation state. |
| `Config.java` | Preferences and per-editor configuration. |
| `EditorConfig.java` | Input-type/package-specific editor behaviour. |

## Fork additions

```text
srcs/juloo.keyboard2/
  macros/
    AppPattern.java          glob matching for package names
    MacroEntry.java          one snippet or key macro
    MacroMatcher.java        longest-trigger lookup
    MacroStore.java          JSON load/save, variables, singleton
    MacroEditorActivity.java in-app JSON editor
  clipboard/                 (planned, see docs/CLIPBOARD.md)
  personal/                  (planned, see docs/AUTOCORRECT.md)
```

### Macro path

```text
KeyEventHandler.key_up
  -> send_text / handle_space_bar / keyevent enter|tab
       -> try_expand_macro(after)
            -> MacroStore.match(package, textBeforeCursor)
            -> replace_surrounding_text(trigger, expansion)
            -> optional evaluate_macro(keys)
```

Design rules:

- One `getTextBeforeCursor` call per delimiter, and only if the store has at
  least one entry.
- Snippets are disabled for password fields (`EditorConfig.snippets_enabled`).
- The store is loaded once when the IME service starts. A broken JSON file
  keeps the previous in-memory configuration rather than crashing.
- The editor validates JSON before replacing the file.

### Clipboard path (planned)

```text
ClipboardManager listener
  -> ClipboardStore.ingest(ClipData)  [background executor]
       -> text: index.json
       -> image: copy bytes, thumbnail, index.json
  -> notify RecyclerView adapter

paste tap
  -> text:  InputConnection.commitText
  -> image: InputConnection.commitContent(FileProvider URI)
             -> false/unsupported: clipboard + paste key event or share sheet
```

See [CLIPBOARD.md](CLIPBOARD.md) for the model, limits and test matrix.

### Personal autocorrect path (planned)

```text
currently typed word
  -> cdict candidate generation (fast, existing)
  -> PersonalModel.rank(word, context, candidates)
       unigram/bigram counts, typo map, per-app weights
  -> CandidatesView

feedback
  accepted suggestion / space autocorrect / undo / typed word
  -> PersonalModel.observe(...)  [background, batched]
```

Design rules:

- No LLM, network or disk read on the per-keystroke path.
- `cdict` stays the candidate generator; the personal model only reranks and
  adds personal vocabulary.
- Learning is incremental and bounded (vocabulary cap, count decay).
- Password fields are excluded from learning and suggestions.
- The model is persisted as a compact binary/JSON file, not a database, to keep
  startup cheap.

## Threading and budgets

| Work | Thread | Budget |
| --- | --- | --- |
| Key handling, text commit | IME main thread | < 2 ms typical |
| Snippet match | IME main thread | one IPC only on delimiter |
| Candidate generation | IME main thread | existing `cdict` path |
| JSON macro load | IME startup | < 10 ms for a few dozen entries |
| Clipboard image copy/thumb | background executor | not user-blocking |
| Personal model update | background, batched | not user-blocking |
| LLM assist (optional) | background, explicit action | seconds, never inline |

## Failure behaviour

- Broken `macros.json`: keep the last good entries; the editor shows the parse
  error.
- Missing clipboard file: drop the entry, do not crash.
- `commitContent` failure: fall back to clipboard + paste, then share sheet.
- Personal model corruption: rebuild from the current dictionary and history;
  typing still works.
- All optional AI features are off by default and must degrade to the classical
  path.

## Tests

- JVM unit tests: pure matchers, parsers, ranking functions.
- Instrumented tests (planned): clipboard store with a temp directory,
  `commitContent` against a fake `InputConnection`.
- Manual matrix: see the tables in `MACROS.md`, `CLIPBOARD.md` and
  `AUTOCORRECT.md`.
