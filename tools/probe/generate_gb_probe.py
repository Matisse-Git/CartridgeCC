"""Original SM83 diagnostic, generated locally only. No ROM/BIOS/logo assets.
GBX v1 footer lets mGBA identify this homebrew without a copyrighted header bitmap.
A inverts the original checkerboard and persists its choice in battery RAM.
GBC additionally exercises a halted MBC3 clock (35 seconds, incremented by A).
"""
from pathlib import Path
import struct

def build(color=False):
    code=bytearray(); labels={}; fixes=[]
    def emit(*values): code.extend(values)
    def mark(name): labels[name]=0x150+len(code)
    def jump(name, opcode=0xC3): emit(opcode,0,0); fixes.append((len(code)-2,name))
    def a(value): emit(0x3E,value)
    def store(address): emit(0xEA,address&255,address>>8)
    def load(address): emit(0xFA,address&255,address>>8)
    def register(address,value): a(value); store(address)
    emit(0xF3,0x31,0xFF,0xDF) # DI; LD SP,$DFFF
    mark('vblank'); load(0xFF44); emit(0xFE,144); jump('vblank',0xDA)
    register(0xFF40,0); register(0,10) # LCD off; enable external RAM
    load(0xA000); emit(0xFE,0x43); jump('initialized',0xCA)
    register(0xA000,0x43); register(0xA001,0)
    if color:
        for bank,value in [(8,35),(9,0),(10,0),(11,0),(12,0x40)]:
            register(0x4000,bank); register(0xA000,value)
        register(0x4000,0)
    mark('initialized')
    emit(0x21,0,0x80,0x06,16) # Tile data: original alternating pixels.
    mark('tile'); a(0xAA); emit(0x22); a(0x55); emit(0x22,0x05); jump('tile',0xC2)
    emit(0x21,0,0x98,0x01,0,4)
    mark('map'); emit(0xAF,0x22,0x0B,0x78,0xB1); jump('map',0xC2)
    for reg,value in [(0xFF42,0),(0xFF43,0),(0xFF26,0x80),(0xFF24,0x77),(0xFF25,0x11),
                      (0xFF11,0x80),(0xFF12,0xF0),(0xFF13,0),(0xFF14,0x87),(0xC000,0)]: register(reg,value)
    jump('palette',0xCD); register(0xFF40,0x91)
    mark('loop'); register(0xFF00,0x10); load(0xFF00); emit(0xE6,1); jump('released',0xC2)
    load(0xC000); emit(0xB7); jump('loop',0xC2)
    register(0xC000,1); load(0xA001); emit(0xEE,1); store(0xA001)
    if color:
        register(0x4000,8); load(0xA000); emit(0x3C); store(0xA000); register(0x4000,0)
    jump('palette',0xCD); jump('loop')
    mark('released'); register(0xC000,0); jump('loop')
    mark('palette'); load(0xA001); emit(0xB7); jump('alternate',0xC2)
    def palette(inverted):
        if color:
            register(0xFF68,0x80)
            values=[0x7FFF,0x001F,0x7C00,0] if not inverted else [0x7FFF,0x03E0,0x03FF,0]
            for value in values: register(0xFF69,value&255); register(0xFF69,value>>8)
        else: register(0xFF47,0x1B if inverted else 0xE4)
        emit(0xC9)
    palette(False); mark('alternate'); palette(True)
    for offset,name in fixes: struct.pack_into('<H',code,offset,labels[name])
    rom=bytearray(32768); rom[0x100:0x104]=bytes([0,0xC3,0x50,1])
    rom[0x134:0x13F]=b'ORBIT PROBE'; rom[0x143]=0xC0 if color else 0
    rom[0x147]=0x10 if color else 0x1B; rom[0x149]=2
    rom[0x150:0x150+len(code)]=code
    checksum=0
    for value in rom[0x134:0x14D]: checksum=(checksum-value-1)&255
    rom[0x14D]=checksum
    rom.extend((b'MBC3' if color else b'MBC5')+bytes([1,0,1 if color else 0,0])+struct.pack('>II',32768,8192)+bytes(32)+struct.pack('>III',64,1,0)+b'GBX!')
    return rom

if __name__=='__main__':
    output=Path(__file__).resolve().parents[2]/'.tools/probe'; output.mkdir(parents=True,exist_ok=True)
    for color,ext in [(False,'gb'),(True,'gbc')]:
        path=output/f'cartridge-probe.{ext}'; path.write_bytes(build(color)); print(path)
