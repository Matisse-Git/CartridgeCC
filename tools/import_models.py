"""Build the runtime media GLBs from the owner's downloaded source models.

Run from the repository root with Blender 4.2 or newer (validated with 5.2.2 LTS; nothing else is needed):

    blender -b --factory-startup --disable-autoexec --python tools/import_models.py

Sources stay untouched under assets/models. Two folder names there do not match their contents:
`gb` holds the wide Game Boy Advance cartridge and `gba` holds the tall Game Boy cartridge. `n64` is not
converted: no console in the app is shown as it yet.

What the app needs and the downloads do not have, this adds:
  * metres, origin at the centre, +Y up, printed face toward +Z, each object at its template size;
  * one primitive per object with the material `Replaceable fictional label` and label UVs spanning
    exactly 0..1 with (0, 0) at the top left, which CartridgeLabels rebinds per game;
  * shell textures at 1024 px with glTF's occlusion/roughness/metallic packing and +Y normals;
  * an opaque disc: its clear hub is composited over the library's dark stage instead of blended.

What it removes: the commercial game label baked into each cartridge's base colour. That region is
filled with the surrounding plastic before anything is written, so no game artwork reaches the APK or Git.
Where a download carries more artwork than its mesh shows (an atlas shared with other objects), every texel
the mesh does not use is filled as well.
"""
import json
import struct
import sys
import tempfile
import zipfile
from pathlib import Path

import bpy
import numpy as np
from mathutils import Matrix, Vector

ROOT = Path(__file__).resolve().parents[1]
SOURCES = ROOT / 'assets/models'
OUTPUT = ROOT / 'app/src/main/assets/models'
LABEL = 'Replaceable fictional label'
TEXTURE = 1024
PAPER = (211 / 255, 205 / 255, 191 / 255)  # TypesetLabel's stock, so an unbound label is never a foreign colour.
RULE = (169 / 255, 162 / 255, 148 / 255)
STAGE = .02  # The library's near-black backdrop, seen through clear plastic.
MEMBER_LIMIT = 64 * 1024 * 1024


# ---------------------------------------------------------------- images

def read_image(path):
    """RGBA float rows, bottom row first: the row index grows with v."""
    image = bpy.data.images.load(str(path))
    width, height = image.size
    data = np.empty(width * height * 4, np.float32)
    image.pixels.foreach_get(data)
    bpy.data.images.remove(image)
    return data.reshape(height, width, 4)


def shrink(data, size, linear=False):
    """Box filter by a whole factor. Colour is averaged in linear light."""
    factor = data.shape[0] // size
    assert data.shape[0] == data.shape[1] == size * factor, data.shape
    if factor == 1:
        return data.copy()
    source = data if linear else np.where(data <= .04045, data / 12.92, ((data + .055) / 1.055) ** 2.4)
    mean = source.reshape(size, factor, size, factor, 4).mean(axis=(1, 3))
    return mean if linear else np.where(mean <= .0031308, mean * 12.92, 1.055 * mean ** (1 / 2.4) - .055)


def write_image(data, path, colour):
    """Save rows as PNG or JPEG by suffix and hand back a file-backed image, which the exporter embeds as is."""
    height, width = data.shape[:2]
    image = bpy.data.images.new(path.stem, width, height, alpha=False)
    rgba = np.ones((height, width, 4), np.float32)
    rgba[..., :3] = np.clip(data[..., :3], 0, 1)
    image.pixels.foreach_set(rgba.ravel())
    image.filepath_raw = str(path)
    image.file_format = 'JPEG' if path.suffix == '.jpg' else 'PNG'
    image.save(quality=92)
    bpy.data.images.remove(image)
    return load_image(path, colour)


def load_image(path, colour):
    image = bpy.data.images.load(str(path))
    image.colorspace_settings.name = 'sRGB' if colour else 'Non-Color'
    return image


def box(data, u0, u1, v0, v1):
    height, width = data.shape[:2]
    return data[max(0, round(v0 * height)):min(height, round(v1 * height)), max(0, round(u0 * width)):min(width, round(u1 * width))]


def erase_print(base, u0, u1, v0, v1):
    """Replace a baked label with the plastic around it and prove nothing printed is left."""
    def chroma(region):
        return float((region[..., :3].max(axis=2) - region[..., :3].min(axis=2) > .18).mean())
    assert chroma(box(base, u0, u1, v0, v1)) > .25, 'no printed label where this source model should have one'
    grow, ring = .003, .02
    height, width = base.shape[:2]
    rows, columns = np.arange(height)[:, None] / height, np.arange(width)[None, :] / width
    def inside(margin):
        return (columns >= u0 - margin) & (columns < u1 + margin) & (rows >= v0 - margin) & (rows < v1 + margin)
    # The median of the band just outside the label ignores any stray print or seam in it.
    plastic = np.median(base[inside(ring) & ~inside(grow)][:, :3], axis=0)
    assert plastic.max() - plastic.min() < .08, f'the fill should be neutral plastic, got {plastic}'
    base[inside(grow), :3] = plastic
    assert chroma(box(base, u0, u1, v0, v1)) == 0
    return plastic


def differing(region, plastic):
    """The share of a region that is not the plastic: print of any colour, black included."""
    return float((np.abs(region[..., :3] - plastic).max(axis=2) > .1).mean())


def erase_box(base, u0, u1, v0, v1, share=.25):
    """erase_print for a label that need not be colourful. The plastic is read from the band around the box."""
    grow, ring = .003, .02
    height, width = base.shape[:2]
    rows, columns = np.arange(height)[:, None] / height, np.arange(width)[None, :] / width
    def inside(margin):
        return (columns >= u0 - margin) & (columns < u1 + margin) & (rows >= v0 - margin) & (rows < v1 + margin)
    plastic = np.median(base[inside(ring) & ~inside(grow)][:, :3], axis=0)
    assert plastic.max() - plastic.min() < .08, f'the fill should be neutral plastic, got {plastic}'
    assert differing(box(base, u0, u1, v0, v1), plastic) > share, 'no printed label where this source model should have one'
    base[inside(grow), :3] = plastic
    return plastic


