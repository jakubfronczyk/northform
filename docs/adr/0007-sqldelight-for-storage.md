---
status: accepted
date: 2026-10-06
---

# SQLDelight for storage

Recordings are stored in SQLite through SQLDelight: the schema and queries are written in SQL and
Kotlin is generated from them, and migrations are versioned `.sqm` files checked against the schema.
WAL journaling and the iOS file-protection class are the driver's defaults already; only
`synchronous=FULL` is set by hand, so a save that returned survives a crash. Raw sensor streams are
stored as binary chunks with the codec in Kotlin.

## Considered options

- **Room (KMP):** works, but models tables as annotated entities. The schema here is designed
  SQL-first (binary stream chunks, a progress row rewritten on every save, hand-written migrations),
  which SQLDelight expresses directly.

Revisit if: the app needs sync to a server, where a store with built-in replication may beat
hand-written sync over SQL.
