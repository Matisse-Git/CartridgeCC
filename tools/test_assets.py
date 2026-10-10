"""Structural checks for the packaged media GLBs; no Android or Blender required."""
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


TEMPLATES = {
    # name: (width, height, depth) in metres, label artwork aspect, label (width, height) in metres
    'gba_cartridge.glb': ((.0600, .0343, .0069), 512 / 256, (.0433, .0225)),
    'gb_cartridge.glb': ((.0575, .0650, .0091), 512 / 448, (.0445, .0392)),
    'ps1_disc.glb': ((.1200, .1200, .0018), 512 / 512, (.1193, .1193)),
}


class MediaTemplateTest(unittest.TestCase):
    """What the renderer and CartridgeLabels rely on, for every object a game can be shown as."""

    def each(self):
        return [(name, Glb(name), expected) for name, expected in TEMPLATES.items()]

    def test_buffers_and_indices_are_valid(self):
        for name in TEMPLATES:
            with self.subTest(name):
                glb = Glb(name)
                for view in glb.doc['bufferViews']:
                    self.assertEqual(0, view.get('byteOffset', 0) % 4)
                    self.assertLessEqual(view.get('byteOffset', 0) + view['byteLength'], len(glb.binary))
                for primitive in glb.primitives:
                    indices = glb.values(primitive['indices'])
                    self.assertEqual(0, len(indices) % 3)
                    self.assertLess(max(indices), glb.doc['accessors'][primitive['attributes']['POSITION']]['count'])

    def test_metre_scale_centred_and_unposed(self):
        for _, glb, (size, _, _) in self.each():
            low, high = glb.bounds()
            for axis in range(3):
                self.assertAlmostEqual(size[axis], high[axis] - low[axis], delta=.0002)
                self.assertAlmostEqual(0, (low[axis] + high[axis]) / 2, delta=.0001)
            # The app places the instance root; a posed node would tilt every object.
            self.assertTrue(all(not {'rotation', 'translation', 'scale', 'matrix'} & set(node) for node in glb.doc['nodes']))

    def test_normals_are_unit_and_agree_with_winding(self):
        for _, glb, _ in self.each():
            for primitive in glb.primitives:
                positions, normals = glb.attribute(primitive, 'POSITION'), glb.attribute(primitive, 'NORMAL')
                indices = glb.values(primitive['indices'])
                for normal in normals:
                    self.assertAlmostEqual(1, sum(v * v for v in normal), places=3)
                agree = faces = 0
                for i in range(0, len(indices), 3):
                    a, b, c = (positions[n] for n in indices[i:i + 3])
                    u, v = [b[n] - a[n] for n in range(3)], [c[n] - a[n] for n in range(3)]
                    cross = (u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0])
                    if math.sqrt(sum(n * n for n in cross)) < 1e-12:
                        continue  # A collapsed sliver of the source mesh has no facing.
                    faces += 1
                    agree += all(sum(cross[n] * normals[k][n] for n in range(3)) > 0 for k in indices[i:i + 3])
                # Smoothed corners may lean past a neighbouring facet; an inside-out shell would fail wholesale.
                self.assertGreater(agree / faces, .97)

    def test_label_is_one_named_primitive_facing_the_viewer(self):
        for _, glb, (_, _, label_size) in self.each():
            label = glb.label()
            material = glb.material(label)
            self.assertIn('baseColorTexture', material['pbrMetallicRoughness'])
            self.assertEqual(0, material['pbrMetallicRoughness']['metallicFactor'])
            self.assertTrue(all(normal[2] > .99 for normal in glb.attribute(label, 'NORMAL')))
            width, height, depth = glb.size([label])
            self.assertAlmostEqual(label_size[0], width, delta=.0002)
            self.assertAlmostEqual(label_size[1], height, delta=.0002)
            self.assertLess(depth, .00001)
            # On the printed side of the object, not buried in it.
            self.assertGreater(glb.bounds([label])[0][2], 0)

    def test_label_uvs_cover_the_artwork_from_the_top_left(self):
        for name, glb, (_, aspect, _) in self.each():
            label = glb.label()
            points = list(zip(glb.attribute(label, 'POSITION'), glb.attribute(label, 'TEXCOORD_0')))
            self.assertTrue(all(-1e-4 <= uv[0] <= 1 + 1e-4 and -1e-4 <= uv[1] <= 1 + 1e-4 for _, uv in points))
            low, high = glb.bounds([label])
            if name == 'ps1_disc.glb':
                # The artwork square spans the whole 120 mm disc; the annulus clips it.
                for (x, y, _), (u, v) in points:
                    self.assertAlmostEqual((x + .06) / .12, u, places=4)
                    self.assertAlmostEqual(1 - (y + .06) / .12, v, places=4)
            else:
                # The whole image maps onto the whole label: U grows right, V grows down, (0, 0) top left.
                for (x, y, _), (u, v) in points:
                    self.assertAlmostEqual((x - low[0]) / (high[0] - low[0]), u, places=4)
                    self.assertAlmostEqual((high[1] - y) / (high[1] - low[1]), v, places=4)
                # Close enough to the decoded artwork's shape that a label is not visibly stretched.
                self.assertAlmostEqual(aspect, (high[0] - low[0]) / (high[1] - low[1]), delta=aspect * .05)

    def test_placeholder_label_has_the_artwork_aspect(self):
        for _, glb, (_, aspect, _) in self.each():
            width, height = glb.image_size(glb.texture_image(glb.material(glb.label())['pbrMetallicRoughness']['baseColorTexture']))
            self.assertAlmostEqual(aspect, width / height, places=2)

    def test_shell_is_opaque_pbr_with_tangents_and_bounded_textures(self):
        for _, glb, _ in self.each():
            shell = glb.shell()
            material = glb.material(shell)
            self.assertEqual(2, len(glb.doc['materials']))
            self.assertTrue(all(m.get('alphaMode', 'OPAQUE') == 'OPAQUE' for m in glb.doc['materials']))
            self.assertIn('normalTexture', material)
            self.assertIn('baseColorTexture', material['pbrMetallicRoughness'])
            self.assertIn('metallicRoughnessTexture', material['pbrMetallicRoughness'])
            self.assertIn('TANGENT', shell['attributes'])
            self.assertFalse(any('uri' in image for image in glb.doc['images']))
            for index in range(len(glb.doc['images'])):
                self.assertLessEqual(max(glb.image_size(index)), 1024)
            self.assertLess(sum(glb.doc['accessors'][p['indices']]['count'] // 3 for p in glb.primitives), 15_000)
            self.assertLess(len(glb.raw), 3 * 1024 * 1024)

    def test_cartridges_carry_baked_occlusion(self):
        for name in ('gba_cartridge.glb', 'gb_cartridge.glb'):
            glb = Glb(name)
            material = glb.material(glb.shell())
            self.assertEqual(material['occlusionTexture']['index'], material['pbrMetallicRoughness']['metallicRoughnessTexture']['index'])

    def test_disc_is_an_annulus_with_a_real_hole(self):
        glb = Glb('ps1_disc.glb')
        radii = [math.hypot(x, y) for primitive in glb.primitives for x, y, _ in glb.attribute(primitive, 'POSITION')]
        self.assertAlmostEqual(.0600, max(radii), delta=.0001)
        self.assertAlmostEqual(.0076, min(radii), delta=.0003)
        printed = [math.hypot(x, y) for x, y, _ in glb.attribute(glb.label(), 'POSITION')]
        # From the stacking ring to just inside the rim.
        self.assertAlmostEqual(.0195, min(printed), delta=.0003)
        self.assertAlmostEqual(.0596, max(printed), delta=.0003)


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