def uv_mask(mesh, polygons, shape, grow):
    """The texels a set of faces shows, and a margin around them for filtering."""
    height, width = shape[:2]
    mask = np.zeros((height, width), bool)
    layer = mesh.uv_layers[0].data
    for polygon in polygons:
        corners = np.array([layer[i].uv for i in polygon.loop_indices]) * (width, height)
        for n in range(1, len(corners) - 1):
            a, b, c = corners[0], corners[n], corners[n + 1]
            x0, x1 = max(0, int(np.floor(min(a[0], b[0], c[0])))), min(width, int(np.ceil(max(a[0], b[0], c[0]))) + 1)
            y0, y1 = max(0, int(np.floor(min(a[1], b[1], c[1])))), min(height, int(np.ceil(max(a[1], b[1], c[1]))) + 1)
            if x0 >= x1 or y0 >= y1:
                continue
            area = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])
            if abs(area) < 1e-9:
                mask[y0:y1, x0:x1] = True  # A sliver: its box is a texel or two.
                continue
            # Texel centres, with half a texel of slack so an edge never falls between two rows.
            px, py = np.meshgrid(np.arange(x0, x1) + .5, np.arange(y0, y1) + .5)
            w0 = ((b[0] - px) * (c[1] - py) - (b[1] - py) * (c[0] - px)) / area
            w1 = ((c[0] - px) * (a[1] - py) - (c[1] - py) * (a[0] - px)) / area
            slack = .75 / max(1e-9, np.sqrt(abs(area)))
            mask[y0:y1, x0:x1] |= (w0 >= -slack) & (w1 >= -slack) & (w0 + w1 <= 1 + slack)
    for _ in range(grow):
        wider = mask.copy()
        wider[1:] |= mask[:-1]; wider[:-1] |= mask[1:]; wider[:, 1:] |= mask[:, :-1]; wider[:, :-1] |= mask[:, 1:]
        mask = wider
    return mask


def green_points_down(normal):
    """True for a -Y ("DirectX") normal map. The slopes of a height field have equal cross derivatives; with the
    green channel turned over they are opposite. Measured, because the downloads do not say."""
    x, y = normal[..., 0].astype(np.float64) - .5, normal[..., 1].astype(np.float64) - .5
    a, b = x[2:, 1:-1] - x[:-2, 1:-1], y[1:-1, 2:] - y[1:-1, :-2]
    agreement = float((a * b).sum() / np.sqrt((a * a).sum() * (b * b).sum()))
    assert abs(agreement) > .2, f'this normal map does not say which way its green channel points ({agreement:+.3f})'
    return agreement < 0


def flat_orm(work, name, roughness, metallic=0.):
    """A download with no surface maps gets the same material as the others, from a constant one."""
    orm = np.ones((8, 8, 4), np.float32)
    orm[..., 1], orm[..., 2] = roughness, metallic
    return write_image(orm, work / f'{name}_orm.png', False)


def flat_normal(work, name):
    """And one with no relief, a level normal map."""
    normal = np.ones((8, 8, 4), np.float32)
    normal[..., :2] = .5
    return write_image(normal, work / f'{name}_normal.png', False)


def pack_orm(occlusion, roughness, metallic):
    orm = np.ones_like(roughness)
    orm[..., 0] = occlusion[..., 0] if occlusion is not None else 1
    orm[..., 1] = roughness[..., 0]
    orm[..., 2] = metallic[..., 0]
    return orm


def opengl_normals(normal, flip_green):
    """Both downloads store -Y ("DirectX") normals; glTF and Filament expect +Y. Renormalise after filtering."""
    vector = normal[..., :3] * 2 - 1
    if flip_green:
        vector[..., 1] = -vector[..., 1]
    vector /= np.maximum(np.linalg.norm(vector, axis=2, keepdims=True), 1e-6)
    result = np.ones_like(normal)
    result[..., :3] = vector * .5 + .5
    return result


def placeholder(width, height, path):
    """Original blank label stock. It is only ever seen if a label fails to bind."""
    data = np.ones((height, width, 4), np.float32)
    data[..., :3] = PAPER
    inset = max(4, width // 32)
    for edge in (data[inset:inset + 2, inset:-inset], data[-inset - 2:-inset, inset:-inset], data[inset:-inset, inset:inset + 2], data[inset:-inset, -inset - 2:-inset]):
        edge[..., :3] = RULE
    return write_image(data, path, colour=True)


# ---------------------------------------------------------------- materials

def principled(name, cull):
    material = bpy.data.materials.new(name)
    if not material.node_tree:
        material.use_nodes = True  # Blender 4.x; later versions always have a tree.
    material.use_backface_culling = cull
    nodes = material.node_tree.nodes
    nodes.clear()
    shader = nodes.new('ShaderNodeBsdfPrincipled')
    output = nodes.new('ShaderNodeOutputMaterial')
    material.node_tree.links.new(shader.outputs['BSDF'], output.inputs['Surface'])
    return material, shader


def texture(material, image):
    node = material.node_tree.nodes.new('ShaderNodeTexImage')
    node.image = image
    return node


def shell_material(name, base, orm, normal):
    material, shader = principled(name, cull=False)
    nodes, links = material.node_tree.nodes, material.node_tree.links
    links.new(texture(material, base).outputs['Color'], shader.inputs['Base Color'])
    channels = nodes.new('ShaderNodeSeparateColor')
    links.new(texture(material, orm).outputs['Color'], channels.inputs['Color'])
    links.new(channels.outputs['Green'], shader.inputs['Roughness'])
    links.new(channels.outputs['Blue'], shader.inputs['Metallic'])
    # The exporter reads occlusion from a group with this exact name.
    group = bpy.data.node_groups.get('glTF Material Output') or bpy.data.node_groups.new('glTF Material Output', 'ShaderNodeTree')
    if not group.interface.items_tree:
        group.interface.new_socket('Occlusion', in_out='INPUT', socket_type='NodeSocketFloat')
    occlusion = nodes.new('ShaderNodeGroup')
    occlusion.node_tree = group
    links.new(channels.outputs['Red'], occlusion.inputs['Occlusion'])
    bump = nodes.new('ShaderNodeNormalMap')
    links.new(texture(material, normal).outputs['Color'], bump.inputs['Color'])
    links.new(bump.outputs['Normal'], shader.inputs['Normal'])
    return material


def label_material(image):
    material, shader = principled(LABEL, cull=True)
    material.node_tree.links.new(texture(material, image).outputs['Color'], shader.inputs['Base Color'])
    shader.inputs['Metallic'].default_value = 0
    shader.inputs['Roughness'].default_value = .62
    return material


# ---------------------------------------------------------------- geometry

def select(obj):
    bpy.ops.object.select_all(action='DESELECT')
    obj.select_set(True)
    bpy.context.view_layer.objects.active = obj


def import_mesh(path, local=False):
    """One mesh object in metres, with its transform baked in or (local) in its own unposed frame."""
    bpy.ops.wm.read_factory_settings(use_empty=True)
    if path.suffix == '.obj':
        bpy.ops.wm.obj_import(filepath=str(path))
    else:
        bpy.ops.import_scene.fbx(filepath=str(path), use_anim=False)
    meshes = [o for o in bpy.context.scene.objects if o.type == 'MESH']
    assert len(meshes) == 1, [o.name for o in bpy.context.scene.objects]
    obj = meshes[0]
    obj.animation_data_clear()  # A stored pose would come back as a node rotation at export.
    world = Matrix.Identity(4) if local else obj.matrix_world.copy()
    obj.parent = None
    obj.matrix_world = world
    select(obj)
    bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)
    return obj


