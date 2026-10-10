"""Original PS1 optical-disc development template. 120 mm x 1.2 mm, 15 mm real hole.
Metres, +Y up, +Z printed face. Label UV covers the square artwork, clipped by annulus mesh.
No adopted models, logos or commercial labels. Artist refinement remains a separate pass.
"""
from pathlib import Path
import math
from glb_geometry import Geometry, png

def generate():
    g = Geometry([
        dict(name='Preserved polycarbonate hub', pbrMetallicRoughness=dict(baseColorFactor=[.5,.52,.49,1],metallicFactor=.25,roughnessFactor=.30)),
        dict(name='Reflective underside', pbrMetallicRoughness=dict(baseColorFactor=[.63,.65,.69,1],metallicFactor=.95,roughnessFactor=.18)),
        dict(name='Replaceable fictional label', pbrMetallicRoughness=dict(baseColorTexture=dict(index=0),metallicFactor=0,roughnessFactor=.64)),
    ])
    n = 128
    def point(r, a, z): return (r*math.cos(a),r*math.sin(a),z)
    for i in range(n):
        a,b = i*math.tau/n,(i+1)*math.tau/n
        for inner,outer,material in [(.0075,.022,0),(.022,.05985,2)]:
            pts=[point(inner,a,.0006),point(outer,a,.0006),point(outer,b,.0006),point(inner,b,.0006)]
            g.face(pts,material,uv=[((x+.06)/.12,1-(y+.06)/.12) for x,y,z in pts])
        g.face([point(.0075,b,-.0006),point(.05985,b,-.0006),point(.05985,a,-.0006),point(.0075,a,-.0006)],1)
        for r,reverse in [(.06,False),(.0075,True)]:
            pts=[point(r,a,-.0006),point(r,b,-.0006),point(r,b,.0006),point(r,a,.0006)]
            normals=[((-1 if reverse else 1)*math.cos(t),(-1 if reverse else 1)*math.sin(t),0) for t in (a,b,b,a)]
            if reverse: pts.reverse(); normals.reverse()
            g.face(pts,0,normals)
        g.face([point(.05985,a,.0006),point(.06,a,.00045),point(.06,b,.00045),point(.05985,b,.0006)],0)
        g.face([point(.05985,b,-.0006),point(.06,b,-.00045),point(.06,a,-.00045),point(.05985,a,-.0006)],0)
    pixels=bytearray()
    for y in range(512):
        for x in range(512):
            r=math.hypot(x-256,y-256)
            line=abs(r-155)<3 or (360<y<390) or (190<x<322 and 130<y<138)
            pixels.extend((39,57,60,255) if line else (195,184,152,255))
    g.write(Path(__file__).resolve().parents[1]/'app/src/main/assets/models/ps1_disc.glb','Original optical-disc study',
        [png(512,512,pixels)],[dict(source=0,sampler=0)],[dict(magFilter=9729,minFilter=9987,wrapS=33071,wrapT=33071)])

if __name__=='__main__': generate()
