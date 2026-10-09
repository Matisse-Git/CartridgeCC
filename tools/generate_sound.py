"""Original quiet mechanical contact, no sampled or third-party audio."""
import math
from pathlib import Path
import random
import struct
import wave

output = Path(__file__).resolve().parents[1]/'app/src/main/res/raw/media_click.wav'
output.parent.mkdir(parents=True, exist_ok=True)
noise = random.Random(7)
samples = []
for i in range(1600):
    t = i/16000
    value = 0
    for onset, amplitude in [(0,.22),(.012,.12)]:
        age = t-onset
        if age >= 0:
            envelope = min(1,age/.0008)*math.exp(-age/ .009)
            value += amplitude*envelope*(.65*math.sin(age*2*math.pi*740)+.35*noise.uniform(-1,1))
    samples.append(int(value*32767))
with wave.open(str(output),'wb') as writer:
    writer.setnchannels(1); writer.setsampwidth(2); writer.setframerate(16000)
    writer.writeframes(struct.pack('<'+'h'*len(samples),*samples))
print(output)
