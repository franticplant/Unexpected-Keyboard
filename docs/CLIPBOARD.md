# Clipboard overhaul

The upstream clipboard pane is a text-only, in-memory list of six strings plus
a separately persisted list of pinned strings. This fork replaces it with a
real clipboard store that handles text and images, **keeps pinned items across
restarts, and treats everything unpinned as ephemeral**.

## Goals

- Capture text **and images** from the system clipboard.
- Preview images with thumbnails; show dimensions, size and age.
- Paste images into chat/editor apps through `InputConnection.commitContent`
  where supported, with an explicit fallback where it is not.
- Searchable in-memory history with pinning, deletion and a clear-all action.
- **Pinned items persist. Unpinned history is memory-only (text) or cache-only
  (images) and is gone when the IME process dies.**
- Per-entry actions: paste, copy, pin/unpin, share, delete.
- Privacy defaults: password fields excluded, size/TTL limits, no backup of
  clipboard files, no content in logs.
- Keep the keyboard responsive: all disk and bitmap work off the main thread.

## Non-goals (for the first pass)

- Cloud sync.
- OCR or image search.
- Cross-device clipboard.
- Storing external content URIs without copying the bytes. The source app may
  revoke access at any time, so we copy on capture.

## Current state

| Area | Upstream |
| --- | --- |
| History | `List<String>` in memory, max 6, TTL from preferences. |
| Pin | `SharedPreferences` JSON array of strings. |
| Capture | `ClipboardManager.OnPrimaryClipChangedListener`, text items only. |
| Paste | `commitText` only. |
| UI | Two `ListView`s inside a `ScrollView`; text only. |
| Persistence | History lost when the process dies. Pinned text survives. |

Relevant files: `ClipboardHistoryService.java`, `ClipboardHistoryView.java`,
`ClipboardPinView.java`, `ClipboardHistoryCheckBox.java`,
`res/layout/clipboard_pane.xml`.

## Data model

```java
enum Kind { TEXT, IMAGE, URI }

final class ClipEntry {
  long id;              // stable, assigned by the store
  Kind kind;
  String text;          // TEXT
  String mime;          // IMAGE/URI, e.g. image/png
  String file;          // relative path under files/clipboard/
  String thumb;         // relative path under files/clipboard/thumbs/
  int width, height;    // IMAGE
  long size;            // bytes of the stored payload
  long created_at;      // epoch millis
  long last_used_at;
  boolean pinned;
  String sha256;        // dedupe key for text and image bytes
  String label;         // optional, e.g. original filename
}
```

`id` is a monotonically increasing `long` persisted in the index. It is not a
content hash so that duplicates can be detected while still allowing a user to
keep two entries with the same bytes if they explicitly pin both. In practice
the store drops exact duplicates.

## Storage layout

```text
files/clipboard/
  pinned.json                # pinned metadata, atomic replace
  images/<id>.<ext>          # pinned images only
  thumbs/<id>.jpg            # pinned thumbnails only
cacheDir/clipboard/
  images/<id>.<ext>          # unpinned images, cleared on startup
  thumbs/<id>.jpg            # unpinned thumbnails, cleared on startup
```

- `pinned.json` is written to a temp file and renamed into place.
- Unpinned text lives only in memory.
- Unpinned images are copied to `cacheDir` so `commitContent` has a stable
  file; the cache is cleared when the store starts and the OS may evict it.
- Pinned text and image metadata live in `pinned.json`; pinned image bytes live
  in `files/`.
- Limits (defaults, configurable later):
  - 50 unpinned history entries (text + images).
  - 100 pinned entries.
  - 20 MiB per image.
  - 200 MiB total clipboard storage (pinned bytes + unpinned cache).
  - TTL from the existing preference; pinned entries never expire.
- Eviction is LRU among unpinned entries. Pinned entries are only removed by
  the user. Images are evicted before text when the count/size pressure is on
  images.
- `android:allowBackup` must not include `files/clipboard`. Use
  `dataExtractionRules`/`fullBackupContent` to exclude it, or disable backup for
  the app.

## Capture pipeline

