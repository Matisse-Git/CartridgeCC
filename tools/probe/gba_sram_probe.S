/* Original battery-save diagnostic. No proprietary logo, BIOS or game data.
 * Latches SRAM byte zero ONCE at boot. A saves green (1); B saves gray (0).
 * Late restoration cannot change the boot latch: this detects the startup-order bug.
 * Uses the emulator HLE BIOS, not a retail-console boot header.
 */
.syntax unified
.cpu arm7tdmi
.arm
.section .text
.global _start
_start:
    b entry
    .space 0x9c, 0
    .ascii "BATTERY TEST"
    .ascii "CS00"
    .ascii "00"
    .byte 0x96
    .space 0x0d, 0
entry:
    mov r0, #0x04000000
    ldr r1, =0x0403
    strh r1, [r0]
    mov r7, #0x0e000000
    ldrb r2, [r7]
    cmp r2, #1
    ldreq r3, =0x03e0
    ldrne r3, =0x2d6b
frame:
    add r6, r0, #0x100
    ldrh r2, [r6, #0x30]
    tst r2, #1
    ldreq r3, =0x03e0
    moveq r1, #1
    strbeq r1, [r7]
    tst r2, #2
    ldreq r3, =0x2d6b
    moveq r1, #0
    strbeq r1, [r7]
    mov r4, #0x06000000
    ldr r5, =38400
fill:
    strh r3, [r4], #2
    subs r5, r5, #1
    bne fill
    b frame
    .ltorg
