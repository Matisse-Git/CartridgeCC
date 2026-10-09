# Cartridge asset pipeline

## Current engineering asset

Blender was unavailable. `tools/generate_cartridge.py` deterministically creates an original 79,780-byte
glTF 2.0 binary using real indexed triangles, physical depth, chamfer rings, a top grip lip, shell seam,
label rim and 16 plated contacts. It embeds a 512×256 original fictional ORBIT FIELD PNG. Materials
are plastic/recess/replaceable label/metal, with distinct metallic and roughness values. No external
artwork or model is copied. `tools/test_assets.py` checks bounds, indices, normals, winding and texture.

```powershell
python tools/generate_cartridge.py
python tools/test_assets.py
```

The asset is deliberately an M0 geometry study. It does not claim production seams, smooth bevels,
micro-normal detail, realistic label recess milling or studio reflection quality. The diffuse spherical
harmonic environment and sun are temporary; there is no prefiltered studio specular cubemap yet.

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

## Compression and future label loading

The M0 PNG avoids another texture-tool dependency. For final assets evaluate KTX2/Basis Universal
with the pinned gltfio runtime, test ETC2/ASTC capability on RP5, and compare GPU memory/visual quality.
Never assume a WebP/extension provider exists. Draco/meshopt compression and runtime label replacement
remain deferred; the separate label material/UV region is ready, but there is no label editor or game-art
texture replacement API in M0. Avoid per-navigation texture decoding and retain the three-instance cache.

The next label proof must assign different user-selected images to instances of this one mesh without
changing adjacent games. Preserve original images; store a crop/fit/rotation recipe per selection and
derive template-sized textures. Publish label UV orientation, aspect ratio and a mask with each template.
Future disc templates also define outer radius and centre-hole masking. Distinguish flat label artwork
from photographs of entire media and case/box art. See [LABEL_SCRAPING.md](LABEL_SCRAPING.md).
