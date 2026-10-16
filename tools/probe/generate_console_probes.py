"""Original diagnostics for the Super Nintendo, NES, Genesis, Master System and Game Gear cores.

Generated locally only: hand-assembled machine code, no ROM, BIOS, logo or font assets.
Every program does the same thing on its console's own hardware registers:

  * fills the screen with a one-pixel checkerboard in white and red;
  * on a press of the button Android's A reaches (SNES A, NES A, Genesis C, Master System 2),
    switches red for green and stores that choice in battery memory;
  * boots with the stored choice, so a kept save is visible in the first frame;
  * where the console has a simple tone generator, holds one tone so PCM delivery can be counted.

Battery memory holds 0x43 at `MAGIC` and the choice (0 or 1) at `FLAG`; see PROBES at the end.
"""
from pathlib import Path
import struct


class Assembler:
    """Bytes, labels and relative/absolute fix-ups for one small program."""

    def __init__(self, origin, big_endian=False):
        self.origin, self.big, self.code, self.labels, self.fixes = origin, big_endian, bytearray(), {}, []

    def emit(self, *values):
        self.code.extend(values)

    def here(self):
        return self.origin + len(self.code)

    def mark(self, name):
        self.labels[name] = self.here()

    def word(self, value):
        self.code.extend(struct.pack('>H' if self.big else '<H', value & 0xFFFF))

    def long(self, value):
        self.code.extend(struct.pack('>I', value & 0xFFFFFFFF))

    def absolute(self, name):
        self.fixes.append(('abs', len(self.code), name)); self.word(0)

    def relative8(self, name):
        """A signed byte measured from the end of the instruction (6502, 65816, Z80)."""
        self.fixes.append(('rel8', len(self.code), name)); self.emit(0)

    def link(self):
        for kind, offset, name in self.fixes:
            target = self.labels[name]
            if kind == 'abs':
                struct.pack_into('>H' if self.big else '<H', self.code, offset, target)
            elif kind == 'rel8':
                distance = target - (self.origin + offset + 1)
                assert -128 <= distance <= 127, name
                self.code[offset] = distance & 0xFF
            elif kind == 'm68k8':   # Bcc.s: measured from the extension word, stored in the opcode's low byte.
                distance = target - (self.origin + offset + 1)
                assert -128 <= distance <= 127 and distance != 0, name
                self.code[offset] = distance & 0xFF
            elif kind == 'm68k16':  # BSR.w / BRA.w: measured from the displacement word itself.
                struct.pack_into('>h', self.code, offset, target - (self.origin + offset))
        return bytes(self.code)


