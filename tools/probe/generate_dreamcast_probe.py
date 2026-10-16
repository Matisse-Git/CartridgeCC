"""Original diagnostic for the Dreamcast core. Generated locally only; nothing here is Sega's.

A bare SH-4 program in an ELF container, which Flycast starts on its built-in BIOS replacement. It
puts the display in a plain 640x480 16-bit framebuffer mode and, every pass:

  * paints alternating white and coloured lines straight into video memory (red first);
  * asks the controller in port A for its state over the Maple bus;
  * on a press of A or B switches red for green;
  * while the stick is held to the right paints blue instead.

That exercises the processor, the video chip's framebuffer path through the host's OpenGL ES
renderer, the buttons and the analog stick. It uses no BIOS call.

The same program is also written as a small GD-ROM image (a GDI sheet and three tracks), so that the
disc path is run too: the sheet and its tracks imported together, the disc's own header read, its
file system searched for the program that header names, and that program started from the header's
own start-up code. The header and start-up code are this project's; nothing in the image is Sega's
but the sixteen characters by which every disc of the console names itself.
"""
from pathlib import Path
import struct


class Sh4:
    """Little-endian SH-4 words with labels, PC-relative literals and short branches."""

    def __init__(self, origin):
        self.origin, self.code, self.labels, self.fixes, self.pool, self.pooled = origin, bytearray(), {}, [], {}, 0

    def word(self, value):
        self.code += struct.pack('<H', value & 0xFFFF)

    def mark(self, name):
        self.labels[name] = self.origin + len(self.code)

    def fix(self, kind, register, name):
        self.fixes.append((kind, len(self.code), register, name)); self.word(0)

    def load(self, register, value):
        """MOV.L @(disp,PC),Rn from a pooled 32-bit literal."""
        if value not in self.pool:
            self.pool[value] = f'literal{self.pooled}'; self.pooled += 1     # Names are unique across pools.
        self.fix('long', register, self.pool[value])

    def poke(self, address, value):
        self.load(1, address); self.load(0, value); self.word(0x2102)       # MOV.L R0,@R1

    def literals(self):
        while len(self.code) % 4: self.word(0x0009)
        for value, name in self.pool.items():
            self.mark(name); self.code += struct.pack('<I', value)
        self.pool = {}

    def link(self):
        assert not self.pool
        for kind, offset, register, name in self.fixes:
            address, target = self.origin + offset, self.labels[name]
            if kind == 'long':
                distance = target - ((address + 4) & ~3)
                assert target % 4 == 0 and 0 <= distance < 1024, name
                value = 0xD000 | register << 8 | distance // 4
            else:
                distance = (target - (address + 4)) // 2
                if kind in ('bt', 'bf'):
                    assert -128 <= distance < 128, name
                    value = (0x8900 if kind == 'bt' else 0x8B00) | distance & 0xFF
                else:
                    assert -2048 <= distance < 2048, name
                    value = (0xA000 if kind == 'bra' else 0xB000) | distance & 0xFFF
            struct.pack_into('<H', self.code, offset, value)
        return bytes(self.code)


def program():
    a = Sh4(0x8C010000)
    a.load(15, 0x8C00F000)
    # 640x480, 16-bit 565, progressive, read from the start of video memory.
    a.poke(0xA05F8044, 0x00800005); a.poke(0xA05F8050, 0); a.poke(0xA05F8054, 0x500)
    a.poke(0xA05F805C, (1 << 20) | (479 << 10) | 319); a.poke(0xA05F80D0, 0x100); a.poke(0xA05F80E8, 0x00160000)
    a.word(0xEB00); a.word(0xEA00); a.word(0xE900)                           # MOV #0: R11 choice, R10 held, R9 stick
    a.fix('bra', 0, 'loop'); a.word(0x0009)
    a.literals()

    a.mark('loop')
    a.fix('bsr', 0, 'paint'); a.word(0x0009)
    # One Maple frame: "get condition" to the controller in port A, answer at 0x0C020100.
    a.load(12, 0x8C020000)
    a.load(0, 0x80000001); a.word(0x2C02)                                    # MOV.L R0,@R12
    a.load(0, 0x0C020100); a.word(0x1C01)                                    # MOV.L R0,@(4,R12)
    a.load(0, 0x01002009); a.word(0x1C02)                                    # MOV.L R0,@(8,R12)
    a.load(0, 0x01000000); a.word(0x1C03)                                    # MOV.L R0,@(12,R12)
    a.load(13, 0xA05F6C00)
    a.load(0, 0x0C020000); a.word(0x1D01)                                    # Table address
    a.word(0xE001); a.word(0x1D05); a.word(0x1D06)                           # MOV #1,R0; enable; start
    a.mark('transfer'); a.word(0x50D6); a.word(0xC801); a.fix('bf', 0, 'transfer')   # until the start bit clears
    a.load(1, 0x8C020100)
    a.word(0x6012); a.word(0x600C); a.word(0x8808); a.fix('bf', 0, 'released')       # MOV.L @R1,R0; EXTU.B; a data answer?
    a.word(0x7108); a.word(0x6011); a.word(0xC906); a.word(0x8806)           # ADD #8,R1; MOV.W @R1,R0; AND #6; CMP/EQ #6
    a.fix('bt', 0, 'released')
    a.word(0x2AA8); a.fix('bf', 0, 'again')                                  # TST R10,R10: already held?
    a.word(0xEA01); a.word(0xE001); a.word(0x2B0A)                           # held; R11 ^= 1
    a.fix('bra', 0, 'again'); a.word(0x0009)
    a.mark('released'); a.word(0xEA00)
    a.mark('again')
    a.load(1, 0x8C02010C); a.word(0x6010); a.word(0x600C)                    # MOV.B @R1,R0; EXTU.B: the stick's X, 0x80 at rest
    a.load(2, 0xC0); a.word(0xE900); a.word(0x3022); a.fix('bf', 0, 'next')  # MOV #0,R9; CMP/HS R2,R0
    a.word(0xE901)
    a.mark('next'); a.fix('bra', 0, 'loop'); a.word(0x0009)
    a.literals()

    a.mark('paint')
    a.load(1, 0xA5000000); a.load(2, 0xFFFFFFFF); a.load(3, 0xF800F800)
    a.word(0x2BB8); a.fix('bt', 0, 'chosen'); a.load(3, 0x07E007E0)          # TST R11,R11
    a.mark('chosen'); a.word(0x2998); a.fix('bt', 0, 'rows'); a.load(3, 0x001F001F)   # TST R9,R9: stick right paints blue
    a.mark('rows'); a.load(4, 240)
    a.mark('pair'); a.load(5, 320)
    a.mark('white'); a.word(0x2122); a.word(0x7104); a.word(0x4510); a.fix('bf', 0, 'white')
    a.load(5, 320)
    a.mark('colour'); a.word(0x2132); a.word(0x7104); a.word(0x4510); a.fix('bf', 0, 'colour')
    a.word(0x4410); a.fix('bf', 0, 'pair')
    a.word(0x000B); a.word(0x0009)                                           # RTS; NOP
    a.literals()
    return a.link()


