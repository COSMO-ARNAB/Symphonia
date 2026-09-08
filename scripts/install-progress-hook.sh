#!/bin/sh
# Installs the progress.md auto-log call into the repo's post-commit hook.
# Inserts it FIRST (before any existing hook content, e.g. graphify's rebuild
# hook, whose early `exit 0` paths would skip anything appended after it).
# The inserted snippet is a single call line with no early exits, so any
# pre-existing hook logic still runs afterwards.
# Idempotent: skips insertion if the marker comment is already present.

set -e
ROOT="$(git rev-parse --show-toplevel)"
# Respect a configured core.hooksPath (git ignores .git/hooks when set).
HOOK_DIR="$(git config core.hooksPath || printf '%s/.git/hooks' "$ROOT")"
mkdir -p "$HOOK_DIR"
HOOK="$HOOK_DIR/post-commit"
SNIPPET_FILE="$ROOT/scripts/progress-commit-log.sh"

[ -f "$SNIPPET_FILE" ] || { echo "error: $SNIPPET_FILE not found"; exit 1; }

SNIPPET='# symphonia-progress-log-start
# Auto-append commit subject to progress.md (see scripts/progress-commit-log.sh)
"'"$SNIPPET_FILE"'" >/dev/null 2>&1 || true
# symphonia-progress-log-end'

if [ -f "$HOOK" ] && grep -q "symphonia-progress-log-start" "$HOOK" 2>/dev/null; then
    echo "progress-log snippet already installed in post-commit hook"
    exit 0
fi

mkdir -p "$HOOK_DIR"
# Build hook content: shebang first (git spawn requires it on line 1), then our
# snippet, then any pre-existing content with its own shebang line stripped
# (it would otherwise sit mid-file as a dead comment).
if [ -f "$HOOK" ]; then
    TMP="$HOOK.tmp"
    printf '#!/bin/sh\n\n' > "$TMP"
    printf '%s\n\n' "$SNIPPET" >> "$TMP"
    tail -n +2 "$HOOK" | sed '1{/^#!/d}' >> "$TMP"
    mv "$TMP" "$HOOK"
else
    printf '#!/bin/sh\n\n%s\n' "$SNIPPET" > "$HOOK"
fi
chmod +x "$HOOK"
echo "installed progress-log snippet into $HOOK"
