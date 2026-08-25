# Acknowledgements

This project is a port of **[teableio/teable](https://github.com/teableio/teable)**.

## The licence, and who holds the copyright

`teable-src/LICENSE` — read, not taken from a badge — says teable is two licences at once,
and both halves matter here.

- Copyright (c) 2023–2025 Teable, Inc.
- `apps/nestjs-backend` and `apps/nextjs-app` are **AGPL-3.0**. `apps/nextjs-app/package.json`
  confirms it: `"license": "AGPL-3.0"`.
- Everything under `packages/` is **MIT**. The three packages this port read from confirm it
  individually: `packages/v2/core` (`@teable/v2-core`), `packages/v2/formula-sql-pg`
  (`@teable/v2-formula-sql-pg`) and `packages/formula` (`@teable/formula`) each carry
  `"license": "MIT"`.
- The repository as a whole is AGPL-3.0, which its root `package.json` declares.

## What was copied

### The interface, wholesale, and it is the AGPL-3.0 half

RENDERING.md R3 has a port ship the original's own front end rather than one of its own, so
`teable-akka/webapp/` is teable's `apps/nextjs-app` and the workspace packages it builds
against, taken from the source tree unchanged except for four files:

| file | what changed |
|---|---|
| `webapp/apps/nextjs-app/next.config.js` | data calls are sent to this port instead of to the process serving the page |
| `webapp/apps/nextjs-app/src/backend/api/rest/axios.ts` | the same, for the calls that build their own base address |
| `webapp/packages/sdk/.../useInstances.ts` | a collection is loaded over the snapshot routes and re-read on a server-sent event, instead of subscribed through ShareDB |
| `webapp/packages/sdk/.../useConnection.tsx` | no SockJS socket is opened |

Every path vendored this way is listed in `teable-akka/.vendored` with its reason, and both
`toolkit/source_hygiene.py` and `toolkit/copied_strings.py` read that file and say what they
skipped rather than reporting a hundred thousand lines nobody wrote.

### No source file was copied into the rebuild

Everything under `teable-akka/src/` was written for this port. What it does share with the
original is text, and the text was found by running something rather than by remembering:

```
python toolkit/copied_strings.py teable --source teable-src
  → 185 literal(s) of 10+ characters in teable-akka, 55 of them also in teable-src
```

The full listing is in `docs/copied-strings.txt`. All fifty-five are in the rebuild's own
Java sources — the vendored interface is skipped, being a copy declared as a path above — and
every one of them is accounted for below. A hit is not a verdict — a port
that answers the same requests in the same words shares that vocabulary on purpose — but each
one gets a sentence, which is the difference between a check nobody performs and one that
takes five minutes.

## The strings the rebuild shares with the original, by kind

**Refusal messages, reproduced deliberately.** The benchmark compares refusals as answers, so
a refusal that reads differently is a different answer. Where the message is one a request
can provoke, it was read out of the running original and `docs/question-log.md` says which row
ran it; the three raised inside the formula evaluator were taken from the original's own source
text instead, and are marked below.

- `Formula field dependency cycle detected` and `Formula field dependency cycle detected: `
  — read out of a run (question-log row 2)
- `Formula field references not found`, `Formula field references not found: ` and
  `Formula references not found: `
- `. These field IDs do not exist in the table.` — read out of a run (row 7)
- `. Formulas must use field IDs (fldXXXXXXXXXXXXXXXX format), not field names.`,
  `must use field IDs` and the `fldXXXXXXXXXXXXXXXX` shape inside it — read out of a run
  (row 7)
- `Failed to backfill computed fields` and `Failed to backfill computed fields [` — read out
  of a run (row 16)
- `FieldId {} is a invalid field id` — teable's wording, its own grammar included, copied from
  `packages/core/src/formula/visitor.ts:484` rather than from a run: no request reaches it,
  because the server refuses an unknown field id earlier
- `Function name ` … ` is not found` and ` needs at least ` — raised inside the formula
  evaluator, copied from the original's own source under `packages/core/src/formula/`. The
  test assertion matching on `IF needs at least 3 params` is a hit for the same reason.
- `cannot take square root of a negative number` — read out of a run (row 16), where it
  arrives inside the backfill refusal above. The test assertion matching on `square root` is a
  hit for the same reason.
- `(dbFieldName=` — part of the text `Table.java` builds when it names a field in a refusal.

The parse refusal `Formula expression … parse error: mismatched input '<EOF>'` is teable's
wording too. It is assembled from fragments shorter than ten characters, so the check does
not report it; it is named here because the check's silence is about length, not about origin.

**Route paths.** `/api/share`, `/api/table`, `/api/table/`, `/{tableId}`, `/{tableId}/field`,
`/{tableId}/record`, `/{tableId}/order`, `/{shareId}/view`, `/{shareId}/view/row-count` and
`/{shareId}/view/aggregations`. The vendored interface asks for these paths because teable's
own code asks for them; changing one would mean changing the interface, which R3 exists to
prevent.

**Keys of the JSON that interface reads.** `cellValueType`, `columnMeta`, `dbFieldName`,
`dbFieldType`, `isComputed`, `isMultipleCellValue`, `formatting`, `description`,
`autoNumber`, `createdTime`, `lastModifiedTime`, `lastModifiedBy`, `groupPoints`,
`includeRecords`, `enableShare` and `aggregations`. Same reason: they are the shapes teable's
grid expects to be handed. `singleLineText` is one of teable's own field type names and
arrives in the requests the interface sends, so it is shared for the same reason.

**teable's formula language, in the tests.** `CONCATENATE({`, `SUBSTITUTE`, `SUBSTITUTE({`,
`ROUNDDOWN(`, `SUM(1, 2, 3)`, `FIND("", {` and `}, BLANK())`. A formula written in teable has
to mean the same thing here or no workload could be run against both sides, so the function
names and the expressions written from them are shared by design.

## Behaviour derived even where no text was copied

Yes, and plainly: every rule in `specs/SPEC-001-teable.md` describes what the running teable
does. The port was built to agree with it, and `bench/REPORT.md` reports 27 of 27 steps
agreeing. This is a derived work in the ordinary sense, whatever the string counts say.

## What licence that forces on this project

**AGPL-3.0.** The vendored interface is the AGPL-3.0 half of teable's split and is shipped
here in source form, so the published repository is AGPL-3.0 as a whole. The Java rebuild
under `src/` reads only from the MIT-licensed `packages/`, and would have been MIT on its
own; it is not published on its own.

## Also used

- Akka — the platform the rebuild runs on.
