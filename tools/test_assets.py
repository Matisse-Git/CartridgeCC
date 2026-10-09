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

    def test_refined_geometry_and_material_budget(self):
        primitives = self.doc['meshes'][0]['primitives']
        self.assertEqual(4, len(primitives))
        self.assertLess(sum(self.doc['accessors'][p['indices']]['count']//3 for p in primitives), 15_000)
        plastic = self.doc['materials'][0]
        self.assertIn('normalTexture', plastic)
        self.assertIn('metallicRoughnessTexture', plastic['pbrMetallicRoughness'])
        self.assertEqual(3, len(self.doc['images']))
        normals, _ = self.values(primitives[0]['attributes']['NORMAL'])
        self.assertTrue(any(.1 < abs(n) < .9 for n in normals))

    def test_label_recess_and_top_left_uv_contract(self):
        primitive = next(p for p in self.doc['meshes'][0]['primitives'] if p['material'] == 2)
        positions, _ = self.values(primitive['attributes']['POSITION'])
        uv, _ = self.values(primitive['attributes']['TEXCOORD_0'])
        points = list(zip(positions[0::3], positions[1::3], positions[2::3], uv[0::2], uv[1::2]))
        self.assertAlmostEqual(.00371, max(p[2] for p in points), places=6)
        self.assertTrue(all(0 <= p[3] <= 1 and 0 <= p[4] <= 1 for p in points))
        top = max(points, key=lambda p: p[1]); bottom = min(points, key=lambda p: p[1])
        self.assertLess(top[4], bottom[4])
        self.assertLess(min(points, key=lambda p:p[0])[3], max(points, key=lambda p:p[0])[3])

    def test_receiver_is_real_geometry_with_slot_clearance(self):
        raw = MODEL.with_name('gba_receiver.glb').read_bytes()
        size = struct.unpack_from('<I', raw, 12)[0]
        doc = json.loads(raw[20:20+size])
        self.assertEqual('2.0', doc['asset']['version'])
        self.assertEqual(2, len(doc['materials']))
        self.assertLess(len(raw), 128*1024)
        binary = raw[28+size:]
        primitive = doc['meshes'][0]['primitives'][0]
        accessor = doc['accessors'][primitive['attributes']['POSITION']]
        view = doc['bufferViews'][accessor['bufferView']]
        positions = struct.unpack_from('<'+'f'*(accessor['count']*3), binary, view['byteOffset'])
        index_accessor = doc['accessors'][primitive['indices']]
        index_view = doc['bufferViews'][index_accessor['bufferView']]
        indices = struct.unpack_from('<'+'H'*index_accessor['count'], binary, index_view['byteOffset'])
        sides = []
        for i in range(0, len(indices), 3):
            triangle = [positions[3*n:3*n+3] for n in indices[i:i+3]]
            if min(p[2] for p in triangle) < 0 < max(p[2] for p in triangle) and all(p[0] > .025 for p in triangle):
                sides.extend(p[0] for p in triangle)
        self.assertGreater(min(sides)*2, .060)  # Measured mesh clearance admits the lip.
        self.assertEqual(0, doc['materials'][1]['pbrMetallicRoughness']['metallicFactor'])

    def test_studio_has_prefiltered_cubemap_and_spherical_harmonics(self):
        raw = (MODEL.parents[1]/'lighting/studio_ibl.ktx').read_bytes()
        self.assertEqual(b'\xabKTX 11\xbb\r\n\x1a\n', raw[:12])
        fields = struct.unpack_from('<13I', raw, 12)
        self.assertEqual(0x04030201, fields[0])
        self.assertEqual((128,128), fields[6:8])
        self.assertEqual(6, fields[10])
        self.assertGreaterEqual(fields[11], 4)
        self.assertGreater(fields[12], 27*4)
        self.assertIn(b'sh\0', raw[64:64+fields[12]])
        self.assertLess(len(raw), 600*1024)


class TallCartridgeAssetTest(unittest.TestCase):
    def load(self):
        raw = MODEL.with_name('gb_cartridge.glb').read_bytes()
        magic, version, length = struct.unpack_from('<III', raw)
        self.assertEqual((0x46546C67, 2, len(raw)), (magic, version, length))
        size = struct.unpack_from('<I', raw, 12)[0]
        reader = CartridgeAssetTest()
        reader.doc = json.loads(raw[20:20+size]); reader.binary = raw[28+size:]
        return reader
    def test_tall_geometry_has_valid_normals_indices_and_physical_thickness(self):
        reader = self.load()
        reader.test_all_buffer_ranges_and_indices_are_valid(); reader.test_normals_and_triangle_winding_agree()
        points=[]
        for p in reader.doc['meshes'][0]['primitives']:
            xyz,_=reader.values(p['attributes']['POSITION']); points.extend(zip(xyz[::3],xyz[1::3],xyz[2::3]))
        bounds=[max(p[n] for p in points)-min(p[n] for p in points) for n in range(3)]
        self.assertTrue(.053<bounds[0]<.057 and .064<bounds[1]<.070 and .008<bounds[2]<.010)
    def test_tall_pbr_and_label_contract_has_correct_aspect_and_orientation(self):
        reader=self.load(); reader.test_refined_geometry_and_material_budget(); reader.test_label_is_embedded_and_replaceable_material()
        p=next(p for p in reader.doc['meshes'][0]['primitives'] if p['material']==2)
        xyz,_=reader.values(p['attributes']['POSITION']); uv,_=reader.values(p['attributes']['TEXCOORD_0'])
        points=list(zip(xyz[::3],xyz[1::3],xyz[2::3],uv[::2],uv[1::2]))
        self.assertTrue(all(0<=p[3]<=1 and 0<=p[4]<=1 for p in points))
        self.assertLess(max(points,key=lambda p:p[1])[4],min(points,key=lambda p:p[1])[4])
        image=reader.doc['bufferViews'][reader.doc['images'][0]['bufferView']]
        self.assertEqual((512,448), struct.unpack_from('>II',reader.binary,image['byteOffset']+16))

if __name__ == '__main__':
    unittest.main()
