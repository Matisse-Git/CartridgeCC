# ADR 0003: Source-built mGBA and SAF document input

Status: accepted for feasibility, 2026-10-09.

Build mGBA 0.10.5's GBA-only Libretro target from a hash-pinned source archive using NDK 27.2.
Package `libmgba_libretro.so` as an extracted arm64 native library for LibretroDroid 0.14.0's dlopen
path. This avoids mutable buildbot binaries and makes corresponding source reproducible.

Use SAF OpenDocument with a read grant and a bounded private copy, addressed by ROM-content SHA-256.
That path is reliable for native loading without requiring filesystem access to content URIs or broad
storage permissions. The original remains untouched. Three slot assignments are session-only in M0.

Cost: temporary local storage and initial build network/cache requirements. Room directory indexing
belongs to M3. GPL frontend plus MPL/LGPL core dependencies impose distribution obligations; this
decision does not authorize a proprietary release.
