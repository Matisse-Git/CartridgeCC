"""Original diagnostic for the PlayStation 2 core. Generated locally only; nothing here is Sony's.

A bare program for the console's main processor in an ELF container. The engine starts it the way
the console starts a game, through the console's own BIOS, which is the owner's and is not here: the
BIOS's loader reads this file and its GsSetCrt call is the BIOS's. It sets a 640x448 display, and on
every vertical blank sends the
graphics chip one small packet through the same DMA channel a game uses: draw state, then a
single screen-sized sprite in red. That exercises the processor's translator, the DMA and
graphics interface path and the host's OpenGL ES renderer. It reads no controller: a pad on this
console is reached through the second processor's system modules, which is far more than a
hand-assembled test should carry.

The same program is also written into a small disc image, a plain ISO 9660 volume holding the
SYSTEM.CNF a PlayStation 2 disc names its program in, so that the core's disc path is run too:
mounting the image, reading its directory, and starting what the disc says to start.
"""
from pathlib import Path
import struct

ZERO, V1, A0, A1, A2, T0, T1, T2, SP = 0, 3, 4, 5, 6, 8, 9, 10, 29
ENTRY, PACKET = 0x00100000, 0x00100400


def program():
    code, labels, fixes = [], {}, []
    def emit(word): code.append(word & 0xFFFFFFFF)
    def lui(rt, imm): emit(0x3C000000 | rt << 16 | imm & 0xFFFF)
    def ori(rt, rs, imm): emit(0x34000000 | rs << 21 | rt << 16 | imm & 0xFFFF)
    def addiu(rt, rs, imm): emit(0x24000000 | rs << 21 | rt << 16 | imm & 0xFFFF)
    def andi(rt, rs, imm): emit(0x30000000 | rs << 21 | rt << 16 | imm & 0xFFFF)
    def sd(rt, offset, base): emit(0xFC000000 | base << 21 | rt << 16 | offset & 0xFFFF)
    def ld(rt, offset, base): emit(0xDC000000 | base << 21 | rt << 16 | offset & 0xFFFF)
    def sw(rt, offset, base): emit(0xAC000000 | base << 21 | rt << 16 | offset & 0xFFFF)
    def lw(rt, offset, base): emit(0x8C000000 | base << 21 | rt << 16 | offset & 0xFFFF)
    def mark(name): labels[name] = len(code)
    def branch(opcode, rs, rt, name): fixes.append((len(code), name)); emit(opcode | rs << 21 | rt << 16); emit(0)   # delay slot: NOP
    lui(SP, 0x0020)
    addiu(A0, ZERO, 1); addiu(A1, ZERO, 2); addiu(A2, ZERO, 1); addiu(V1, ZERO, 2); emit(0x0000000C)   # GsSetCrt(interlaced, NTSC, frame)
    lui(T0, 0x1000); ori(T0, T0, 0xE000); addiu(T1, ZERO, 1); sw(T1, 0, T0)       # DMA on.
    lui(T0, 0x1200)
    addiu(T1, ZERO, 0x200); sd(T1, 0x1000, T0)                                     # Reset the graphics chip...
    addiu(T1, ZERO, 1); sd(T1, 0x0000, T0)                                         # ...read circuit 1 on...
    addiu(T1, ZERO, 0x1400); sd(T1, 0x0070, T0)                                    # ...from a 640-wide 32-bit buffer at 0...
    lui(T2, PACKET >> 16); ld(T1, (PACKET & 0xFFFF) - 8, T2); sd(T1, 0x0080, T0)   # ...shown as 640x448.
    mark('frame')
    lui(T0, 0x1200); addiu(T1, ZERO, 8); sd(T1, 0x1000, T0)                        # Acknowledge the last vertical blank.
    mark('blank'); ld(T1, 0x1000, T0); andi(T1, T1, 8); branch(0x10000000, T1, ZERO, 'blank')
    lui(T0, 0x1000); ori(T0, T0, 0xA000)                                           # Channel 2: the graphics interface.
    lui(T1, PACKET >> 16); ori(T1, T1, PACKET & 0xFFFF); sw(T1, 0x10, T0)
    addiu(T1, ZERO, 11); sw(T1, 0x20, T0)
    addiu(T1, ZERO, 0x101); sw(T1, 0x00, T0)
    mark('sent'); lw(T1, 0, T0); andi(T1, T1, 0x100); branch(0x14000000, T1, ZERO, 'sent')
    emit(0x08000000 | (ENTRY + labels['frame'] * 4) >> 2); emit(0)                 # J frame; NOP
    for index, name in fixes:
        code[index] |= (labels[name] - (index + 1)) & 0xFFFF
    return struct.pack(f'<{len(code)}I', *code)


