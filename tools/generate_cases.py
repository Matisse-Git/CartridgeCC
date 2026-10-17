"""Generate the retail cases a game is shown in (ADR 0029).

Run from the repository root with Blender 4.2 or newer (validated with 5.2.2 LTS):

    blender -b --factory-startup --disable-autoexec --python tools/generate_cases.py

ADR 0019 forbids a generator for the objects converted from the owner's downloads. This one is the exception
ADR 0029 allows: no usable case model could be downloaded without an account, and a case is a simple shape
whose look is its cover. It writes only `*_case.glb` and `*_box.glb`, never a file `import_models.py` writes.

Each case follows the media contract the renderer relies on:
  * metres, origin at the centre, +Y up, front cover toward +Z, at the real case's size;
  * exactly one primitive with the material `Replaceable fictional label`. Its UVs unfold the cover the way
    ScreenScraper's `box-texture` lays it out, left to right: back, spine, front; (0, 0) is the top left;
  * an embedded placeholder of the label's aspect, plain label stock, so an unbound case is never foreign.

Two kinds:
  * `box`: a cardboard box printed all over. Back, spine (the left side) and front carry their part of the
    cover; the right side, top and bottom take the colour of the front's adjacent edge, as a printed flap does.
    The art primitive is the whole box.
  * `case`: a plastic case. The shell is dark glossy plastic (clear plastic over a black tray reads as that on
    the library's dark stage). The cover is a sheet laid 0.1 mm over the front, back and hinge side, inside
    the case's frame.
"""
import struct
import json
from pathlib import Path

import bpy
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / 'app/src/main/assets/models'
LABEL = 'Replaceable fictional label'
PAPER = (211 / 255, 205 / 255, 191 / 255)  # TypesetLabel's stock, as in import_models.py.
RULE = (169 / 255, 162 / 255, 148 / 255)
LIFT = .0001  # A case's cover sheet sits this far outside its shell.

# name: kind, (width, height, depth) in mm, and for a `case` the cover's visible panels in mm:
# front (width, height), back (width, height), and the inset of the front panel from the right edge.
# Sizes are the real cases' (ADR 0029); boxes from box-protector listings, the jewel case from its standard.
CASES = {
    # Game Boy Advance in North America and Europe, nearly square.
    'gba_box': dict(kind='box', size=(125, 125, 22)),
    # Game Boy Advance in Japan: narrower and taller.
    'gba_box_jp': dict(kind='box', size=(88, 136, 22)),
    # The standard CD jewel case: a 120 mm booklet in the lid, the tray card's back visible behind.
    'jewel_case': dict(kind='case', size=(142, 125, 10.4), front=(120, 120), back=(138, 118), inset=3,
                       shell=(.018, .018, .02), roughness=.12, cover_roughness=.3),
}


def wrap(spec):
    """Texture u boundaries (back end, front start) and the label artwork's width/height aspect."""
    width, height, depth = spec['size']
    if spec['kind'] == 'box':
        back, spine, front, tall = width, depth, width, height
    else:
        back, spine, front, tall = spec['back'][0], depth, spec['front'][0], max(spec['front'][1], spec['back'][1])
    total = back + spine + front
    return back / total, (back + spine) / total, total / tall


def label_size(spec):
    """The placeholder's pixels: 512 wide like every label, as tall as the unfolded cover's aspect asks."""
    aspect = wrap(spec)[2]
    return 512, max(64, round(512 / aspect))