def snes():
    a = Assembler(0x8000)
    def lda(value): a.emit(0xA9, value)
    def sta(address): a.emit(0x8D); a.word(address)
    def stz(address): a.emit(0x9C); a.word(address)
    def load(address): a.emit(0xAD); a.word(address)
    def lda_long(address): a.emit(0xAF, address & 255, (address >> 8) & 255, address >> 16)
    def sta_long(address): a.emit(0x8F, address & 255, (address >> 8) & 255, address >> 16)
    def branch(opcode, name): a.emit(opcode); a.relative8(name)
    a.emit(0x78, 0x18, 0xFB, 0xC2, 0x10, 0xE2, 0x20)      # SEI; CLC; XCE; REP #$10; SEP #$20
    a.emit(0xA2, 0xFF, 0x1F, 0x9A)                        # LDX #$1FFF; TXS
    lda(0x8F); sta(0x2100); stz(0x4200)                   # Screen off; no interrupts yet.
    stz(0x2105); lda(0x04); sta(0x2107); stz(0x210B)      # Mode 0; BG1 map at $0400, tiles at $0000.
    lda(0x01); sta(0x212C); lda(0x80); sta(0x2115)        # BG1 on the main screen; step after the high byte.
    a.emit(0xA2, 0x00, 0x00, 0x8E, 0x16, 0x21)            # LDX #0; STX $2116
    for _ in range(4):                                    # Tile 0: alternating pixels, second plane clear.
        lda(0xAA); sta(0x2118); stz(0x2119); lda(0x55); sta(0x2118); stz(0x2119)
    a.emit(0xA2, 0x00, 0x04, 0x8E, 0x16, 0x21)            # LDX #$0400; STX $2116
    a.mark('map'); stz(0x2118); stz(0x2119); a.emit(0xCA); branch(0xD0, 'map')   # 1024 map words of tile 0.
    lda_long(0x700000); a.emit(0xC9, 0x43); branch(0xF0, 'kept')
    lda(0x43); sta_long(0x700000); lda(0x00); sta_long(0x700001)
    a.mark('kept'); stz(0x0000)
    a.emit(0x20); a.absolute('palette')
    lda(0x0F); sta(0x2100); lda(0x01); sta(0x4200)        # Screen on; automatic joypad read.
    a.mark('loop')
    a.mark('vblank'); load(0x4212); branch(0x10, 'vblank')
    a.mark('pad'); load(0x4212); a.emit(0x29, 0x01); branch(0xD0, 'pad')
    load(0x4218); a.emit(0x29, 0x80); branch(0xF0, 'released')
    load(0x0000); branch(0xD0, 'frame')
    lda(0x01); sta(0x0000)
    lda_long(0x700001); a.emit(0x49, 0x01); sta_long(0x700001)
    a.emit(0x20); a.absolute('palette'); branch(0x80, 'frame')
    a.mark('released'); stz(0x0000)
    a.mark('frame'); load(0x4212); branch(0x30, 'frame'); branch(0x80, 'loop')
    a.mark('palette'); stz(0x2121); lda(0xFF); sta(0x2122); lda(0x7F); sta(0x2122)
    lda_long(0x700001); branch(0xD0, 'green')
    lda(0x1F); sta(0x2122); lda(0x00); sta(0x2122); a.emit(0x60)
    a.mark('green'); lda(0xE0); sta(0x2122); lda(0x03); sta(0x2122); a.emit(0x60)
    a.mark('idle'); a.emit(0x40)                          # RTI
    code = a.link()
    rom = bytearray(0x8000); rom[:len(code)] = code
    rom[0x7FC0:0x7FD5] = b'ORBIT PROBE'.ljust(21)
    rom[0x7FD5:0x7FDA] = bytes([0x20, 0x02, 0x05, 0x03, 0x01])   # LoROM, battery RAM, 32 KiB, 8 KiB RAM.
    idle = a.labels['idle']
    for vector in (0x7FE4, 0x7FE6, 0x7FE8, 0x7FEA, 0x7FEE, 0x7FF4, 0x7FF8, 0x7FFA, 0x7FFE):
        struct.pack_into('<H', rom, vector, idle)
    struct.pack_into('<H', rom, 0x7FFC, 0x8000)
    struct.pack_into('<HH', rom, 0x7FDC, 0xFFFF, 0)
    checksum = sum(rom) & 0xFFFF
    struct.pack_into('<HH', rom, 0x7FDC, checksum ^ 0xFFFF, checksum)
    return bytes(rom)