def bake(obj, matrix):
    obj.matrix_world = matrix
    select(obj)
    bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)


def bounds(mesh):
    points = np.array([v.co for v in mesh.vertices])
    return points.min(axis=0), points.max(axis=0)


def centre_and_size(obj, axis, metres):
    """Centre the bounding box on the origin and scale it so one axis has the template's physical size."""
    low, high = bounds(obj.data)
    scale = metres / float(high[axis] - low[axis])
    bake(obj, Matrix.Scale(scale, 4) @ Matrix.Translation(Vector((-(low + high) / 2).tolist())))
    return scale


def assign_label(mesh, polygons, flat=True):
    """Slot 1 is the label. Call once both materials exist: replacing the slots resets every face to slot 0."""
    wanted = {polygon.index for polygon in polygons}
    for polygon in mesh.polygons:
        polygon.material_index = 1 if polygon.index in wanted else 0
    if not flat:
        return  # A label on a curved shell keeps the shell's own shading.
    # A printed label is flat. The source shades these faces into the surrounding bevel; give them their own facing.
    normals = [tuple(corner.vector) for corner in mesh.corner_normals]
    for polygon in mesh.polygons:
        if polygon.index in wanted:
            for loop in polygon.loop_indices:
                normals[loop] = tuple(polygon.normal)
    mesh.normals_split_custom_set(normals)


def front_faces(mesh):
    """Flat polygons looking at the viewer (-Y in Blender, +Z once exported)."""
    return [p for p in mesh.polygons if p.normal.y < -.995]


def recess_floor(mesh, width, height):
    """The one flat sheet of front faces with a label's size: front faces layered by depth, 0.02 mm apart or more."""
    layers = []
    for polygon in sorted(front_faces(mesh), key=lambda p: p.center.y):
        if layers and polygon.center.y - layers[-1][-1].center.y < .00002:
            layers[-1].append(polygon)
        else:
            layers.append([polygon])
    floors = []
    for polygons in layers:
        points = np.array([mesh.vertices[i].co for p in polygons for i in p.vertices])
        wide, tall = np.ptp(points[:, 0]), np.ptp(points[:, 2])
        if width[0] < wide < width[1] and height[0] < tall < height[1] and sum(p.area for p in polygons) > .97 * wide * tall:
            floors.append((polygons, points))
    assert len(floors) == 1, f'expected one label floor, found {len(floors)}'
    return floors[0]


def spread_label_uvs(mesh, polygons, x0, x1, z0, z1):
    """The whole image over the whole label; returns where those faces were printed from before."""
    layer = mesh.uv_layers[0].data
    printed = np.array([layer[i].uv for p in polygons for i in p.loop_indices])
    for polygon in polygons:
        for loop in polygon.loop_indices:
            co = mesh.vertices[mesh.loops[loop].vertex_index].co
            layer[loop].uv = ((co.x - x0) / (x1 - x0), (co.z - z0) / (z1 - z0))
    return printed[:, 0].min(), printed[:, 0].max(), printed[:, 1].min(), printed[:, 1].max()


def sample(base, mesh, polygon):
    """The base colour at the middle of a face."""
    layer = mesh.uv_layers[0].data
    u, v = np.mean([layer[i].uv for i in polygon.loop_indices], axis=0)
    return base[int(np.clip(v * base.shape[0], 0, base.shape[0] - 1)), int(np.clip(u * base.shape[1], 0, base.shape[1] - 1)), :3]


def shade(obj, crease):
    """Smooth, with an edge wherever two faces meet at more than the crease angle (degrees)."""
    select(obj)
    if obj.data.has_custom_normals:
        bpy.ops.mesh.customdata_custom_splitnormals_clear()
    obj.data.shade_smooth()
    obj.data.set_sharp_from_angle(angle=np.radians(crease))


def open_edge(mesh):
    """The vertices on a mesh's open edges, by index."""
    uses = {}
    for polygon in mesh.polygons:
        for key in polygon.edge_keys:
            uses[key] = uses.get(key, 0) + 1
    return {i for key, count in uses.items() if count == 1 for i in key}


def places(mesh, vertices):
    """Where some vertices are, to a thousandth of a millimetre."""
    return {tuple(round(c, 6) for c in mesh.vertices[i].co) for i in vertices}


def settle(obj, passes):
    """A scan's flat sides are noisy, and collapsing noise leaves ripples: smooth it first."""
    smooth = obj.modifiers.new('Settle', 'SMOOTH')
    smooth.factor, smooth.iterations = .5, passes
    select(obj)
    bpy.ops.object.modifier_apply(modifier='Settle')


def reduce(obj, triangles, crease, keep_open_edges=False):
    """Collapse a dense download to the triangle budget. The source's own normals do not survive that; edges
    sharper than the crease angle are kept as edges instead. Where a label was cut out of the shell, the cut and
    the faces along it can be left exactly as they are."""
    mesh = obj.data
    count = sum(len(p.vertices) - 2 for p in mesh.polygons)
    assert count > triangles, count
    modifier = obj.modifiers.new('Reduce', 'DECIMATE')
    modifier.ratio = triangles / count
    modifier.use_collapse_triangulate = True
    if keep_open_edges:
        # The modifier never collapses an edge between two vertices outside its group: the cut, and one ring around it.
        kept = open_edge(mesh)
        kept |= {i for key in mesh.edge_keys if kept & set(key) for i in key}
        group = obj.vertex_groups.new(name='Reduce')
        group.add([v.index for v in mesh.vertices if v.index not in kept], 1.0, 'REPLACE')
        modifier.vertex_group = group.name
    select(obj)
    bpy.ops.object.modifier_apply(modifier='Reduce')
    obj.vertex_groups.clear()
    assert len(obj.data.polygons) <= triangles + 8, len(obj.data.polygons)
    shade(obj, crease)


def separate(obj, polygons):
    """Move some faces into an object of their own and hand it back."""
    wanted = {p.index for p in polygons}
    for polygon in obj.data.polygons:
        polygon.select = polygon.index in wanted
    for edge in obj.data.edges:
        edge.select = False
    for vertex in obj.data.vertices:
        vertex.select = False
    before = set(bpy.context.scene.objects)
    select(obj)
    # Face mode first: entering edit mode in vertex mode would drop faces whose vertices are not selected.
    bpy.context.tool_settings.mesh_select_mode = (False, False, True)
    bpy.ops.object.mode_set(mode='EDIT')
    bpy.ops.mesh.separate(type='SELECTED')
    bpy.ops.object.mode_set(mode='OBJECT')
    created = [o for o in bpy.context.scene.objects if o not in before]
    assert len(created) == 1 and len(created[0].data.polygons) == len(wanted), [o.name for o in created]
    return created[0]


