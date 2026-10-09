# Product scope and UX

Project Cartridge is one native, offline-first console experience: physical media collection →
insertion → internal gameplay → app-owned pause/save/load → ejection → collection. The cartridge
is the centerpiece. Warm charcoal, quiet lighting, restrained text and controller-first navigation
guide later refinement. Original labels only; no Apple/Nintendo visual assets or commercial game data.

The supplied master specification governs the roadmap. The active milestone is **M0 feasibility**.

## Implemented M0 slice

- Landscape immersive Android shell, with a horizontal three-object scene.
- One genuine instanced GBA-shaped GLB, molded-plastic/label/contact PBR materials.
- D-pad/stick focus, deterministic axis repeat and spring retargeting; touch fallback.
- Three engineering slots; assign one user-selected `.gba` per slot for this app session.
- Short lift, release barrier, internal LibretroDroid/mGBA surface, truthful readiness/errors.
- App-owned pause menu, resume, single state slot, SaveRAM and return to selected collection item.
- Reduced-motion toggle and two menu-shortcut choices saved locally.

The fictional ORBIT FIELD label is engineering artwork, not a claim a playable game is bundled.
Empty study slots cannot launch. Metadata does not block a user-selected game.

## Explicitly deferred

The final believable slot/insertion/ejection animation and mechanical sound, production mesh/textures,
full gameplay touch controls, per-button remapping, details panels, Room metadata, directory scanning,
multi-directory indexing, favorites, artwork assignment, vertical/grid layouts, additional systems,
HOME launcher mode and backup. Backgrounding currently ends the session after persisting RAM;
the app does not claim seamless resume of a lost EGL context.

No confirmation dialog is added for ordinary insertion/ejection. Loading/saving errors remain visible
and preserve selection. A failed save is reported even though native resources are still shut down.
Force-stop/process death cannot guarantee a final SaveRAM write; periodic checkpoints are future work.