`ClipboardMonitor` listens to `OnPrimaryClipChangedListener` and also calls
`capture_current()` when the keyboard view starts, because Android restricts
background clipboard reads.

```text
clipboard changed
  -> read ClipData (catch SecurityException)
  -> for each item:
       text/plain or text/html -> TEXT entry
       image/* with URI       -> copy stream to images/<id>.<ext>
                                 decode bounds -> width/height
                                 generate thumb
                                 IMAGE entry
       other URI              -> URI entry (copy bytes if readable)
  -> dedupe by sha256
  -> insert at head, evict expired/LRU
  -> notify UI (main thread)
```

Rules:

- Never read the clipboard in a password field. The current editor config
  already exposes the input type; add `clipboard_capture_enabled`.
- Ignore empty text and text that is only whitespace.
- Ignore our own writes (set a short `suppress_until` timestamp).
- Copy bytes on a background executor; do not hold the `ClipData` or URI.
- Cap image dimensions for decoding (`inJustDecodeBounds` first) and use
  `inSampleSize` for thumbnails.
- Animated GIF/WebP: store the original; thumbnail the first frame.
- If a stream fails, keep the text fallback (e.g. the URI string) or drop the
  item, but never crash the IME.

## Paste pipeline

### Text

`InputConnection.commitText(text, 1)`, with the existing `CurrentlyTypedWord`
bookkeeping. This is the current path and remains unchanged.

### Images and other rich content

Android 25+ exposes `InputConnection.commitContent(InputContentInfo, int, Bundle)`.
The IME must expose a content URI that the target app can read.

```text
user taps an image entry
  -> build content://<application-id>.clipboard/<id> URI
  -> InputContentInfo(uri, ClipDescription(label, mime), null)
  -> inputConnection.commitContent(info,
       InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)
  -> true: done
  -> false / exception: fallback
```

Requirements:

- A `ContentProvider` (or `androidx.core.content.FileProvider`) declared in the
  manifest, `exported="false"`, `grantUriPermissions="true"`, with a paths XML
  that exposes only `files/clipboard/images/` and `thumbs/`.
- The provider must support `openFile` with `ContentResolver.openInputStream`.
- `commitContent` may throw `SecurityException` or return `false`; catch both.
- Check `EditorInfo.contentMimeTypes` when available. If the app advertises
  MIME types, use the first compatible one; otherwise try the entry's MIME.
- `InputContentInfo` is API 25. On API 21-24, skip `commitContent`.

### Fallback when `commitContent` is unsupported

Not every editor accepts content. In order:

1. **Clipboard + paste key event.** Put the image `ClipData` on the system
   clipboard and send `KEYCODE_PASTE` (or Ctrl+V) to the editor. This works in
   apps that implement image paste themselves, but the target app must be able
   to read our URI. Because `ClipboardManager` has no grant flags, expose a
   short-lived read-only provider URI and keep the file alive while the paste
   completes. Show a toast if the app does not react.
2. **Share intent.** Open the system share sheet with the image URI. This is
   always available and is the honest fallback when the editor cannot accept
   inline content.

Do not silently insert a placeholder string such as `[image]`.

### Copy action

- Text: `ClipboardManager.setPrimaryClip(ClipData.newPlainText(...))`.
- Image: `ClipData.newUri(contentResolver, label, uri)` and set it. Other apps
  may or may not be able to read it; the UI must label this as "Copy image"
  rather than promising a paste.
- Never copy an external URI without first copying the bytes into our store.

## Implementation status

Implemented in this fork:

- `ClipEntry` + `ClipboardStore` with the memory/pinned split.
- Text capture, dedupe, filters, pin/unpin, delete, clear history, clear pinned.
- Image capture into cache (unpinned) or `files/` (pinned), thumbnails, bounds,
  size, dedupe.
- `FileProvider` + `commitContent` image paste, clipboard + paste-key fallback,
  and a share action.
- Password-field capture exclusion, backup exclusion, limits and migration from
  the old pinned-text preference.
- JVM tests for the store and formatting helpers.

Still pending:

