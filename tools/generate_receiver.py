"""Original receiving slot. Pure Python, deterministic glTF 2.0 binary.

Coordinates: metres, +Y up. The 62 mm opening admits the 60 mm cartridge templates built by import_models.py.
Run: python tools/generate_receiver.py
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "app/src/main/assets/models/gba_receiver.glb"


def generate():
    from glb_geometry import Geometry
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
    receiver.write(OUTPUT, 'Original minimal receiving slot')


if __name__ == '__main__':
    generate()