def nes():
    a = Assembler(0xC000)
    def lda(value): a.emit(0xA9, value)
    def sta(address): a.emit(0x8D); a.word(address)
    def load(address): a.emit(0xAD); a.word(address)
    def branch(opcode, name): a.emit(opcode); a.relative8(name)
    a.emit(0x78, 0xD8, 0xA2, 0xFF, 0x9A)                  # SEI; CLD; LDX #$FF; TXS
    lda(0x00); sta(0x2000); sta(0x2001)
    a.emit(0x2C, 0x02, 0x20)                              # BIT $2002
    for name in ('warm1', 'warm2'):
        a.mark(name); a.emit(0x2C, 0x02, 0x20); branch(0x10, name)
    load(0x6000); a.emit(0xC9, 0x43); branch(0xF0, 'kept')
    lda(0x43); sta(0x6000); lda(0x00); sta(0x6001)
    a.mark('kept'); lda(0x00); a.emit(0x85, 0x00)         # STA $00
    lda(0x20); sta(0x2006); lda(0x00); sta(0x2006)
    a.emit(0xA2, 0x04, 0xA0, 0x00)                        # LDX #4; LDY #0
    a.mark('clear'); sta(0x2007); a.emit(0xC8); branch(0xD0, 'clear'); a.emit(0xCA); branch(0xD0, 'clear')
    a.emit(0x20); a.absolute('palette')
    lda(0x01); sta(0x4015); lda(0xBF); sta(0x4000); lda(0xFD); sta(0x4002); lda(0x00); sta(0x4003)
    lda(0x0A); sta(0x2001)                                # Background on.
    a.mark('loop')
    a.mark('vblank'); a.emit(0x2C, 0x02, 0x20); branch(0x10, 'vblank')
    lda(0x01); sta(0x4016); lda(0x00); sta(0x4016)
    load(0x4016); a.emit(0x29, 0x01); branch(0xF0, 'released')
    a.emit(0xA5, 0x00); branch(0xD0, 'loop')              # LDA $00
    lda(0x01); a.emit(0x85, 0x00)
    load(0x6001); a.emit(0x49, 0x01); sta(0x6001)
    a.emit(0x20); a.absolute('palette'); a.emit(0x4C); a.absolute('loop')
    a.mark('released'); lda(0x00); a.emit(0x85, 0x00); a.emit(0x4C); a.absolute('loop')
    a.mark('palette'); lda(0x3F); sta(0x2006); lda(0x00); sta(0x2006); lda(0x30); sta(0x2007)
    load(0x6001); branch(0xD0, 'green'); lda(0x16); sta(0x2007); a.emit(0x4C); a.absolute('aim')
    a.mark('green'); lda(0x2A); sta(0x2007)
    a.mark('aim'); lda(0x00); sta(0x2006); sta(0x2006); sta(0x2005); sta(0x2005); a.emit(0x60)
    a.mark('idle'); a.emit(0x40)
    code = a.link()
    program = bytearray(0x4000); program[:len(code)] = code
    struct.pack_into('<HHH', program, 0x3FFA, a.labels['idle'], 0xC000, a.labels['idle'])
    tiles = bytearray(0x2000); tiles[0:8] = bytes([0xAA, 0x55] * 4)
    return b'NES\x1a' + bytes([1, 1, 0x02, 0]) + bytes(8) + bytes(program) + bytes(tiles)   # NROM, battery RAM.