- Free-text search.
- Image preview dialog.
- Settings for limits and per-app capture exclusions.
- Manual device matrix (the local emulator crashes in this environment before
  boot, so this still needs a physical Pixel 8 run).

## UI

The two-list `ScrollView` is replaced by one `ListView` under a history toggle
and a filter bar:

```text
+--------------------------------------------------+
| [x] Clipboard history                  [Clear]    |
| [ All ] [ Text ] [ Images ] [ Pinned ]           |
+--------------------------------------------------+
| [thumb] Screenshot 2026-10-30 22:41   2.1 MB  📌 🗑 |
|        1080 x 2400 · 2.1 MiB · 3m ago            |
+--------------------------------------------------+
| "the quick brown fox..."              12:04   📌 🗑 |
+--------------------------------------------------+
| [thumb] pasted image                  11:58   📌 🗑 |
+--------------------------------------------------+
|                     [keyboard bottom row]        |
+--------------------------------------------------+
```

- Filters are `All`, `Text`, `Images`, `Pinned`. Pinned entries sort first.
- Tap = paste. Long-press = Copy, Pin/Unpin, Share, Delete.
- Row buttons pin/unpin and delete; the delete action asks for confirmation.
- `Clear` offers "Clear history" (unpinned) and "Clear pinned".
- Empty states explain when history is off or there is nothing pinned.
- Image thumbnails load on a background executor with an LRU bitmap cache.
- The bottom row keeps the existing `clipboard_bottom_row.xml` keyboard.
- Free-text search is deferred until it can be tested inside the IME window;
  an `EditText` in the input view is a focus/recursion risk that needs a device.

## Privacy and security

- Exclude `files/clipboard` from Android backup.
- Unpinned history is deliberately ephemeral: it is not written to `files/`
  and does not survive the IME process.
- Do not log entry contents. Log only ids, kinds and sizes at debug level.
- Disable capture in password fields and in apps explicitly excluded by the
  user (future per-app setting).
- A "Clear clipboard history" action clears the unpinned list and cache. A
  separate "Clear pinned" action is required for pinned items.
- Pinned images remain until deleted; make that explicit in the UI.
- The content provider is not exported and only serves files under the
  clipboard directories, by id. It must reject path traversal.

## Migration

On first run of the new store:

1. Read the old `pinned_clipboards` SharedPreferences JSON array.
2. Create TEXT entries with `pinned = true`, preserving order where possible.
3. Write `files/clipboard/pinned.json`, then remove the old preference key.
4. Old in-memory history is not recoverable; start empty.

Keep the old preference untouched for one release so a downgrade does not lose
pinned text.

## Implementation phases

1. **Store + capture** — done: `ClipEntry`, `ClipboardStore` text history,
   pinned JSON, migration, unit tests.
2. **Images** — done: cache/files split, thumbnails, bounds/size, dedupe.
3. **Paste** — done: FileProvider, `commitContent`, fallback.
4. **UI** — done for filters, rows, pin/delete/clear, thumbnails; search and
   preview dialog pending.
5. **Polish** — backup exclusion and password-field exclusion done; settings
   for limits/TTL, metrics and the manual matrix pending.

## Test matrix

| App | Text paste | Image `commitContent` | Image fallback | Notes |
| --- | --- | --- | --- | --- |
| WhatsApp | yes | verify | clipboard + paste | |
| Telegram | yes | verify | clipboard + paste | |
| Signal | yes | verify | share | |
| Gmail | yes | n/a | n/a | body is text |
| Chrome address bar | yes | n/a | n/a | no rich content |
| Chrome text field | yes | verify | clipboard + paste | web editors vary |
| Termux | yes | n/a | n/a | raw key events |
| Discord | yes | verify | clipboard + paste | |
| Slack | yes | verify | share | |
| Android Settings search | yes | n/a | n/a | |
| Password field | disabled | disabled | disabled | privacy |

Also test: copy image from Photos, Chrome, Files; rotate the device; kill the
IME process (unpinned history must be gone, pinned must survive); low storage;
20 MiB image; GIF; screenshot; clear history; pin and rebuild the process.