def elf():
    code = program()
    header = struct.pack('<16sHHIIIIIHHHHHH', b'\x7fELF\x01\x01\x01' + bytes(9), 2, 42, 1, 0x8C010000, 0x34, 0x54, 0,
                         52, 32, 1, 40, 1, 0)
    segment = struct.pack('<IIIIIIII', 1, 0x100, 0x8C010000, 0x8C010000, len(code), len(code), 7, 4)
    return (header + segment + bytes(40)).ljust(0x100, b'\0') + code


def gdrom():
    """The three tracks of a GD-ROM and the sheet that lists them. The game's track begins at sector 45000."""
    SECTOR, AREA, VOLUME, ROOT, PROGRAM = 2048, 45000, 16, 18, 19
    code = program()

    def both(value, short=False):
        return struct.pack('<H' if short else '<I', value) + struct.pack('>H' if short else '>I', value)

    def record(name, extent, size, directory=False):
        name = name if isinstance(name, bytes) else name.encode('ascii')
        entry = bytearray(33 + len(name) + (len(name) % 2 == 0)); entry[0] = len(entry)
        entry[2:10] = both(AREA + extent); entry[10:18] = both(size); entry[18:25] = bytes([126, 10, 11, 12, 0, 0, 0])
        entry[25] = 2 if directory else 0; entry[28:32] = both(1, True); entry[32] = len(name); entry[33:33 + len(name)] = name
        return entry

    track = bytearray(64 * SECTOR)
    # The disc's header: what it is, where it may be sold, what it needs, and the file to start.
    header = b''.join(text.encode('ascii').ljust(size) for text, size in (
        ('SEGA SEGAKATANA', 16), ('CARTRIDGE PROBE', 16), ('0000', 5), ('GD-ROM', 6), ('1/1', 5), ('JUE', 8), ('E000F10', 8),
        ('T-00000', 10), ('V1.000', 6), ('20261011', 8), ('', 8), ('1ST_READ.BIN', 16), ('CARTRIDGE', 16), ('ORIGINAL PROBE', 128)))
    assert len(header) == 256
    track[:256] = header
    # The start-up code a console runs from the header: here, only a jump to the loaded program.
    track[0x300:0x30C] = struct.pack('<HHHHI', 0xD001, 0x402B, 0x0009, 0x0009, 0x8C010000)
    volume = bytearray(SECTOR); volume[:7] = b'\x01CD001\x01'
    volume[8:40] = b'SEGA SEGAKATANA'.ljust(32); volume[40:72] = b'CARTRIDGE_PROBE'.ljust(32); volume[80:88] = both(64)
    volume[120:124] = both(1, True); volume[124:128] = both(1, True); volume[128:132] = both(SECTOR, True)
    volume[156:190] = record(b'\0', ROOT, SECTOR, True); volume[881] = 1
    track[VOLUME * SECTOR:(VOLUME + 1) * SECTOR] = volume
    track[(VOLUME + 1) * SECTOR:(VOLUME + 1) * SECTOR + 7] = b'\xffCD001\x01'
    records = record(b'\0', ROOT, SECTOR, True) + record(b'\1', ROOT, SECTOR, True) + record('1ST_READ.BIN;1', PROGRAM, len(code))
    track[ROOT * SECTOR:ROOT * SECTOR + len(records)] = records
    track[PROGRAM * SECTOR:PROGRAM * SECTOR + len(code)] = code
    sheet = '3\n1 0 4 2048 track01.iso 0\n2 450 0 2352 track02.raw 0\n3 45000 4 2048 track03.iso 0\n'
    return {'disc.gdi': sheet.encode('ascii'), 'track01.iso': bytes(16 * SECTOR), 'track02.raw': bytes(4 * 2352), 'track03.iso': bytes(track)}


if __name__ == '__main__':
    output = Path(__file__).resolve().parents[2] / '.tools/probe'
    output.mkdir(parents=True, exist_ok=True)
    path = output / 'cartridge-probe.elf'
    path.write_bytes(elf()); print(path)
    disc = output / 'dreamcast'
    disc.mkdir(exist_ok=True)
    for name, data in gdrom().items():
        (disc / name).write_bytes(data); print(disc / name)