def genesis():
    a = Assembler(0x200, big_endian=True)
    def move_w(value, address): a.emit(0x33, 0xFC); a.word(value); a.long(address)
    def move_l(value, address): a.emit(0x23, 0xFC); a.long(value); a.long(address)
    def move_b(value, address): a.emit(0x13, 0xFC, 0x00, value); a.long(address)
    def load_b(address): a.emit(0x10, 0x39); a.long(address)        # MOVE.B (address).L, D0
    def load_w(address): a.emit(0x30, 0x39); a.long(address)
    def store_b(address): a.emit(0x13, 0xC0); a.long(address)       # MOVE.B D0, (address).L
    def short(opcode, name): a.emit(opcode); a.fixes.append(('m68k8', len(a.code), name)); a.emit(0)
    def far(opcode, name): a.emit(opcode, 0x00); a.fixes.append(('m68k16', len(a.code), name)); a.word(0)
    a.emit(0x46, 0xFC, 0x27, 0x00)                                  # MOVE #$2700, SR
    load_b(0xA10001); a.emit(0x02, 0x00, 0x00, 0x0F); short(0x67, 'unlocked')
    move_l(0x53454741, 0xA14000)                                    # "SEGA" to the lock-out register.
    a.mark('unlocked')
    for register, value in [(0, 0x04), (1, 0x04), (2, 0x30), (3, 0x00), (4, 0x07), (5, 0x78), (7, 0x00), (10, 0xFF),
                            (11, 0x00), (12, 0x81), (13, 0x3F), (15, 0x02), (16, 0x01), (17, 0x00), (18, 0x00)]:
        move_w(0x8000 | register << 8 | value, 0xC00004)
    move_l(0x40000000, 0xC00004)                                    # Tile 0: alternating pixels of colours 1 and 0.
    for _ in range(4):
        move_l(0x10101010, 0xC00000); move_l(0x01010101, 0xC00000)
    move_b(0x01, 0xA130F1)                                          # Battery memory at $200001, odd bytes.
    load_b(0x200001); a.emit(0x0C, 0x00, 0x00, 0x43); short(0x67, 'kept')
    move_b(0x43, 0x200001); move_b(0x00, 0x200003)
    a.mark('kept'); move_b(0x00, 0xFF0000)
    far(0x61, 'palette')
    for value in (0x8E, 0x0F, 0x90): move_b(value, 0xC00011)        # One PSG tone at full volume.
    move_b(0x40, 0xA10009); move_b(0x40, 0xA10003)                  # Pad: TH high selects C, B and the directions.
    move_w(0x8144, 0xC00004)                                        # Display on.
    a.mark('loop')
    a.mark('vblank'); load_w(0xC00004); a.emit(0x08, 0x00, 0x00, 0x03); short(0x67, 'vblank')
    load_b(0xA10003); a.emit(0x08, 0x00, 0x00, 0x05); short(0x66, 'released')
    a.emit(0x4A, 0x39); a.long(0xFF0000); short(0x66, 'frame')       # TST.B
    move_b(0x01, 0xFF0000)
    load_b(0x200003); a.emit(0x0A, 0x00, 0x00, 0x01); store_b(0x200003)
    far(0x61, 'palette'); short(0x60, 'frame')
    a.mark('released'); move_b(0x00, 0xFF0000)
    a.mark('frame'); load_w(0xC00004); a.emit(0x08, 0x00, 0x00, 0x03); short(0x66, 'frame')
    far(0x60, 'loop')
    a.mark('palette'); move_l(0xC0000000, 0xC00004); move_w(0x0EEE, 0xC00000)
    load_b(0x200003); a.emit(0x02, 0x00, 0x00, 0x01); short(0x66, 'green')
    move_w(0x000E, 0xC00000); a.emit(0x4E, 0x75)
    a.mark('green'); move_w(0x00E0, 0xC00000); a.emit(0x4E, 0x75)
    a.mark('idle'); a.emit(0x4E, 0x73)                              # RTE
    code = a.link()
    rom = bytearray(0x8000)
    struct.pack_into('>II', rom, 0, 0x00FFFE00, 0x200)
    for vector in range(2, 64): struct.pack_into('>I', rom, vector * 4, a.labels['idle'])
    def field(offset, text, length): rom[offset:offset + length] = text.encode('ascii').ljust(length)[:length]
    field(0x100, 'SEGA GENESIS', 16); field(0x110, '(C)ORBIT 2026', 16)
    field(0x120, 'ORBIT PROBE', 48); field(0x150, 'ORBIT PROBE', 48); field(0x180, 'GM 00000000-00', 14)
    field(0x190, 'J', 16)
    struct.pack_into('>IIII', rom, 0x1A0, 0, len(rom) - 1, 0xFF0000, 0xFFFFFF)
    rom[0x1B0:0x1B4] = b'RA\xf8\x20'; struct.pack_into('>II', rom, 0x1B4, 0x200001, 0x203FFF)
    field(0x1BC, '', 52); field(0x1F0, 'JUE', 16)
    rom[0x200:0x200 + len(code)] = code
    return bytes(rom)


