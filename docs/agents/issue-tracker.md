# Issue tracker: GitHub Issues

**Tickets live in GitHub Issues on this repo** (moved 2026-07-18, when the rebuild tickets were
published). Use `gh` to read and write them.

`.scratch/` still exists, but holds **PRDs, spikes and research notes only** — no tickets. The
local-markdown ticket convention described at the bottom of this file now applies only to
wayfinding efforts.

## Conventions

- One issue per **vertical slice** — a narrow but complete path through every layer, demoable on
  its own, sized to fit a single fresh context window.
- Each issue body carries **What to build**, **Acceptance criteria** (checkboxes), and a
  **Blocked by** section naming the issues that gate it — or "None — can start immediately".
- Triage state is a **GitHub label** (see `triage-labels.md`).
- Conversation happens in issue comments.

## When a skill says "publish to the issue tracker"

```bash
gh issue create --title "<title>" --body-file <file> --label ready-for-agent
```

Publish in dependency order (blockers first) so each issue's **Blocked by** can reference real
issue numbers.

## When a skill says "fetch the relevant ticket"

```bash
gh issue view <number> --comments
```

## Finding the frontier

The **frontier** is any open issue whose **Blocked by** list is fully closed. `gh issue list`, then
read the blocking edges — the numbering is not a work order.

## Wayfinding operations (local files)

Used by `/wayfinder`, which is a research/exploration surface rather than a build queue, and stays
on local files. The **map** is a file with one **child** file per ticket.

- **Map**: `.scratch/<effort>/map.md` — the Notes / Decisions-so-far / Fog body.
- **Child ticket**: `.scratch/<effort>/issues/NN-<slug>.md`, numbered from `01`, with the question in the body. A `Type:` line records the ticket type (`research`/`prototype`/`grilling`/`task`); a `Status:` line records `claimed`/`resolved`.
- **Blocking**: a `Blocked by: NN, NN` line near the top. A ticket is unblocked when every file it lists is `resolved`.
- **Frontier**: scan `.scratch/<effort>/issues/` for files that are open, unblocked, and unclaimed; first by number wins.
- **Claim**: set `Status: claimed` and save before any work.
- **Resolve**: append the answer under an `## Answer` heading, set `Status: resolved`, then append a context pointer (gist + link) to the map's Decisions-so-far in `map.md`.
