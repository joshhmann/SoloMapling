# Issue tracker: Local Markdown

Issues and specs live as markdown files in `.pi-flow/`.

## Conventions

- One feature per directory: `.pi-flow/<feature-slug>/`.
- The spec is `.pi-flow/<feature-slug>/spec.md`.
- Implementation tickets are one file each at `.pi-flow/<feature-slug>/issues/<NN>-<slug>.md`, numbered from `01` in dependency order.
- A `Status:` line near the top records triage state; see `triage-labels.md`.
- Append conversation history under `## Comments`.

## Operations

- To publish a spec or ticket, create the appropriate file under `.pi-flow/<feature-slug>/`.
- To fetch a ticket, read its referenced path.
- A wayfinding map is `.pi-flow/<effort>/map.md`; decision tickets are its numbered issue files.
- Each decision ticket has `Type:` (`research`, `prototype`, `grilling`, or `task`) and `Status:` (`claimed` or `resolved`).
- A `Blocked by:` line names required ticket numbers. The frontier is the first unclaimed, unresolved ticket whose blockers are resolved.
- Claim before work by setting `Status: claimed`. Resolve by appending `## Answer`, setting `Status: resolved`, then linking the result from the map's decisions-so-far.

`/pi-wayfinder` uses these map conventions.

## Wayfinding operations

Use this tracker's markdown conventions for Wayfinder maps and decision tickets.

- Map: `.pi-flow/<effort>/map.md`, with title link `wayfinder:map`.
- Resolved decision tickets: `.pi-flow/<effort>/decisions/<NN>-<slug>.md`; the map's decisions-so-far links point here.
- Implementation tickets: `.pi-flow/<effort>/issues/<NN>-<slug>.md` from `01`, in dependency order.
- Ticket body: exact `## Question` only, plus `Type:`, `Status:`, optional `Blocked by:` naming ticket numbers.
- Claim: set `Status: claimed` before investigating.
- Frontier: first ticket with `Status: open`, no `Blocked by:` unresolved, unclaimed.
- Resolve: append `## Answer`, set `Status: resolved`, link findings from ticket, append linked one-liner to map `Decisions so far`.
- Dependencies: `Blocked by:` line naming required numbers; wire only after all tickets exist.
- Out of scope: close ticket, add linked line under map `Out of scope`.