def image(name, width, height):
    data = np.ones((height, width, 4), np.float32)
    data[..., :3] = PAPER
    inset = max(4, width // 64)
    for edge in (data[inset:inset + 2, inset:-inset], data[-inset - 2:-inset, inset:-inset], data[inset:-inset, inset:inset + 2], data[inset:-inset, -inset - 2:-inset]):
        edge[..., :3] = RULE
    result = bpy.data.images.new(name, width, height, alpha=False)
    result.pixels.foreach_set(data[::-1].ravel())  # Blender stores the bottom row first.
    result.file_format = 'PNG'
    result.pack()
    return result


def material(name, colour=None, roughness=.5, picture=None):
    result = bpy.data.materials.new(name)
    if not result.node_tree:
        result.use_nodes = True
    result.use_backface_culling = True
    nodes = result.node_tree.nodes
    nodes.clear()
    shader = nodes.new('ShaderNodeBsdfPrincipled')
    output = nodes.new('ShaderNodeOutputMaterial')
    result.node_tree.links.new(shader.outputs['BSDF'], output.inputs['Surface'])
    shader.inputs['Metallic'].default_value = 0
    shader.inputs['Roughness'].default_value = roughness
    if colour:
        shader.inputs['Base Color'].default_value = (*colour, 1)
    if picture:
        node = nodes.new('ShaderNodeTexImage')
        node.image = picture
        result.node_tree.links.new(node.outputs['Color'], shader.inputs['Base Color'])
    return result


class Builder:
    """Quads, each with its own four vertices so every face is flat, and its own texture coordinates."""

    def __init__(self):
        self.vertices, self.faces, self.uvs = [], [], []

    def quad(self, corners, uvs):
        """Corners counter-clockwise as seen from outside, so the face normal points away from the object."""
        start = len(self.vertices)
        self.vertices += corners
        self.uvs += uvs
        self.faces.append((start, start + 1, start + 2, start + 3))

    def object(self, name, shading):
        mesh = bpy.data.meshes.new(name)
        # Shapes are written in the runtime's axes (+Y up, +Z front); Blender is +Z up, and the exporter turns
        # Blender's (x, y, z) into glTF's (x, z, -y). A rotation, so winding and normals are kept.
        mesh.from_pydata([(x, -z, y) for x, y, z in self.vertices], [], self.faces)
        layer = mesh.uv_layers.new(name='UVMap').data
        for polygon in mesh.polygons:
            for loop in polygon.loop_indices:
                u, v = self.uvs[mesh.loops[loop].vertex_index]
                layer[loop].uv = (u, 1 - v)  # The contract's v grows down; Blender's grows up.
        mesh.materials.append(shading)
        obj = bpy.data.objects.new(name, mesh)
        bpy.context.scene.collection.objects.link(obj)
        return obj


def cuboid(builder, x0, x1, y0, y1, z0, z1, uv):
    """Six faces; `uv(face, x, y, z)` gives each corner's texture coordinate. Faces: front, back, left, right, top, bottom."""
    corners = {
        'front': [(x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)],
        'back': [(x1, y0, z0), (x0, y0, z0), (x0, y1, z0), (x1, y1, z0)],
        'left': [(x0, y0, z0), (x0, y0, z1), (x0, y1, z1), (x0, y1, z0)],
        'right': [(x1, y0, z1), (x1, y0, z0), (x1, y1, z0), (x1, y1, z1)],
        'top': [(x0, y1, z1), (x1, y1, z1), (x1, y1, z0), (x0, y1, z0)],
        'bottom': [(x0, y0, z0), (x1, y0, z0), (x1, y0, z1), (x0, y0, z1)],
    }
    for face, points in corners.items():
        coordinates = [uv(face, *point) for point in points]
        if coordinates[0] is not None:
            builder.quad(points, coordinates)


def box(name, spec, label):
    width, height, depth = (value / 1000 for value in spec['size'])
    back_end, front_start, _ = wrap(spec)
    x0, x1, y0, y1, z0, z1 = -width / 2, width / 2, -height / 2, height / 2, -depth / 2, depth / 2
    edge = .004  # How much of the front's border a flap repeats.

    def v(y):
        return (y1 - y) / height

    def uv(face, x, y, z):
        if face == 'front':
            return front_start + (x - x0) / width * (1 - front_start), v(y)
        if face == 'back':  # Seen from behind, +x is on the left.
            return (x1 - x) / width * back_end, v(y)
        if face == 'left':
            return back_end + (z - z0) / depth * (front_start - back_end), v(y)
        if face == 'right':
            return 1 - edge * (1 - (z - z0) / depth), v(y)
        across = front_start + (x - x0) / width * (1 - front_start)
        return across, (edge * (1 - (z - z0) / depth)) if face == 'top' else 1 - edge * (1 - (z - z0) / depth)

    builder = Builder()
    cuboid(builder, x0, x1, y0, y1, z0, z1, uv)
    return builder.object(name, label)


def case(name, spec, label):
    width, height, depth = (value / 1000 for value in spec['size'])
    back_end, front_start, _ = wrap(spec)
    shell = material(f'{name} shell', spec['shell'], spec['roughness'])
    builder = Builder()
    x0, x1, y0, y1, z0, z1 = -width / 2, width / 2, -height / 2, height / 2, -depth / 2, depth / 2
    cuboid(builder, x0, x1, y0, y1, z0, z1, lambda *_: (0, 0))
    body = builder.object(name, shell)

    front_w, front_h = (value / 1000 for value in spec['front'])
    back_w, back_h = (value / 1000 for value in spec['back'])
    inset = spec['inset'] / 1000
    tall = max(front_h, back_h)
    sheet = Builder()
    # Front: the booklet, against the right of the lid; the hinge is at the left.
    fx1 = x1 - inset; fx0 = fx1 - front_w; fy0, fy1 = -front_h / 2, front_h / 2
    top = (tall - front_h) / 2 / tall
    sheet.quad([(fx0, fy0, z1 + LIFT), (fx1, fy0, z1 + LIFT), (fx1, fy1, z1 + LIFT), (fx0, fy1, z1 + LIFT)],
               [(front_start, 1 - top), (1, 1 - top), (1, top), (front_start, top)])
    # Back: the tray card, centred; seen from behind its left is +x.
    bx0, bx1, by0, by1 = -back_w / 2, back_w / 2, -back_h / 2, back_h / 2
    top = (tall - back_h) / 2 / tall
    sheet.quad([(bx1, by0, z0 - LIFT), (bx0, by0, z0 - LIFT), (bx0, by1, z0 - LIFT), (bx1, by1, z0 - LIFT)],
               [(0, 1 - top), (back_end, 1 - top), (back_end, top), (0, top)])
    # Spine: the tray card's edge, seen through the hinge side, the case's whole depth less its walls.
    wall = min(.001, depth / 6)
    sz0, sz1 = z0 + wall, z1 - wall
    sheet.quad([(x0 - LIFT, by0, sz0), (x0 - LIFT, by0, sz1), (x0 - LIFT, by1, sz1), (x0 - LIFT, by1, sz0)],
               [(back_end, 1 - top), (front_start, 1 - top), (front_start, top), (back_end, top)])
    cover = sheet.object(f'{name} cover', label)
    join(body, cover)
    return body


def join(target, extra):
    bpy.ops.object.select_all(action='DESELECT')
    target.select_set(True)
    extra.select_set(True)
    bpy.context.view_layer.objects.active = target
    bpy.ops.object.join()


def export(obj, name):
    obj.name = obj.data.name = name
    obj.modifiers.new('Triangulate', 'TRIANGULATE')
    bpy.ops.object.select_all(action='DESELECT')
    obj.select_set(True)
    bpy.context.view_layer.objects.active = obj
    path = OUTPUT / f'{name}.glb'
    bpy.ops.export_scene.gltf(filepath=str(path), export_format='GLB', use_selection=True, export_apply=True, export_yup=True,
                              export_texcoords=True, export_normals=True, export_tangents=True, export_materials='EXPORT',
                              export_image_format='AUTO', export_cameras=False, export_lights=False, export_animations=False,
                              export_skins=False, export_morph=False, export_extras=False, export_draco_mesh_compression_enable=False)
    return path


def check(path, spec):
    """What the runtime depends on, in the file that ships."""
    raw = path.read_bytes()
    size = struct.unpack_from('<I', raw, 12)[0]
    doc = json.loads(raw[20:20 + size])
    assert all(m.get('alphaMode', 'OPAQUE') == 'OPAQUE' for m in doc['materials'])
    assert all(not {'rotation', 'translation', 'scale', 'matrix'} & set(node) for node in doc['nodes'])
    labels = [p for mesh in doc['meshes'] for p in mesh['primitives'] if doc['materials'][p['material']]['name'] == LABEL]
    assert len(labels) == 1, 'exactly one replaceable label primitive'
    print(f'{path.name}: {len(raw):,} bytes, {spec["size"]} mm, label {label_size(spec)} px')


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    for name, spec in CASES.items():
        bpy.ops.wm.read_factory_settings(use_empty=True)
        width, height = label_size(spec)
        label = material(LABEL, roughness=.55 if spec['kind'] == 'box' else spec['cover_roughness'], picture=image(f'{name} label', width, height))
        obj = box(name, spec, label) if spec['kind'] == 'box' else case(name, spec, label)
        check(export(obj, name), spec)


main()
