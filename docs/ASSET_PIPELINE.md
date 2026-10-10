# Cartridge asset pipeline

## Current media: imported models (2026-10-10, ADR 0019)

The three objects a game is shown as are converted from models the owner downloaded from Sketchfab.
They replace the generated cartridge, tall cartridge and disc. The receiving slot and its generator
were removed with ADR 0020: every object now leaves through the bottom of the screen.

| Runtime file | Source under `assets/models` | Result |
| --- | --- | --- |
| `gba_cartridge.glb` | `gb/source/GBA_SP_Cartridge.zip` (FBX and AO map inside), `gb/textures` | 2,294,124 bytes, 5,802 triangles, 60.00 × 34.33 × 6.87 mm, label 43.26 × 22.54 mm |
| `gb_cartridge.glb` | `gba/source/Gameboy_Cartridge.fbx`, `gba/textures` | 2,463,136 bytes, 536 triangles, 57.52 × 65.00 × 9.12 mm, label 44.54 × 39.15 mm |
| `ps1_disc.glb` | `cd/source/compact-disc_001.fbx`, `cd/textures` | 805,128 bytes, 6,144 triangles, 120.00 × 120.00 × 1.83 mm, label ring 19.5–59.6 mm |

The source folder names do not match their contents: `gb` holds the wide Game Boy Advance cartridge
and `gba` the tall Game Boy one. The tool maps them by shape and leaves the folders as they are.
The sources are about 68 MB, their textures still carry the commercial game labels, and an ordinary
build does not need them, so `assets/models` is ignored by Git and stays on the owner's machine.

```powershell
& 'C:\Program Files\Blender Foundation\Blender 5.2\blender.exe' -b --factory-startup --disable-autoexec --python tools/import_models.py
python tools/test_assets.py
node tools/validate_assets.cjs
```

Blender 4.2 or newer is enough; this was run with 5.2.2 LTS and needs nothing installed beside it.
What `tools/import_models.py` does to each source:

