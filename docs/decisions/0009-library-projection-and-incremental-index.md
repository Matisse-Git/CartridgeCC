# 0009 — Persistent library views and conservative document indexing

Accepted 2026-10-09 for the owner's explicit M3 request.

Keep canonical import-order slots and content hashes. Persist query preferences separately and apply
a pure stable projection for title/recent/playtime sorting, favorites and text search. Retain focused
hash through projection changes; persist navigation/removal using canonical positions. Empty results
show one unlaunchable study placeholder. All-library scraping uses canonical entries, independently
of the view. Freeze projection during native gameplay/preview and refresh after ejection.

Room v5 extends existing rows and adds a root/document index with explicit migration. Never reset
accepted artwork, metadata, removal exclusions or saves. Local metadata edits replace only changed
fields and carry their own provenance. Details and local file facts are optional for launch.

Choose conservative incremental SAF scanning: skip positively stamped same-size/mtime documents only
when the private working copy exists at the indexed size. Missing/unknown stamps rehash. Expose full
Verify all because provider stamps are an optimization, not a cryptographic guarantee. Changed content
under a filename creates another identity. Mark unseen documents missing only after complete enumeration;
permission/subfolder failures and cancellation must not imply deletion. Preserve cached playability and
source ownership. Automatic scan on open defaults on with a persisted opt-out; no scheduled worker.

Forgetting a root removes its index/registration and releases that root grant, keeping library entries,
cached ROMs, media and saves. Selecting the root again is the supported reauthorization path. Exact-hash
Locate same ROM repairs a working copy/source without transferring another game's label or saves.

Count only native Running time with a monotonic clock. Checkpoint every 15 seconds and flush on pause,
eject/background. Keep failed history checkpoints keyed by their original game and retry separately;
display failures without blocking emulation. Activity/process death can lose uncommitted deltas. This
is simpler than a durable session journal and does not promise seamless suspend or zero-loss history.

Opaque details/edit/folder panels pause the existing library renderer. Controller R1/L1 and B menu
actions remain separate from gameplay; ordinary Android/Compose handles text entry. Existing native
surface ownership and provider review semantics remain unchanged. Dependencies stay pinned.

Official references: [SAF](https://developer.android.com/training/data-storage/shared/documents-files),
[Room migrations](https://developer.android.com/training/data-storage/room/migrating-db-versions).
See M3_PROGRESS and TESTING for actual validation and remaining gates.
