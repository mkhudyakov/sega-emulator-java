package com.segaemu.z80;

/**
 * Placeholder for the Mega Drive's Zilog Z80 sound co-processor.
 *
 * <p>On real hardware the Z80 runs its own program (uploaded by the 68000 into
 * the 8&nbsp;KB sound RAM) to drive the YM2612 and SN76489. Most games boot and
 * run their main logic on the 68000 alone; the Z80 only matters once you want
 * music and sound effects.
 *
 * <p>This stub models the bus-request / reset handshake (so the 68000's sound
 * driver upload sequence completes) but does not execute Z80 instructions yet.
 * Implementing a full Z80 core is a roadmap item parallel to the YM2612.
 */
public final class Z80 {

    private boolean halted = true;

    public void reset() {
        halted = true;
    }

    /** Run for approximately {@code cycles} Z80 cycles (currently a no-op). */
    public void run(long cycles) {
        // No instruction execution yet — see class javadoc.
    }

    public void setHalted(boolean halted) {
        this.halted = halted;
    }

    public boolean isHalted() {
        return halted;
    }
}
