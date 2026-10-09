# 0006 — Growing GBA collection and durable scrape review

Accepted 2026-10-09 after the owner requested continuing the working API workflow.

Keep the existing ordered slot table and game content keys. Compact active entries and append newly
imported identities. On initialization collapse legacy duplicate assignments by SHA-256, preserving
first-occurrence order and the focused game identity. Different hashes with identical titles remain
separate. The owner found three legacy assignments of the same ROM; these were not useful duplicates.
Empty collections have one study placeholder. Room v3 adds directories, v4 adds removed-game identities;
explicit v1→v2→v3→v4 migrations preserve enrichment without destructive fallback.

B opens an application-owned collection menu for removal and single-step Undo. Removal atomically
hides the entry, compacts positions and selects a neighbor, while retaining the game record, ROM,
saves and artwork. Rescan respects the persisted exclusion. Explicit X / Add .gba restores hidden
identities and focuses the existing/new entry; it never introduces a duplicate. Undo restores the
previous position during the current Activity session; after reopening, Add .gba is the recovery path.

Use ACTION_OPEN_DOCUMENT_TREE, retain its read grant, and traverse DocumentsContract child URIs on IO.
Read only .gba documents; copy unchanged originals into bounded private working files. Commit each
completed file independently, preserve progress on cancellation, and report per-file errors. Keep
registered roots for an explicit Rescan; no unrestricted storage permissions or source-file writes.
Bounds are 10,000 visited entries and 16 directory levels per scan. Rescans currently rehash files.

The collection's length changes; the GLB instance pool stays three. Rebind the visible window around
the selected index, transform those instances using global focus/spring state, and decode/cache only
visible labels. The renderer reports both the first visible index and label keys, so previews after
the third game still require a matching rendered frame. Inactive instances beyond a small collection
are placed outside the far clip; label inputs are padded to the fixed pool. This preserves surface ownership.

ScrapeJournal stores a manifest of target IDs, corrected queries, search/error status and review index,
plus a separate candidate file per title. Android AtomicFile and a shared mutex protect writes; write
sequences reject late older manifest/candidate saves across resets. Targets are saved before the first
network request. Only the current title's candidates occupy queue RAM.
Search results are saved before marking completion. Resume performs pending searches and reads
completed candidates from disk. Accepted Room choices and image originals remain separate.

Cold launch does not resume network work automatically. Y → Resume saved batch is explicit; a new
scope replaces unfinished search progress while preserving applied choices. Unapplied image/metadata
drafts are discarded on recreation. One saved batch is supported; directory scan position itself is
not durable, but rescan safely deduplicates prior imports. Full process-kill/disk-full and very large
library profiling remain physical hardening tasks. See LIBRARY_PROGRESS and TESTING.
