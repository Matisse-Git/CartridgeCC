# 0004 — Persistent collection and independent labels

Accepted 2026-10-09 for the first M1 increment.

M0 validated native surfaces but kept slot assignments in Activity memory. A relaunch lost the user's
collection, and a shared label material could not support the requested scraped labels.

Use Room 2.7.2 behind CollectionRepository for three slots, content-hash games, selected focus and
accepted label recipes. Export/version schemas with Room's matching Gradle plugin and KSP 2.1.20-1.0.32.
This first database needs no M0 migration because there was no persisted M0 library. Later schema
changes require explicit migrations; never destroy the library to upgrade it.

Move operation decisions into an Activity-owned, Android-free CollectionCoordinator. A busy action
cannot enqueue another session. Preparation overlaps the short insertion motion, then releases
Filament before mounting the emulator. Backgrounding cancels preparation but awaits save writes
before shutdown. Viewport generation rejects stale callbacks. Tests can substitute engine/repository
ports for transition checks; production always uses the real mGBA/Room implementations.

Retain SHA-256-addressed image originals and a Fit/Crop/quarter-turn recipe. Derive 512×256 RGBA on IO.
Only the named label primitive receives its own duplicate glTF material and mipmapped sRGB texture.
Free replacements on change and before asset teardown. Do not duplicate cartridge geometry per title.
Apply waits for the current recipe's rendered-frame acknowledgement; Cancel leaves Room unchanged.

Local image import proves the same preview/persistence path that online providers will use. It is a
fallback and foundation, not a substitute for the owner's primary API/database scraping requirement.
Three slots intentionally bound the first playable collection. Directory indexing, richer metadata,
accepted-version undo, provider matches and resumable batch jobs are incremental later work.
