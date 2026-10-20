# Reaction Imbalance QA: Front-end Integration Guide

This guide is for the developer (or Claude) adding the reaction imbalance check to the Angular front-end
(`reactome/curator-tool-frontend`). The back-end lives in `curator-tool-ws` and is complete. No back-end
changes are needed.

**Before writing code, read the front-end repo's conventions and follow them.** Look at how existing services call
`curator-tool-ws` (base URL/environment config, the HTTP service pattern, the JWT interceptor, error
notification), how dialogs are opened, and how the event tree's context menu is built. Everything below describes
*what* to build. Match the repo for *how*.

---

## 1. Background: what the check does

This is a port of the desktop CuratorTool's "Imbalance Check" (`org.gk.qualityCheck.ImbalanceCheck`).

For each `Reaction`, it looks at both sides and asks whether the inputs and outputs contain the same proteins
in the same numbers:

- Inputs and outputs are broken down through complexes, sets and polymers to individual proteins
  (`ReferenceGeneProduct`s, i.e. UniProt entries), multiplied by stoichiometry.
- Proteins inside polymer modifications on a protein (e.g. polyubiquitin chains) are counted too.
- A reaction is **imbalanced** if any protein's input count differs from its output count.
- An imbalanced reaction is also flagged as a **cleavage** if the mismatch looks like a protein being cut
  (the same protein appears with different end coordinates). Cleavages are usually expected, so curators
  typically want to see them separately from, or hidden behind, the "real" imbalances.
- Small molecules are **not** checked. Only proteins are.
- Only the `Reaction` class is checked. `BlackBoxEvent`, `Polymerisation`, `Depolymerisation` and
  `FailedReaction` are skipped.

## 2. API

### Pathway / reaction check (new)

```
GET {wsBase}/api/curation/qa/reactionImbalance/{dbId}
Authorization: Bearer <JWT>        (same as every other curation endpoint)
```

- `dbId` may be a **Pathway** (every `Reaction` it contains, at any depth via `hasEvent`, is checked) or a single
  **Reaction**. Any other `Event` returns `checkedReactions: 0`.
- **Only imbalanced reactions are returned.** An empty `reactions` array means everything passed.
- Speed: a top-level pathway such as Signal Transduction (2,410 reactions) takes about 1 second. A progress
  spinner is enough. No paging or streaming is needed.

#### Response `200`

```json
{
  "eventDbId": 162582,
  "eventDisplayName": "Signal Transduction",
  "checkedReactions": 2410,
  "reactions": [
    {
      "dbId": 5358340,
      "displayName": "Autoproteolytic cleavage of Hh precursors",
      "cleavage": true,
      "lastAuthor": "Matthews, Lisa, 2023-03-08",
      "imbalances": [
        { "refDbId": 53626, "identifier": "O43323", "displayName": "UniProt:O43323 DHH", "inputCount": 1, "outputCount": 2 },
        { "refDbId": 57202, "identifier": "Q14623", "displayName": "UniProt:Q14623 IHH", "inputCount": 1, "outputCount": 2 },
        { "refDbId": 64432, "identifier": "Q15465", "displayName": "UniProt:Q15465 SHH", "inputCount": 1, "outputCount": 2 }
      ]
    }
  ]
}
```

- `reactions` is sorted by `displayName`, and each `imbalances` list is sorted by `displayName`.
- `lastAuthor` is the display name of the latest InstanceEdit (curator name + date). It can be omitted from the
  JSON (null fields are dropped) if a reaction has no InstanceEdits.
- `inputCount` / `outputCount` are integers, and either one may be `0` (the protein appears on one side only).

#### Errors

| Status | When | Body |
|--------|------|------|
| `404` | `dbId` does not exist or is not an `Event` | `{ "status": 404, "message": "...", "timestamp": 1760000000000 }` |
| `401` | missing or expired JWT | handled by the existing auth interceptor |

### TypeScript model

```ts
export interface ReferenceImbalance {
  refDbId: number;
  identifier: string;     // UniProt accession, e.g. "Q15465"
  displayName: string;    // e.g. "UniProt:Q15465 SHH"
  inputCount: number;
  outputCount: number;
}

export interface ReactionImbalance {
  dbId: number;
  displayName: string;
  cleavage: boolean;
  lastAuthor?: string;
  imbalances: ReferenceImbalance[];
}

export interface ReactionImbalanceReport {
  eventDbId: number;
  eventDisplayName: string;
  checkedReactions: number;
  reactions: ReactionImbalance[];
}
```

### Single-reaction check (already exists, for reference)

The check is also registered in the generic per-instance QA flow (`POST api/curation/qaReport` with the instance).
For a Reaction, `qaResults` now includes an entry like this:

