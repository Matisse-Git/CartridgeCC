"""Structural checks for the packaged case GLBs (ADR 0029); no Android or Blender required."""
import json
import math
from pathlib import Path
import struct
import unittest

MODELS = Path(__file__).resolve().parents[1] / 'app/src/main/assets/models'
LABEL = 'Replaceable fictional label'


class Glb:
    def __init__(self, name):
        self.raw = (MODELS / name).read_bytes()
        magic, version, length = struct.unpack_from('<III', self.raw)
        assert magic == 0x46546C67 and version == 2 and length == len(self.raw)
        size, kind = struct.unpack_from('<II', self.raw, 12)
        assert kind == 0x4E4F534A
        self.doc = json.loads(self.raw[20:20 + size])
        binary_size, kind = struct.unpack_from('<II', self.raw, 20 + size)
        assert kind == 0x004E4942
        self.binary = self.raw[28 + size:28 + size + binary_size]
        self.primitives = [p for mesh in self.doc['meshes'] for p in mesh['primitives']]

    def values(self, index):
        a = self.doc['accessors'][index]
        view = self.doc['bufferViews'][a['bufferView']]
        assert 'byteStride' not in view
        components = {'SCALAR': 1, 'VEC2': 2, 'VEC3': 3, 'VEC4': 4}[a['type']]
        fmt = {5126: 'f', 5125: 'I', 5123: 'H', 5121: 'B'}[a['componentType']]
        flat = struct.unpack_from('<' + fmt * (a['count'] * components), self.binary, view.get('byteOffset', 0) + a.get('byteOffset', 0))
        return flat if components == 1 else list(zip(*[flat[i::components] for i in range(components)]))

    def attribute(self, primitive, name):
        return self.values(primitive['attributes'][name])

    def material(self, primitive):
        return self.doc['materials'][primitive['material']]

    def label(self):
        labels = [p for p in self.primitives if self.material(p)['name'] == LABEL]
        assert len(labels) == 1, 'exactly one replaceable label primitive per template'
        return labels[0]

    def shell(self):
        return next(p for p in self.primitives if self.material(p)['name'] != LABEL)

    def bounds(self, primitives=None):
        points = [p for primitive in (primitives or self.primitives) for p in self.attribute(primitive, 'POSITION')]
        return [min(p[i] for p in points) for i in range(3)], [max(p[i] for p in points) for i in range(3)]

    def size(self, primitives=None):
        low, high = self.bounds(primitives)
        return [high[i] - low[i] for i in range(3)]

    def image_size(self, index):
        image = self.doc['images'][index]
        view = self.doc['bufferViews'][image['bufferView']]
        data = self.binary[view['byteOffset']:view['byteOffset'] + view['byteLength']]
        if image['mimeType'] == 'image/png':
            assert data[:8] == b'\x89PNG\r\n\x1a\n'
            return struct.unpack_from('>II', data, 16)
        assert image['mimeType'] == 'image/jpeg' and data[:2] == b'\xff\xd8'
        offset = 2
        while offset < len(data):  # Walk the segments to the frame header.
            marker, length = struct.unpack_from('>HH', data, offset)
            if 0xFFC0 <= marker <= 0xFFCF and marker not in (0xFFC4, 0xFFC8, 0xFFCC):
                height, width = struct.unpack_from('>HH', data, offset + 5)
                return width, height
            offset += 2 + length
        raise AssertionError('no JPEG frame header')

    def texture_image(self, reference):
        return self.doc['textures'][reference['index']]['source']


# ADR 0029: name -> (width, height, depth) in metres, and the cover's back, spine, front widths and height in mm,
# as `Media.cover` and tools/generate_cases.py state them.
CASES = {
    'jewel_case.glb': ((.142, .125, .0104), (138, 10.4, 120, 120)),
    'gba_box.glb': ((.125, .125, .022), (125, 22, 125, 125)),
    'gba_box_jp.glb': ((.088, .136, .022), (88, 22, 88, 136)),
    'gb_box_jp.glb': ((.090, .120, .022), (90, 22, 90, 120)),
    'game_gear_box.glb': ((.130, .165, .030), (130, 30, 130, 165)),
    'nes_box.glb': ((.127, .184, .032), (127, 32, 127, 184)),
    'famicom_box.glb': ((.133, .095, .025), (133, 25, 133, 95)),
    'snes_box.glb': ((.184, .127, .038), (184, 38, 184, 127)),
    'super_famicom_box.glb': ((.190, .107, .031), (190, 31, 190, 107)),
    'clamshell_case.glb': ((.135, .190, .025), (132, 25, 132, 187)),
    'saturn_case.glb': ((.145, .215, .022), (142, 22, 142, 212)),
    'dvd_case.glb': ((.135, .190, .014), (130, 14, 130, 183)),
    'gamecube_case.glb': ((.135, .186, .014), (130, 14, 130, 180)),
}


