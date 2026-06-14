package com.segaemu.io;

/**
 * A Mega Drive control pad — 3-button by default, with optional 6-button mode.
 *
 * <p><b>3-button protocol.</b> The pad multiplexes its eight buttons onto six data
 * lines using the TH select line (bit 6 of the data port):
 * <pre>
 *   TH = 1:  bit0 Up  bit1 Down  bit2 Left  bit3 Right  bit4 B  bit5 C
 *   TH = 0:  bit0 Up  bit1 Down  bit2 0     bit3 0      bit4 A  bit5 Start
 * </pre>
 * A button that is <i>pressed</i> reads as 0 (active-low).
 *
 * <p><b>6-button protocol.</b> The 6-button pad counts TH high&rarr;low
 * transitions. The first two cycles read like a 3-button pad; on the third TH=0
 * the low nibble reads 0000 (the "extra buttons available" signature) and the
 * following TH=1 returns the extra buttons Mode/X/Y/Z. Real hardware resets the
 * transition counter after ~1.5&nbsp;ms of inactivity; since a game reads the whole
 * sequence within one frame, {@link #startFrame()} (called once per frame) resets
 * it — a faithful-enough stand-in.
 *
 * <p>Button state is updated from the UI thread and read from the emulation
 * thread, hence the {@code volatile} bitmask.
 */
public final class Controller {

    public enum Button { UP, DOWN, LEFT, RIGHT, A, B, C, START, X, Y, Z, MODE }

    private volatile int pressed = 0; // bit set per Button.ordinal()

    private int thLine = 1;     // current TH select line value
    private int control = 0;    // data-direction / control latch
    private boolean sixButton = false;
    private int thCounter = 0;  // TH high→low transitions in the current sequence

    public void press(Button b) {
        pressed |= (1 << b.ordinal());
    }

    public void release(Button b) {
        pressed &= ~(1 << b.ordinal());
    }

    public void setSixButton(boolean sixButton) {
        this.sixButton = sixButton;
    }

    public boolean isSixButton() {
        return sixButton;
    }

    /** Reset the 6-button transition counter (call once per frame). */
    public void startFrame() {
        thCounter = 0;
    }

    private boolean down(Button b) {
        return (pressed & (1 << b.ordinal())) != 0;
    }

    /** The 68000 sets TH (bit 6) here to choose which button group to read. */
    public void writeData(int value) {
        int newTh = (value >> 6) & 1;
        if (sixButton && thLine == 1 && newTh == 0) {
            thCounter++; // count the high→low edges that drive the 6-button state
        }
        thLine = newTh;
    }

    public void writeControl(int value) {
        control = value & 0xFF;
    }

    /** Read the current button group (active-low), with TH echoed back in bit 6. */
    public int readData() {
        if (sixButton) {
            if (thLine == 1) {
                return thCounter == 3 ? extraButtons() : highGroup();
            }
            return thCounter == 3 ? signature() : lowGroup();
        }
        return thLine == 1 ? highGroup() : lowGroup();
    }

    // TH = 1: Up Down Left Right B C
    private int highGroup() {
        return bit(0, !down(Button.UP))
                | bit(1, !down(Button.DOWN))
                | bit(2, !down(Button.LEFT))
                | bit(3, !down(Button.RIGHT))
                | bit(4, !down(Button.B))
                | bit(5, !down(Button.C))
                | 0x40;
    }

    // TH = 0: Up Down 0 0 A Start
    private int lowGroup() {
        return bit(0, !down(Button.UP))
                | bit(1, !down(Button.DOWN))
                | bit(4, !down(Button.A))
                | bit(5, !down(Button.START));
    }

    // 3rd TH = 0: low nibble 0 signals a 6-button pad; A/Start still in 4/5.
    private int signature() {
        return bit(4, !down(Button.A)) | bit(5, !down(Button.START));
    }

    // 4th TH = 1: extra buttons Mode/X/Y/Z in the low nibble.
    private int extraButtons() {
        return bit(0, !down(Button.Z))
                | bit(1, !down(Button.Y))
                | bit(2, !down(Button.X))
                | bit(3, !down(Button.MODE))
                | 0x30 | 0x40;
    }

    private static int bit(int pos, boolean high) {
        return high ? (1 << pos) : 0;
    }
}
