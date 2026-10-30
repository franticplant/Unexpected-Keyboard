# Personal autocorrect

The target device is a Pixel 8. It is fast enough for a small statistical model
but does not guarantee Gemini Nano/AICore. The autocorrect design therefore
starts with **on-device online learning**, not an LLM.

The model learns from what you type and from which corrections you accept. It
never sends text anywhere.

## Baseline

Upstream `Suggestions` uses `cdict`:

1. Exact match.
2. Words with the current word as a prefix (`suffixes`).
3. Words within edit distance 1 (`distance`).
4. An emoji suggestion.

It is context-free: it only sees the current word, not the previous one, and it
never adapts to the user. It is also very fast, which is the property to keep.

## What "self-learning" means here

This is **online incremental learning**, not gradient descent on the phone:

- Count words and word pairs you actually type.
- Learn mappings from a typed form to the correction you accept.
- Track per-app preferences (chat vs. email vs. terminal).
- Decay old counts so the model follows your current vocabulary.
- Persist compactly and reload at IME startup.

A small neural reranker can be added later, trained offline from an exported,
user-owned history. It is not required for the first useful version.

## Implementation status

Implemented in this fork:

- `PersonalModel`: unigram/bigram counts, personal prefix lookup, correction
  memory, decay and caps.
- Candidate reranking in `Suggestions`, plus personal vocabulary candidates.
- Accepted-correction learning and backspace-undo rejection.
- Batched atomic persistence to `files/personal/model.json`; excluded from
  backup.
- Password-field exclusion and a **Learn my typing** setting.
- JVM tests for learning, reranking, corrections, persistence and backspace.

Still pending:

- Per-app weights and per-app opt-out.
- Debug metrics (acceptance, undo rate, ranking latency).
- Export/delete model actions.
- Real-device tuning; the local emulator crashes before boot in this
  environment.
- The optional micro-LLM assist.

## Model

```text
PersonalModel
  unigram:    word -> count
  bigram:     previous_word + "\u0000" + word -> count
  corrections: typed -> intended -> {count, accepted, rejected}
  per_app:    package -> word -> count        (bounded, optional)
  meta:       observations, last_decay, schema_version
```

Limits:

| Item | Default cap |
| --- | --- |
| Vocabulary | 10,000 words |
| Bigrams | 25,000 pairs |
| Corrections | 2,000 mappings |
| Per-app words | 2,000 per package, 20 packages |
| Word length | 64 characters |

Storage: `files/personal/model.json` for the first version (atomic write), then
a compact binary format if startup or size becomes a problem. Writes are
batched on a background executor every few seconds and on IME shutdown.

## Ranking

`cdict` still generates candidates. The personal model reranks them and may add
personal words.

```text
candidates = cdict.find/suffixes/distance
           + personal_prefix_matches(current_word)
           + correction_for(current_word)

score(c) = w_edit   * edit_score(current_word, c)
         + w_uni    * log(1 + unigram[c])
         + w_bi     * log(1 + bigram[previous_word][c])
         + w_typo   * correction_confidence(current_word, c)
         + w_app    * log(1 + app_count[package][c])
         + w_recency* recency(c)
```

Initial weights are hand-tuned and kept in one place. The model only reranks a
small candidate list, so the cost is negligible compared with the existing
dictionary lookup.

Candidate generation rules:

- Personal vocabulary prefix search is capped at 50 results.
- Edit-distance search for personal words is only attempted for words of
  length >= 3 and only against a bounded set (prefix bucket + correction map).
- The current `cdict` candidates always remain available; the personal model
  cannot make the keyboard worse than baseline by returning nothing.

## Learning signals

