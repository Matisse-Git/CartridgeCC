# ADR 0002: Exclusive GPU surface ownership

Status: accepted for feasibility, 2026-10-09.

Filament and LibretroDroid use independently owned GL contexts. Release/flush Filament and detach
before starting the emulator; stop/save/destroy/detach the emulator before recreating Filament.
Use explicit release barriers and idempotent teardown instead of simultaneous active surfaces or
shared contexts. The pause overlay remains ordinary Compose UI over the frozen emulator.

Cost: no fancy GPU crossfade; recreation requires a short loading cue. Test SurfaceView z-order,
context loss, repeated handoffs and memory on RP5. M0 ends sessions on backgrounding; full resume
must recreate safely and restore state in later hardening.

Device evidence, 2026-10-09: 50 real native handoffs passed on RP5, including save/load and GPU pixel
checks. The owner confirmed ordinary pause/resume/eject. This supports the exclusive-surface decision;
context loss, longer memory trends and ordinary UI stress remain open in TESTING.
