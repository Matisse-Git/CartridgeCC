"""Original studio environment; analytic softboxes, no downloaded photograph or texture.

Writes Radiance RGBE for matching Filament cmgen. Runtime gets only the prefiltered KTX.
"""
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / '.tools/studio/cartridge_studio.hdr'


def generate():
    width, height = 512, 256
    pixels = bytearray()
    panels = [(-.65, .55, .52, 1.2, (.98, .94, .86)),
              (.8, .22, .56, .75, (.85, .9, 1.0)),
              (0, .9, -.44, .5, (1.0, .96, .9))]
    for y in range(height):
        phi = math.pi*(y+.5)/height
        for x in range(width):
            theta = 2*math.pi*(x+.5)/width
            direction = (math.sin(phi)*math.cos(theta), math.cos(phi), math.sin(phi)*math.sin(theta))
            color = [.045, .044, .042]
            for px, py, pz, strength, tint in panels:
                length = math.sqrt(px*px+py*py+pz*pz)
                dot = sum(d*p/length for d,p in zip(direction,(px,py,pz)))
                # Smooth finite-area panels, restrained highlights rather than a point-light sun.
                falloff = max(0, (dot-.88)/.12)**3
                for c in range(3): color[c] += strength*tint[c]*falloff
            maximum = max(color)
            mantissa, exponent = math.frexp(maximum)
            scale = mantissa*256/maximum
            pixels.extend((*[min(255, int(c*scale)) for c in color], exponent+128))
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_bytes(f'#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y {height} +X {width}\n'.encode()+pixels)
    print(OUTPUT)


if __name__ == '__main__': generate()
