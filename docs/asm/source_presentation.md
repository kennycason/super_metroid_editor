# Source annotations and the editor gutter

The source viewer and project editor hide exact `;A0C895;`-style annotations
from code. A following description remains an ordinary comment:
`dw CLCRTS_A0C2BC ;A0C895; 4: Shootable air` displays as
`dw CLCRTS_A0C2BC ; 4: Shootable air`. Existing whitespace is retained; this is
not a source formatter or a baseline migration.

Click anywhere in the gutter to cycle **Line numbers → Original SNES addresses →
Lines + original SNES addresses**. There is no separate label or header button;
the whole column, including blank rows, is clickable. It follows horizontal
scrolling and does not take typing focus from the editor. This preference is
shared by workspace panes for the session. An address comes only from an exact six-hex-digit tag
at the beginning of an actual comment. Quoted strings, ordinary comments,
labels, `org` directives, and executable `;@` lines are not address tags.
Missing addresses stay blank, with no extrapolation from neighbouring lines.
These are original SNES CPU addresses, not ROM file offsets or current-build
addresses. Edits can make an origin stale.

## Source/display boundary

`AsmSourcePresentation` owns the projection and both offset conversions.
The authoritative workspace buffer, project overrides, frozen baseline,
compiler, native source readers, diagnostics and symbol tools still use full
source. Merely opening a document, selecting text, searching, or toggling
the gutter does not change source or create undo history.

The editable text field receives the projected text, rather than a hidden-text
visual transformation. Native arrow movement, deletion, selection and clipboard
operations therefore cannot step through or copy invisible address characters.
Syntax spans, links and find highlights use the same projection. Document-local
find searches visible code/comments and returns full-source coordinates for
navigation; address lookup remains available through the workspace address search.

Visible edits are reconciled into full source before entering the existing
source-edit/undo/save pipeline. Unchanged lines retain their exact source bytes.
Modified retained lines keep their annotation inside a comment. A split keeps
the origin on the first fragment (insertion before a line keeps it with the old
line); a join keeps the first available origin and moves it after the joined
code. Fully deleted lines lose their annotations, inserted lines get none, and
equal-line-count edits retain per-line identities. Pasted explicit annotations
take precedence. An intentionally empty ordinary comment is preserved even when
that requires dropping an origin that would otherwise hide the comment marker.
Unterminated string literals and executable `;@` lines likewise take precedence
over retaining an optional annotation; metadata must never change executable text.
Address annotations are historical context, not persistent instruction identities.

Tests cover lexical boundaries, bidirectional offsets, annotated spans, find
coordinates, CRLF/Unicode, splits/joins, deletion, indentation, pasting, and
randomized visible edits. Headless Compose tests exercise actual native arrow
and Backspace handling, clipboard copying, comment edits, undo/redo, saved raw
overrides, and gutter row alignment at the largest font size.
Gutter interaction tests cover clicks below EOF, missing-address rows, both
columns, focus/selection preservation, wheel scrolling, and horizontal clipping.
