# 0007 — Battery saves before boot, bounded native capture

Accepted 2026-10-09 for M1 reliability. Dependency versions remain unchanged.

The former adapter restored SaveRAM after FrameRendered. Games can read battery memory once at boot,
so late restoration does not restore their in-memory progress. LibretroDroid 0.14.0's SRAM JNI ignores
the native rejection result; both SRAM/state capture JNI paths leave their new[] buffer unfreed, and
state capture discards retro_serialize's boolean. Do not depend on those paths for periodic saving.

Keep the session LifecycleRegistry STARTED while the app-owned GL surface initializes. Wait for
SurfaceCreated, restore bounded battery bytes on that GL thread, verify readback, then RESUME and wait
for a real FrameRendered. Failed restoration releases the core and preserves the stored file.

M2 regression correction: pinned 0.14.0 emits SurfaceCreated even after initializeCore swallowed a load
error, while error delivery is asynchronous. With stored SRAM this could race into an uninitialized
mGBA memory API. Prime the private session's lifecycle coroutine observer before setup, record its
observer count, and require the successful RenderLifecycleObserver registration before any SaveBridge
restore. Audited initializeCore posts this observer to Main before SurfaceCreated only after load and
native surface setup succeed. A synchronous Main Handler check follows that synchronous upstream post;
this avoids coroutine async messages bypassing UI synchronization barriers. No fixed delay/reflection.
The session registry has no unrelated observer producers. This guard depends on the pinned frontend
implementation and must be re-audited for any dependency change. The physical invalid-ROM fixture now
always seeds existing battery bytes, then checks Error→Idle and subsequent valid native sessions.

SaveBridge is original C/JNI glue over the loaded core's public Libretro memory/serialization exports.
RTLD_NOLOAD acquires/relinquishes a reference; it never loads or initializes another core. Every call
runs on the existing GL queue under the engine's session mutex. GBA SaveRAM is capped at 128 KiB;
states at 16 MiB. Copies use managed Java buffers and release JNI elements, check native serialization
success and preserve existing files on failure. State loading remains the pinned frontend's checked API.

Checkpoint changed battery bytes every 15 seconds during Running, on pause, after save/load state,
and during normal shutdown. Capture between frames; write/sync/rename on IO. Skip unchanged/empty RAM.
Write failure keeps the session usable and shows a persistent warning until a successful retry. A GL
capture timeout releases the session rather than leaving an unknown native operation running.

SaveStore bounds reads/writes and uses independent temporary files plus atomic replacement. A shared
directory guard serializes readers/writers across adapter instances, including Windows host tests.
No destructive repair, stale-temp promotion, automatic save-state resume or storage permission added.

Original SRAM diagnostic reads byte zero once at boot; GPU green/gray proves startup order. Tests also
exercise checkpoint timing, pause/eject/background, abrupt fixture native release/recreation, bounded
restore rejection, invalid-file preservation and storage-write warning/retry. These do not establish
physical reboot, OS process kill, power-loss durability, long-run pacing or general core compatibility.
Sudden termination may lose changes since the latest successful checkpoint; no zero-loss claim.

Inspected primary sources: [pinned frontend JNI](https://github.com/Swordfish90/LibretroDroid/blob/8835c3098514390a271e36983957f7bb5f40abf1/libretrodroid/src/main/cpp/libretrodroidjni.cpp),
[view lifecycle](https://github.com/Swordfish90/LibretroDroid/blob/8835c3098514390a271e36983957f7bb5f40abf1/libretrodroid/src/main/java/com/swordfish/libretrodroid/GLRetroView.kt),
[mGBA 0.10.5 memory/deferred setup](https://github.com/mgba-emu/mgba/blob/0.10.5/src/platform/libretro/libretro.c),
and [Android dynamic-loader API](https://android.googlesource.com/platform/bionic/+/refs/heads/main/libc/include/dlfcn.h).
