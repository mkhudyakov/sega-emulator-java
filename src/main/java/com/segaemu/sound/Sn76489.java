package com.segaemu.sound;

/**
 * Placeholder for the Texas Instruments SN76489 PSG (programmable sound
 * generator) — three square-wave tone channels plus a noise channel, inherited
 * from the Master System. It handles simple beeps and some sound effects.
 *
 * <p>Unlike the YM2612 the PSG is small and a full implementation is a realistic
 * near-term step. For now register writes are latched and {@link #mix} returns
 * silence.
 */
public final class Sn76489 {

    private final int[] tone = new int[4];   // frequency counters per channel
    private final int[] volume = {0xF, 0xF, 0xF, 0xF}; // attenuation (0xF = off)
    private int latchedChannel = 0;

    public void reset() {
        java.util.Arrays.fill(tone, 0);
        java.util.Arrays.fill(volume, 0xF);
        latchedChannel = 0;
    }

    /** Write a byte to the single PSG control port at $C00011. */
    public void write(int value) {
        value &= 0xFF;
        if ((value & 0x80) != 0) { // LATCH/DATA byte
            latchedChannel = (value >> 5) & 3;
            if ((value & 0x10) != 0) {
                volume[latchedChannel] = value & 0x0F;
            } else {
                tone[latchedChannel] = (tone[latchedChannel] & 0x3F0) | (value & 0x0F);
            }
        } else { // DATA byte — high bits of the latched tone register
            tone[latchedChannel] = (tone[latchedChannel] & 0x00F) | ((value & 0x3F) << 4);
        }
    }

    public void mix(int[] out, int count) {
        java.util.Arrays.fill(out, 0, count, 0);
    }
}