def uv_to_point(mesh, polygons, u, v):
    """The point of a planar, affinely unwrapped face that carries texture coordinate (u, v)."""
    layer = mesh.uv_layers[0].data
    for polygon in sorted(polygons, key=lambda p: -p.area):
        coords = np.array([[layer[i].uv.x, layer[i].uv.y, 1] for i in polygon.loop_indices])
        if not (coords[:, 0].min() - 1e-4 <= u <= coords[:, 0].max() + 1e-4 and coords[:, 1].min() - 1e-4 <= v <= coords[:, 1].max() + 1e-4):
            continue
        points = np.array([mesh.vertices[mesh.loops[i].vertex_index].co for i in polygon.loop_indices])
        solution = np.linalg.lstsq(coords, points, rcond=None)[0]
        assert np.abs(coords @ solution - points).max() < 5e-4 * np.ptp(points, axis=0).max() + 1e-7, 'the face is not affinely unwrapped'
        return np.array([u, v, 1]) @ solution
    raise AssertionError(f'no front face carries uv {u}, {v}')


def rounded_outline(x0, x1, z0, z1, radius, segments=6):
    """Counter-clockwise as seen from the front, so the face normal points at the viewer."""
    points = []
    for cx, cz, start in ((x1 - radius, z0 + radius, -90), (x1 - radius, z1 - radius, 0), (x0 + radius, z1 - radius, 90), (x0 + radius, z0 + radius, 180)):
        for step in range(segments + 1):
            angle = np.radians(start + 90 * step / segments)
            points.append((cx + radius * np.cos(angle), cz + radius * np.sin(angle)))
    return points


def add_faces(name, vertices, faces, uvs, material):
    mesh = bpy.data.meshes.new(name)
    mesh.from_pydata(vertices, [], faces)
    layer = mesh.uv_layers.new(name='UVMap').data
    for polygon in mesh.polygons:
        for loop in polygon.loop_indices:
            layer[loop].uv = uvs[mesh.loops[loop].vertex_index]
    mesh.materials.append(material)
    obj = bpy.data.objects.new(name, mesh)
    bpy.context.scene.collection.objects.link(obj)
    return obj


def join(target, extra):
    target.data.uv_layers[0].name = extra.data.uv_layers[0].name = 'UVMap'
    bpy.ops.object.select_all(action='DESELECT')
    target.select_set(True)
    extra.select_set(True)
    bpy.context.view_layer.objects.active = target
    bpy.ops.object.join()


# ---------------------------------------------------------------- export

def export(obj, name):
    obj.name = obj.data.name = name
    obj.modifiers.new('Triangulate', 'TRIANGULATE')  # Tangents need triangles; the exporter applies this.
    select(obj)
    path = OUTPUT / f'{name}.glb'
    bpy.ops.export_scene.gltf(filepath=str(path), export_format='GLB', use_selection=True, export_apply=True, export_yup=True,
                              export_texcoords=True, export_normals=True, export_tangents=True, export_materials='EXPORT',
                              export_image_format='AUTO', export_cameras=False, export_lights=False, export_animations=False,
                              export_skins=False, export_morph=False, export_extras=False, export_draco_mesh_compression_enable=False)
    return path


def read_glb(path):
    raw = path.read_bytes()
    size = struct.unpack_from('<I', raw, 12)[0]
    return json.loads(raw[20:20 + size]), raw[28 + size:]


