"""Original MIPS PS-X EXE + ISO9660/CUE diagnostic. No Sony SDK, logo, BIOS or adopted game.
Any circle/cross press toggles a solid GPU rectangle. BIOS PAD calls work with PCSX's HLE.
Output is ignored .tools content; never bundled in the release APK.
"""
from pathlib import Path
import struct
import hashlib

class Mips:
    def __init__(self): self.words=[]; self.labels={}; self.fixups=[]
    def emit(self,w): self.words.append(w)
    def i(self,op,rt,rs,imm): self.emit(op<<26|rs<<21|rt<<16|(imm&65535))
    def li(self,reg,value): self.i(15,reg,0,value>>16); self.i(13,reg,reg,value&65535)
    def label(self,name): self.labels[name]=len(self.words)
    def branch(self,op,rs,rt,label): self.fixups.append((len(self.words),label)); self.i(op,rt,rs,0); self.emit(0)
    def jump(self,label): self.branch(4,0,0,label)
    def call_bios(self,function):
        self.li(9,function); self.li(8,0xb0); self.emit(8<<21|31<<11|9); self.emit(0)
    def bytes(self):
        for at,label in self.fixups: self.words[at]|=(self.labels[label]-at-1)&65535
        return struct.pack('<'+'I'*len(self.words),*self.words)

def executable():
    m=Mips();m.li(29,0x801fff00)
    # Read a private test data sector using the emulated PS1 BIOS, before controller setup.
    m.li(4,0);m.li(5,64);m.li(6,0x80018080);m.call_bios(0x4f)
    m.li(17,0x80018000);m.i(35,10,17,0x80);m.emit(0);m.i(12,10,10,1);m.i(43,10,17,0x80);m.i(43,0,17,0x84)
    m.li(4,0x80018000);m.li(5,34);m.li(6,0x80018040);m.li(7,34);m.call_bios(0x12);m.call_bios(0x13)
    m.li(16,0x1f801810);m.li(17,0x80018000)
    # Original quiet looping ADPCM waveform played by the emulated SPU, not Android test audio.
    m.li(18,0x1f801c00)
    for offset,value in [(0x1aa,0xc020),(0x1ac,4),(0x1a6,0x200)]:m.li(8,value);m.i(41,8,18,offset)
    samples=[0,3,6,7,6,3,0,-3,-6,-7,-6,-3,0,3,6,7,6,3,0,-3,-6,-7,-6,-3,0,3,6,0]
    block=bytes([2,3])+bytes((samples[i]&15)|((samples[i+1]&15)<<4) for i in range(0,28,2))
    for value in struct.unpack('<8H',block):m.li(8,value);m.i(41,8,18,0x1a8)
    for offset,value in [(0,0x2000),(2,0x2000),(4,0x0800),(6,0x200),(8,0x00ff),(10,0),(14,0x200),(0x180,0x2000),(0x182,0x2000),(0x1aa,0xc000),(0x188,1)]:m.li(8,value);m.i(41,8,18,offset)
    for command in [0,0x08000001,0x05000000,0x06c60260,0x07042018,0x03000000]:
        m.li(8,command);m.i(43,8,16,4)
    m.label('loop')
    m.i(36,8,17,1);m.emit(0);m.li(9,0x41);m.branch(5,8,9,'draw')
    m.i(37,8,17,2);m.emit(0);m.i(12,8,8,0x6000);m.li(9,0x6000);m.branch(4,8,9,'released')
    m.i(35,10,17,0x84);m.emit(0);m.branch(5,10,0,'draw')
    m.i(35,10,17,0x80);m.emit(0);m.i(14,10,10,1);m.i(43,10,17,0x80)
    m.li(4,0);m.li(5,64);m.li(6,0x80018080);m.call_bios(0x4e)
    m.li(10,1);m.i(43,10,17,0x84);m.jump('draw')
    m.label('released');m.i(43,0,17,0x84)
    m.label('draw')
    for command in [0x02252220,0,0x00f00140]: m.li(8,command);m.i(43,8,16,0)
    m.i(35,10,17,0x80);m.emit(0);m.branch(5,10,0,'blue')
    m.li(8,0x023040b0);m.jump('color')
    m.label('blue');m.li(8,0x02b07830)
    m.label('color');m.i(43,8,16,0)
    for command in [0x00400040,0x007000c0]:m.li(8,command);m.i(43,8,16,0)
    m.li(11,50000);m.label('delay');m.i(9,11,11,-1);m.branch(5,11,0,'delay');m.jump('loop')
    code=m.bytes();code+=b'\0'*(-len(code)%2048)
    header=bytearray(2048);header[:8]=b'PS-X EXE'
    for at,value in [(0x10,0x80010000),(0x18,0x80010000),(0x1c,len(code)),(0x30,0x801fff00)]:struct.pack_into('<I',header,at,value)
    header[0x4c:0x4c+24]=b'Original Cartridge probe'
    return bytes(header)+code

