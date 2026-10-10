# ADR 0019: Imported media models and a stable pooled object per game

Date: 2026-10-10. Status: accepted by the owner's direction; supersedes the generated media templates
of ADR 0008, 0013 and 0017 and the window mapping of ADR 0012.

## Context

The owner replaced the three generated media templates with downloaded models: a Game Boy Advance
cartridge, a Game Boy cartridge and a compact disc. They are detailed PBR assets, but none has what
the renderer depends on: a separate label primitive, metre scale, a common orientation, or glTF.
Both cartridges also carry a commercial game label baked into their base colour.

Separately, browsing a shelf of more than three games flickered. Pooled objects were addressed by
their position in the visible window (`index + windowStart`), so every step moved every game to
another object. The label list that reached the renderer was also positional and one composition
behind, so for a frame or more all visible labels were released, the template label showed, and the
textures were uploaded again.

## Decision

**Models.** `tools/import_models.py` (Blender, headless) converts the sources in `assets/models`
into the three runtime GLBs. It normalises units, origin and orientation, sizes each object to the
template the receiving slot and on-screen scales were measured for, packs textures to 1024 px in
glTF's conventions, and gives each object exactly one `Replaceable fictional label` primitive whose
UVs span the artwork from the top left. The baked game label is erased from the base colour before
anything is written. The receiving slot stayed the project's own generated asset until ADR 0020
removed it.

**A game keeps its object.** `CollectionLayout.slot(index)` maps a shelf index to one of
`CollectionLayout.POOL` (15) objects per template by the index alone. The staged window is the
visible objects plus what one step brings in: five for the carousels, five rows for the grid. Moving
the window therefore binds labels only for the objects that entered it, and those are off screen.

**Labels travel by key, with their owner.** The Activity keeps decoded labels in a map by image key
and stages `(system, owner, key, pixels?)` per window entry. `CartridgeLabels` never shows one
game's label on another's object: an object that changed hands is hidden until its own label is
bound, and a game whose label is being replaced keeps the current one until the new one is ready.

## Consequences

- 45 instances instead of 27, and at most 15 label textures instead of 9. Meshes and shell textures
  are still shared per template; the label decode cache is bounded to twice the window.
- A shelf object never appears with the template's placeholder label. The diagnostic activities,
  which stage no window, still show the bare template.
- Grid rows outside the three on screen are kept ready and grow in or out as the grid scrolls,
  instead of appearing and vanishing at full size.
- The disc's clear hub is composited over the stage colour and drawn opaque. It would need blending
  to be shown over anything but the dark library backdrop.
- The models are third-party downloads with their makers' marks on the shells. See LICENSING.
- Regenerating the media needs Blender and the source folder; an ordinary build does not.
