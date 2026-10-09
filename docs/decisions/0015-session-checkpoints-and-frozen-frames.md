# ADR 0015 — Completed session checkpoints and fresh GPU ownership on wake

Accepted, 2026-10-10.

Persist automatic full-state checkpoints independently of manual save states and battery files.
SessionStore writes a bounded state generation, syncs it, then atomically publishes a small manifest
with game/system identity, core integration revision, byte count and SHA-256. Reject incomplete,
corrupt, unsafe or incompatible records. Keep the previous completed generation until publication.
File IO runs off Main; an already captured write completes despite coroutine cancellation.

Checkpoint during the existing 15-second schedule, on pause and before background shutdown.
Release the native frontend and GPU surface in background. A same-Activity wake restores a fresh
view and pauses after real output; cold launch stays in the collection with Resume session.
Manual ejection clears only the automatic checkpoint after a successful stop. Recovery failure
returns a visible error and preserves manual/battery files and library focus. Discard remains available.
Sudden process death can lose progress since the last completed checkpoint; Android offers no
guarantee of a final lifecycle callback. Never retain native views in a ViewModel.

Compose must key the emulator AndroidView by view identity. A stopped collector can skip the
intermediate Library screen, otherwise reusing a released host when wake creates another GLRetroView.
The native wrapper counts actual non-null video callbacks: upstream FrameRendered may correspond
to a timing step that ran zero core frames. Two produced frames settle initial/restored video before
the new view can be paused; preparation never relies on animation completion or a fabricated screen.

Android's paused GLSurfaceView releases its buffer data. PixelCopy captures the actual presented
frame before pause for the custom menu; it is drawn by Compose while the native render loop/audio
are paused. Snapshot capture is bounded and optional when the window is already hidden. No active
Filament loop or shared emulator/Filament contexts are retained behind gameplay.

RP5 tests cover real frame/output identity, native state and SRAM, Android sleep/wake, cold Activity
launch and corrupt-state rejection. They do not constitute a reboot/default-HOME test or exhaustive
process-kill/power-loss durability proof. Longer stability and backup are subsequent M6 work.