def both(value,short=False): return struct.pack('<H' if short else '<I',value)+struct.pack('>H' if short else '>I',value)
def record(name,extent,size,directory=False):
    name=name if isinstance(name,bytes) else name.encode('ascii')
    b=bytearray(33+len(name)+(len(name)%2==0));b[0]=len(b)
    b[2:10]=both(extent);b[10:18]=both(size);b[18:25]=bytes([126,10,10,12,0,0,0]);b[25]=2 if directory else 0
    b[28:32]=both(1,True);b[32]=len(name);b[33:33+len(name)]=name;return b
def iso(exe):
    data=bytearray(64*2048);pvd=bytearray(2048);pvd[:7]=b'\x01CD001\x01'
    pvd[8:40]=b'CARTRIDGE'.ljust(32,b' ');pvd[40:72]=b'ORIGINAL_PROBE'.ljust(32,b' ');pvd[80:88]=both(64)
    pvd[120:124]=both(1,True);pvd[124:128]=both(1,True);pvd[128:132]=both(2048,True)
    pvd[156:190]=record(b'\0',22,2048,True);pvd[881]=1;data[16*2048:17*2048]=pvd
    data[17*2048:17*2048+7]=b'\xffCD001\x01'
    cnf=b'BOOT = cdrom:\\PROBE.EXE;1\r\nTCB = 4\r\nEVENT = 10\r\nSTACK = 801fff00\r\n'
    records=record(b'\0',22,2048,True)+record(b'\1',22,2048,True)+record('SYSTEM.CNF;1',23,len(cnf))+record('PROBE.EXE;1',24,len(exe))
    data[22*2048:22*2048+len(records)]=records;data[23*2048:23*2048+len(cnf)]=cnf;data[24*2048:24*2048+len(exe)]=exe
    return data

def chd(raw):
    # CHDv5 uncompressed layout, as specified by the pinned libchdr chd.h.
    units=b''.join(raw[n:n+2352]+bytes(96) for n in range(0,len(raw),2352))
    hunk=2448*4;count=len(units)//hunk
    metadata=b'TRACK:1 TYPE:MODE2_RAW SUBTYPE:NONE FRAMES:64 PREGAP:0 PGTYPE:MODE2_RAW PGSUB:NONE POSTGAP:0\0'
    data=bytearray(hunk);data[:8]=b'MComprHD'
    struct.pack_into('>II',data,8,124,5);struct.pack_into('>QQQII',data,32,len(units),124,124+count*4,hunk,2448)
    data[64:84]=hashlib.sha1(units).digest()
    for i in range(count):struct.pack_into('>I',data,124+i*4,1+i)
    at=124+count*4;struct.pack_into('>IIQ',data,at,0x43485432,len(metadata),0);data[at+16:at+16+len(metadata)]=metadata
    return data+units

if __name__=='__main__':
    out=Path(__file__).resolve().parents[2]/'.tools/probe/ps1';out.mkdir(parents=True,exist_ok=True)
    exe=executable();disc=iso(exe);(out/'probe.exe').write_bytes(exe);(out/'probe.iso').write_bytes(disc)
    raw=bytearray()
    for n in range(64):
        # MODE2 Form1 raw sectors; test image has no CD audio or ECC claims.
        h=bytearray(24);h[0:12]=b'\0'+b'\xff'*10+b'\0';h[15]=2
        raw+=h+disc[n*2048:(n+1)*2048]+bytes(280)
    (out/'probe.bin').write_bytes(raw)
    (out/'probe.chd').write_bytes(chd(raw))
    (out/'probe.cue').write_text('FILE "probe.bin" BINARY\n  TRACK 01 MODE2/2352\n    INDEX 01 00:00:00\n',encoding='utf-8')
    print('Original PS1 diagnostic EXE, ISO and CUE/BIN generated')
