"""Headless export of selected, manually polished Blender cartridge meshes."""
from pathlib import Path
import bpy

destination = Path(__file__).resolve().parents[1] / 'app/src/main/assets/models/gba_cartridge.glb'
meshes = [obj for obj in bpy.context.selected_objects if obj.type == 'MESH']
if not meshes:
    raise RuntimeError('Select the cartridge meshes in the saved .blend before exporting')
destination.parent.mkdir(parents=True, exist_ok=True)
bpy.ops.export_scene.gltf(filepath=str(destination), export_format='GLB', use_selection=True,
                          export_apply=True, export_normals=True, export_tangents=True,
                          export_animations=False, export_cameras=False, export_lights=False)
