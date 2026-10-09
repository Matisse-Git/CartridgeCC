# 0011 — Versioned offline scrape candidates

Accepted 2026-10-09 while finishing M3 before M4.

Adopt a narrow Cartridge ZIP/JSON v1 format with required ROM hashes and image checksums. Match only
canonical existing games. Do not guess by title or import ROMs/saves/membership. Reuse ScrapeCoordinator,
metadata choices, region ranking, actual GLB preview and enrichment undo. Keep this independent from
the online provider: local candidates/URLs never cause network fallback and resume remains offline.

Bound compressed/expanded bytes, entries and every record/image. Never extract archive paths directly;
use generated private staging filenames. Reject paths/duplicates/unknown versions/undeclared files and
checksum or image-validation failures before promoting the bundle. Retain original provenance separately
from internal URLs. Candidate journal adds optional local/source fields while retaining v1/v2 compatibility.

Accepted data remains in Room; drafts are not commits. Valid cancelled/unmatched bundles can retain
unreferenced files, pending reference-aware garbage collection. API scraping remains the primary path.
Existing provider/GPL/image obligations remain; no new library or proprietary asset is adopted. See
SCRAPE_PACK_FORMAT and TESTING for precise schema, limits, commands and results.
