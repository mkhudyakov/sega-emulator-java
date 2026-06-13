package com.segaemu.debug;

import com.segaemu.GenesisSystem;
import com.segaemu.cartridge.Rom;
import com.segaemu.vdp.Vdp;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * A headless debugging harness around {@link GenesisSystem}, codifying the
 * single-step / PC-history / RAM-watch / screenshot diagnostics used to track
 * down the boot bugs in the Sonic ROM. It is the standard tool for every
 * development phase: run a ROM without the Swing window, advance by frames or
 * instructions, set a PC breakpoint, dump the framebuffer, and inspect state.
 *
 * <p>It installs an instruction hook on the system, so it sees every CPU PC even
 * inside interrupt handlers (which plain {@code cpu.step()} loops miss because
 * they don't deliver interrupts).
 */
public final class Debugger {

    private final GenesisSystem system;

    // Ring buffer of recently executed PCs (size is a power of two).
    private final int[] pcRing = new int[256];
    private int pcRingPos;
    private long instructionCount;

    private int breakpointPc = -1;
    private boolean breakpointHit;

    public Debugger(GenesisSystem system) {
        this.system = system;
        system.setInstructionHook(this::onStep);
    }

    /** Load a ROM from disk and wrap a fresh system around it. */
    public static Debugger load(Path romPath) {
        return new Debugger(new GenesisSystem(Rom.load(romPath)));
    }

    private void onStep(int pc) {
        pcRing[pcRingPos] = pc;
        pcRingPos = (pcRingPos + 1) & (pcRing.length - 1);
        instructionCount++;
        if (pc == breakpointPc) {
            breakpointHit = true;
            system.requestBreak();
        }
    }

    public GenesisSystem system() {
        return system;
    }

    public long instructionCount() {
        return instructionCount;
    }

    // ---- running ----------------------------------------------------------

    /** Run exactly {@code frames} full frames. */
    public void runFrames(int frames) {
        for (int i = 0; i < frames; i++) {
            system.stepFrame();
        }
    }

    /**
     * Run up to {@code maxFrames} frames, stopping as soon as the CPU reaches
     * {@code pc}. Returns true if the breakpoint was hit.
     */
    public boolean runUntilPc(int pc, int maxFrames) {
        breakpointPc = pc;
        breakpointHit = false;
        try {
            for (int i = 0; i < maxFrames && !breakpointHit; i++) {
                system.stepFrame();
            }
        } finally {
            breakpointPc = -1;
        }
        return breakpointHit;
    }

    // ---- inspection -------------------------------------------------------

    /** Read a byte from 68000 work RAM (side-effect free). */
    public int readByte(int addr) {
        return system.readWorkRam(addr);
    }

    /** Read a big-endian word from 68000 work RAM. */
    public int readWord(int addr) {
        return system.readWorkRamWord(addr);
    }

    /** Read a big-endian long from 68000 work RAM. */
    public int readLong(int addr) {
        return (readWord(addr) << 16) | readWord(addr + 2);
    }

    /** The most recent {@code count} PCs in chronological order (oldest first). */
    public int[] recentPcs(int count) {
        count = Math.min(count, pcRing.length);
        int[] out = new int[count];
        int start = (pcRingPos - count) & (pcRing.length - 1);
        for (int i = 0; i < count; i++) {
            out[i] = pcRing[(start + i) & (pcRing.length - 1)];
        }
        return out;
    }

    // ---- output -----------------------------------------------------------

    /** A stable hash of the current framebuffer, for golden-image comparisons. */
    public long frameHash() {
        long h = 1125899906842597L;
        for (int p : system.framebuffer()) {
            h = 31 * h + p;
        }
        return h;
    }

    /** Count of pixels that differ from the top-left pixel (rough activity gauge). */
    public int nonBackgroundPixels() {
        int[] fb = system.framebuffer();
        int bg = fb[0];
        int n = 0;
        for (int p : fb) {
            if (p != bg) {
                n++;
            }
        }
        return n;
    }

    /** Write the current framebuffer to a PNG file. */
    public void screenshot(Path out) throws IOException {
        int[] fb = system.framebuffer();
        BufferedImage img =
                new BufferedImage(Vdp.SCREEN_W, Vdp.SCREEN_H, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, Vdp.SCREEN_W, Vdp.SCREEN_H, fb, 0, Vdp.SCREEN_W);
        ImageIO.write(img, "png", out.toFile());
    }
}
