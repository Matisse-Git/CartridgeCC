"""Original M0 geometry. Pure Python, deterministic glTF 2.0 binary + fictional label.

Coordinates: metres, +Y up, label faces +Z. This is an engineering asset, not M1 polish.
Run: python tools/generate_cartridge.py
"""
import json
import math
from pathlib import Path
import struct
import zlib

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "app/src/main/assets/models/gba_cartridge.glb"


def label_png():
    width, height = 512, 256
    pixels = bytearray([202, 181, 139, 255] * width * height)

    def pixel(x, y, color):
        if 0 <= x < width and 0 <= y < height:
            offset = (y * width + x) * 4
            pixels[offset:offset + 4] = bytes((*color, 255))

    for y in range(height):
        for x in range(width):
            radius = math.hypot((x - 395) * 0.85, y - 113)
            if abs(radius - 62) < 2 or abs(radius - 86) < 1:
                pixel(x, y, (83, 88, 78))
            if (x - 395) ** 2 + (y - 113) ** 2 < 22 ** 2:
                pixel(x, y, (61, 71, 67))
            if y > 207:
                pixel(x, y, (48, 56, 54))
    glyphs = {
        'O': [14, 17, 17, 17, 17, 17, 14], 'R': [30, 17, 17, 30, 20, 18, 17],
        'B': [30, 17, 17, 30, 17, 17, 30], 'I': [31, 4, 4, 4, 4, 4, 31],
        'T': [31, 4, 4, 4, 4, 4, 4], 'F': [31, 16, 16, 30, 16, 16, 16],
        'E': [31, 16, 16, 30, 16, 16, 31], 'L': [16, 16, 16, 16, 16, 16, 31],
        'D': [30, 17, 17, 17, 17, 17, 30], 'M': [17, 27, 21, 21, 17, 17, 17],
        '0': [14, 17, 19, 21, 25, 17, 14], ' ': [0] * 7,
    }

    def text(word, x, y, scale, color):
        for char in word:
            for row, bits in enumerate(glyphs[char]):
                for column in range(5):
                    if bits & (1 << (4 - column)):
                        for dy in range(scale):
                            for dx in range(scale):
                                pixel(x + column * scale + dx, y + row * scale + dy, color)
            x += 6 * scale
    text('ORBIT', 32, 61, 5, (48, 56, 54))
    text('FIELD', 32, 111, 5, (48, 56, 54))
    text('M0', 32, 221, 2, (202, 181, 139))

    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
    rows = b''.join(b'\0' + pixels[y * width * 4:(y + 1) * width * 4] for y in range(height))
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(rows, 9)) + chunk(b'IEND', b''))