class CaseTemplateTest(unittest.TestCase):
    """A case's one art primitive unfolds back, spine and front the way ScreenScraper's box-texture does."""

    def test_size_frame_and_one_cover_primitive(self):
        for name, (size, _) in CASES.items():
            with self.subTest(name):
                glb = Glb(name)
                low, high = glb.bounds()
                for axis in range(3):
                    self.assertAlmostEqual(size[axis], high[axis] - low[axis], delta=.0003)
                    self.assertAlmostEqual(0, (low[axis] + high[axis]) / 2, delta=.0001)
                self.assertTrue(all(not {'rotation', 'translation', 'scale', 'matrix'} & set(node) for node in glb.doc['nodes']))
                glb.label()
                self.assertTrue(all(m.get('alphaMode', 'OPAQUE') == 'OPAQUE' for m in glb.doc['materials']))
                self.assertFalse(any('uri' in image for image in glb.doc['images']))
                self.assertLess(sum(glb.doc['accessors'][p['indices']]['count'] // 3 for p in glb.primitives), 1_000)

    def test_cover_unfolds_back_spine_front_from_the_top_left(self):
        for name, (_, (back, spine, front, tall)) in CASES.items():
            with self.subTest(name):
                glb = Glb(name)
                label = glb.label()
                back_end, front_start = back / (back + spine + front), (back + spine) / (back + spine + front)
                faces = {'front': [], 'back': [], 'spine': []}
                for (x, y, z), (nx, ny, nz), (u, v) in zip(glb.attribute(label, 'POSITION'), glb.attribute(label, 'NORMAL'), glb.attribute(label, 'TEXCOORD_0')):
                    self.assertTrue(-1e-4 <= u <= 1 + 1e-4 and -1e-4 <= v <= 1 + 1e-4)
                    if nz > .98: faces['front'].append((x, y, u, v))
                    elif nz < -.98: faces['back'].append((x, y, u, v))
                    elif nx < -.98: faces['spine'].append((z, y, u, v))
                self.assertTrue(all(faces.values()), 'the cover reaches front, back and spine')
                for side, (start, end), sign in (('front', (front_start, 1), 1), ('back', (0, back_end), -1), ('spine', (back_end, front_start), 1)):
                    points = faces[side]
                    us = [u for *_, u, _ in points]
                    self.assertAlmostEqual(start, min(us), delta=1e-3); self.assertAlmostEqual(end, max(us), delta=1e-3)
                    # Left to right as seen from outside: u grows with x on the front, against it on the back, with z on the spine.
                    a = min(points, key=lambda p: p[2]); b = max(points, key=lambda p: p[2])
                    self.assertGreater(sign * (b[0] - a[0]), 0, side)
                    # Up the case is up the picture: v shrinks as y grows.
                    top = max(points, key=lambda p: p[1]); bottom = min(points, key=lambda p: p[1])
                    self.assertLess(top[3], bottom[3], side)

    def test_placeholder_has_the_unfolded_cover_aspect(self):
        for name, (_, (back, spine, front, tall)) in CASES.items():
            with self.subTest(name):
                glb = Glb(name)
                width, height = glb.image_size(glb.texture_image(glb.material(glb.label())['pbrMetallicRoughness']['baseColorTexture']))
                self.assertAlmostEqual((back + spine + front) / tall, width / height, delta=.02)


class StudioTest(unittest.TestCase):
    def test_studio_has_prefiltered_cubemap_and_spherical_harmonics(self):
        raw = (MODELS.parent / 'lighting/studio_ibl.ktx').read_bytes()
        self.assertEqual(b'\xabKTX 11\xbb\r\n\x1a\n', raw[:12])
        fields = struct.unpack_from('<13I', raw, 12)
        self.assertEqual(0x04030201, fields[0])
        self.assertEqual((128, 128), fields[6:8])
        self.assertEqual(6, fields[10])
        self.assertGreaterEqual(fields[11], 4)
        self.assertGreater(fields[12], 27 * 4)
        self.assertIn(b'sh\0', raw[64:64 + fields[12]])
        self.assertLess(len(raw), 600 * 1024)


if __name__ == '__main__':
    unittest.main()
