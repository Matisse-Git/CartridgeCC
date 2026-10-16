"""Original diagnostics for the Saturn core. Generated locally only; nothing here is Sega's.

The Saturn core cannot start without a BIOS image and Cartridge never bundles or downloads one, so the
device test supplies a stand-in of its own: 512 KiB that hold an exception table and a few hand-assembled
SH-2 instructions. They fill the back-screen colour table with alternating white and red lines, switch
the display on and rest. That is enough to prove the core loads, runs its main processor, renders and
serializes on the device. It is not a BIOS: it reads no disc and can start no game.

The disc is one cooked data track whose first sector begins with the console's name, which is how the
importer tells a Saturn disc from a PlayStation one. It carries none of the security code a real disc
has, so the core itself treats it as an unknown disc and takes its default region.
"""
from pathlib import Path
import struct


class Sh2:
    def __init__(self, origin):
        self.origin, self.code, self.labels, self.fixes = origin, bytearray(), {}, []

    def word(self, value):
        self.code += struct.pack('>H', value & 0xFFFF)

    def long(self, value):
        self.code += struct.pack('>I', value)

    def mark(self, name):
        self.labels[name] = self.origin + len(self.code)

    def fix(self, kind, register, name):
        self.fixes.append((kind, len(self.code), register, name)); self.word(0)

    def link(self):
        for kind, offset, register, name in self.fixes:
            address, target = self.origin + offset, self.labels[name]
            if kind == 'long':      # MOV.L @(disp,PC),Rn
                distance = target - ((address + 4) & ~3)
                assert target % 4 == 0 and 0 <= distance < 1024
                value = 0xD000 | register << 8 | distance // 4
            elif kind == 'word':    # MOV.W @(disp,PC),Rn
                distance = target - (address + 4)
                assert 0 <= distance < 512
                value = 0x9000 | register << 8 | distance // 2
            else:                   # BF
                distance = (target - (address + 4)) // 2
                assert -128 <= distance < 128
                value = 0x8B00 | distance & 0xFF
            struct.pack_into('>H', self.code, offset, value)
        return bytes(self.code)


def boot_image():
    a = Sh2(0x1000)
    a.fix('long', 1, 'registers'); a.fix('long', 2, 'memory'); a.fix('long', 6, 'backscreen')
    a.fix('word', 4, 'white'); a.word(0xE51F); a.fix('word', 3, 'lines')      # R4 white; MOV #31,R5 red; R3 = 256
    a.mark('line')
    a.word(0x2241); a.word(0x7202); a.word(0x2251); a.word(0x7202)           # MOV.W R4,@R2; ADD #2,R2; MOV.W R5,@R2; ADD #2,R2
    a.word(0x4310); a.fix('bf', 0, 'line')                                    # DT R3; BF line
    a.fix('word', 0, 'perline'); a.word(0x2601)                               # Back screen: one colour per line...
    a.word(0xE000); a.word(0x8161); a.word(0x8111)                            # ...from the start of video memory; no external sync.
    a.fix('word', 0, 'perline'); a.word(0x2101)                               # Display on.
    a.mark('rest'); a.word(0xAFFE); a.word(0x0009)                            # BRA rest; NOP
    while len(a.code) % 4: a.word(0x0009)
    a.mark('registers'); a.long(0x25F80000); a.mark('memory'); a.long(0x25E00000); a.mark('backscreen'); a.long(0x25F800AC)
    a.mark('white'); a.word(0x7FFF); a.mark('lines'); a.word(0x0100); a.mark('perline'); a.word(0x8000); a.word(0)
    code = a.link()
    image = bytearray(512 * 1024)
    for vector in range(256): struct.pack_into('>I', image, vector * 4, a.labels['rest'])
    struct.pack_into('>IIII', image, 0, 0x1000, 0x06002000, 0x1000, 0x06002000)   # Power-on and reset: program, stack.
    image[0x1000:0x1000 + len(code)] = code
    return bytes(image)


def disc():
    sectors = 600
    data = bytearray(2048 * sectors)
    def field(offset, text, length): data[offset:offset + length] = text.encode('ascii').ljust(length)[:length]
    field(0x00, 'SEGA SEGASATURN', 16); field(0x10, 'ORBIT', 16); field(0x20, 'T-00000', 10); field(0x2A, 'V1.000', 6)
    field(0x30, '20261011', 8); field(0x38, 'CD-1/1', 8); field(0x40, 'U', 16); field(0x50, 'J', 16); field(0x60, 'ORBIT PROBE', 112)
    cue = 'FILE "saturn.bin" BINARY\n  TRACK 01 MODE1/2048\n    INDEX 01 00:00:00\n'
    return cue.encode('ascii'), bytes(data)


if __name__ == '__main__':
    output = Path(__file__).resolve().parents[2] / '.tools/probe/saturn'
    output.mkdir(parents=True, exist_ok=True)
    cue, track = disc()
    for name, content in (('boot.bin', boot_image()), ('saturn.cue', cue), ('saturn.bin', track)):
        (output / name).write_bytes(content); print(output / name)
