"""Original refined geometry. Pure Python, deterministic glTF 2.0 binary + fictional label.

Coordinates: metres, +Y up, label faces +Z. Rounded bevels, recessed label and original micrograin; no proprietary geometry.
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
    text('ORBIT', 32, 221, 2, (202, 181, 139))

    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
    rows = b''.join(b'\0' + pixels[y * width * 4:(y + 1) * width * 4] for y in range(height))
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(rows, 9)) + chunk(b'IEND', b''))


def generate():
    from glb_geometry import Geometry, rounded, png
    materials = [
        dict(name='Molded charcoal plastic', pbrMetallicRoughness=dict(baseColorFactor=[.10, .115, .11, 1],
             metallicFactor=0, roughnessFactor=1, metallicRoughnessTexture=dict(index=1)),
             normalTexture=dict(index=2, scale=.22)),
        dict(name='Seam and label recess', pbrMetallicRoughness=dict(baseColorFactor=[.028, .032, .03, 1], metallicFactor=0, roughnessFactor=.84)),
        dict(name='Replaceable fictional label', pbrMetallicRoughness=dict(baseColorTexture=dict(index=0), metallicFactor=0, roughnessFactor=.62)),
        dict(name='Contact plating', pbrMetallicRoughness=dict(baseColorFactor=[.72, .54, .21, 1], metallicFactor=.92, roughnessFactor=.28)),
    ]
    geometry = Geometry(materials)
    # An actual recessed opening, not a raised label rectangle pasted over a front face.
    geometry.body(.059, .034, .008, .0021, .00055, 0,
                  hole=(.047, .024, .0015, 0, .0005, .00032), segments=8, bevel_steps=4)
    geometry.body(.060, .0023, .0088, .00085, .00018, 0, (0, .0164, 0))
    # Parting seam only around the outside shell; it never crosses the label.
    profile, _ = rounded(.05906, .03406, .00213)
    for i in range(len(profile)):
        j = (i+1) % len(profile)
        a, b = profile[i], profile[j]
        geometry.face([(*a, -.00010), (*b, -.00010), (*b, .00010), (*a, .00010)], 1)
    # Narrow moulded grip ribs at the top, smooth enough to catch the studio highlights.
    for i in range(7):
        geometry.body(.032, .00032, .00022, .00014, .00004, 0, (0, .0135+i*.00040, .00405))
    label, _ = rounded(.0452, .0226, .001, (0, .0005))
    points = [(x, y, .00371) for x, y in label]
    geometry.face(points, 2, uv=[(max(0, min(1, (x+.0226)/.0452)),
                                max(0, min(1, 1-(y+.0108)/.0226))) for x, y in label])
    # Dark connector recess and individually visible plated fingers. No console logos.
    geometry.body(.0388, .0020, .0034, .00065, .00018, 1, (0, -.0164, -.0005))
    for i in range(16):
        geometry.body(.00145, .0017, .00020, .00012, .000035, 3, ((i-7.5)*.0023, -.0164, .00132))
    orm, normal = bytearray(), bytearray()
    for y in range(128):
        for x in range(128):
            grain = ((x*73856093 ^ y*19349663) & 255)
            orm.extend((255, 185+grain//18, 0, 255))
            normal.extend((126+grain%5, 126+(grain//5)%5, 255, 255))
    geometry.write(OUTPUT, 'Original GBA refined template',
                   [label_png(), png(128, 128, orm), png(128, 128, normal)],
                   [dict(source=0, sampler=0), dict(source=1, sampler=1), dict(source=2, sampler=1)],
                   [dict(magFilter=9729, minFilter=9987, wrapS=33071, wrapT=33071),
                    dict(magFilter=9729, minFilter=9987, wrapS=10497, wrapT=10497)])
    receiver = Geometry([
        dict(name='Receiver graphite', pbrMetallicRoughness=dict(baseColorFactor=[.045,.05,.047,1], metallicFactor=.25, roughnessFactor=.68)),
        dict(name='Receiver interior', pbrMetallicRoughness=dict(baseColorFactor=[.008,.009,.008,1], metallicFactor=0, roughnessFactor=.95)),
    ])
    # Slot width/clearance fits the template. Front lip physically occludes the inserted shell.
    receiver.body(.071, .019, .008, .002, .0006, 0, (0,-.0095,.014))
    receiver.body(.071, .019, .008, .002, .0006, 0, (0,-.0095,-.006))
    for x in (-.03325, .03325):
        receiver.body(.0045, .019, .018, .0013, .0004, 0, (x,-.0095,.004))
    receiver.body(.063, .003, .012, .001, .0003, 1, (0,-.019,.004))
    receiver.write(OUTPUT.with_name('gba_receiver.glb'), 'Original minimal receiving slot')


if __name__ == '__main__':
    generate()
