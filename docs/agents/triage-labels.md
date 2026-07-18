# Triage Labels

The skills speak in terms of five canonical triage roles. This file maps those roles to the actual
label strings used in this repo's tracker.

| Label in mattpocock/skills | Label in our tracker | Meaning                                  |
| -------------------------- | -------------------- | ---------------------------------------- |
| `needs-triage`             | `needs-triage`       | Maintainer needs to evaluate this issue  |
| `needs-info`               | `needs-info`         | Waiting on reporter for more information |
| `ready-for-agent`          | `ready-for-agent`    | Fully specified, ready for an AFK agent  |
| `ready-for-human`          | `ready-for-human`    | Requires human implementation            |
| `wontfix`                  | `wontfix`            | Will not be actioned                     |

These are **GitHub labels** applied to issues (as of 2026-07-18):

```bash
gh issue edit <number> --add-label ready-for-agent --remove-label needs-triage
gh issue list --label ready-for-agent
```

The rebuild tickets were published `ready-for-agent` by construction — they are vertical slices with
acceptance criteria.

For the local-markdown wayfinding tickets under `.scratch/` (see `issue-tracker.md`), state is still
a `Status:` line near the top of the file.

When a skill mentions a role (e.g. "apply the AFK-ready triage label"), use the corresponding label
string from this table.
