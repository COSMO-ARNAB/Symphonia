#!/bin/sh
# Symphonia progress.md auto-log (called by the post-commit hook).
# Appends "[date] <short-hash> <subject>" to the AUTO-COMMIT-LOG section of
# progress.md (repo root) after every commit. progress.md is local-only
# (.gitignore excludes *.md), so this never enters history.
# Never fails: a logging hook must not block a commit.

MARKER="<!-- AUTO-COMMIT-LOG -->"
ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || exit 0
PROGRESS="$ROOT/progress.md"
# Gate: only act in repos that ship this script (core.hooksPath can be global).
[ -f "$ROOT/scripts/progress-commit-log.sh" ] || exit 0
[ -f "$PROGRESS" ] || exit 0

HASH=$(git rev-parse --short=7 HEAD 2>/dev/null) || exit 0
SUBJECT=$(git log -1 --format=%s 2>/dev/null) || exit 0
DATE=$(git log -1 --format=%ad --date=format:%Y-%m-%d 2>/dev/null) || exit 0
[ -n "$HASH" ] && [ -n "$SUBJECT" ] || exit 0

# Create the log section on first commit if the marker is absent.
if ! grep -qF "$MARKER" "$PROGRESS" 2>/dev/null; then
    printf '\n%s\n\n## Auto Commit Log\n' "$MARKER" >> "$PROGRESS"
fi
printf '%s %s %s\n' "$DATE" "$HASH" "$SUBJECT" >> "$PROGRESS"
exit 0
