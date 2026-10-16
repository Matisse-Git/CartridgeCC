# Cartridge backup v1

PS1 extension (v17): each new manual-state blob carries a `core` revision, matched to its game's
system during inspection. Old mGBA archives still use the v1 root `core` fallback; PS1 states require
their explicit PCSX revision. Automatic session records carry their own system revision. PS1 manual
state bytes also include a revision envelope checked before native unserialization. Memory-card bytes
use the existing bounded 128 KiB SRAM entry. Disc bundles, BIOS and SAF grants remain excluded.

Shader choices (ADR 0027): `settings` may carry a `shaders` object, `{"global": …, "consoles": {"<GameSystem>": …},
"games": {"<content id>": …}}`, each value `off` or `preset:<path in the shader library>`; known consoles,
hexadecimal ids and plain preset paths only, at most 10,000 games. It is restored with the other settings when
*Also restore settings* is chosen. A backup without it, made before shaders, still validates. The shader
library itself is never in a backup; Update shaders downloads it again.

Console settings → Export backup writes a ZIP through Android's document picker. Restore backup reads and validates it into generated private staging, shows game/image/save counts, and offers Merge missing data, optional settings, or Cancel. Export/restore require the idle collection. Keep backups private; archives are not encrypted.

`backup.json` declares `project-cartridge.backup`, version 1, logical Room game records, canonical order/removal exclusions, selected slot/query, whitelisted settings and checksummed blobs. Images are `images/<SHA-256>.image`, battery/RTC/manual states are `saves/<ROM-SHA-256>.<kind>`, and manual state blobs carry the integration revision, and an optional compatible automatic checkpoint is `session/state.bin`. ROM bytes, BIOS, Android roles/grants, signing material, credentials, caches, folder indexes, pending scrape reviews/packs and enrichment undo are excluded. Accepted image originals and provenance are preserved. Source content URIs are hints; reauthorize folders or Locate same ROM on another device.

Limits: 10,000 records; 60,001 entries; 32 MiB manifest; 16 MiB maximum individual state; 12 MiB image; 128 KiB battery; 4 KiB RTC; 1 GiB compressed/expanded totals. Unsafe, duplicate and undeclared file paths, unsupported versions/settings/console identities, invalid images and mismatched sizes/hashes are rejected. ZIP names never become filesystem extraction paths.

Merge appends missing games while retaining existing membership, source, artwork, metadata and media. It fills absent fields, ORs favorites, and takes maxima for playtime/last-played to avoid duplicate history. Existing save files and automatic checkpoints are never overwritten, even if corrupt. An empty target restores saved order/query/focus. Settings restore is off by default and excludes HOME/default-home selection. Manual artwork/metadata can still be changed through their ordinary review interfaces after restore.

Room changes commit in one transaction. Files use immutable image identities and atomic, append-only save replacement. There is no cross-filesystem/SQLite transaction: an interrupted merge can leave new unreferenced images/saves, but retains accepted existing data and can be retried. After explicit acceptance, file/settings writes finish despite lifecycle cancellation. A failed document export can leave an incomplete external file; errors are visible and that file cannot pass restore validation. No success is shown before the writer completes.

2026-10-10 RP5: all three backup instrumentation tests pass (3.394 seconds): full logical record/image/save/checkpoint byte round trip, removed membership, history, protected existing metadata/manual saves, altered/traversal/undeclared/oversized/version rejection and real panel/picker callback/export/review/settings/cancel/recreation. The test document provider replaces only the picker grant; production uses SAF. Portable state bytes are verified; cross-firmware/core compatibility is intentionally constrained, and other document-provider write failures remain manual coverage.
