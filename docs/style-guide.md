# Style Guide

This guide covers project-specific UI conventions. For OOP and architecture
rules (coupling, dependency injection, abstraction levels, etc.), see the
external [`rules-oop`](https://github.com/SeanShubin/rules-oop) repo.
For project-specific patterns and philosophy:

- [docs/testing-philosophy.md](testing-philosophy.md) — fakes over mocks, test against interfaces
- [docs/architectural-insight.md](architectural-insight.md) — relational projection over storage
- [docs/debugging-workflow.md](debugging-workflow.md) — relational vs raw views

## Two UI Styles

The frontend has two distinct visual styles, applied by two distinct shell
classes. Each page is one OR the other — never a mix.

### Aesthetic style — for user interaction

**Shell class**: `.container`
**Used for**: pages a user spends time *doing things* on — login, registration,
home, election creation, viewing one election, casting a ballot, password
reset, user management, role transfers.

Properties:
- Centered, `max-width: 800px` reading column
- Generous padding, soft shadow, rounded card
- Larger typography, comfortable line height, color accents on interactive elements
- Lists are semantic `<div class="...-list">` of `<div class="...-item">` rows
  (e.g. `.elections-list` / `.election-item`, `.users-list` / `.user-row`),
  not HTML `<table>`s

Touchpoints in CSS: `.container`, `.form`, `.menu`, `.button-row`, `.section`,
`.tabs`, `.elections-list`, `.users-list`, `.ranked-ballot*`.

### Detailed style — for data display

**Shell class**: `.admin-container`
**Used for**: pages where the user is *inspecting data* — Raw Tables, Debug
Tables, anything that reveals the underlying database state for an admin or
auditor.

Properties:
- No `max-width` — reclaims the full browser width
- `overflow-x: auto` on the card so wide tables scroll horizontally inside the
  card rather than the whole page
- HTML `<table class="data-table">` with `width: max-content` and a sticky
  header (`thead th { position: sticky; top: 0 }`)
- Tab strip (`.tab-strip`) for switching between table names

Touchpoints in CSS: `.admin-container`, `.data-table`, `.tab-strip`.

## How CSS and HTML cooperate

The pairing is enforced by class names, not file location:

1. **A page picks one shell**: the outermost `Div({ classes("container") })` or
   `Div({ classes("admin-container") })`. Mixing them on one page is a smell.
2. **Tables of data use `.data-table`**, not a bare `<table>`. The bare-table
   styles are inherited defaults (100%-width with stripes); they exist so
   one-off tables look acceptable without the full detailed treatment, but
   a dataset that the user is meant to *read* belongs in `.data-table` inside
   `.admin-container`.
3. **Lists of interactive items use semantic divs**, not tables. A list of
   elections each with a "View" button is a `.elections-list`, not a table —
   the user is choosing one to act on, not reading the values.

If you find yourself writing a table inside `.container`, you've probably
crossed the line. Either push it into a `.data-table` if the data is small
and the user only glances at it, or split it out into its own
`.admin-container` page and link to it from the aesthetic page.

## Linking aesthetic → detailed

Aesthetic pages should expose links to relevant detailed pages so the user
can drill into raw data when they need it, without clutter on the everyday
flow. Examples:

- **HomePage** (aesthetic) → "Raw Tables" / "Debug Tables" buttons (detailed),
  visible only to AUDITOR+.
- An election results overview (aesthetic) → a full pairwise-matrix detail
  page (detailed), when the matrix gets wide enough to warrant horizontal
  scroll.

The aesthetic page summarizes; the detailed page reveals.

## Picking a style for a new page

Ask: *what is the user doing here?*

- Filling a form, picking from a list, taking a single action → **aesthetic**
- Reading rows, comparing values, scanning many records → **detailed**

When in doubt, start aesthetic. Splitting an aesthetic page into a separate
detailed page later is straightforward; cramming detailed data into an
aesthetic page tends to ratchet outward (wider container, smaller fonts,
more horizontal scrolling) until both styles end up half-broken.
