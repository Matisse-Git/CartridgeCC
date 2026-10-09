"""Original tall GB/GBC development template; no logos or adopted models. Metres, +Y up, +Z label.
Reuses the deterministic original geometry/PBR pipeline. Final shell art direction remains manual.
"""
from pathlib import Path
from glb_geometry import Geometry, rounded, png

def generate():
    materials = [
        dict(name='Preserved warm grey plastic', pbrMetallicRoughness=dict(baseColorFactor=[.38,.39,.35,1], metallicFactor=0, roughnessFactor=1, metallicRoughnessTexture=dict(index=1)), normalTexture=dict(index=2, scale=.18)),
        dict(name='Seam and recess', pbrMetallicRoughness=dict(baseColorFactor=[.07,.075,.065,1], metallicFactor=0, roughnessFactor=.86)),
        dict(name='Replaceable fictional label', pbrMetallicRoughness=dict(baseColorTexture=dict(index=0), metallicFactor=0, roughnessFactor=.64)),
        dict(name='Contact plating', pbrMetallicRoughness=dict(baseColorFactor=[.72,.54,.21,1], metallicFactor=.92, roughnessFactor=.28)),
    ]
    g = Geometry(materials)
    g.body(.054,.065,.0085,.0025,.00065,0,hole=(.040,.035,.0015,0,-.003,.00035),segments=8,bevel_steps=4)
    outline,_=rounded(.0541,.0651,.00255)
    for i,a in enumerate(outline):
        b=outline[(i+1)%len(outline)]
        g.face([(*a,-.00012),(*b,-.00012),(*b,.00012),(*a,.00012)],1)
    for n in range(6):
        g.body(.041,.00055,.00035,.0002,.00008,0,(0,.020+n*.0011,.00425))
    label,_=rounded(.0388,.03395,.001,(0,-.003))
    g.face([(x,y,.0039) for x,y in label],2,uv=[((x+.0194)/.0388,1-(y+.019975)/.03395) for x,y in label])
    g.body(.040,.0025,.0038,.0007,.0002,1,(0,-.0318,-.0004))
    for n in range(18):
        g.body(.00135,.002,.0002,.0001,.00003,3,((n-8.5)*.0021,-.0318,.0015))
    artwork=bytearray()
    for y in range(448):
        for x in range(512):
            # Original fictional orbital survey print; a label, not a playable bundled game.
            radius=((x-256)**2+(y-200)**2)**.5
            dark=abs(radius-112)<3 or abs(radius-152)<2 or y>370 or (235<x<277 and 175<y<225)
            artwork.extend((48,65,66,255) if dark else (184,185,155,255))
    orm,normal=bytearray(),bytearray()
    for y in range(128):
        for x in range(128):
            grain=(x*73856093 ^ y*19349663)&255
            orm.extend((255,192+grain//22,0,255)); normal.extend((126+grain%5,126+(grain//5)%5,255,255))
    g.write(Path(__file__).resolve().parents[1]/'app/src/main/assets/models/gb_cartridge.glb', 'Original tall GB/GBC development template',
        [png(512,448,artwork),png(128,128,orm),png(128,128,normal)],
        [dict(source=0,sampler=0),dict(source=1,sampler=1),dict(source=2,sampler=1)],
        [dict(magFilter=9729,minFilter=9987,wrapS=33071,wrapT=33071),dict(magFilter=9729,minFilter=9987,wrapS=10497,wrapT=10497)])

if __name__=='__main__': generate()
