"""Build the three runtime media GLBs from the owner's downloaded source models.

Run from the repository root with Blender 4.2 or newer (validated with 5.2.2 LTS; nothing else is needed):

    blender -b --factory-startup --disable-autoexec --python tools/import_models.py

Sources stay untouched under assets/models. The folder names there do not match their contents:
`gb` holds the wide Game Boy Advance cartridge and `gba` holds the tall Game Boy cartridge.

What the app needs and the downloads do not have, this adds:
  * metres, origin at the centre, +Y up, printed face toward +Z, each object at its template size;
  * one primitive per object with the material `Replaceable fictional label` and label UVs spanning
    exactly 0..1 with (0, 0) at the top left, which CartridgeLabels rebinds per game;
  * shell textures at 1024 px with glTF's occlusion/roughness/metallic packing and +Y normals;
  * an opaque disc: its clear hub is composited over the library's dark stage instead of blended.

What it removes: the commercial game label baked into each cartridge's base colour. That region is
filled with the surrounding plastic before anything is written, so no game artwork reaches the APK or Git.
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


def assign_label(mesh, polygons):
    """Slot 1 is the label. Call once both materials exist: replacing the slots resets every face to slot 0."""
    wanted = {polygon.index for polygon in polygons}
    for polygon in mesh.polygons:
        polygon.material_index = 1 if polygon.index in wanted else 0
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
    # Front faces layered by depth (0.02 mm apart or more); the label floor is the one 43 x 22.5 mm sheet among them.
    layers = []
    for polygon in sorted(front_faces(mesh), key=lambda p: p.center.y):
        if layers and polygon.center.y - layers[-1][-1].center.y < .00002:
            layers[-1].append(polygon)
        else:
            layers.append([polygon])
    floors = []
    for polygons in layers:
        points = np.array([mesh.vertices[i].co for p in polygons for i in p.vertices])
        width, height = np.ptp(points[:, 0]), np.ptp(points[:, 2])
        if .040 < width < .047 and .020 < height < .025 and sum(p.area for p in polygons) > .97 * width * height:
            floors.append((polygons, points))
    assert len(floors) == 1, f'expected one label floor, found {len(floors)}'
    polygons, points = floors[0]
    x0, x1, z0, z1 = points[:, 0].min(), points[:, 0].max(), points[:, 2].min(), points[:, 2].max()
    layer = mesh.uv_layers[0].data
    printed = np.array([layer[i].uv for p in polygons for i in p.loop_indices])
    for polygon in polygons:
        for loop in polygon.loop_indices:
            co = mesh.vertices[mesh.loops[loop].vertex_index].co
            layer[loop].uv = ((co.x - x0) / (x1 - x0), (co.z - z0) / (z1 - z0))

    textures = folder / 'textures'
    base = read_image(textures / 'Cartridge_MAT_SG_BaseColor.1001.png')
    erase_print(base, printed[:, 0].min(), printed[:, 0].max(), printed[:, 1].min(), printed[:, 1].max())
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


if __name__ == '__main__':
    with tempfile.TemporaryDirectory() as temporary:
        for build in (gba_cartridge, gb_cartridge, disc):
            build(Path(temporary))
    sys.stdout.flush()
