"""Original diagnostic for the GameCube core. Generated locally only; nothing here is Nintendo's.

A bare PowerPC program in a DOL container, which Dolphin starts without any system menu. It
points the video interface at a framebuffer of its own in main memory and, every pass:

  * paints alternating white and coloured lines into that framebuffer (red first);
  * reads the controller in port 1 from the serial interface's input register;
  * on a press of a face button switches red for green;
  * while the stick is held to the right paints blue instead.

That exercises the processor's translator, the video interface path through the host's OpenGL ES
renderer, the buttons and the analog stick. It reads no disc.
"""
from pathlib import Path
import struct

ENTRY = 0x80003100
FRAMEBUFFER = 0x00100000        # Physical; the program writes it through 0x80100000.


class PowerPc:
    def __init__(self, origin):
        self.origin, self.code, self.labels, self.fixes = origin, [], {}, []

    def emit(self, word): self.code.append(word & 0xFFFFFFFF)
    def mark(self, name): self.labels[name] = len(self.code)
    def lis(self, rd, imm): self.emit(0x3C000000 | rd << 21 | imm & 0xFFFF)
    def ori(self, ra, rs, imm): self.emit(0x60000000 | rs << 21 | ra << 16 | imm & 0xFFFF)
    def li(self, rd, imm): self.emit(0x38000000 | rd << 21 | imm & 0xFFFF)
    def addi(self, rd, ra, imm): self.emit(0x38000000 | rd << 21 | ra << 16 | imm & 0xFFFF)
    def sth(self, rs, d, ra): self.emit(0xB0000000 | rs << 21 | ra << 16 | d & 0xFFFF)
    def stw(self, rs, d, ra): self.emit(0x90000000 | rs << 21 | ra << 16 | d & 0xFFFF)
    def lwz(self, rd, d, ra): self.emit(0x80000000 | rd << 21 | ra << 16 | d & 0xFFFF)
    def word(self, rd, value): self.lis(rd, value >> 16); self.ori(rd, rd, value & 0xFFFF)

    def branch(self, opcode, name):
        """opcode carries the condition; 'far' ones have a 24-bit reach, the rest 14 bits."""
        self.fixes.append((len(self.code), name, opcode)); self.emit(opcode)

    def link(self):
        for index, name, opcode in self.fixes:
            distance = (self.labels[name] - index) * 4
            mask = 0x03FFFFFC if opcode >> 26 == 18 else 0xFFFC
            self.code[index] = opcode | distance & mask
        return struct.pack(f'>{len(self.code)}I', *self.code)


def program():
    a = PowerPc(ENTRY)
    B, BL, BEQ, BNE, BLT, BDNZ = 0x48000000, 0x48000001, 0x41820000, 0x40820000, 0x41800000, 0x42000000
    # Video interface: 240 active lines per field, standard 480i blanking, both fields from one framebuffer.
    a.word(3, 0xCC002000)
    a.li(4, 0x0F06); a.sth(4, 0x00, 3)
    a.word(4, 0x00030018); a.stw(4, 0x0C, 3)
    a.word(4, 0x00020019); a.stw(4, 0x10, 3)
    a.word(4, 0x10000000 | FRAMEBUFFER >> 5); a.stw(4, 0x1C, 3); a.stw(4, 0x24, 3)
    # Serial interface: ask port 1 for its state on every field.
    a.word(5, 0xCC006400)
    a.word(4, 0x00400300); a.stw(4, 0x00, 5)
    a.word(4, 0x00F70288); a.stw(4, 0x30, 5)
    a.word(4, 0x80000000); a.stw(4, 0x38, 5)
    a.li(31, 0); a.li(30, 0); a.li(29, 0)                # choice, held, stick

    a.mark('loop')
    a.branch(BL, 'paint')
    a.lwz(4, 0x04, 5)                                    # Buttons, stick X, stick Y.
    a.emit(0x74000000 | 4 << 21 | 6 << 16 | 0x0F00)      # ANDIS. r6,r4: A, B, X or Y
    a.branch(BEQ, 'released')
    a.emit(0x2C000000 | 30 << 16); a.branch(BNE, 'stick')    # CMPWI r30,0: already held?
    a.li(30, 1); a.emit(0x68000000 | 31 << 21 | 31 << 16 | 1)  # XORI r31,r31,1
    a.branch(B, 'stick')
    a.mark('released'); a.li(30, 0)
    a.mark('stick')
    a.emit(0x54000000 | 4 << 21 | 6 << 16 | 24 << 11 | 24 << 6 | 31 << 1)    # RLWINM r6,r4,24,24,31: stick X, 0x80 at rest
    a.li(29, 0)
    a.emit(0x28000000 | 6 << 16 | 0xC0); a.branch(BLT, 'loop')               # CMPLWI r6,0xC0
    a.li(29, 1)
    a.branch(B, 'loop')

    a.mark('paint')
    a.word(7, 0x80000000 | FRAMEBUFFER)
    a.word(8, 0xEB80EB80)                                # White, as two luma samples around shared chroma.
    a.word(9, 0x515A51F0)                                # Red.
    a.emit(0x2C000000 | 31 << 16); a.branch(BEQ, 'chosen')
    a.word(9, 0x91369122)                                # Green.
    a.mark('chosen')
    a.emit(0x2C000000 | 29 << 16); a.branch(BEQ, 'rows')
    a.word(9, 0x29F0296E)                                # Blue.
    a.mark('rows'); a.li(10, 240)
    a.mark('pair')
    for colour, name in ((8, 'white'), (9, 'colour')):
        a.li(11, 320); a.emit(0x7C0903A6 | 11 << 21)     # MTCTR r11
        a.mark(name); a.stw(colour, 0, 7); a.addi(7, 7, 4); a.branch(BDNZ, name)
    a.addi(10, 10, -1); a.emit(0x2C000000 | 10 << 16); a.branch(BNE, 'pair')
    a.emit(0x4E800020)                                   # BLR
    return a.link()


def dol():
    code = program()
    code += bytes(-len(code) % 32)
    header = bytearray(0x100)
    struct.pack_into('>I', header, 0x00, 0x100)          # First text section: file offset...
    struct.pack_into('>I', header, 0x48, ENTRY)          # ...load address...
    struct.pack_into('>I', header, 0x90, len(code))      # ...size.
    struct.pack_into('>I', header, 0xE0, ENTRY)          # Entry point.
    return bytes(header) + code


if __name__ == '__main__':
    output = Path(__file__).resolve().parents[2] / '.tools/probe'
    output.mkdir(parents=True, exist_ok=True)
    path = output / 'cartridge-probe.dol'
    path.write_bytes(dol()); print(path)
