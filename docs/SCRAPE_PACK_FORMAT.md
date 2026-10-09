# Private scrape packs — version 1

Implemented in version 0.3.2-m3-packs (code 10). Y → Scrape → Import scrape pack opens Android's
SAF document picker. The ZIP remains untouched; validated private candidate/image copies are retained.
Matching uses existing ROM SHA-256 hashes. No ROM/BIOS bytes, credentials, library membership or save
files are imported. Unmatched games are ignored; zero matches reports an error without replacing a
saved batch. Review every match, label/media and metadata through the same API chooser. Nothing is
accepted automatically. Resume stays offline; original source/provider/licence and regional/language
choices survive the candidate journal. Existing labels/fields stay protected and undo remains available.

This is an explicit Cartridge schema, not a claim of compatibility with every third-party scraper
export. Convert such exports to this format. The optional PC helper calculates image checksums and can
hash one local ROM without including it. Primary artwork retrieval remains the real API scraper.

## ZIP contents

`pack.json` at root plus only declared PNG/JPEG/WebP images. Required SHA-256 values are lowercase hex.
Example manifest (replace ROM hash and image checksum; fictional data only):

```json
{
  "format": "project-cartridge.scrape-pack",
  "version": 1,
  "provider": "My private scraper export",
  "license": "Artwork rights remain with their owners",
  "assets": [
    {"id": "orbit-us", "path": "media/orbit-us.png", "sha256": "<image SHA-256>"}
  ],
  "games": [{
    "romSha256": "<ROM SHA-256>",
    "matches": [{
      "id": "record-one",
      "title": "Orbit Field",
      "platform": "Game Boy Advance",
      "sourceUrl": "https://example.invalid/record-one",
      "metadata": {
        "title": [{"value": "Orbit Field", "region": "us"}],
        "year": [{"value": "2002"}],
        "description": [{"value": "Original fictional description", "language": "en"}]
      },
      "media": [{
        "asset": "orbit-us", "type": "support-texture", "region": "us",
        "sourceUrl": "https://example.invalid/original-label"
      }]
    }]
  }]
}
```

Supported metadata: title/year/developer/publisher/genre/description, each a list of up to 16 objects
with value and optional source region/language. Media: support-texture (flat label), support-2D,
box-2D, ss/screenshot, sstitle, wheel. Multiple game matches and regions remain choices. Box art cannot
become a flat label implicitly. `assets: []`/`media: []` permit metadata-only packs. Currently GBA only.

Limits: compressed stream 128 MiB, expanded total 256 MiB, 2,048 ZIP entries, 1-MiB manifest,
1,024 declared assets, 1,000 game records, 16 matches/game, 64 media/match, 12 MiB/image,
16,384 characters/metadata value. LabelStore validates dimensions/formats before retaining a bundle.
Unsupported versions, malformed records, duplicate entries/IDs/hashes, undeclared files, altered image
checksums, absolute/traversal paths and data-bearing directory entries are rejected. Archive names
never become filesystem paths; extraction uses generated private filenames. No URL in a pack triggers
an online download. Image promotion follows full validation; accepted Room data is separate.

```powershell
python tools/pack_scrape.py path/to/manifest.json path/to/private-pack.zip --rom path/to/game.gba
```

The helper reads local images under the manifest directory, fills image checksums, optionally fills
one ROM hash and refuses existing output/source overwrite. Do not commit private packs, commercial
labels/ROMs or credentials. No redistribution rights are inferred from importing/creating a pack.
Reference-aware cleanup remains pending; unmatched/cancelled valid bundles may retain private
originals until app data is cleared. An interrupted draft is not accepted on relaunch. If candidate
files are missing, reimport the pack; it will not silently fall back to network search.

## Actual validation

Both APKs compile; 34 host tests and 8 asset checks pass, lint 0 errors/24 warnings. Two RP5 pack tests
passed in 3.521 seconds on Android 13, firmware RP5_V1.0.0.189_20251128_174732_user. They use original
solid-color PNGs, isolated Room and a provider that throws on every network call. SAF selection, real
GLB texture readiness/apply, missing-only merge, local-value protection, source provenance, recreation,
offline resume and independent undo pass. Unknown versions/bad checksums leave no retained bundle;
path and compressed-bomb boundaries are host-tested. Full regression results are in TESTING.

M3's requested library/enrichment features are implemented. Real large-library/grant/storage-failure
and long-run hardware gates remain open and will be carried forward; this is not full product acceptance.
