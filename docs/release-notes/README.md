# Release notes

One file per tag, named exactly like the tag:

```text
docs/release-notes/v2.1.0-fork.1.md
```

The release workflow reads this file at the tag. If it is missing, GitHub's
generated notes are used. Keep notes user-facing: what changed, what to test,
and any migration or signing changes. Do not include internal-only details.

A release note should contain:

```markdown
## Highlights

- ...

## Upgrade notes

- ...

## Known issues

- ...
```
