# Macros and snippets

This fork adds a user-editable macro layer on top of Unexpected Keyboard's
layout-level macros.

The goal is deliberately small: **type a trigger, get text or a key sequence.**
The layer is local, synchronous and has no network dependency.

## Where it lives

The configuration is a JSON file in the app's private files directory:

```text
/data/data/<application-id>/files/macros.json
```

On a debug build the application id ends in `.debug`. The settings screen
(`Macros & snippets`) contains a built-in editor; the file can also be pulled
and pushed with adb:

```sh
adb shell run-as juloo.keyboard2.debug cat files/macros.json
adb push macros.json /data/local/tmp/macros.json
adb shell run-as juloo.keyboard2.debug cp /data/local/tmp/macros.json files/macros.json
```

The store reloads when the keyboard service starts. After editing through adb,
switch away from and back to the keyboard, or reboot.

## How expansion works

When you press **space**, **enter** or **tab**, the keyboard looks at the text
immediately before the cursor. If the end of that text matches an enabled
trigger for the current app, the trigger is replaced by the expansion.

- The **longest** matching trigger wins.
- Matching is case-sensitive and literal (no regex in triggers).
- Snippets are disabled in password fields.
- The trigger must be at the end of the text before the cursor; `;sig` does not
  expand in the middle of a word.
- If `append_delimiter` is `false`, the space/enter/tab that triggered the
  expansion is not kept. This is useful for snippets that end with punctuation
  or a newline.

## Schema

```json
{
  "version": 1,
  "snippets": [
    {
      "name": "signature",
      "trigger": ";sig",
      "text": "Best,\nEnes",
      "apps": ["com.whatsapp", "org.telegram.*"],
      "exclude_apps": ["com.example.work"],
      "enabled": true,
      "append_delimiter": true
    }
  ],
  "macros": [
    {
      "name": "select_all",
      "trigger": ";selall",
      "keys": "ctrl,a",
      "enabled": true
    }
  ]
}
```

### Common fields

| Field | Required | Meaning |
| --- | --- | --- |
| `name` | no | Human-readable name. Defaults to the trigger. |
| `trigger` | yes | Literal text that triggers the entry. |
| `enabled` | no | Defaults to `true`. Disabled entries are ignored. |
| `apps` | no | Package-name patterns this entry is limited to. |
| `exclude_apps` | no | Package-name patterns where this entry must not run. |
| `append_delimiter` | no | Defaults to `true`. Keep the triggering space/enter/tab. |

`apps` and `exclude_apps` accept a string or an array of strings. Patterns
support `*` (any sequence) and `?` (one character), for example
`org.telegram.*`.

### Text snippets

Use `text` with an optional set of variables:

| Variable | Result |
| --- | --- |
| `{date}` | `2026-10-30` |
| `{time}` | `22:41` |
| `{datetime}` | `2026-10-30 22:41` |
| `{clipboard}` | Current clipboard text, truncated to 8192 characters. |
| `{app}` | Current package name. |
| `{newline}` | A newline. |
| `{tab}` | A tab. |

Unknown variables are left as written. A literal `{` is not special unless it
is followed by a closing `}`; there is currently no escape sequence.

### Key macros

Use `keys` with the same syntax as the layout-level macro feature:

- `ctrl,a` — press Ctrl, then A.
- `ctrl,c` — copy.
- `ctrl,shift,left` — select one word to the left.
- `keyevent:67` — send Android keycode 67 (backspace).

Multiple keys are separated by commas. The full key-name vocabulary is in
[Possible key values](../doc/Possible-key-values.md).

A key macro is evaluated after the trigger is deleted. If `append_delimiter`
is `true` and it was triggered by space, a space is sent after the sequence.

## Examples

**Signature only in chat apps**

```json
{"trigger": ";sig", "text": "— Enes", "apps": ["com.whatsapp", "org.telegram.*"]}
```

**Termux path**

```json
{"trigger": ";proj", "text": "/data/data/com.termux/files/home/projects/", "apps": ["com.termux"]}
```

**Select all, copy, then paste**

```json
{"trigger": ";dupe", "keys": "ctrl,a,ctrl,c"}
```

**Timestamp with no trailing space**

```json
{"trigger": ";ts", "text": "[{datetime}]", "append_delimiter": false}
```

## Design constraints

- Expansion runs on the IME main thread and must stay fast. There is one
  `getTextBeforeCursor` call per delimiter, only when at least one entry exists.
- The keyboard does not execute shell commands, JavaScript or arbitrary code
  from `macros.json`. Only literal text and known key definitions are allowed.
- Clipboard content is read only when a `{clipboard}` variable is expanded, not
  on every keypress.
- Snippets are disabled for password fields. This is a privacy boundary, not a
  preference.

## Known limitations

- No macro picker UI yet; entries are invoked by typing a trigger.
- No conditions, variables from selection, or per-app layouts yet.
- No undo for an expansion; backspace removes one character at a time.
- The editor is raw JSON. Validation errors are shown before saving.
- `{selection}` is not supported. Triggering a snippet replaces the selection
  through the normal commit path, so the selection is not available as input.

## Tests

`test/juloo/keyboard2/macros/MacroMatcherTest.java` covers longest-trigger
selection, app restrictions, glob matching, disabled entries and exclusions.
