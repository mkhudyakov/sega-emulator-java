package com.segaemu.io;

/**
 * A Mega Drive 3-button control pad.
 *
 * <p>The pad multiplexes its eight buttons onto six data lines using the TH
 * select line (bit 6 of the data port). The 68000 toggles TH and reads two
 * different button groups:
 * <pre>
 *   TH = 1:  bit0 Up  bit1 Down  bit2 Left  bit3 Right  bit4 B  bit5 C
 *   TH = 0:  bit0 Up  bit1 Down  bit2 0     bit3 0      bit4 A  bit5 Start
 * </pre>
 * A button that is <i>pressed</i> reads as 0 (active-low), so the idle state of
 * each data line is 1.
 *
 * <p>Button state is updated from the UI thread and read from the emulation
 * thread, hence the {@code volatile} bitmask.
 */
public final class Controller {

    public enum Button { UP, DOWN, LEFT, RIGHT, A, B, C, START }

    private volatile int pressed = 0; // bit set per Button.ordinal()

    private int thLine = 1;   // current TH select line value
    private int control = 0;  // data-direction / control latch ($A1000B etc.)

    public void press(Button b) {
        pressed |= (1 << b.ordinal());
    }

    public void release(Button b) {
        pressed &= ~(1 << b.ordinal());
    }

    private boolean down(Button b) {
        return (pressed & (1 << b.ordinal())) != 0;
    }

    /** The 68000 sets TH (bit 6) here to choose which button group to read. */
    public void writeData(int value) {
        thLine = (value >> 6) & 1;
    }

    public void writeControl(int value) {
        control = value & 0xFF;
    }

    /** Read the current button group, active-low, with TH echoed back in bit 6. */
    public int readData() {
        int v;
        if (thLine == 1) {
            v = bit(0, !down(Button.UP))
                    | bit(1, !down(Button.DOWN))
                    | bit(2, !down(Button.LEFT))
                    | bit(3, !down(Button.RIGHT))
                    | bit(4, !down(Button.B))
                    | bit(5, !down(Button.C));
        } else {
            v = bit(0, !down(Button.UP))
                    | bit(1, !down(Button.DOWN))
                    | bit(4, !down(Button.A))
                    | bit(5, !down(Button.START));
            // bits 2,3 read 0 in this group (used by games to detect a pad).
        }
        v |= (thLine << 6);
        return v & 0xFF;
    }

    private static int bit(int pos, boolean high) {
        return high ? (1 << pos) : 0;
    }
}
