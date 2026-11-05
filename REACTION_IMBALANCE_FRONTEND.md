# Reaction Imbalance QA: Front-end Integration Guide

This guide is for the developer (or Claude) adding the reaction imbalance check to the Angular front-end
(`reactome/curator-tool-frontend`). The back-end lives in `curator-tool-ws` and is complete. No back-end
changes are needed. The check is part of the generic QA report: there is no separate endpoint.

**Before writing code, read the front-end repo's conventions and follow them.** Look at how the QA report is
requested and displayed today (`qaReport`, the QA report dialog and the QA actions menu), how dialogs are opened,
and how the event tree's context menu is built. Everything below describes *what* to build. Match the repo for
*how*.

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

The check runs as part of the QA report, for **Reactions** and **Pathways**:

```
POST {wsBase}/api/curation/qaReport
Authorization: Bearer <JWT>        (same as every other curation endpoint)
Body: the instance (SimpleInstance JSON), as already sent for the QA report
```

`qaResults` contains one entry with `"checkName": "Reaction Imbalance Check"`. A passing check has neither
`rows` nor `issue`. Other event classes (e.g. `BlackBoxEvent`) get no entry for this check.

### For a Reaction

```json
{
  "checkName": "Reaction Imbalance Check",
  "issue": "Possible cleavage reaction",
  "columns": ["Reference", "Identifier", "Input", "Output"],
  "rows": [
    ["UniProt:O43323 DHH", "O43323", "1", "2"],
    ["UniProt:Q14623 IHH", "Q14623", "1", "2"],
    ["UniProt:Q15465 SHH", "Q15465", "1", "2"]
  ]
}
```

`issue` is present only for cleavages.

### For a Pathway

Every `Reaction` the pathway contains, at any depth via `hasEvent`, is checked. There is **one row per imbalanced
protein of each imbalanced reaction**, so a reaction with three imbalanced proteins has three rows:

```json
{
  "checkName": "Reaction Imbalance Check",
  "issue": "86 of 2410 reactions imbalanced (35 possible cleavages)",
  "columns": ["Reaction_DBID", "Reaction", "Reference", "Identifier", "Input", "Output",
              "Possible_Cleavage", "Last_Author"],
  "rows": [
    ["5358340", "Autoproteolytic cleavage of Hh precursors", "UniProt:O43323 DHH", "O43323", "1", "2",
     "Yes", "Matthews, Lisa, 2023-03-08"]
  ]
}
```

- `issue` summarises the result: `<imbalanced> of <checked> reactions imbalanced`, plus the number of possible
  cleavages when there are any.
- Rows are sorted by reaction name, then protein name.
- All values are strings (`Input`/`Output` are integers as text, either may be `"0"`). `Possible_Cleavage` is
  `"Yes"` or `"No"`. `Last_Author` is the latest InstanceEdit (curator name + date), or `""`.
- Speed: the whole QA report for Signal Transduction (2,410 reactions) takes about 0.4 seconds.

## 3. UI

If the front-end already shows `qaReport` results generically as a table, both cases work with no extra code.
The improvements below are optional, for the pathway case, where the flat table can be long.

- **Group by reaction:** group rows by `Reaction_DBID` (expandable rows, or a reaction-level table with the proteins
  as detail rows), and make the DB_ID open the reaction in the instance view.
- **Hide cleavages:** a "Hide possible cleavages" toggle filtering on `Possible_Cleavage`, on by default. Cleavages
  are usually expected. This replaces the desktop tool's separate "Cleavage" tab.
- **Entry point:** a "QA report" action on Pathway nodes in the event tree, if there isn't one already.

Use the column names (`columns`) to find values in `rows` rather than fixed positions.

## 4. Test data (verified against the current graph database)

| dbId | Class | Expected |
|------|-------|----------|
| `5218891` | Reaction, balanced (has stoichiometry 4) | passed |
| `177946` | Reaction, "Pro-EGF is cleaved to form mature EGF" (1 EGF on each side) | passed |
| `5358340` | Reaction, "Autoproteolytic cleavage of Hh precursors" | 3 rows, DHH/IHH/SHH each 1 → 2, issue "Possible cleavage reaction" |
| `9666616` | Reaction, "APC is deubiquitinated" | 4 rows (ubiquitin proteins, e.g. UBC 27 → 9), no issue |
| `162582` | Pathway, Signal Transduction | issue "86 of 2410 reactions imbalanced (35 possible cleavages)" |

## 5. Back-end reference

- Checker: `org.reactome.curation.qa.ReactionImbalanceCheck`, registered in `QAService`
- Logic: `org.reactome.curation.qa.ReactionImbalanceHelper`
- Tests: `src/test/java/org/reactome/curation/qa/ReactionImbalanceCheckTest.java`
- The separate `GET qa/reactionImbalance/{dbId}` endpoint has been removed.
