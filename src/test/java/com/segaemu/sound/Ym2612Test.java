package com.segaemu.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for the YM2612 timers and FM synthesis added in Phase 6. */
class Ym2612Test {

    private Ym2612 ym;

    @BeforeEach
    void setUp() {
        ym = new Ym2612();
    }

    private void reg(int bank, int address, int value) {
        ym.writeRegister(bank, address, value);
    }

    private int peak(int[] l, int[] r) {
        int p = 0;
        for (int i = 0; i < l.length; i++) {
            p = Math.max(p, Math.max(Math.abs(l[i]), Math.abs(r[i])));
        }
        return p;
    }

    @Test
    void timerAOverflowSetsStatusFlag() {
        reg(0, 0x24, 0xFF);     // timer A high 8 bits
        reg(0, 0x25, 0x00);     // -> timer A = 1020, overflows after 4 ticks
        reg(0, 0x27, 0x05);     // start A + enable A flag
        assertFalse(ym.timerAOverflow());
        ym.stepTimers(144 * 10); // ~10 FM sample ticks
        assertTrue(ym.timerAOverflow());
        assertEquals(0x01, ym.readStatus() & 0x01);
    }

    @Test
    void timerBOverflowSetsStatusFlag() {
        reg(0, 0x26, 0xFE);     // timer B = 254 -> overflow after 2 * 16 ticks
        reg(0, 0x27, 0x0A);     // start B + enable B flag
        ym.stepTimers(144 * 40);
        assertTrue(ym.timerBOverflow());
        assertEquals(0x02, ym.readStatus() & 0x02);
    }

    @Test
    void resetBitClearsTimerFlag() {
        reg(0, 0x24, 0xFF);
        reg(0, 0x27, 0x05);
        ym.stepTimers(144 * 10);
        assertTrue(ym.timerAOverflow());
        reg(0, 0x27, 0x15);     // keep running + reset flag A
        assertFalse(ym.timerAOverflow());
    }

    @Test
    void keyOnInstantAttackReachesFullVolume() {
        reg(0, 0x50, 0x1F);     // ch0 slot0: KS0, AR31 (instant attack)
        reg(0, 0x60, 0x00);     // DR0 -> stays put after attack
        reg(0, 0x28, 0x10);     // key on operator 0 of channel 0
        assertEquals(0, ym.channelEnvelope(0, 0), "instant attack -> full volume");
        assertEquals(1, ym.channelEgState(0, 0), "moved to decay");
    }

    @Test
    void keyOffMovesToRelease() {
        reg(0, 0x50, 0x1F);     // AR31
        reg(0, 0x80, 0x0F);     // SL0, RR15 (fast release)
        reg(0, 0x28, 0x10);     // key on op0
        reg(0, 0x28, 0x00);     // key off op0
        assertEquals(3, ym.channelEgState(0, 0), "release state");

        int[] l = new int[2000];
        int[] r = new int[2000];
        int before = ym.channelEnvelope(0, 0);
        ym.mix(l, r, l.length);
        assertTrue(ym.channelEnvelope(0, 0) >= before, "release ramps toward silence");
    }

    @Test
    void dacModeOutputsSample() {
        reg(0, 0x2B, 0x80);     // enable DAC
        reg(0, 0x2A, 0xFF);     // a loud positive sample
        reg(1, 0xB6, 0xC0);     // channel 6 (bank1 ch3 -> index 5) panned L+R
        assertTrue(ym.isDacEnabled());
        int[] l = new int[256];
        int[] r = new int[256];
        ym.mix(l, r, l.length);
        assertTrue(peak(l, r) > 0, "DAC sample reaches the output");
    }

    @Test
    void fmChannelProducesOutput() {
        // Channel 0, algorithm 7 (all four operators are carriers).
        reg(0, 0xB0, 0x07);
        for (int slot = 0; slot < 4; slot++) {
            reg(0, 0x40 + slot * 4, 0x00); // TL 0 (loudest)
            reg(0, 0x50 + slot * 4, 0x1F); // AR 31
            reg(0, 0x60 + slot * 4, 0x00); // DR 0
            reg(0, 0x30 + slot * 4, 0x01); // MUL 1
        }
        reg(0, 0xA4, (4 << 3) | 0x02);     // block 4, fnum high
        reg(0, 0xA0, 0x80);                // fnum low -> mid frequency
        reg(0, 0xB4, 0xC0);                // pan L+R
        reg(0, 0x28, 0xF0);                // key on all four operators of channel 0

        int[] l = new int[4000];
        int[] r = new int[4000];
        ym.mix(l, r, l.length);

        boolean sawPositive = false, sawNegative = false;
        for (int s : l) {
            if (s > 0) sawPositive = true;
            if (s < 0) sawNegative = true;
        }
        assertTrue(sawPositive && sawNegative, "FM channel emits an oscillating signal");
    }
}
