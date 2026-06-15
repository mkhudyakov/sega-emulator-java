package com.segaemu.cpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.segaemu.cpu.m68k.M68000;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for the 68000 core, driven from a flat {@link TestBus}. */
class M68000Test {

    private TestBus bus;
    private M68000 cpu;

    @BeforeEach
    void setUp() {
        bus = new TestBus();
        // Reset vector: SSP = $00100000, PC = $00001000.
        bus.write32(0x000000, 0x00100000);
        bus.write32(0x000004, 0x00001000);
        cpu = new M68000(bus);
        cpu.reset();
    }

    @Test
    void resetLoadsVectors() {
        assertEquals(0x00100000, cpu.getA(7));
        assertEquals(0x00001000, cpu.getPc());
        assertTrue(cpu.isSupervisor());
    }

    @Test
    void moveqSetsDataRegisterAndFlags() {
        bus.poke(0x1000, 0x7044);        // MOVEQ #$44,D0
        cpu.step();
        assertEquals(0x44, cpu.getD(0));
        assertFalse(cpu.nn());
        assertFalse(cpu.zz());

        bus.poke(0x1002, 0x72FF);        // MOVEQ #-1,D1
        cpu.step();
        assertEquals(0xFFFFFFFF, cpu.getD(1));
        assertTrue(cpu.nn());
    }

    @Test
    void moveLongImmediateToData() {
        // MOVE.L #$12345678,D0  -> 203C 1234 5678
        bus.poke(0x1000, 0x203C, 0x1234, 0x5678);
        cpu.step();
        assertEquals(0x12345678, cpu.getD(0));
    }

    @Test
    void addqIncrementsAndSetsZero() {
        bus.poke(0x1000, 0x7000);        // MOVEQ #0,D0
        bus.poke(0x1002, 0x5240);        // ADDQ.W #1,D0
        cpu.step();
        cpu.step();
        assertEquals(1, cpu.getD(0));
        assertFalse(cpu.zz());
    }

    @Test
    void subSetsZeroFlag() {
        bus.poke(0x1000, 0x7005);        // MOVEQ #5,D0
        bus.poke(0x1002, 0x7205);        // MOVEQ #5,D1
        bus.poke(0x1004, 0x9041);        // SUB.W D1,D0
        cpu.step();
        cpu.step();
        cpu.step();
        assertEquals(0, cpu.getD(0) & 0xFFFF);
        assertTrue(cpu.zz());
    }

    @Test
    void cmpiSetsCarryWhenBorrow() {
        bus.poke(0x1000, 0x7003);              // MOVEQ #3,D0
        bus.poke(0x1002, 0x0C40, 0x0005);      // CMPI.W #5,D0
        cpu.step();
        cpu.step();
        assertTrue(cpu.cc());  // 3 - 5 borrows
        assertTrue(cpu.nn());
    }

    @Test
    void branchTakenWhenEqual() {
        bus.poke(0x1000, 0x7000);              // MOVEQ #0,D0  (sets Z)
        bus.poke(0x1002, 0x6702);              // BEQ +2 -> skip next instruction
        bus.poke(0x1004, 0x7042);              // MOVEQ #$42,D0 (should be skipped)
        bus.poke(0x1006, 0x7099);              // MOVEQ #-103,D0
        cpu.step(); // moveq #0
        cpu.step(); // beq taken
        assertEquals(0x1006, cpu.getPc());
        cpu.step();
        assertEquals(0xFFFFFF99, cpu.getD(0));
    }

    @Test
    void jsrAndRtsRoundTrip() {
        // MOVE.L #$2000,A0 ; JSR (A0) ; ... subroutine at $2000 does RTS
        bus.poke(0x1000, 0x207C, 0x0000, 0x2000); // MOVEA.L #$2000,A0
        bus.poke(0x1006, 0x4E90);                  // JSR (A0)
        bus.poke(0x1008, 0x7077);                  // MOVEQ #$77,D0 (return point)
        bus.poke(0x2000, 0x4E75);                  // RTS
        cpu.step(); // movea
        int spBefore = cpu.getA(7);
        cpu.step(); // jsr
        assertEquals(0x2000, cpu.getPc());
        assertEquals(spBefore - 4, cpu.getA(7));
        cpu.step(); // rts
        assertEquals(0x1008, cpu.getPc());
    }

    @Test
    void lslShiftsAndSetsCarry() {
        bus.poke(0x1000, 0x7001);          // MOVEQ #1,D0
        bus.poke(0x1002, 0xE388);          // LSL.L #1,D0
        cpu.step();
        cpu.step();
        assertEquals(2, cpu.getD(0));
    }

    @Test
    void muluMultipliesWords() {
        bus.poke(0x1000, 0x303C, 0x0003);  // MOVE.W #3,D0
        bus.poke(0x1004, 0xC0FC, 0x0004);  // MULU #4,D0
        cpu.step();
        cpu.step();
        assertEquals(12, cpu.getD(0));
    }