def finish(path):
    """Check what the runtime depends on, in the file that ships, and report it."""
    doc, binary = read_glb(path)
    assert all(material.get('alphaMode', 'OPAQUE') == 'OPAQUE' for material in doc['materials'])
    assert all(not {'rotation', 'translation', 'scale', 'matrix'} & set(node) for node in doc['nodes']), doc['nodes']
    primitives = [p for mesh in doc['meshes'] for p in mesh['primitives']]
    labels = [p for p in primitives if doc['materials'][p['material']]['name'] == LABEL]
    assert len(labels) == 1, 'exactly one replaceable label primitive'
    accessor = doc['accessors'][labels[0]['attributes']['TEXCOORD_0']]
    view = doc['bufferViews'][accessor['bufferView']]
    assert accessor['componentType'] == 5126 and 'byteStride' not in view
    uv = np.frombuffer(binary, '<f4', accessor['count'] * 2, view.get('byteOffset', 0) + accessor.get('byteOffset', 0)).reshape(-1, 2)
    assert uv.min() > -1e-4 and uv.max() < 1 + 1e-4, (uv.min(axis=0), uv.max(axis=0))
    low =[min(doc['accessors'][p['attributes']['POSITION']]['min'][i] for p in primitives) for i in range(3)]
    high = [max(doc['accessors'][p['attributes']['POSITION']]['max'][i] for p in primitives) for i in range(3)]
    triangles = sum(doc['accessors'][p['indices']]['count'] // 3 for p in primitives)
    images = ', '.join(f"{i['mimeType'].split('/')[1]} {doc['bufferViews'][i['bufferView']]['byteLength'] // 1024} KiB" for i in doc.get('images', []))
    print(f"BUILT {path.name}: {path.stat().st_size} bytes, {triangles} triangles, {len(doc['materials'])} materials, "
          f"{' x '.join(f'{(high[i] - low[i]) * 1000:.2f}' for i in range(3))} mm, label uv {uv.min(axis=0).round(4).tolist()}..{uv.max(axis=0).round(4).tolist()}, images: {images}")


# ---------------------------------------------------------------- the three objects

def extract(archive, suffix, target):
    """Copy one archive member to a path chosen here; member names are never used as paths."""
    with zipfile.ZipFile(archive) as bundle:
        members = [m for m in bundle.infolist() if not m.is_dir() and m.filename.lower().endswith(suffix)]
        assert len(members) == 1 and members[0].file_size <= MEMBER_LIMIT, [m.filename for m in bundle.infolist()]
        with bundle.open(members[0]) as source:
            target.write_bytes(source.read(MEMBER_LIMIT))
    return target


def gba_cartridge(work):
    """The wide cartridge. Its label recess is modelled, so the recess floor itself becomes the label."""
    folder = SOURCES / 'gb'
    archive = folder / 'source/GBA_SP_Cartridge.zip'
    obj = import_mesh(extract(archive, '.fbx', work / 'gba.fbx'))
    centre_and_size(obj, 0, .060)  # The template width the on-screen scales were tuned for.
    mesh = obj.data
    # The label floor is the one 43 x 22.5 mm sheet among the front faces.
    polygons, points = recess_floor(mesh, (.040, .047), (.020, .025))
    x0, x1, z0, z1 = points[:, 0].min(), points[:, 0].max(), points[:, 2].min(), points[:, 2].max()
    printed = spread_label_uvs(mesh, polygons, x0, x1, z0, z1)

    textures = folder / 'textures'
    base = read_image(textures / 'Cartridge_MAT_SG_BaseColor.1001.png')
    erase_print(base, *printed)
    occlusion = read_image(extract(archive, '_ao.1001.png', work / 'gba_ao.png'))
    orm = pack_orm(shrink(occlusion, TEXTURE, linear=True), shrink(read_image(textures / 'Cartridge_MAT_SG_Roughness.1001.png'), TEXTURE, linear=True),
                   shrink(read_image(textures / 'Cartridge_MAT_SG_Metalness.1001.png'), TEXTURE, linear=True))
    normal = opengl_normals(shrink(read_image(textures / 'Cartridge_MAT_SG_Normal.1001.png'), TEXTURE, linear=True), flip_green=True)
    mesh.materials.clear()
    mesh.materials.append(shell_material('Game Boy Advance cartridge shell', write_image(shrink(base, TEXTURE), work / 'gba_base.jpg', True),
                                         write_image(orm, work / 'gba_orm.png', False), write_image(normal, work / 'gba_normal.png', False)))
    mesh.materials.append(label_material(placeholder(256, 128, work / 'gba_label.png')))
    assign_label(mesh, polygons)
    print(f'LABEL gba: {len(polygons)} recess faces, {(x1 - x0) * 1000:.2f} x {(z1 - z0) * 1000:.2f} mm')
    finish(export(obj, 'gba_cartridge'))


def relief_frame(normal, u0, u1, v0, v1):
    """Inner edges of a recessed rectangle in a -Y normal map, searched inside a loose box."""
    window = box(normal, u0, u1, v0, v1)
    height, width = window.shape[:2]
    red = window[height * 3 // 10:height * 7 // 10, :, 0].mean(axis=0) - .5
    green = window[:, width * 3 // 10:width * 7 // 10, 1].mean(axis=1) - .5
    left, right = np.nonzero(red[:width // 2] > .15)[0], np.nonzero(red[width // 2:] < -.15)[0] + width // 2
    bottom, top = np.nonzero(green[:height // 2] < -.15)[0], np.nonzero(green[height // 2:] > .15)[0] + height // 2
    assert len(left) and len(right) and len(bottom) and len(top), 'the label recess is not where this source model has it'
    scale_u, scale_v = (u1 - u0) / width, (v1 - v0) / height
    return u0 + (left.max() + 1) * scale_u, u0 + right.min() * scale_u, v0 + (bottom.max() + 1) * scale_v, v0 + top.min() * scale_v


def gb_cartridge(work):
    """The tall cartridge. Its recess exists only in the normal map, so a label sheet is laid into that recess."""
    folder = SOURCES / 'gba'
    obj = import_mesh(folder / 'source/Gameboy_Cartridge.fbx')
    centre_and_size(obj, 2, .065)
    mesh = obj.data
    textures = folder / 'textures'
    source_normal = read_image(textures / 'Normal_Material.jpg')
    u0, u1, v0, v1 = relief_frame(source_normal, .48, .88, .14, .52)
    front = front_faces(mesh)
    depth = min(p.center.y for p in front)
    plane = [p for p in front if p.center.y < depth + 1e-5]
    corners = np.array([uv_to_point(mesh, plane, u, v) for u, v in ((u0, v0), (u1, v0), (u1, v1), (u0, v1))])
    assert np.ptp(corners[:, 1]) < 1e-5 and abs(corners[0, 0] - corners[3, 0]) < 1e-4 and abs(corners[0, 2] - corners[1, 2]) < 1e-4, corners
    x0, x1, z0, z1 = corners[:, 0].min(), corners[:, 0].max(), corners[:, 2].min(), corners[:, 2].max()
    assert .040 < x1 - x0 < .047 and .036 < z1 - z0 < .042, (x1 - x0, z1 - z0)
    outline = rounded_outline(x0, x1, z0, z1, .0012)
    # 0.05 mm proud of the baked recess floor, which has no depth of its own.
    sheet = add_faces('label', [(x, depth - .00005, z) for x, z in outline], [list(range(len(outline)))],
                      [((x - x0) / (x1 - x0), (z - z0) / (z1 - z0)) for x, z in outline], label_material(placeholder(256, 224, work / 'gb_label.png')))

    base = read_image(textures / 'BaseColor_Material.png')
    erase_print(base, u0, u1, v0, v1)
    orm = pack_orm(shrink(read_image(textures / 'AO_Material.jpg'), TEXTURE, linear=True), shrink(read_image(textures / 'Roughness_Material.jpg'), TEXTURE, linear=True),
                   shrink(read_image(textures / 'Metallic_Material.jpg'), TEXTURE, linear=True))
    normal = opengl_normals(shrink(source_normal, TEXTURE, linear=True), flip_green=True)
    mesh.materials.clear()
    mesh.materials.append(shell_material('Game Boy cartridge shell', write_image(shrink(base, TEXTURE), work / 'gb_base.jpg', True),
                                         write_image(orm, work / 'gb_orm.png', False), write_image(normal, work / 'gb_normal.png', False)))
    join(obj, sheet)
    print(f'LABEL gb: sheet {(x1 - x0) * 1000:.2f} x {(z1 - z0) * 1000:.2f} mm from recess uv {u0:.4f}..{u1:.4f}, {v0:.4f}..{v1:.4f}')
    finish(export(obj, 'gb_cartridge'))


def disc(work):
    """The compact disc. The printed side's broad annulus becomes the label; the clear hub is shown over a dark stage."""
    folder = SOURCES / 'cd'
    obj = import_mesh(folder / 'source/compact-disc_001.fbx', local=True)  # The download is posed at a tilt.
    mesh = obj.data
    low, high = bounds(mesh)
    assert high[2] - low[2] < .2 * (high[0] - low[0]), 'the disc should lie in its own XY plane'
    textures = folder / 'textures'
    roughness = read_image(textures / 'compact-disc_roughness.png')
    layer = mesh.uv_layers[0].data

    def annulus(sign):
        """The quads that run from the stacking ring to the rim on one side."""
        return [p for p in mesh.polygons if p.normal.z * sign > .9 and np.ptp([mesh.vertices[i].co.xy.length for i in p.vertices]) > .5 * high[0]]

    def printed(polygons):
        samples = [roughness[min(roughness.shape[0] - 1, int(layer[i].uv.y * roughness.shape[0])), min(roughness.shape[1] - 1, int(layer[i].uv.x * roughness.shape[1])), 0]
                   for p in polygons for i in p.loop_indices]
        return float(np.mean(samples))
    up, down = annulus(1), annulus(-1)
    assert len(up) == len(down) > 64
    # The printed side is the matt one. It turns to face the viewer (-Y here, +Z once exported).
    turn = np.pi / 2 if printed(up) > printed(down) else -np.pi / 2
    bake(obj, Matrix.Rotation(turn, 4, 'X') @ Matrix.Scale(.120 / float(high[0] - low[0]), 4) @ Matrix.Translation(Vector((-(low + high) / 2).tolist())))
    label = [p for p in mesh.polygons if p.normal.y < -.9 and np.ptp([mesh.vertices[i].co.xz.length for i in p.vertices]) > .03]
    assert len(label) == len(up)
    radii = [mesh.vertices[i].co.xz.length for p in label for i in p.vertices]
    for polygon in label:
        for loop in polygon.loop_indices:
            co = mesh.vertices[mesh.loops[loop].vertex_index].co
            layer[loop].uv = ((co.x + .06) / .12, (co.z + .06) / .12)  # The whole artwork square spans the 120 mm disc.

    base = read_image(textures / 'compact-disc_color.png')
    # Clear polycarbonate is stored as alpha. Everything here is opaque, so show it as it looks on the dark stage.
    alpha = base[..., 3:4]
    base[..., :3] = base[..., :3] * alpha + STAGE * (1 - alpha)
    orm = pack_orm(None, shrink(roughness, TEXTURE, linear=True), shrink(read_image(textures / 'compact-disc_metalness.png'), TEXTURE, linear=True))
    normal = opengl_normals(shrink(read_image(textures / 'compact-disc_normal.png'), TEXTURE, linear=True), flip_green=False)
    mesh.materials.clear()
    mesh.materials.append(shell_material('Compact disc', write_image(shrink(base, TEXTURE), work / 'disc_base.jpg', True),
                                         write_image(orm, work / 'disc_orm.png', False), write_image(normal, work / 'disc_normal.png', False)))
    mesh.materials.append(label_material(placeholder(256, 256, work / 'disc_label.png')))
    assign_label(mesh, label)
    print(f'LABEL disc: {len(label)} faces, radius {min(radii) * 1000:.2f}..{max(radii) * 1000:.2f} mm')
    finish(export(obj, 'ps1_disc'))


def nes_cartridge(work):
    """The Game Pak. Its label recess is modelled, so the recess floor becomes the label. The same print folds
    over the top edge; that strip goes back to plastic."""
    folder = SOURCES / 'nes'
    obj = import_mesh(folder / 'source/NES_CartEXP.fbx')
    centre_and_size(obj, 0, .120)
    mesh = obj.data
    polygons, points = recess_floor(mesh, (.050, .058), (.085, .095))
    x0, x1, z0, z1 = points[:, 0].min(), points[:, 0].max(), points[:, 2].min(), points[:, 2].max()
    low, high = bounds(mesh)
    assert (x0 + x1) / 2 > 0 and z1 > .9 * high[2], 'the label belongs at the top right of the front'
    printed = spread_label_uvs(mesh, polygons, x0, x1, z0, z1)

    textures = folder / 'textures'
    base = read_image(textures / 'NES_Cart_Diff.jpg')
    # This base colour is one plastic grey and the label, nothing else: whatever is not that grey is print.
    plastic = np.median(base[::8, ::8, :3].reshape(-1, 3), axis=0)
    assert plastic.max() - plastic.min() < .02, plastic
    print_share = differing(base, plastic)
    assert .08 < print_share < .25 and differing(box(base, *printed), plastic) > .7, print_share
    marked = np.abs(base[..., :3] - plastic).max(axis=2) > .03
    for _ in range(6):  # JPEG ringing around the print.
        wider = marked.copy()
        wider[1:] |= marked[:-1]; wider[:-1] |= marked[1:]; wider[:, 1:] |= marked[:, :-1]; wider[:, :-1] |= marked[:, 1:]
        marked = wider
    base[marked, :3] = plastic
    assert differing(base, plastic) == 0
    source_normal = read_image(textures / 'NES_CartEXP_lambert1_Normal.png')
    normal = opengl_normals(shrink(source_normal, TEXTURE, linear=True), flip_green=green_points_down(source_normal))
    # The download has no surface maps: moulded ABS, a little rougher than the handheld shells.
    mesh.materials.clear()
    mesh.materials.append(shell_material('NES cartridge shell', write_image(shrink(base, TEXTURE), work / 'nes_base.jpg', True),
                                         flat_orm(work, 'nes', roughness=.58), write_image(normal, work / 'nes_normal.png', False)))
    mesh.materials.append(label_material(placeholder(256, 428, work / 'nes_label.png')))
    assign_label(mesh, polygons)
    print(f'LABEL nes: {len(polygons)} recess faces, {(x1 - x0) * 1000:.2f} x {(z1 - z0) * 1000:.2f} mm, print was {print_share:.3f} of the texture')
    finish(export(obj, 'nes_cartridge'))


def snes_cartridge(work):
    """The rounded Super Famicom and PAL cartridge. Its front bulges, and the label follows it: the label is a
    copy of the shell's own faces under the print, so it keeps the curve. The dense CAD mesh is reduced."""
    folder = SOURCES / 'snes-paljp'
    obj = import_mesh(folder / 'source/carte_step.fbx')
    # The download stands on its label edge with the print toward +Y. Turn the print to the viewer.
    bake(obj, Matrix.Rotation(np.pi, 4, 'Z'))
    centre_and_size(obj, 0, .128)
    mesh = obj.data
    textures = folder / 'textures'
    base = read_image(textures / 'carte_step_my_super_nicE-carte_BaseColor.png')
    front = [p for p in mesh.polygons if p.normal.y < -.98]
    black = [p for p in front if sample(base, mesh, p).max() < .08]
    points = np.array([mesh.vertices[i].co for p in black for i in p.vertices])
    x0, x1, z0, z1 = points[:, 0].min(), points[:, 0].max(), points[:, 2].min(), points[:, 2].max()
    assert .095 < x1 - x0 < .102 and .033 < z1 - z0 < .038 and z1 > .9 * bounds(mesh)[1][2], (x1 - x0, z1 - z0, z1)
    def within(polygon):
        return all(x0 - 1e-6 <= mesh.vertices[i].co.x <= x1 + 1e-6 and z0 - 1e-6 <= mesh.vertices[i].co.z <= z1 + 1e-6 for i in polygon.vertices)
    polygons = [p for p in front if within(p)]
    assert abs(sum(p.area for p in polygons) / ((x1 - x0) * (z1 - z0)) - 1) < .01, 'the faces under the print should tile its rectangle'
    bulge = np.ptp([mesh.vertices[i].co.y for p in polygons for i in p.vertices])
    spread_label_uvs(mesh, polygons, x0, x1, z0, z1)
    # The print (a black card with the uploader's site name) is found from its pixels: the unwrap is not a projection.
    window = base[:base.shape[0] * 3 // 10, :base.shape[1] * 6 // 10, :3].max(axis=2) < .08
    rows, columns = np.nonzero(window.sum(axis=1) > 40)[0], np.nonzero(window.sum(axis=0) > 40)[0]
    erase_box(base, columns.min() / base.shape[1], (columns.max() + 1) / base.shape[1], rows.min() / base.shape[0], (rows.max() + 1) / base.shape[0])
    assert (base[:base.shape[0] * 3 // 10, :base.shape[1] * 6 // 10, :3].max(axis=2) < .08).mean() < .0005

    label = separate(obj, polygons)
    count = len(label.data.polygons)
    reduce(obj, 14_000 - count, crease=30, keep_open_edges=True)
    # The label was cut out of the shell. Reducing the shell must leave the cut where it was, or light shows through.
    assert places(label.data, open_edge(label.data)) <= places(obj.data, open_edge(obj.data)), 'the shell moved away from the label it was cut around'
    shade(label, crease=30)
    source_normal = read_image(textures / 'carte_step_my_super_nicE-carte_Normal.png')
    orm = pack_orm(None, shrink(read_image(textures / 'carte_step_my_super_nicE-carte_Roughness.png'), TEXTURE, linear=True),
                   shrink(read_image(textures / 'carte_step_my_super_nicE-carte_Metallic.png'), TEXTURE, linear=True))
    normal = opengl_normals(shrink(source_normal, TEXTURE, linear=True), flip_green=green_points_down(source_normal))
    obj.data.materials.clear()
    obj.data.materials.append(shell_material('Super Famicom cartridge shell', write_image(shrink(base, TEXTURE), work / 'snes_base.jpg', True),
                                             write_image(orm, work / 'snes_orm.png', False), write_image(normal, work / 'snes_normal.png', False)))
    label.data.materials.clear()
    label.data.materials.append(label_material(placeholder(512, 184, work / 'snes_label.png')))
    join(obj, label)
    print(f'LABEL snes: {count} curved faces, {(x1 - x0) * 1000:.2f} x {(z1 - z0) * 1000:.2f} mm, bulge {bulge * 1000:.2f} mm')
    finish(export(obj, 'snes_cartridge'))


def snes_us_cartridge(work):
    """The square North American cartridge, from a scan. A scan has no faces of its own for the label: a sheet is
    laid over the print, the scanned surface under it is pressed just below the sheet, and every texel that
    showed the print is filled. The scan is reduced to the triangle budget."""
    folder = SOURCES / 'snes-us'
    obj = import_mesh(extract(folder / 'source/Super Mario World Game Cartridge.zip', '.obj', work / 'snes_us.obj'))
    mesh = obj.data
    base = read_image(folder / 'textures/Super Mario World Game Cartridge_0.jpeg')

    def colourful(polygon):
        colour = sample(base, mesh, polygon)
        return colour.max() - colour.min() > .18
    # The scan lies on its back or its front. The printed label is the colourful side; its end is the top.
    low, high = bounds(mesh)
    assert high[2] - low[2] < .3 * (high[1] - low[1]) < .3 * (high[0] - low[0]), 'the scan should lie flat, wider than tall'
    up = sum(p.area for p in mesh.polygons if p.normal.z > .9 and colourful(p))
    down = sum(p.area for p in mesh.polygons if p.normal.z < -.9 and colourful(p))
    sign = 1 if up > down else -1
    middle = (low[1] + high[1]) / 2
    near = sum(p.area for p in mesh.polygons if p.normal.z * sign > .9 and colourful(p) and p.center.y < middle)
    far = sum(p.area for p in mesh.polygons if p.normal.z * sign > .9 and colourful(p) and p.center.y >= middle)
    # Printed side to -Y; then, about that axis, the label's end up.
    turn = Matrix.Rotation(sign * np.pi / 2, 4, 'X')
    top, bottom = (low[1], high[1]) if near > far else (high[1], low[1])
    upright = (turn @ Vector((0, top, 0))).z > (turn @ Vector((0, bottom, 0))).z
    bake(obj, (Matrix.Identity(4) if upright else Matrix.Rotation(np.pi, 4, 'Y')) @ turn)
    settle(obj, 8)
    centre_and_size(obj, 0, .136)
    print(f'SCAN snes_us: print faced {"+" if sign > 0 else "-"}Z, {max(near, far) / (near + far):.3f} of it at one end')

    # Where the print is: the faces of the front's upper half that are not plastic, colourful or black, by area and
    # without the stray half percent at either end.
    facing = [p for p in mesh.polygons if p.normal.y < -.9]
    shell = np.median(np.array([sample(base, mesh, p) for p in facing]), axis=0)
    def printed(polygon):
        colour = sample(base, mesh, polygon)
        return colour.max() - colour.min() > .18 or colour.max() < .45 * shell.max()
    front = [p for p in facing if p.center.z > 0 and printed(p)]
    weights = np.array([p.area for p in front])
    centres = np.array([p.center for p in front])
    def span(values):
        order = np.argsort(values)
        cumulative = np.cumsum(weights[order]) / weights.sum()
        return float(values[order][np.searchsorted(cumulative, .005)]), float(values[order][np.searchsorted(cumulative, .995)])
    (x0, x1), (z0, z1) = span(centres[:, 0]), span(centres[:, 2])
    low, high = bounds(mesh)
    x0, x1, z0, z1 = x0 - .0005, x1 + .0005, z0 - .0005, min(z1 + .0005, high[2])
    share = sum(p.area for p in front if x0 <= p.center.x <= x1 and z0 <= p.center.z <= z1) / ((x1 - x0) * (z1 - z0))
    print(f'SCAN snes_us: print {x0 * 1000:.2f}..{x1 * 1000:.2f} x {z0 * 1000:.2f}..{z1 * 1000:.2f} mm of {high[0] * 1000:.2f} x {high[2] * 1000:.2f}, {share:.3f} of that rectangle printed')
    assert .078 < x1 - x0 < .095 and .036 < z1 - z0 < .050 and abs(x0 + x1) < .006 and share > .8, (x0, x1, z0, z1, share)

    def near(polygon, margin):
        c = polygon.center
        return x0 - margin <= c.x <= x1 + margin and z0 - margin <= c.z <= z1 + margin
    # Under the sheet; whatever of the same print the scan shows beside it; and the edge above it, where the worn end
    # of the label folds over the top.
    covered = [p for p in mesh.polygons if p.center.y < low[1] + .008 and (
        (p.normal.y < -.3 and near(p, .0015)) or (x0 - .003 <= p.center.x <= x1 + .003 and p.center.z >= z0 - .003 and printed(p)) or
        (x0 - .0015 <= p.center.x <= x1 + .0015 and p.center.z >= z1 - .0005))]
    ring = [sample(base, mesh, p) for p in facing if near(p, .008) and not near(p, .003) and not printed(p)]
    plastic = np.median(np.array(ring), axis=0)
    assert plastic.max() - plastic.min() < .08 and len(ring) > 50, (plastic, len(ring))
    base[uv_mask(mesh, covered, base.shape, 6), :3] = plastic
    # A scan's atlas is padded with stretched copies of its edges, print included: texels no face shows go too.
    base[~uv_mask(mesh, mesh.polygons, base.shape, 8), :3] = plastic
    left = [p for p in facing if p.center.z > 0 and colourful(p)]
    assert sum(p.area for p in left) < .01 * weights.sum(), 'print is still showing on the front'

    reduce(obj, 13_900, crease=40)
    mesh = obj.data
    # The sheet goes where the scanned label was: at the level most of that surface has.
    inside = [v for v in mesh.vertices if x0 < v.co.x < x1 and z0 < v.co.z < z1 and v.co.y < low[1] + .004]
    level = float(np.percentile([v.co.y for v in inside], 10))
    for vertex in inside:
        vertex.co.y = max(vertex.co.y, level + .0001)
    outline = rounded_outline(x0, x1, z0, z1, .002)
    sheet = add_faces('label', [(x, level, z) for x, z in outline], [list(range(len(outline)))],
                      [((x - x0) / (x1 - x0), (z - z0) / (z1 - z0)) for x, z in outline], label_material(placeholder(512, 228, work / 'snes_us_label.png')))
    mesh.materials.clear()
    mesh.materials.append(shell_material('Super Nintendo cartridge shell', write_image(shrink(base, TEXTURE), work / 'snes_us_base.jpg', True),
                                         flat_orm(work, 'snes_us', roughness=.62), flat_normal(work, 'snes_us')))
    join(obj, sheet)
    print(f'LABEL snes_us: sheet {(x1 - x0) * 1000:.2f} x {(z1 - z0) * 1000:.2f} mm, {len(covered)} scanned faces repainted, {len(inside)} vertices pressed below it')
    finish(export(obj, 'snes_us_cartridge'))


def genesis_cartridge(work):
    """The Genesis cartridge, from a low-polygon game asset. Its front panel is one flat face that shows artwork
    above a moulded name plate: a sheet is laid over the artwork. Its 256 px atlas is shared with three other
    cartridges, each with a game's artwork: all of that is filled."""
    folder = SOURCES / 'segagenesis'
    obj = import_mesh(extract(folder / 'source/Mobile - Sonic Dash - Sega Genesis Cartridges.zip', 'cartridge_3.obj', work / 'genesis.obj'))
    centre_and_size(obj, 0, .107)
    mesh = obj.data
    base = read_image(folder / 'textures/Megadrive_1.png')
    assert base.shape[0] == base.shape[1] == 256, base.shape
    front = front_faces(mesh)
    depth = min(p.center.y for p in front)
    panel = [p for p in front if p.center.y < depth + 1e-5]
    layer = mesh.uv_layers[0].data
    panel_uv = np.array([layer[i].uv for p in panel for i in p.loop_indices])
    pu0, pu1, pv0, pv1 = panel_uv[:, 0].min(), panel_uv[:, 0].max(), panel_uv[:, 1].min(), panel_uv[:, 1].max()
    # The artwork is the colourful block of the panel; the name plate under it and the frame are grey.
    window = box(base, pu0, pu1, pv0, pv1)
    colour = window[..., :3].max(axis=2) - window[..., :3].min(axis=2) > .18
    rows, columns = np.nonzero(colour.mean(axis=1) > .5)[0], np.nonzero(colour.mean(axis=0) > .3)[0]
    assert len(rows) > 40 and len(columns) > 100 and np.ptp(rows) == len(rows) - 1, (len(rows), len(columns))
    scale_u, scale_v = (pu1 - pu0) / window.shape[1], (pv1 - pv0) / window.shape[0]
    u0, u1, v0, v1 = pu0 + columns.min() * scale_u, pu0 + (columns.max() + 1) * scale_u, pv0 + rows.min() * scale_v, pv0 + (rows.max() + 1) * scale_v
    corners = np.array([uv_to_point(mesh, panel, u, v) for u, v in ((u0, v0), (u1, v0), (u1, v1), (u0, v1))])
    assert np.ptp(corners[:, 1]) < 1e-5, corners
    x0, x1, z0, z1 = corners[:, 0].min(), corners[:, 0].max(), corners[:, 2].min(), corners[:, 2].max()
    assert .058 < x1 - x0 < .070 and .030 < z1 - z0 < .045 and z1 > .95 * bounds(mesh)[1][2], (x1 - x0, z1 - z0, z1)
    sheet = add_faces('label', [(x0, depth - .00005, z0), (x1, depth - .00005, z0), (x1, depth - .00005, z1), (x0, depth - .00005, z1)], [[0, 1, 2, 3]],
                      [(0, 0), (1, 0), (1, 1), (0, 1)], label_material(placeholder(512, 304, work / 'genesis_label.png')))

    # Everything this cartridge does not show, then every colourful texel it does: the artwork and the strip of it
    # that folds over the top edge. What is left is the grey of the mould.
    plastic = np.median(np.array([sample(base, mesh, p) for p in mesh.polygons if np.ptp(sample(base, mesh, p)) < .1]), axis=0)
    assert plastic.max() - plastic.min() < .08, plastic
    base[~uv_mask(mesh, mesh.polygons, base.shape, 1), :3] = plastic
    art = base[..., :3].max(axis=2) - base[..., :3].min(axis=2) > .1
    assert .1 < art.mean() < .5, art.mean()
    for _ in range(2):
        wider = art.copy()
        wider[1:] |= art[:-1]; wider[:-1] |= art[1:]; wider[:, 1:] |= art[:, :-1]; wider[:, :-1] |= art[:, 1:]
        art = wider
    base[art, :3] = plastic
    assert float((base[..., :3].max(axis=2) - base[..., :3].min(axis=2) > .1).mean()) == 0
    # The artwork's figures stand on the name plate: their white feet are left in its top rows. The plate is dark
    # there, above its lettering, so anything lighter than the plate in those rows is artwork too.
    feet = box(base, u0, u1, v0 - 10 / 256, v0)
    plate = np.median(feet[..., :3].reshape(-1, 3), axis=0)
    light = feet[..., :3].max(axis=2) > plate.max() + .1
    assert plate.max() < .3 and light.mean() < .2, (plate, light.mean())
    feet[light, :3] = plate
    mesh.materials.clear()
    mesh.materials.append(shell_material('Genesis cartridge shell', write_image(base, work / 'genesis_base.png', True),
                                         flat_orm(work, 'genesis', roughness=.6), flat_normal(work, 'genesis')))
    join(obj, sheet)
    print(f'LABEL genesis: sheet {(x1 - x0) * 1000:.2f} x {(z1 - z0) * 1000:.2f} mm over panel uv {u0:.4f}..{u1:.4f}, {v0:.4f}..{v1:.4f}')
    finish(export(obj, 'genesis_cartridge'))


if __name__ == '__main__':
    with tempfile.TemporaryDirectory() as temporary:
        for build in (gba_cartridge, gb_cartridge, disc, nes_cartridge, snes_cartridge, snes_us_cartridge, genesis_cartridge):
            build(Path(temporary))
    sys.stdout.flush()
