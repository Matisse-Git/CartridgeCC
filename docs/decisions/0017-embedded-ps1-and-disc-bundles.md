# ADR 0017 — embedded PS1 and scoped disc bundles

Date: 2026-10-10. Status: accepted for the private development increment.

## Decision

Embed source-built PCSX ReARMed, pinned to `c8816799b50388e61cfe237fe2cdbb7d8175f20a`
with SHA-256 `5e1b15f17d0e97225d8b2eb29a8412fb0d3b03ff242c5456d055ec2967667a83`.
The CMake source/flag list follows that revision's Android arm64 recipe. Use Ari64's arm64
dynarec and synchronous NEON software GPU/SPU/CD processing; omit async worker threads,
Lightrec, standalone frontend and VFS. Android's private filesystem provides core paths.
Retain the proven exclusive Filament → Libretro EGL surface handoff and one-session mutex.
SwanStation/hardware GPU complexity is deferred until compatibility/performance evidence warrants it.

Give PS1 a distinct system identity, core path and revision. Instrument the original frontend
translation unit's real video callback, just as for mGBA. Running requires two actual non-null
video buffers. Diagnostic audio counts non-silent PCM passed to the frontend, not acoustic output.
SaveBridge accesses only the loaded core on its own GL thread. PCSX exposes its first 128 KiB
memory card directly through Libretro memory; its second card is disabled. mGBA's RTC/VFile
synchronization remains specific to mGBA. PS1 manual states have a revision envelope; automatic
checkpoints and backup state entries retain the appropriate system's core revision.

Single-file CHD/PBP/ISO and PS-X EXE keep their original extensions inside an immutable
`<content-id>.ps1/game.<extension>` bundle. CUE imports require a granted SAF folder, restrict FILE
entries to BINARY files in that folder, preserve track layout and rewrite names to generated
private basenames. Hash normalized CUE + ordered track bytes/lengths and optional validated SBI.
Publish only a complete, synced bundle. Stream on IO with cancellation and a 2 GiB total cap.
No raw BIN library entries: tracks belong to their CUE, preventing audio tracks becoming games.
Rehash CUE tracks on every scan; the CUE's size/mtime cannot prove tracks are unchanged.

Use one original 120 mm annular GLB with physical thickness and a true center hole. The printed
face retains the existing named label-material contract, with 512×512 artwork. The bounded pool
has nine instances per physical template (27 total), with only the current layout's objects visible.
Reuse controller, scrape review, metadata, history and saves. Title-based PS1 scraping avoids
hashing a CUE or compressed container as if it were a provider's canonical ROM fingerprint.

BIOS import is explicit SAF, exact 512 KiB and allowlisted retail MD5. Store a single private
configured BIOS; the core identifies region from bytes. No firmware download or bundle. HLE
fallback is labeled in PS1 settings and has limited compatibility.

## Consequences

An additional GPL-2.0-or-later core and permissive decompression dependencies require source/notices
in the distribution audit. Public release remains gated. Initial controller is digital; analog-required
games, multitap, rumble, M3U/disc swapping and whole-library compatibility are future tested increments.
The mesh and generic loading motion are development art; no final disc tray or iridescence claim.
Private disc working copies consume storage; folder grants never imply unrestricted Android storage.
Content identity is encoding-specific: differently encoded dumps and each disc remain separate entries.