- **Frame.** Bakes the object transform (the disc's stored tilt is discarded), centres the bounding
  box on the origin, and scales to the template size above: metres, +Y up, printed face toward +Z.
  The cartridge width stays 60 mm and the tall cartridge 65 mm high, so `CartridgeSurface`'s
  per-template scales did not change.
- **Label.** Exactly one primitive per object has the material `Replaceable fictional label`.
  On the GBA cartridge it is the modelled recess floor itself (122 faces); on the disc it is the
  printed side's annulus from the stacking ring to the rim (192 faces). The Game Boy cartridge's
  recess exists only in its normal map, so a rounded sheet is laid 0.05 mm above the front, placed
  from the recess walls found in that map. Label normals are flattened.
- **Label UVs.** Cartridges: the whole image over the whole label, U right, V down, (0, 0) top
  left. Disc: the artwork square spans the 120 mm disc and the annulus clips it. The label areas
  are within about 4 % (GBA) and 1 % (Game Boy) of the 2:1 and 8:7 artwork the app decodes.
- **Baked game labels removed.** Both cartridge downloads carry a commercial game label in their
  base colour. That region is filled with the median of the plastic around it and the tool asserts
  that no saturated pixel is left there, so no game artwork reaches the APK or Git. The embedded
  placeholder label is plain label stock in `TypesetLabel`'s paper colour.
- **Textures.** 1024 px: base colour as JPEG, occlusion/roughness/metallic packed into one PNG,
  normals as PNG. All three sources were measured before conversion: the cartridges store −Y
  ("DirectX") normals, read from the walls of a known recess, and are flipped to glTF's +Y; the
  disc is already +Y, read from its concentric rings. Tangents are exported.
- **Disc.** The source stores its clear polycarbonate hub as alpha 0.07. The runtime disc is opaque:
  the hub is composited over the library's near-black stage colour and reads as clear plastic there.
  The source's anisotropy map is not used; the pinned ubershaders have no slot for it.

Checks: 10 structural tests (frame, size, unit normals and winding, one named label facing the
viewer, UV contract, placeholder aspect, opaque PBR with tangents, texture and triangle budgets,
disc annulus, studio lighting) and the Khronos validator with zero errors and zero warnings on all
three GLBs.

The sections below describe the generated assets these replaced. Their generators
(`generate_cartridge.py`, `generate_gb_cartridge.py`, `generate_disc.py`) were removed because they
wrote to the same files; they remain in Git history. The studio lighting, contact sound and
compression notes still apply.

## History: generated assets (M2–PS1)

PS1 adds `python tools/generate_disc.py`: an original 120 mm × 1.2 mm optical disc with 15 mm actual
center hole, reflective underside, hub and named replaceable label primitive. Metres, +Y up, +Z print;
512×512 label UVs map the full artwork square, clipped by the annular geometry. It shares the existing
Filament texture lifecycle and matching runtime/validator. 1,792 triangles, three PBR materials,
106,416 bytes. Development template: no claim of artist-final tray motion or iridescent response.

### M2 source assets

Blender is unavailable on this host. `tools/generate_cartridge.py` and `tools/glb_geometry.py` create an
original 494,152-byte glTF 2.0 cartridge: 6,866 triangles, four materials, smooth segmented bevel normals,
recessed rounded label, grip ribs, an outside shell seam and 16 plated contacts. The label is below the
front plane rather than overlaid on a complete front face. It retains a 512×256 fictional ORBIT FIELD
PNG, with 128×128 original normal and roughness/metal textures. Tangents are explicitly orthogonalized
per vertex from face UVs; Blender/MikkTSpace remains available for final hand-authored textures.

The independent receiving-slot GLB is 61,564 bytes/1,180 triangles/two materials. Its measured 62 mm
opening admits the 60 mm cartridge lip. Geometry blocks the front of the descending shell. It is hidden
beyond the far clip at rest, not a permanently visible console. No external model or artwork is copied.

```powershell
python tools/generate_cartridge.py
python tools/generate_sound.py
python tools/test_assets.py
```

Eight structural tests check ranges, indices, metre-scale dimensions, unit normals/winding, mesh/material
budget, embedded textures, recessed label/top-left UVs, receiver clearance and KTX faces/mips/SH. The
official Khronos validator additionally checks both GLBs with zero errors/warnings:

```powershell
npm.cmd install --prefix .tools/gltf-validation --ignore-scripts --no-audit --no-fund gltf-validator@2.0.0-dev.3.10
node tools/validate_assets.cjs
```

The npm package integrity is `sha512-odJ4k0tRkGXiDGn78yDBg+fBbAIvBnXxh3RwAta0emSxGtyagFE8B4xELB1oYe3S5RD8Ci3uZAsZaascH2LAEQ==`.
The script reports all issues and fails for errors or warnings; no ignored-issue list. Reports remain
under ignored `.tools/`. Only runtime assets and original generators belong in Git.

This is the first scripted M2 refinement, not a claim that an artist has manually finished a production
Blender asset. Further shoulder/grip/cavity accuracy, close-up art direction and lighting calibration can
be refined in Blender. The reproducible generator remains a useful baseline and genuine physical mesh.

## Original studio lighting and contact sound

`tools/generate_studio.py` creates a 512×256 linear HDR Radiance source from analytic softboxes with warm
neutral/cool fill lighting. No photograph, third-party environment or sample cmgen asset is used.
Matching **Filament cmgen 1.72.1** generates GGX-prefiltered specular levels and three-band diffuse SH.
The 128-pixel/six-face/four-level KTX1 is 522,616 bytes; it is the sole packaged environment. No skybox
or runtime prefilter is needed. Filament, gltfio and filament-utils all pin 1.72.1. KTX1Loader owns both
the light and cubemap, and the renderer explicitly releases both at handoff.

Download the appropriate [official Filament 1.72.1 tools](https://github.com/google/filament/releases/tag/v1.72.1)
into an ignored tool directory. This host extracted only `bin/cmgen.exe` from the Windows archive;
its archive SHA-256 is `d013c17d27e2acbbaadc172e9136acee75f9a54c919a5703c877dd6c0079fd5f`.
No download occurs during an ordinary APK build; the generated KTX is versioned.

```powershell
.\tools\Build-Studio.ps1 -Cmgen C:/path/to/filament-1.72.1/bin/cmgen.exe
```

On other hosts: generate the HDR with Python, then run
`cmgen --quiet --size=128 --format=ktx --deploy=.tools/studio/prefilter .tools/studio/cartridge_studio.hdr`
and copy `prefilter_ibl.ktx` to `app/src/main/assets/lighting/studio_ibl.ktx`. Source/output folder names
determine cmgen's output name. Matching tool version matters; multithreaded floating-point generation
can have insignificant platform differences, so do not infer bitwise cross-platform reproducibility.

`generate_sound.py` writes an original 100 ms mono 16 kHz PCM contact to `res/raw/media_click.wav`.
Its decaying oscillation/noise is seeded, quiet and contains no sampled audio. Sound defaults off;
Motion off suppresses the transition sound. SoundPool loads once per Activity and closes at destruction.

## Blender handoff

1. Generate the GLB, then import it into Blender with File → Import → glTF 2.0.
2. Use metric units, one unit = one metre. Nominal media size: ~60×35×8 mm. Origin centered,
   +Y up and label facing +Z in exported glTF. Blender's exporter converts its Z-up scene; verify
   exported coordinates using the asset tests instead of manually applying a second axis rotation.
3. Keep separate shell, label recess, seam/grip and contact material regions. Model real shoulder
   cutouts, a recessed label panel, contact cavity and restrained seams. Bevel edges ~0.2–0.6 mm,
   add weighted/smooth normals appropriately, and apply transforms before export.
4. Keep the label as its own UV-mapped material/texture region. Artwork is independent of metadata.
   One template mesh is instanced for the collection; never export a unique shell for every game.
   Preserve the material name `Replaceable fictional label`, with exactly one label primitive per
   instance. CartridgeLabels locates this name and replaces the gltfio `baseColorMap` binding.
   The UVs cover a 2:1 region facing +Z with rounded geometry masking the corners. U grows right;
   V grows down: top-left=(0,0). Do not apply a second vertical flip. RP5 tests sample four original
   asymmetric print swatches from the real GPU surface; retain this contract after Blender export.
5. Use glTF-compatible Principled BSDF materials: plastic metallic 0, roughness ~0.65–0.85; label
   ~0.55–0.75; plated contacts metallic ~0.8–1, roughness ~0.25–0.4. Avoid stylized dirt, scratches
   and glossy toy surfaces. Albedo is sRGB; roughness/metal/AO/normal data is linear. Pack ORM as
   R=occlusion, G=roughness, B=metallic. Use OpenGL tangent-space normals; verify green-channel direction.
6. Start with <=15k triangles, <=4 materials, and 1k shell textures; profile rather than assume the
   budget is final. Labels can be 512–1024 px. Use original or audited art and retain source files/licenses.
7. Export selected objects as glTF 2.0 → **GLB**, with applied modifiers, normals, tangents when needed,
   UVs and materials. Disable cameras/lights/animations unless intentionally authored.
8. Replace `app/src/main/assets/models/gba_cartridge.glb`, run structural checks and a Khronos glTF
   Validator check, then build and visually inspect on RP5 under the intended light/camera angles.

For reproducible headless Blender re-export of a polished `.blend`, use
`blender -b assets/source/gba_cartridge.blend --python tools/export_blender.py`. Preserve original
source models under `assets/source/` only when reasonably sized and required for reproduction.
The provided export helper exports selected mesh objects and validates a nonempty selection.

## Compression and label loading

The cartridge PNGs avoid another runtime texture-tool dependency. Roughness is linear in G, metallic
is zero in B, and R is constant 255 (no baked occlusion claim). The normal texture is linear OpenGL
tangent-space with scale 0.22. Grain is restrained; no artificial dirt/scratches. Plastic UVs repeat
at 14 mm with mipmaps. Label albedo remains sRGB and supports existing per-game replacement.
For final assets evaluate KTX2/Basis Universal
with the pinned gltfio runtime, test ETC2/ASTC capability on RP5, and compare GPU memory/visual quality.
Never assume a glTF WebP/extension provider exists. Draco/meshopt compression remains deferred. M1's
LabelStore decodes ordinary user PNG/JPEG/WebP on IO into 512×256 RGBA; CartridgeLabels uploads distinct
sRGB textures/material instances on Main and frees them during replacement/teardown. Fit/Crop/rotation
recipes are stored independently from unchanged originals. At most six decoded recipes and three
current GPU label textures are retained per viewport; navigation reuses them.

The RP5 label proof assigns different original red/blue images to instances of this one mesh, reads
actual GPU pixels, and restores accepted textures after cancel/recreation/database reopening. This
proves independence, not the fidelity of a production scan. The M2 swatch test extends this to
asymmetric orientation.
Publish label UV orientation, aspect ratio and a mask with each polished template.
The initial PS1 template uses 512×512 label UVs and actual annular geometry for its outer radius and
centre hole (see the PS1 addition above). Future disc variants retain that contract. Distinguish flat label artwork
from photographs of entire media and case/box art. See [LABEL_SCRAPING.md](LABEL_SCRAPING.md).

The API adapter now requests regional support-texture flat labels and routes them through this same
bounded preview pipeline. Additional provider media never binds to the label primitive. The owner
confirmed real remote artwork thumbnails/application/reopening; final scan fidelity and asymmetric UV
orientation still need product validation. Keep retrieved commercial assets in private runtime caches,
never in model generators, source art, tests or APKs. See PROVIDER_RESEARCH and SCRAPING_REPORT.

Version 5 keeps three GLB instances while collection length grows. Inactive instances beyond a one/two
game collection live outside the far clip, with padded label buffers. The visible window rebinds labels
by global index; six derived buffers remain the cache cap. The RP5 test reads blue pixels on game six,
persists its recipe and enters/exits real internal gameplay without allocating a model per library item.
This is a bounded rendering proof, not a hundreds-of-games frame-pacing measurement.

## History: M5 tall GB/GBC template (superseded)

`python tools/generate_gb_cartridge.py` creates original `gb_cartridge.glb`: 473,164 bytes, 6,866 triangles, four PBR materials; 54×65×8.5 mm, +Y up/+Z label, top-left UV. Recessed label printing is 512×448 (8:7) with the same named replaceable primitive. The original orbital-survey print and grain maps are embedded. GB and GBC currently share this development shell; it is not a final artist-polished shape. Ten structural tests and all three GLBs pass official validation. Nine slots each reuse GBA/tall instances, with at most nine active labels and bounded aspect-specific decode cache.