def sega8(game_gear):
    a = Assembler(0x0080)
    def lda(value): a.emit(0x3E, value)
    def out(port): a.emit(0xD3, port)
    def store(address): a.emit(0x32); a.word(address)
    def load(address): a.emit(0x3A); a.word(address)
    def jump(opcode, name): a.emit(opcode); a.relative8(name)
    def control(low, high): lda(low); out(0xBF); lda(high); out(0xBF)
    lda(0x08); store(0xFFFC)                                        # Cartridge RAM at $8000.
    for register, value in [(0, 0x04), (1, 0x00), (2, 0xFF), (3, 0xFF), (4, 0xFF), (5, 0xFF), (6, 0xFB), (7, 0x00),
                            (8, 0x00), (9, 0x00), (10, 0xFF)]:
        control(value, 0x80 | register)
    control(0x00, 0x40); a.emit(0x06, 0x04)                         # VRAM $0000; LD B,4
    a.mark('tile')
    for row in (0xAA, 0x55):
        lda(row); out(0xBE); a.emit(0xAF); out(0xBE); out(0xBE); out(0xBE)
    jump(0x10, 'tile')
    control(0x00, 0x78); a.emit(0x01, 0x00, 0x07)                   # VRAM $3800; LD BC,$0700
    a.mark('map'); a.emit(0xAF); out(0xBE); a.emit(0x0B, 0x78, 0xB1); jump(0x20, 'map')
    control(0x00, 0x7F); lda(0xD0); out(0xBE)                       # No sprites.
    load(0x8000); a.emit(0xFE, 0x43); jump(0x28, 'kept')
    lda(0x43); store(0x8000); a.emit(0xAF); store(0x8001)
    a.mark('kept'); a.emit(0xAF); store(0xC000)
    a.emit(0xCD); a.absolute('palette')
    for value in (0x8E, 0x0F, 0x90): lda(value); out(0x7F)          # One PSG tone at full volume.
    control(0x40, 0x81)                                             # Display on.
    a.mark('loop')
    a.mark('vblank'); a.emit(0xDB, 0xBF, 0x07); jump(0x30, 'vblank')   # IN A,($BF); RLCA; JR NC
    a.emit(0xDB, 0xDC, 0xE6, 0x20); jump(0x20, 'released')          # Button 2 is bit 5, low when held.
    load(0xC000); a.emit(0xB7); jump(0x20, 'loop')
    lda(0x01); store(0xC000)
    load(0x8001); a.emit(0xEE, 0x01); store(0x8001)
    a.emit(0xCD); a.absolute('palette'); jump(0x18, 'loop')
    a.mark('released'); a.emit(0xAF); store(0xC000); jump(0x18, 'loop')
    a.mark('palette'); control(0x00, 0xC0)
    white, red, green = ([0xFF, 0x0F], [0x0F, 0x00], [0xF0, 0x00]) if game_gear else ([0x3F], [0x03], [0x0C])
    def colour(values):
        for value in values: lda(value); out(0xBE)
    colour(white); load(0x8001); a.emit(0xB7); jump(0x20, 'green')
    colour(red); a.emit(0xC9)
    a.mark('green'); colour(green); a.emit(0xC9)
    code = a.link()
    # 64 KiB: Genesis Plus GX gives an unlisted image the Sega mapper, and with it cartridge RAM, only above 48 KiB.
    rom = bytearray(0x10000)
    rom[0:8] = bytes([0xF3, 0xED, 0x56, 0x31, 0xF0, 0xDF, 0xC3, 0x80])   # DI; IM 1; LD SP,$DFF0; JP $0080
    rom[8] = 0x00
    rom[0x38:0x3A] = bytes([0xED, 0x4D]); rom[0x66:0x68] = bytes([0xED, 0x45])   # RETI; RETN
    rom[0x80:0x80 + len(code)] = code
    return bytes(rom)


# extension -> (builder, offset of 0x43 in battery memory, offset of the stored choice)
PROBES = {
    'sfc': (snes, 0, 1), 'nes': (nes, 0, 1), 'md': (genesis, 1, 3),
    'sms': (lambda: sega8(False), 0, 1), 'gg': (lambda: sega8(True), 0, 1),
}

if __name__ == '__main__':
    output = Path(__file__).resolve().parents[2] / '.tools/probe'
    output.mkdir(parents=True, exist_ok=True)
    for extension, (build, _, _) in PROBES.items():
        path = output / f'cartridge-probe.{extension}'
        path.write_bytes(build())
        print(path)