```json
{
  "checkName": "Reaction Imbalance Check",
  "issue": "Possible cleavage reaction",
  "columns": ["Reference", "Identifier", "Input", "Output"],
  "rows": [["UniProt:Q15465 SHH", "Q15465", "1", "2"]]
}
```

`issue` is present only for cleavages. A passing reaction has neither `rows` nor `issue`. If the front-end
already displays `qaReport` results generically, this works with no extra code. The rest of this guide is
about the **pathway-level** view.

## 3. UI to build

### Entry point

Add a **"Check Reaction Imbalance"** action to the event tree's context menu (or wherever pathway-level actions
live), enabled for `Pathway` and `Reaction` nodes. This is where the desktop tool exposed it. Optionally add a
matching action to the instance view of a Pathway or Reaction.

### Results view (dialog or side panel, following the repo's patterns)

**Header:** `<eventDisplayName>: <N> imbalanced of <checkedReactions> reactions checked`
(plus `<M> cleavage` when the toggle below is on).

**Toolbar:**
- **"Hide cleavage reactions"** toggle, **on by default**. This replaces the desktop's separate "Cleavage" tab.
- Text filter on reaction name / protein identifier (nice to have).
- Export to TSV (nice to have). Columns: reaction DB_ID, reaction name, cleavage, last author, protein,
  identifier, input, output.

**Main table: one row per reaction** (Angular Material `mat-table` with `matSort`):

| Column | Source | Notes |
|--------|--------|-------|
| DB_ID | `dbId` | Clickable: opens the reaction in the instance view (use the app's existing "open instance" navigation) |
| Reaction | `displayName` | |
| Imbalanced proteins | `imbalances.length` | |
| Cleavage | `cleavage` | Icon or chip; only visible when cleavages are shown |
| Last author | `lastAuthor` | |

**Expandable detail row: one row per protein** (the desktop's `ImbalanceTableModel`):

| Protein | Identifier | Input | Output | Δ |
|---------|------------|-------|--------|---|
| `displayName` | `identifier` | `inputCount` | `outputCount` | `outputCount - inputCount`, signed (e.g. `+1`, `-2`) |

Use the Material "expandable rows" pattern (`multiTemplateDataRows` + a detail row with `@detailExpand`).

**States:**
- Loading: spinner. The request can take a few seconds on large pathways.
- Empty: "All `<checkedReactions>` reactions are balanced." (If the toggle hides everything, say how many
  cleavage reactions are hidden.)
- `checkedReactions === 0`: "No reactions found under this event."
- Error: the repo's standard error notification, showing `message`.

## 4. Suggested implementation steps

1. Add the three interfaces above next to the existing QA/instance models.
2. Add `checkReactionImbalance(dbId: number): Observable<ReactionImbalanceReport>` to the service that already
   talks to `api/curation` (the one used for `qaReport` and similar), reusing its base URL and error handling.
3. Create a `reaction-imbalance-report` component that takes the report and renders the toolbar, the main
   table and the expandable detail rows.
4. Open it from the new event tree context-menu action, showing a spinner while the request runs.
5. Unit tests: mock the service and cover rendering, the cleavage toggle, expand/collapse, the empty state and
   the error state.

## 5. Test data (verified against the current graph database)

| dbId | What | Expected |
|------|------|----------|
| `5218891` | Balanced reaction (has stoichiometry 4) | `checkedReactions: 1`, `reactions: []` |
| `177946` | "Pro-EGF is cleaved to form mature EGF" (1 EGF on each side) | `reactions: []` |
| `5358340` | "Autoproteolytic cleavage of Hh precursors" | 1 reaction, `cleavage: true`, DHH/IHH/SHH each 1 → 2 |
| `9666616` | "APC is deubiquitinated" | 1 reaction, `cleavage: false`, 4 ubiquitin proteins (e.g. UBC 27 → 9) |
| `162582` | Signal Transduction (pathway) | `checkedReactions: 2410`, 86 imbalanced, 35 of them cleavage |

Quick manual check (the token comes from `POST /api/auth/login`):

```bash
curl -H "Authorization: Bearer $TOKEN" http://localhost:9090/api/curation/qa/reactionImbalance/5358340
```

`curator-tool-ws` runs on port 9090 by default (`server.port` in `application.properties`).

## 6. Back-end reference

- Endpoint: `CurationController.checkReactionImbalance()`, `GET qa/reactionImbalance/{dbId}`
- Logic: `org.reactome.curation.qa.ReactionImbalanceHelper`
- Per-instance checker: `org.reactome.curation.qa.ReactionImbalanceCheck`
- Models: `org.reactome.curation.qa.model.ReactionImbalanceReport`, `ReactionImbalance`, `ReferenceImbalance`
- Tests: `src/test/java/org/reactome/curation/qa/ReactionImbalanceCheckTest.java`