| Signal | Update |
| --- | --- |
| Word committed with space/punctuation | `unigram[word]++`, `bigram[prev][word]++` |
| Suggestion accepted | `unigram[target]++`, `corrections[typed][target].accepted++` |
| Space autocorrect applied | same as accepted, but with a lower weight |
| Autocorrect undone with backspace | `corrections[typed][target].rejected++`; never suggest it again unless counts recover |
| Word typed after a correction was rejected | `unigram[typed]++` |
| Candidate shown but not chosen | no update (avoid noise) |

Decay:

- Every 2,000 observations, multiply counts by 0.9 and drop entries below 1.
- Corrections with `rejected >= 2` and `accepted == 0` are removed.
- Per-app counts decay twice as fast as global counts.

Password fields, `TYPE_NULL` editors and user-excluded apps are never observed.

## Context

`CurrentlyTypedWord` already tracks the current word. Add a small
`PreviousWordTracker` in `KeyEventHandler`:

- On a word boundary (space, enter, punctuation), push the committed word.
- Keep the last two words plus the current package.
- Reset on selection changes and editor restarts.

The model uses the previous word for bigram scoring. A trigram is optional later;
it costs memory and rarely helps on short chat messages.

## Where it plugs in

```text
KeyEventHandler.currently_typed_word(word)
  -> Suggestions.currently_typed_word(word)
       -> cdict candidates
       -> PersonalModel.rank(word, previous_word, package, candidates)
       -> CandidatesView
```

The existing `Suggestions` API and `CandidatesView` contract stay intact. This
keeps the change reviewable and makes it possible to disable the personal model
with one preference.

Feedback hooks:

- `KeyEventHandler.suggestion_entered` -> `PersonalModel.accepted(...)`.
- `KeyEventHandler.handle_backspace` when undoing an autocorrect ->
  `PersonalModel.rejected(...)`.
- `send_text` on a completed word -> `PersonalModel.observe_word(...)`.

All updates are posted to a single-thread executor; the IME main thread never
waits for disk.

## Privacy

- No network permission is needed for this feature (the app already has
  `INTERNET` for dictionary downloads; the personal model must not use it).
- No text in logs. Counts and ids only.
- Export/delete actions in settings: `Export model` (JSON), `Delete model`.
- Per-app opt-out list, default empty. Password fields are always excluded.
- The model file is excluded from Android backup, like the clipboard store.

## Evaluation

Add a debug screen (hidden behind a long-press in settings) showing:

- Vocabulary size, bigram count, corrections count.
- Top-1/top-3 acceptance rate for suggestions.
- Autocorrect undo rate.
- Average ranking time (microseconds).

The success criterion is not "more corrections"; it is **fewer undos and fewer
keystrokes for the same text**. A model that corrects aggressively but gets
undone is a regression.

## Micro-LLM assist (optional, later)

The user may want a small local model for rewriting or continuing text. Keep it
strictly out of the typing path:

- Triggered by an explicit button/long-press, never by a keystroke.
- LiteRT-LM on Android, with a downloadable 270M-600M model (e.g. Gemma 3 270M
  or Qwen3 0.6B, 4-bit). Pixel 8 performance and thermals must be measured
  before shipping.
- Uses: "rewrite this message", "continue this sentence", "fix grammar",
  "make it shorter". Not autocorrect, not next-word.
- The classical model remains the default and the fallback.
- Model download is explicit, resumable and size-labeled. No silent 500 MB
  download.
- If Gemini Nano becomes available on the device, an adapter can use the ML Kit
  Prompt API, but it is not a dependency.

## Phases

1. **Instrumentation**: previous-word tracking, observation hooks, debug counts,
   no ranking changes. Verify no latency regression.
2. **Personal vocabulary**: unigram/bigram counts, prefix candidates, rerank.
3. **Corrections**: accepted/rejected typo map, undo handling, per-app weights.
4. **Persistence**: atomic model file, decay, caps, export/delete.
5. **Evaluation**: acceptance/undo metrics, tune weights on real usage.
6. **Optional micro-LLM**: explicit rewrite actions, LiteRT-LM, model manager.
