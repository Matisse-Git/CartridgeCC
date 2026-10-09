/* Original Project Cartridge diagnostic: no Nintendo logo, BIOS, or commercial data.
 * ARM7TDMI cartridge entry. Mode 3 framebuffer, A sets green, B resets gray; square-wave tone.
 * This fixture is for emulators using HLE BIOS; it is not bootable on a retail GBA.
 */
.syntax unified
.cpu arm7tdmi
.arm
.section .text
.global _start
_start:
    b entry
    .space 0x9c, 0
    .ascii "CARTRIDGE M0"
    .ascii "CM00"
    .ascii "00"
    .byte 0x96
    .space 0x0d, 0
entry:
    mov r0, #0x04000000
    ldr r1, =0x0403
    strh r1, [r0]
    mov r1, #0x80
    strh r1, [r0, #0x84]
    ldr r1, =0x1177
    strh r1, [r0, #0x80]
    ldr r1, =0xF080
    strh r1, [r0, #0x62]
    ldr r1, =0x8700
    strh r1, [r0, #0x64]
    ldr r3, =0x2D6B
frame:
    add r6, r0, #0x100
    ldrh r2, [r6, #0x30]
    tst r2, #1
    ldreq r3, =0x03E0
    tst r2, #2
    ldreq r3, =0x2D6B
    mov r4, #0x06000000
    ldr r5, =38400
fill:
    strh r3, [r4], #2
    subs r5, r5, #1
    bne fill
    b frame
    .ltorg
