package com.segaemu.sound;

/**
 * Placeholder for the Yamaha YM2612 (OPN2) 6-channel FM synthesis chip.
 *
 * <p>The YM2612 is the source of the Mega Drive's signature music. Each of its
 * six channels has four operators with configurable envelopes, and it supports
 * a PCM sample channel. Faithful FM synthesis — operator phase generation,
 * envelope generators, the LFO and the non-linear DAC — is a large, self-
 * contained subsystem and is intentionally left as a roadmap item.
 *
 * <p>For now the chip accepts register writes (latched but inert) so games that
 * program it during boot do not stall. {@link #mix} returns silence.
 */
public final class Ym2612 {

    private final int[][] registers = new int[2][0x100]; // two register banks
    private final int[] latchedAddr = new int[2];          // selected register per bank

    public void reset() {
        for (int[] bank : registers) {
            java.util.Arrays.fill(bank, 0);
        }
        latchedAddr[0] = latchedAddr[1] = 0;
    }

    public void writeRegister(int bank, int reg, int value) {
        registers[bank & 1][reg & 0xFF] = value & 0xFF;
    }

    /**
     * Write one of the four 68000-facing ports ($A04000–$A04003): even ports
     * latch a register number, odd ports write that register's data. Ports 0/1
     * address bank 0, ports 2/3 address bank 1.
     */
    public void writePort(int port, int value) {
        value &= 0xFF;
        int bank = (port >> 1) & 1;
        if ((port & 1) == 0) {
            latchedAddr[bank] = value;
        } else {
            writeRegister(bank, latchedAddr[bank], value);
        }
    }

    /**
     * The YM2612 status byte. Bit 7 is BUSY and bits 0-1 are the timer
     * overflow flags. The stub is always ready, so this returns 0 and the
     * busy-wait loops games run after each register write terminate at once.
     */
    public int readStatus() {
        return 0x00;
    }

    /** Produce {@code count} stereo samples; silent until FM is implemented. */
    public void mix(int[] left, int[] right, int count) {
        java.util.Arrays.fill(left, 0, count, 0);
        java.util.Arrays.fill(right, 0, count, 0);
    }
}
