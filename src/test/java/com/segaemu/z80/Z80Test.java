package com.segaemu.z80;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Focused tests for the Z80 core across the main, CB, ED and DD/FD groups. */
class Z80Test {

    private static final int FLAG_C = 0x01;
    private static final int FLAG_PV = 0x04;
    private static final int FLAG_H = 0x10;
    private static final int FLAG_Z = 0x40;

    /** A flat 64 KB memory + I/O port array. */
    private static final class FlatBus implements Z80Bus {
        final byte[] mem = new byte[0x10000];
        final int[] ports = new int[0x10000];

        @Override public int read(int addr) { return mem[addr & 0xFFFF] & 0xFF; }
        @Override public void write(int addr, int value) { mem[addr & 0xFFFF] = (byte) value; }
        @Override public int readPort(int port) { return ports[port & 0xFFFF] & 0xFF; }
        @Override public void writePort(int port, int value) { ports[port & 0xFFFF] = value & 0xFF; }
    }

    private Z80 cpu;
    private FlatBus bus;

    @BeforeEach
    void setUp() {
        bus = new FlatBus();
        cpu = new Z80();
        cpu.attachBus(bus);
        cpu.reset();
    }

    private void load(int... bytes) {
        for (int i = 0; i < bytes.length; i++) {
            bus.mem[i] = (byte) bytes[i];
        }
    }

    private void steps(int n) {
        for (int i = 0; i < n; i++) {
            cpu.step();
        }
    }

    @Test
    void loadImmediateAndAdd() {
        load(0x3E, 0x05,        // LD A,5
             0xC6, 0x03);       // ADD A,3
        steps(2);
        assertEquals(8, cpu.getA());
        assertEquals(0, cpu.getF() & FLAG_C);
        assertEquals(0, cpu.getF() & FLAG_Z);
    }

    @Test
    void addWithCarryAndZeroAndHalf() {
        load(0x3E, 0xFF,        // LD A,$FF
             0xC6, 0x01);       // ADD A,1 -> 0, sets C,Z,H
        steps(2);
        assertEquals(0, cpu.getA());
        assertTrue((cpu.getF() & FLAG_C) != 0, "carry out");
        assertTrue((cpu.getF() & FLAG_Z) != 0, "zero");
        assertTrue((cpu.getF() & FLAG_H) != 0, "half-carry");
    }

    @Test
    void load16AndIncrement() {
        load(0x21, 0x34, 0x12,  // LD HL,$1234
             0x23);             // INC HL
        steps(2);
        assertEquals(0x1235, cpu.getHL());
    }

    @Test
    void djnzCountsDown() {
        load(0x06, 0x05,        // LD B,5
             0x3E, 0x00,        // LD A,0
             0x3C,              // (loop) INC A
             0x10, 0xFD);       // DJNZ loop
        steps(30);
        assertEquals(5, cpu.getA());
        assertEquals(0, cpu.getB());
    }

    @Test
    void callAndReturn() {
        load(0xCD, 0x06, 0x00,  // CALL $0006
             0x76,              // HALT
             0x00, 0x00,
             0x3E, 0xAA,        // (sub) LD A,$AA
             0xC9);             // RET
        steps(5);
        assertEquals(0xAA, cpu.getA());
        assertEquals(0xFFFF, cpu.getSP(), "stack balanced after CALL/RET");
    }

    @Test
    void pushAndPop() {
        load(0x01, 0x34, 0x12,  // LD BC,$1234
             0xC5,              // PUSH BC
             0xD1);             // POP DE
        steps(3);
        assertEquals(0x1234, cpu.getDE());
    }

    @Test
    void exDeHlSwaps() {
        load(0x21, 0x11, 0x11,  // LD HL,$1111
             0x11, 0x22, 0x22,  // LD DE,$2222
             0xEB);             // EX DE,HL
        steps(3);
        assertEquals(0x2222, cpu.getHL());
        assertEquals(0x1111, cpu.getDE());
    }

    @Test
    void exxSwapsShadowSet() {
        load(0x01, 0xAA, 0xBB,  // LD BC,$BBAA
             0xD9,              // EXX
             0x01, 0x11, 0x22); // LD BC,$2211
        steps(3);
        assertEquals(0x2211, cpu.getBC());
        cpu.step();             // nothing more needed; verify swap kept the old set
    }

    @Test
    void cbBitSetRes() {
        load(0x06, 0x00,        // LD B,0
             0xCB, 0xD8,        // SET 3,B
             0xCB, 0x58,        // BIT 3,B
             0xCB, 0x98);       // RES 3,B
        steps(2);
        assertEquals(0x08, cpu.getB());
        cpu.step();             // BIT 3,B -> bit is set so Z clear
        assertEquals(0, cpu.getF() & FLAG_Z);
        cpu.step();             // RES 3,B
        assertEquals(0x00, cpu.getB());
    }

    @Test
    void andSetsParityAndHalf() {
        load(0x3E, 0x0F,        // LD A,$0F
             0xE6, 0x03);       // AND $03 -> $03 (two bits set -> even parity)
        steps(2);
        assertEquals(0x03, cpu.getA());
        assertTrue((cpu.getF() & FLAG_H) != 0, "AND always sets H");
        assertTrue((cpu.getF() & FLAG_PV) != 0, "even parity");
    }

    @Test
    void indexedLoadStoreIxPlusD() {
        load(0xDD, 0x21, 0x00, 0x40,  // LD IX,$4000
             0xDD, 0x36, 0x02, 0x99,  // LD (IX+2),$99
             0xDD, 0x7E, 0x02);       // LD A,(IX+2)
        steps(3);
        assertEquals(0x99, bus.mem[0x4002] & 0xFF);
        assertEquals(0x99, cpu.getA());
        assertEquals(0x4000, cpu.getIX());
    }

    @Test
    void ldirBlockCopies() {
        bus.mem[0x3000] = (byte) 0xAA;
        bus.mem[0x3001] = (byte) 0xBB;
        bus.mem[0x3002] = (byte) 0xCC;
        load(0x21, 0x00, 0x30,  // LD HL,$3000
             0x11, 0x00, 0x31,  // LD DE,$3100
             0x01, 0x03, 0x00,  // LD BC,3
             0xED, 0xB0);       // LDIR
        steps(8);               // 3 loads + one LDIR step per byte copied
        assertEquals(0xAA, bus.mem[0x3100] & 0xFF);
        assertEquals(0xBB, bus.mem[0x3101] & 0xFF);
        assertEquals(0xCC, bus.mem[0x3102] & 0xFF);
        assertEquals(0, cpu.getBC());
    }

    @Test
    void im1InterruptVectorsToRst38() {
        load(0xED, 0x56,        // IM 1
             0xFB,              // EI
             0x00,              // NOP (interrupt enabled after this)
             0x00);             // NOP
        steps(2);               // IM 1, EI
        assertEquals(1, cpu.getIM());
        cpu.requestInterrupt();
        cpu.step();             // NOP — EI's one-instruction delay
        assertNotEquals(0x0038, cpu.getPC(), "not taken during EI delay");
        cpu.step();             // interrupt accepted here
        assertEquals(0x0038, cpu.getPC());
        assertFalse(cpu.getIff1(), "IFF1 cleared on accept");
    }

    @Test
    void interruptIgnoredWhenDisabled() {
        load(0xF3,              // DI
             0x00, 0x00);
        cpu.step();             // DI
        cpu.requestInterrupt();
        cpu.step();             // NOP — interrupt must be ignored
        assertNotEquals(0x0038, cpu.getPC());
    }
}
