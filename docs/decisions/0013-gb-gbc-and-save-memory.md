# ADR 0013 — GB/GBC through the existing mGBA integration

Accepted, 2026-10-10.

Add GB/GBC before a new core. Pinned mGBA already contains their engines and provides the same
software-video Libretro ABI. Enabling M_CORE_GB preserves one frontend, timing/audio adapter and
serialized GPU handoff. A separate system enum travels through ROM identity, Room, provider calls,
queue persistence and template selection; an extension alone is insufficient for colour capability.

Reuse the same carousel/vertical/grid selection and bounded nine-slot renderer. Each slot has a GBA
and tall GB/GBC instance, with only one visible; alternative label aspect ratios have separate cache
keys. This doubles the bounded mesh instances, rather than allocating per game. Future formats can
replace this small fixed catalog when profiling warrants it.

Libretro RTC is distinct from SAVE_RAM. mGBA's RTC memory points to a file suffix, while clock registers
are live elsewhere. State loading also creates a temporary battery VFile mask; it must be committed
before immediately reading SAVE_RAM. The original CMake wrapper includes the unchanged pinned
frontend and exposes two narrowly scoped synchronization functions. JNI calls them only on the
existing session GL thread under its mutex. Keep wrapper/build source with corresponding-source
distribution. Public memory buffers alone were insufficient for reliable immediate saves.

Original GBX-header homebrew diagnostics establish native output/input/state/battery/RTC behavior
without distributing proprietary ROM or BIOS data. Compatibility and artistic validation remain
explicitly scoped; an added system is not a promise of all mappers or peripherals.