def generate():
    binary = bytearray()
    views, accessors, primitives = [], [], []

    def append(data, target=None):
        binary.extend(b'\0' * (-len(binary) % 4))
        view = dict(buffer=0, byteOffset=len(binary), byteLength=len(data))
        if target:
            view['target'] = target
        views.append(view)
        binary.extend(data)
        return len(views) - 1

    def accessor(values, count, kind, components, component=5126, bounds=False):
        view = append(struct.pack('<' + ('f' if component == 5126 else 'H') * len(values), *values),
                      34963 if component == 5123 else 34962)
        record = dict(bufferView=view, componentType=component, count=count, type=kind)
        if bounds:
            record['min'] = [min(values[c::components]) for c in range(components)]
            record['max'] = [max(values[c::components]) for c in range(components)]
        accessors.append(record)
        return len(accessors) - 1

    def mesh(faces, material, textured=False):
        positions, normals, uv, indices = [], [], [], []
        for points in faces:
            a, b, c = points[:3]
            u, v = [b[i] - a[i] for i in range(3)], [c[i] - a[i] for i in range(3)]
            n = [u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0]]
            length = math.sqrt(sum(x * x for x in n))
            n = [x / length for x in n]
            base = len(positions) // 3
            for i, point in enumerate(points):
                positions.extend(point)
                normals.extend(n)
                uv.extend([(0, 1), (1, 1), (1, 0), (0, 0)][i % 4])
            for i in range(1, len(points) - 1):
                indices.extend([base, base + i, base + i + 1])
        attrs = dict(POSITION=accessor(positions, len(positions) // 3, 'VEC3', 3, bounds=True),
                     NORMAL=accessor(normals, len(normals) // 3, 'VEC3', 3))
        if textured:
            attrs['TEXCOORD_0'] = accessor(uv, len(uv) // 2, 'VEC2', 2)
        primitives.append(dict(attributes=attrs, indices=accessor(indices, len(indices), 'SCALAR', 1, 5123),
                               material=material))

    def body(width, height, depth, bevel, material, center=(0, 0, 0)):
        # Convex, chamfered profile with a real thickness and sloped edge ring.
        w, h = width / 2, height / 2
        outline = [(-w + bevel, -h), (w - bevel, -h), (w, -h + bevel), (w, h - bevel),
                   (w - bevel, h), (-w + bevel, h), (-w, h - bevel), (-w, -h + bevel)]
        rings = []
        for z, inset in [(-depth / 2, bevel / 3), (-depth / 2 + bevel / 3, 0),
                         (depth / 2 - bevel / 3, 0), (depth / 2, bevel / 3)]:
            rings.append([(x * (1 - inset / w) + center[0], y * (1 - inset / h) + center[1],
                           z + center[2]) for x, y in outline])
        faces = [list(reversed(rings[0])), rings[-1]]
        for lower, upper in zip(rings, rings[1:]):
            for i in range(8):
                j = (i + 1) % 8
                faces.append([lower[i], lower[j], upper[j], upper[i]])
        mesh(faces, material)

    body(.059, .034, .008, .0022, 0)
    body(.060, .0035, .0086, .0007, 0, (0, .0157, 0))  # top grip lip
    body(.0592, .032, .00028, .0018, 1)  # contrasting shell seam
    body(.047, .0235, .0007, .0012, 1, (0, .0007, .0041))  # label recess rim
    mesh([[(-.0226, -.0101, .00449), (.0226, -.0101, .00449),
           (.0226, .0115, .00449), (-.0226, .0115, .00449)]], 2, True)
    # Visible connector opening and individual plated contact fingers at the bottom edge.
    body(.039, .0016, .0042, .0003, 1, (0, -.0167, -.001))
    for index in range(16):
        body(.0015, .0022, .00025, .00012, 3, ((index - 7.5) * .0023, -.016, .0013))
    image_view = append(label_png())
    document = dict(
        asset=dict(version='2.0', generator='Project Cartridge original deterministic M0 generator'),
        scene=0, scenes=[dict(nodes=[0])], nodes=[dict(mesh=0, name='GBA engineering template')],
        meshes=[dict(primitives=primitives)], buffers=[dict(byteLength=len(binary))],
        bufferViews=views, accessors=accessors,
        materials=[
            dict(name='Molded charcoal plastic', pbrMetallicRoughness=dict(baseColorFactor=[.19, .20, .19, 1], metallicFactor=0, roughnessFactor=.78)),
            dict(name='Seam and label recess', pbrMetallicRoughness=dict(baseColorFactor=[.07, .08, .075, 1], metallicFactor=0, roughnessFactor=.86)),
            dict(name='Replaceable fictional label', pbrMetallicRoughness=dict(baseColorTexture=dict(index=0), metallicFactor=0, roughnessFactor=.65)),
            dict(name='Contact plating', pbrMetallicRoughness=dict(baseColorFactor=[.72, .54, .21, 1], metallicFactor=.82, roughnessFactor=.3)),
        ], images=[dict(bufferView=image_view, mimeType='image/png')],
        textures=[dict(source=0, sampler=0)], samplers=[dict(magFilter=9729, minFilter=9987, wrapS=33071, wrapT=33071)])
    encoded = json.dumps(document, separators=(',', ':')).encode()
    encoded += b' ' * (-len(encoded) % 4)
    binary += b'\0' * (-len(binary) % 4)
    glb = (struct.pack('<III', 0x46546C67, 2, 12 + 8 + len(encoded) + 8 + len(binary))
           + struct.pack('<II', len(encoded), 0x4E4F534A) + encoded
           + struct.pack('<II', len(binary), 0x004E4942) + binary)
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_bytes(glb)
    print(f'{OUTPUT}: {len(glb)} bytes, {len(primitives)} primitives')


if __name__ == '__main__':
    generate()
