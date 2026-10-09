# Product scope and UX

Project Cartridge is one native, offline-first console experience: physical media collection →
insertion → internal gameplay → app-owned pause/save/load → ejection → collection. The cartridge
is the centerpiece. Warm charcoal, quiet lighting, restrained text and controller-first navigation
guide later refinement. Original labels only; no Apple/Nintendo visual assets or commercial game data.

The supplied master specification governs the roadmap. **M0 feasibility passed on the physical RP5
on 2026-10-09**; M1 is the next milestone, with broader lifecycle hardening still required.

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

## Required label import and scraping experience

User addition, 2026-10-09: importing already scraped cartridge/disc labels and choosing scraped
media/metadata is a first-class feature. Support a single title, selected titles, one or selected
consoles, and the entire local library. These scopes operate on user-owned library entries, not a
provider's whole ROM catalogue. Local image/scrape-pack import must also work without a provider account.

Show controller-friendly candidate thumbnails when multiple matches or assets exist. Include region,
language, edition, disc number, provider and resolution where available. Let the user choose game
identity, cartridge/disc label, other media and metadata fields independently, including mixing sources.
Preview the selected label on the actual 3D template with crop/fit/rotation controls. Box art, a complete
cartridge photograph and a flat label are distinct media; never silently stretch one into another.

Bulk scraping defaults to filling missing fields, protects manual selections, reports progress and
supports cancel/retry/skip. Ambiguous matches go to a review queue. Replacement is explicit, with a
reviewable change summary. Region/language preferences rank choices but do not hide alternatives.
Accepted assets and provenance are cached locally; network failures must never block launching a game.

This requirement is recorded, not implemented in M0. See [LABEL_SCRAPING.md](LABEL_SCRAPING.md) for
workflow, data boundaries, provider research and acceptance criteria. Prove per-game texture assignment
after M0; implement the complete import/scrape workflow with the persistent M3 library.

## Explicitly deferred

The final believable slot/insertion/ejection animation and mechanical sound, production mesh/textures,
full gameplay touch controls, per-button remapping, details panels, Room metadata, directory scanning,
multi-directory indexing, favorites, artwork assignment, vertical/grid layouts, additional systems,
HOME launcher mode and backup. Backgrounding currently ends the session after persisting RAM;
the app does not claim seamless resume of a lost EGL context.

No confirmation dialog is added for ordinary insertion/ejection. Loading/saving errors remain visible
and preserve selection. A failed save is reported even though native resources are still shut down.
Force-stop/process death cannot guarantee a final SaveRAM write; periodic checkpoints are future work.