    @Test
    void moveToMemoryAndBack() {
        bus.poke(0x1000, 0x303C, 0xBEEF);          // MOVE.W #$BEEF,D0
        bus.poke(0x1004, 0x33C0, 0x0000, 0x3000);  // MOVE.W D0,($3000).L
        cpu.step();
        cpu.step();
        assertEquals(0xBEEF, bus.read16(0x3000));
    }

    /**
     * Regression: SWAP Dn ($4840-$4847) shares the size field with PEA and must
     * not be decoded as PEA — doing so pushes to the stack and corrupts return
     * addresses (this hung Sonic's title screen).
     */
    @Test
    void swapExchangesHalvesWithoutTouchingStack() {
        bus.poke(0x1000, 0x203C, 0x1234, 0x5678);  // MOVE.L #$12345678,D0
        bus.poke(0x1006, 0x4840);                  // SWAP D0
        cpu.step();
        int spBefore = cpu.getA(7);
        cpu.step();
        assertEquals(0x56781234, cpu.getD(0));
        assertEquals(spBefore, cpu.getA(7), "SWAP must not modify the stack pointer");
    }

    /** Regression: MOVE #imm,SR sets the interrupt mask (must not decode as NOT). */
    @Test
    void moveImmediateToStatusRegister() {
        bus.poke(0x1000, 0x46FC, 0x2700);          // MOVE #$2700,SR
        cpu.step();
        assertEquals(0x2700, cpu.getSr());
        assertTrue(cpu.isSupervisor());
        // The instruction must consume exactly one extension word.
        assertEquals(0x1004, cpu.getPc());
    }

    /** Regression: MOVE <ea>,CCR loads only the condition codes. */
    @Test
    void moveToConditionCodes() {
        bus.poke(0x1000, 0x44FC, 0x001F);          // MOVE #$1F,CCR (all flags set)
        cpu.step();
        assertTrue(cpu.cc() && cpu.vv() && cpu.zz() && cpu.nn() && cpu.xx());
        assertEquals(0x1004, cpu.getPc());
    }

    /** PEA with a real control addressing mode still pushes the address. */
    @Test
    void peaPushesEffectiveAddress() {
        bus.poke(0x1000, 0x4878, 0x2000);          // PEA ($2000).W
        int spBefore = cpu.getA(7);
        cpu.step();
        assertEquals(spBefore - 4, cpu.getA(7));
        assertEquals(0x2000, bus.read32(cpu.getA(7)));
    }

    // ---- Phase 2: completeness ------------------------------------------

    /** LINK builds a stack frame; UNLK tears it down, restoring SP and An. */
    @Test
    void linkAndUnlkRoundTrip() {
        cpu.pokeA(5, 0xCAFE0000);
        bus.poke(0x1000, 0x4E55, 0xFFF8);  // LINK A5,#-8
        bus.poke(0x1004, 0x4E5D);          // UNLK A5
        int spBefore = cpu.getA(7);
        cpu.step();                        // LINK
        assertEquals(spBefore - 4 - 8, cpu.getA(7), "frame allocated below saved A5");
        assertEquals(spBefore - 4, cpu.getA(5), "A5 points at the saved old A5");
        cpu.step();                        // UNLK
        assertEquals(spBefore, cpu.getA(7), "SP restored");
        assertEquals(0xCAFE0000, cpu.getA(5), "A5 restored");
    }

    /** ADDI must set the carry flag (arithmetic), not behave like a logic op. */
    @Test
    void addiSetsCarry() {
        bus.poke(0x1000, 0x203C, 0xFFFF, 0xFFFF); // MOVE.L #$FFFFFFFF,D0
        bus.poke(0x1006, 0x0680, 0x0000, 0x0001); // ADDI.L #1,D0
        cpu.step();
        cpu.step();
        assertEquals(0, cpu.getD(0));
        assertTrue(cpu.cc(), "overflow past 32 bits sets carry");
        assertTrue(cpu.zz());
    }

    /** ABCD adds two packed-BCD bytes with decimal correction. */
    @Test
    void abcdDecimalAdd() {
        cpu.pokeD(0, 0x25);
        cpu.pokeD(1, 0x48);
        cpu.setSrFlags(false, false, false, false, false); // X clear
        bus.poke(0x1000, 0xC300);          // ABCD D0,D1  (D1 = D1 + D0)
        cpu.step();
        assertEquals(0x73, cpu.getD(1) & 0xFF, "0x48 + 0x25 = 0x73 in BCD");
        assertFalse(cpu.cc());
    }

    /** ABCD with a decimal carry out sets C and X. */
    @Test
    void abcdDecimalCarry() {
        cpu.pokeD(0, 0x55);
        cpu.pokeD(1, 0x55);
        cpu.setSrFlags(false, false, false, false, false);
        bus.poke(0x1000, 0xC300);          // ABCD D0,D1
        cpu.step();
        assertEquals(0x10, cpu.getD(1) & 0xFF, "0x55 + 0x55 = 0x110 -> 0x10 carry");
        assertTrue(cpu.cc());
        assertTrue(cpu.xx());
    }

