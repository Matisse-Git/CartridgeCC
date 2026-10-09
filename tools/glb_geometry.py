"""Small original glTF writer for reproducible physical media and receiver geometry."""
import json
import math
import struct
import zlib


def png(width, height, pixels):
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
    rows = b''.join(b'\0' + pixels[y * width * 4:(y + 1) * width * 4] for y in range(height))
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(rows, 9)) + chunk(b'IEND', b''))


def rounded(width, height, radius, center=(0, 0), segments=8):
    """CCW outline and analytic outward XY normals; metres, +Z front."""
    w, h = width / 2, height / 2
    points, normals = [], []
    for x, y, start in [(w-radius, -h+radius, -90), (w-radius, h-radius, 0),
                        (-w+radius, h-radius, 90), (-w+radius, -h+radius, 180)]:
        for i in range(segments + 1):
            a = math.radians(start + i * 90 / segments)
            nx, ny = math.cos(a), math.sin(a)
            points.append((center[0]+x+radius*nx, center[1]+y+radius*ny))
            normals.append((nx, ny))
    return points, normals


class Geometry:
    def __init__(self, materials):
        self.materials = materials
        self.groups = [[] for _ in materials]

    def face(self, points, material, normals=None, uv=None):
        a, b, c = points[:3]
        u, v = [b[i]-a[i] for i in range(3)], [c[i]-a[i] for i in range(3)]
        cross = (u[1]*v[2]-u[2]*v[1], u[2]*v[0]-u[0]*v[2], u[0]*v[1]-u[1]*v[0])
        length = math.sqrt(sum(n*n for n in cross))
        normal = tuple(n/length for n in cross)
        if uv is None:
            axis = max(range(3), key=lambda i: abs(normal[i]))
            coords = [(1, 2), (0, 2), (0, 1)][axis]
            uv = [(p[coords[0]]/.014, p[coords[1]]/.014) for p in points]
        self.groups[material].append((points, normals or [normal]*len(points), uv))

    def body(self, width, height, depth, radius, bevel, material, center=(0, 0, 0), hole=None,
             segments=4, bevel_steps=2):
        rings = []
        for back in (True, False):
            for step in range(bevel_steps+1):
                angle = (-math.pi/2 + step*math.pi/(2*bevel_steps)) if back else step*math.pi/(2*bevel_steps)
                inset = bevel*(1-math.cos(angle))
                z = (-depth/2+bevel if back else depth/2-bevel)+bevel*math.sin(angle)
                points, normals = rounded(width-2*inset, height-2*inset, radius-inset, center[:2], segments)
                rings.append(([(x, y, z+center[2]) for x, y in points],
                              [(nx*math.cos(angle), ny*math.cos(angle), math.sin(angle)) for nx, ny in normals]))
        self.face(list(reversed(rings[0][0])), material)
        for (lower, ln), (upper, un) in zip(rings, rings[1:]):
            for i in range(len(lower)):
                j = (i+1) % len(lower)
                self.face([lower[i], lower[j], upper[j], upper[i]], material, [ln[i], ln[j], un[j], un[i]])
        outer = rings[-1][0]
        if hole is None:
            self.face(outer, material)
        else:
            hw, hh, hr, hx, hy, recess = hole
            inner, _ = rounded(hw, hh, hr, (hx, hy), segments)
            front = [(x, y, depth/2+center[2]) for x, y in inner]
            bottom = [(x, y, depth/2+center[2]-recess) for x, y in inner]
            for i in range(len(outer)):
                j = (i+1) % len(outer)
                self.face([outer[i], outer[j], front[j], front[i]], material)
                self.face([front[i], front[j], bottom[j], bottom[i]], 1)
            self.face(bottom, 1)

    def write(self, path, name, images=(), textures=(), samplers=()):
        binary, views, accessors, primitives = bytearray(), [], [], []
        def append(data, target=None):
            binary.extend(b'\0'*(-len(binary) % 4))
            view = dict(buffer=0, byteOffset=len(binary), byteLength=len(data))
            if target: view['target'] = target
            views.append(view); binary.extend(data)
            return len(views)-1
        def accessor(values, components, kind, indices=False, bounds=False):
            view = append(struct.pack('<'+('H' if indices else 'f')*len(values), *values), 34963 if indices else 34962)
            record = dict(bufferView=view, componentType=5123 if indices else 5126, count=len(values)//components, type=kind)
            if bounds:
                record.update(min=[min(values[c::components]) for c in range(components)],
                              max=[max(values[c::components]) for c in range(components)])
            accessors.append(record); return len(accessors)-1
        for material, faces in enumerate(self.groups):
            if not faces: continue
            positions, normals, uv, tangents, indices = [], [], [], [], []
            for points, ns, uvs in faces:
                base = len(positions)//3
                positions.extend(v for p in points for v in p)
                normals.extend(v for n in ns for v in n)
                uv.extend(v for p in uvs for v in p)
                if 'normalTexture' in self.materials[material]:
                    a,b,c = points[:3]
                    e1,e2 = [b[i]-a[i] for i in range(3)], [c[i]-a[i] for i in range(3)]
                    du1,dv1 = uvs[1][0]-uvs[0][0], uvs[1][1]-uvs[0][1]
                    du2,dv2 = uvs[2][0]-uvs[0][0], uvs[2][1]-uvs[0][1]
                    determinant = du1*dv2-dv1*du2
                    t = [(e1[i]*dv2-e2[i]*dv1)/determinant for i in range(3)]
                    bt = [(e2[i]*du1-e1[i]*du2)/determinant for i in range(3)]
                    for n in ns:
                        dot = sum(t[i]*n[i] for i in range(3))
                        projected = [t[i]-dot*n[i] for i in range(3)]
                        length = math.sqrt(sum(v*v for v in projected))
                        tangent = [v/length for v in projected]
                        cross = (n[1]*tangent[2]-n[2]*tangent[1], n[2]*tangent[0]-n[0]*tangent[2], n[0]*tangent[1]-n[1]*tangent[0])
                        sign = 1 if sum(cross[i]*bt[i] for i in range(3)) >= 0 else -1
                        tangents.extend((*tangent, sign))
                for i in range(1, len(points)-1): indices.extend((base, base+i, base+i+1))
            attrs = dict(POSITION=accessor(positions, 3, 'VEC3', bounds=True), NORMAL=accessor(normals, 3, 'VEC3'))
            if 'normalTexture' in self.materials[material] or 'baseColorTexture' in self.materials[material].get('pbrMetallicRoughness', {}):
                attrs['TEXCOORD_0'] = accessor(uv, 2, 'VEC2')
            if tangents: attrs['TANGENT'] = accessor(tangents, 4, 'VEC4')
            primitives.append(dict(attributes=attrs, indices=accessor(indices, 1, 'SCALAR', indices=True), material=material))
        embedded = [dict(bufferView=append(data), mimeType='image/png') for data in images]
        doc = dict(asset=dict(version='2.0', generator='Project Cartridge original M2 generator'),
                   scene=0, scenes=[dict(nodes=[0])], nodes=[dict(mesh=0, name=name)], meshes=[dict(primitives=primitives)],
                   buffers=[dict(byteLength=len(binary))], bufferViews=views, accessors=accessors, materials=self.materials)
        if images: doc.update(images=embedded, textures=textures, samplers=samplers)
        encoded = json.dumps(doc, separators=(',', ':')).encode(); encoded += b' '*(-len(encoded) % 4)
        binary += b'\0'*(-len(binary) % 4)
        glb = (struct.pack('<III', 0x46546C67, 2, 28+len(encoded)+len(binary))
               + struct.pack('<II', len(encoded), 0x4E4F534A)+encoded
               + struct.pack('<II', len(binary), 0x004E4942)+binary)
        path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(glb)
        triangles = sum(len(f[0])-2 for group in self.groups for f in group)
        print(f'{path.name}: {len(glb)} bytes, {triangles} triangles, {len(primitives)} materials')
