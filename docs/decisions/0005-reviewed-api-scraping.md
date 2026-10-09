# 0005 — Reviewed API scraping

Accepted 2026-10-09 for the owner's primary label workflow.

Use RetroBase Collection's public v1 API for the initial GBA proof. Its documented anonymous access
requires no account or embedded secret. Inspect OpenAPI and actual records: `support-texture` provides
flat regional labels; `support-2D`, boxes, screenshots and title logos are separate additional media.
The taxonomy endpoint alone was insufficient: actual GBA responses contain support-texture variants.
Socket's precise backend is unverified. A community Slot fork documents generated Skyscraper labels;
we do not claim this adapter supplies every identical Socket/Slot image.

ArtworkProvider hides network/parser details. ScrapeCoordinator takes a deduplicated snapshot of the
existing collection and searches sequentially, by SHA-1/size/platform then corrected title. Only
fingerprints and title queries are sent, never ROM bytes. A match is a candidate, not an accepted identity.
The ROM SHA-256 remains the database key. All changes require a user choice; there is no automatic
replacement. Metadata fields, regional label and other media can come from different records.

The flat-label choice feeds the existing genuine 3D preview and explicit Apply. Other media never
rebind the label. Room v2 adds nullable provenance/metadata/media columns with an explicit v1→v2
migration. Field/category merges preserve unchecked choices. Originals are content-addressed and
accepted references survive offline relaunch; candidates expire after one hour and occupy at most
32 files/32 MiB. HTTPS image hosts, byte counts and decoded dimensions are bounded.

This first review queue is Activity-scoped. Cancel stops unaccepted work; retry preserves successful
searches. Process-resumable jobs, undo, multiple selected consoles, directory-scale indexing and a
second provider are deferred. Current scope is one title or the three-slot GBA collection.

RetroBase's final data license is pending, and it grants no license to game images. Keep originals out
of Git/APKs, store source/record/region/license/time with accepted selections, visibly credit the provider,
and resolve distribution terms before public release. ScreenScraper requires developer credentials;
none are borrowed from another app. See PROVIDER_RESEARCH and LICENSING.
