# 0010 — Incremental reviewed enrichment and persistent undo

Accepted 2026-10-09 for the active M3 continuation.

Search only the current review title rather than waiting for an entire library. Snapshot/deduplicate
selected canonical ROM hashes and durably publish targets before the first request. Advance explicitly,
loading completed candidates from the journal or searching the next pending title. Cancel/relaunch do
not repeat successful searches or implicitly resume network work. Keep a single saved batch and only
current candidates in RAM. Journal v2 adds applied/skipped status and regional/language tags; read v1.

A match opens a compact review with preferred flat-label preview, fill-missing metadata and next-title
actions. Keep all title matches, regions, media and per-field variants accessible. Region/language
preferences rank source-tagged alternatives stably; unknown values stay untagged. Do not infer language
from region, equate box art with flat labels, or auto-accept ambiguous matches. Fill missing writes
transactionally against current Room values, protecting manual and previous provider choices even
when the journal contains old bare game snapshots. Replacement remains an explicit per-field/media action.

Room v6 introduces one previous enrichment snapshot per game. A changed label, metadata merge/clear or
additional-media selection atomically replaces that snapshot before updating accepted values. Undo
restores only label recipe/provenance, metadata and additional media, then consumes the snapshot. It
cannot rewind ROM/source identity, membership, favorites, play history, query preferences or save files.
No-op writes preserve the undo point. Original files are retained, so restored choices remain decodable.
This is bounded single-step undo, not a history stack or redo system.

A scrape target hidden by search/favorites may temporarily project canonical games into a 3D draft.
Never persist its temporary query/selection. Apply/cancel/recreation return to the original persisted
view/focus; writes address the target hash independently. Opaque scrape pages pause the Filament loop;
the real label preview resumes it. Native gameplay surface/context ownership remains unchanged.

No new libraries, account requirements, background workers, providers or asset licences are introduced.
RetroBase's current public OpenAPI and actual response keys were rechecked; region/language coverage is
record-specific. See M3_SCRAPE_REVIEW, LABEL_SCRAPING and TESTING for results and remaining gates.
