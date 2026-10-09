"""Structural checks for any exported replacement GLB; no Android or Blender required."""
import json
from pathlib import Path
import struct
import unittest

MODEL = Path(__file__).resolve().parents[1] / 'app/src/main/assets/models/gba_cartridge.glb'


class CartridgeAssetTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.raw = MODEL.read_bytes()
        magic, version, length = struct.unpack_from('<III', cls.raw)
        assert magic == 0x46546C67 and version == 2 and length == len(cls.raw)
        size, kind = struct.unpack_from('<II', cls.raw, 12)
        assert kind == 0x4E4F534A
        cls.doc = json.loads(cls.raw[20:20 + size])
        binary_size, kind = struct.unpack_from('<II', cls.raw, 20 + size)
        assert kind == 0x004E4942
        cls.binary = cls.raw[28 + size:28 + size + binary_size]

    def values(self, index):
        a = self.doc['accessors'][index]
        view = self.doc['bufferViews'][a['bufferView']]
        components = {'SCALAR': 1, 'VEC2': 2, 'VEC3': 3, 'VEC4': 4}[a['type']]
        fmt = {5126: 'f', 5123: 'H'}[a['componentType']]
        count = a['count'] * components
        return struct.unpack_from('<' + fmt * count, self.binary, view.get('byteOffset', 0)), components

    def test_all_buffer_ranges_and_indices_are_valid(self):
        for view in self.doc['bufferViews']:
            self.assertEqual(0, view.get('byteOffset', 0) % 4)
            self.assertLessEqual(view.get('byteOffset', 0) + view['byteLength'], len(self.binary))
        for mesh in self.doc['meshes']:
            for primitive in mesh['primitives']:
                indices, _ = self.values(primitive['indices'])
                positions = self.doc['accessors'][primitive['attributes']['POSITION']]
                self.assertEqual(0, len(indices) % 3)
                self.assertLess(max(indices), positions['count'])

    def test_cartridge_has_physical_depth_and_meter_scale(self):
        xyz = []
        for mesh in self.doc['meshes']:
            for p in mesh['primitives']:
                points, _ = self.values(p['attributes']['POSITION'])
                xyz.extend(zip(points[0::3], points[1::3], points[2::3]))
        bounds = [max(p[i] for p in xyz) - min(p[i] for p in xyz) for i in range(3)]
        self.assertTrue(.055 < bounds[0] < .065)
        self.assertTrue(.030 < bounds[1] < .040)
        self.assertTrue(.006 < bounds[2] < .012)

    def test_normals_and_triangle_winding_agree(self):
        for mesh in self.doc['meshes']:
            for p in mesh['primitives']:
                positions, _ = self.values(p['attributes']['POSITION'])
                normals, _ = self.values(p['attributes']['NORMAL'])
                indices, _ = self.values(p['indices'])
                for i in range(0, len(normals), 3):
                    self.assertAlmostEqual(1, sum(v * v for v in normals[i:i + 3]), places=5)
                for i in range(0, len(indices), 3):
                    a, b, c = [positions[3 * n:3 * n + 3] for n in indices[i:i + 3]]
                    u, v = [b[n] - a[n] for n in range(3)], [c[n] - a[n] for n in range(3)]
                    cross = (u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0])
                    normal = normals[3 * indices[i]:3 * indices[i] + 3]
                    self.assertGreater(sum(cross[n] * normal[n] for n in range(3)), 0)

    def test_label_is_embedded_and_replaceable_material(self):
        label = next(m for m in self.doc['materials'] if 'label' in m['name'].lower() and 'baseColorTexture' in m['pbrMetallicRoughness'])
        self.assertEqual(0, label['pbrMetallicRoughness']['metallicFactor'])
        image = self.doc['images'][0]
        self.assertEqual('image/png', image['mimeType'])
        view = self.doc['bufferViews'][image['bufferView']]
        offset = view['byteOffset']
        self.assertEqual(b'\x89PNG\r\n\x1a\n', self.binary[offset:offset + 8])
        self.assertFalse(any('uri' in i for i in self.doc['images']))


if __name__ == '__main__':
    unittest.main()