def packet():
    def register(address, value): return struct.pack('<QQ', value, address)
    display = 652 | 26 << 12 | 3 << 23 | 2559 << 32 | 447 << 44
    return struct.pack('<Q', display) + b''.join([
        struct.pack('<QQ', 10 | 1 << 15 | 1 << 60, 0xE),                 # Ten register writes, end of packet.
        register(0x1A, 1),                                               # Primitive attributes come from PRIM.
        register(0x4C, 10 << 16),                                        # Frame buffer at 0, 640 wide, 32-bit.
        register(0x4E, 140 | 1 << 32),                                   # Depth buffer never written.
        register(0x18, 0),                                               # No drawing offset.
        register(0x40, 639 << 16 | 447 << 48),                           # Scissor: the whole frame.
        register(0x47, 1 << 16 | 1 << 17),                               # Depth test always passes.
        register(0x00, 6),                                               # A sprite...
        register(0x01, 0xFF | 0x80 << 24 | 0x3F800000 << 32),            # ...in red...
        register(0x05, 0), register(0x05, 640 * 16 | (448 * 16) << 16),  # ...from corner to corner.
    ])


def elf():
    code = program()
    assert len(code) <= PACKET - 8 - ENTRY
    image = code.ljust(PACKET - 8 - ENTRY, b'\0') + packet()
    header = struct.pack('<16sHHIIIIIHHHHHH', b'\x7fELF\x01\x01\x01' + bytes(9), 2, 8, 1, ENTRY, 0x34, 0x54, 0x20924001,
                         52, 32, 1, 40, 1, 0)
    segment = struct.pack('<IIIIIIII', 1, 0x100, ENTRY, ENTRY, len(image), len(image), 7, 16)
    return (header + segment + bytes(40)).ljust(0x100, b'\0') + image


def iso(program):
    """Sixty-four 2048-byte sectors: the volume descriptor, a path table, the root directory and two files."""
    def both(value, short=False):
        return struct.pack('<H' if short else '<I', value) + struct.pack('>H' if short else '>I', value)
    def record(name, extent, size, directory=False):
        name = name if isinstance(name, bytes) else name.encode('ascii')
        entry = bytearray(33 + len(name) + (len(name) % 2 == 0)); entry[0] = len(entry)
        entry[2:10] = both(extent); entry[10:18] = both(size); entry[18:25] = bytes([126, 10, 11, 12, 0, 0, 0])
        entry[25] = 2 if directory else 0; entry[28:32] = both(1, True); entry[32] = len(name); entry[33:33 + len(name)] = name
        return entry
    SECTOR, TABLE, OTHER_TABLE, ROOT, CONFIG, PROGRAM = 2048, 18, 19, 22, 23, 24
    data = bytearray(64 * SECTOR)
    volume = bytearray(SECTOR); volume[:7] = b'\x01CD001\x01'
    volume[8:40] = b'PLAYSTATION'.ljust(32); volume[40:72] = b'CARTRIDGE_PROBE'.ljust(32); volume[80:88] = both(64)
    volume[120:124] = both(1, True); volume[124:128] = both(1, True); volume[128:132] = both(SECTOR, True)
    volume[132:140] = both(10); volume[140:144] = struct.pack('<I', TABLE); volume[148:152] = struct.pack('>I', OTHER_TABLE)
    volume[156:190] = record(b'\0', ROOT, SECTOR, True); volume[881] = 1
    data[16 * SECTOR:17 * SECTOR] = volume
    data[17 * SECTOR:17 * SECTOR + 7] = b'\xffCD001\x01'
    # One path-table record each way: the root directory, whose parent is itself.
    data[TABLE * SECTOR:TABLE * SECTOR + 10] = struct.pack('<BBIH', 1, 0, ROOT, 1) + b'\0\0'
    data[OTHER_TABLE * SECTOR:OTHER_TABLE * SECTOR + 10] = struct.pack('>BBIH', 1, 0, ROOT, 1) + b'\0\0'
    config = b'BOOT2 = cdrom0:\\PROBE.ELF;1\r\nVER = 1.00\r\nVMODE = NTSC\r\n'
    records = record(b'\0', ROOT, SECTOR, True) + record(b'\1', ROOT, SECTOR, True) + \
        record('SYSTEM.CNF;1', CONFIG, len(config)) + record('PROBE.ELF;1', PROGRAM, len(program))
    data[ROOT * SECTOR:ROOT * SECTOR + len(records)] = records
    data[CONFIG * SECTOR:CONFIG * SECTOR + len(config)] = config
    data[PROGRAM * SECTOR:PROGRAM * SECTOR + len(program)] = program
    return bytes(data)


if __name__ == '__main__':
    output = Path(__file__).resolve().parents[2] / '.tools/probe'
    output.mkdir(parents=True, exist_ok=True)
    path = output / 'cartridge-probe-ps2.elf'
    path.write_bytes(elf()); print(path)
    path = output / 'cartridge-probe-ps2.iso'
    path.write_bytes(iso(elf())); print(path)
