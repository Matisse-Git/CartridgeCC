# ADR 0016 — Portable logical backups with protected merges

Accepted, 2026-10-10.

Export logical Room records transactionally rather than copying live SQLite/WAL files. Use a bounded, explicitly declared ZIP manifest with SHA-256 for original accepted images, battery/RTC/manual saves and the compatible completed session. Read ZIP names only as keys; write generated private staging paths. Reject invalid data before accepted writes and recheck reviewed bytes before merge.

A user reviews counts before merging. Existing choices, membership and saves win. History uses maxima; optional settings require an explicit choice. Android roles and URI permissions are never portable. ROMs/BIOS and transient scrape/cache data stay outside backup. Immutable appended images and atomic append-only save writes preserve previous files; Room membership/enrichment commits in one transaction. Partial new unreferenced files can remain after failure, so describe retry behavior without claiming a filesystem/database atomic transaction.

The existing coordinator serializes idle-library operations; backup never races an active core. Whitelisted console preferences restore only after acceptance and refresh the live controller/presentation state.