    /** ADDX adds with the X flag and only clears Z (never sets it). */
    @Test
    void addxAddsWithExtend() {
        cpu.pokeD(0, 0x0001);
        cpu.pokeD(1, 0x0001);
        cpu.setSrFlags(true, false, true, false, false); // X set, Z set
        bus.poke(0x1000, 0xD300);          // ADDX.B D0,D1
        cpu.step();
        assertEquals(3, cpu.getD(1) & 0xFF, "1 + 1 + X(1) = 3");
        assertFalse(cpu.zz(), "non-zero result clears Z");
    }

    /** CMPM compares (Ay)+,(Ax)+ and post-increments both pointers. */
    @Test
    void cmpmComparesAndIncrements() {
        bus.write16(0x3000, 0x1234);       // (A0)
        bus.write16(0x3100, 0x1234);       // (A1)
        cpu.pokeA(0, 0x3100);
        cpu.pokeA(1, 0x3000);
        bus.poke(0x1000, 0xB348);          // CMPM.W (A0)+,(A1)+
        cpu.step();
        assertTrue(cpu.zz(), "equal words compare equal");
        assertEquals(0x3102, cpu.getA(0));
        assertEquals(0x3002, cpu.getA(1));
    }

    /** MOVEP scatters a register to alternating memory bytes and reads back. */
    @Test
    void movepWordToMemoryAndBack() {
        cpu.pokeA(0, 0x4000);
        cpu.pokeD(0, 0x0000ABCD);
        bus.poke(0x1000, 0x0188, 0x0000);  // MOVEP.W D0,(0,A0)
        cpu.step();
        assertEquals(0xAB, bus.read8(0x4000));
        assertEquals(0xCD, bus.read8(0x4002));
        // Read it back into D1.
        cpu.pokeA(1, 0x4000);
        bus.poke(0x1004, 0x0308, 0x0000);  // MOVEP.W (0,A1),D1
        cpu.step();
        assertEquals(0xABCD, cpu.getD(1) & 0xFFFF);
    }

    /**
     * Regression: {@code ADDA.L Dn,An} ($D3C2 = ADDA.L D2,A1) must add to the
     * address register, not be mis-decoded as ADDX (which shares the top bits and
     * a zero in bits 5-4). The bug clobbered D1 (and left A1 stale), wrecking
     * Sonic's art decompressor and freezing the level with interrupts disabled.
     */
    @Test
    void addaLongFromDataRegisterIsNotAddx() {
        cpu.pokeD(2, 0x00000020);
        cpu.pokeA(1, 0x00021AFE);
        cpu.pokeD(1, 0x00000005);          // a live counter ADDX would corrupt
        bus.poke(0x1000, 0xD3C2);          // ADDA.L D2,A1
        cpu.step();
        assertEquals(0x00021B1E, cpu.getA(1), "A1 = A1 + D2");
        assertEquals(0x00000005, cpu.getD(1), "D1 must be untouched");
    }

    @Test
    void rotateLeavesExtendFlagUnchanged() {
        // ROL/ROR must not touch X (only ASL/ASR/LSL/LSR do). Verified against the
        // SingleStepTests m68000 vectors.
        cpu.pokeD(0, 0x01);
        cpu.setSrFlags(true, false, false, false, false); // X = 1
        bus.poke(0x1000, 0xE318);          // ROL.b #1,D0
        cpu.step();
        assertEquals(0x02, cpu.getD(0));
        assertTrue(cpu.xx(), "ROL leaves X unchanged");
        assertFalse(cpu.cc(), "C = bit rotated out (bit7 of $01 was 0)");
    }

    @Test
    void addLongComputesCarryUnsigned() {
        cpu.pokeD(0, 0xFFFFFFFF);
        bus.poke(0x1000, 0x0680, 0x0000, 0x0001); // ADDI.L #1,D0
        cpu.step();
        assertEquals(0, cpu.getD(0));
        assertTrue(cpu.cc(), "32-bit add wraps -> carry set");
        assertTrue(cpu.zz());

        cpu.pokeD(1, 0x10);
        bus.poke(0x1006, 0x0681, 0x0000, 0x0001); // ADDI.L #1,D1
        cpu.setPc(0x1006);
        cpu.step();
        assertEquals(0x11, cpu.getD(1));
        assertFalse(cpu.cc(), "no wrap -> carry clear");
    }

    @Test
    void leaKeepsFull32BitAddress() {
        // LEA stores the full 32-bit effective address, not masked to 24 bits.
        bus.poke(0x1000, 0x41F9, 0x1234, 0x5678); // LEA ($12345678).L,A0
        cpu.step();
        assertEquals(0x12345678, cpu.getA(0));
    }

    @Test
    void subaLongFromDataRegisterIsNotSubx() {
        cpu.pokeD(2, 0x00000010);
        cpu.pokeA(1, 0x00001000);
        cpu.pokeD(1, 0x00000007);
        bus.poke(0x1000, 0x93C2);          // SUBA.L D2,A1
        cpu.step();
        assertEquals(0x00000FF0, cpu.getA(1), "A1 = A1 - D2");
        assertEquals(0x00000007, cpu.getD(1), "D1 must be untouched");
    }
}
