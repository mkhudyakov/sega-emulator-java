package com.segaemu;

import com.segaemu.bus.GenesisBus;
import com.segaemu.cartridge.Rom;
import com.segaemu.cpu.m68k.M68000;
import com.segaemu.io.Controller;
import com.segaemu.sound.Sn76489;
import com.segaemu.sound.Ym2612;
import com.segaemu.vdp.Vdp;
import com.segaemu.z80.Z80;

/**
 * The Mega Drive as a whole: it wires the 68000, VDP, Z80 and sound chips to the
 * {@link GenesisBus} and owns the master clock. Analogous to {@code NesSystem}
 * in the sibling NES emulator.
 *
 * <p><b>Timing model.</b> The reference clock is the NTSC 68000 speed,
 * ~7.67&nbsp;MHz. A frame is 262 scanlines at ~59.92&nbsp;fps, so each scanline
 * is roughly 488 CPU cycles. {@link #stepFrame()} runs the CPU scanline by
 * scanline, stepping the VDP after each line and delivering the level-6 vertical
 * interrupt when the VDP enters vblank. This is instruction-stepped (not
 * cycle-exact); it is enough to run boot code and render static backgrounds.
 */
public final class GenesisSystem {

    /** Master 68000 clock (NTSC), Hz. */
    public static final double CPU_CLOCK = 7_670_454.0;
    /** Z80 clock (NTSC), Hz — the master crystal / 15. */
    public static final double Z80_CLOCK = 3_579_545.0;
    public static final double FPS = 59.92;
    private static final int SCANLINES = 262;
    private static final int CYCLES_PER_LINE = (int) Math.round(CPU_CLOCK / FPS / SCANLINES);
    private static final int Z80_CYCLES_PER_LINE = (int) Math.round(Z80_CLOCK / FPS / SCANLINES);

    // VDP interrupt levels and autovector numbers on the Mega Drive. The 68000
    // autovector for level N is 24+N (so level 6 → 30 → $78, level 4 → 28 → $70).
    private static final int VINT_LEVEL = 6;
    private static final int VINT_VECTOR = 30;
    private static final int HINT_LEVEL = 4;
    private static final int HINT_VECTOR = 28;

    private final Rom rom;
    private final M68000 cpu;
    private final Vdp vdp;
    private final Z80 z80;
    private final Ym2612 ym2612;
    private final Sn76489 psg;
    private final Controller pad1;
    private final Controller pad2;
    private final GenesisBus bus;

    /**
     * Optional instruction-level hook for the debug harness. When set, it is
     * invoked with the current PC before every CPU instruction inside
     * {@link #stepFrame()}. Production paths leave it null (one null check per
     * instruction is negligible).
     */
    @FunctionalInterface
    public interface InstructionHook {
        void onStep(int pc);
    }

    private InstructionHook instructionHook;
    private volatile boolean breakRequested;

    /** Tracks the Z80 reset line so its falling edge re-resets the Z80 core. */
    private boolean z80WasReset = true;

    private final int scanlinesPerFrame;
    private final double framesPerSecond;

    public GenesisSystem(Rom rom) {
        this.rom = rom;
        this.vdp = new Vdp();
        this.pad1 = new Controller();
        this.pad2 = new Controller();
        this.z80 = new Z80();
        this.ym2612 = new Ym2612();
        this.psg = new Sn76489();
        this.bus = new GenesisBus(rom, vdp, pad1, pad2, ym2612, psg);
        this.z80.attachBus(bus);
        this.cpu = new M68000(bus);

        boolean pal = rom.header().isPal();
        vdp.setPal(pal);
        this.scanlinesPerFrame = pal ? 313 : SCANLINES;
        this.framesPerSecond = pal ? 49.70 : FPS;
        if (rom.header().supportsSixButton()) {
            pad1.setSixButton(true);
            pad2.setSixButton(true);
        }
        reset();
    }

    /** Frames per second for this cartridge's video standard (NTSC or PAL). */
    public double fps() {
        return framesPerSecond;
    }

    public boolean isPal() {
        return vdp.isPal();
    }

    public void reset() {
        vdp.reset();
        z80.reset();
        ym2612.reset();
        psg.reset();
        cpu.reset();
        z80WasReset = true;
    }

    /**
     * Run the machine until the VDP completes one full frame. Returns early if a
     * break was requested through the debug hook (then the frame is incomplete).
     */
    public void stepFrame() {
        breakRequested = false;
        pad1.startFrame();
        pad2.startFrame();
        for (int line = 0; line < scanlinesPerFrame; line++) {
            long budget = 0;
            while (budget < CYCLES_PER_LINE) {
                if (instructionHook != null) {
                    instructionHook.onStep(cpu.getPc());
                    if (breakRequested) {
                        return;
                    }
                }
                budget += cpu.step();
            }
            // The Z80 INT line is tied to the VDP vertical interrupt: it pulses
            // at the start of vblank, independent of the 68000's interrupt enable.
            if (line == Vdp.SCREEN_H) {
                z80.requestInterrupt();
            } else if (line == Vdp.SCREEN_H + 1) {
                z80.clearInterrupt();
            }
            // Run the Z80 when it is out of reset and holds its own bus. Its reset
            // line's falling edge resets the core (PC→0) before it first runs.
            boolean inReset = bus.isZ80Reset();
            if (z80WasReset && !inReset) {
                z80.reset();
            }
            z80WasReset = inReset;
            if (!inReset && !bus.isZ80BusRequested()) {
                z80.run(Z80_CYCLES_PER_LINE);
            }
            // The YM2612 timers run continuously (independent of audio pull) so
            // drivers that pace on the timer-overflow flags keep time.
            ym2612.stepTimers(CYCLES_PER_LINE);
            boolean frameDone = vdp.stepScanline();
            // Deliver the vertical interrupt (higher priority) first, then the
            // horizontal one. Each stays pending until the CPU actually accepts
            // it (interrupts are level-triggered).
            if (vdp.isVerticalInterruptPending() && cpu.interrupt(VINT_LEVEL, VINT_VECTOR)) {
                vdp.clearVerticalInterrupt();
            }
            if (vdp.isHorizontalInterruptPending() && cpu.interrupt(HINT_LEVEL, HINT_VECTOR)) {
                vdp.clearHorizontalInterrupt();
            }
            if (frameDone) {
                break;
            }
        }
    }

    /** Install (or clear, with null) the debug instruction hook. */
    public void setInstructionHook(InstructionHook hook) {
        this.instructionHook = hook;
    }

    /** Ask {@link #stepFrame()} to return at the next instruction boundary. */
    public void requestBreak() {
        this.breakRequested = true;
    }

    public int[] framebuffer() {
        return vdp.framebuffer();
    }

    public Controller pad1() {
        return pad1;
    }

    public Controller pad2() {
        return pad2;
    }

    public Rom rom() {
        return rom;
    }

    public M68000 cpu() {
        return cpu;
    }

    public Vdp vdp() {
        return vdp;
    }

    public Z80 z80() {
        return z80;
    }

    public Ym2612 ym2612() {
        return ym2612;
    }

    public Sn76489 psg() {
        return psg;
    }

    public GenesisBus bus() {
        return bus;
    }

    /** Read a byte from 68000 work RAM ($FF0000–$FFFFFF) with no side effects. */
    public int readWorkRam(int addr) {
        return bus.peekWorkRam(addr);
    }

    /** Read a big-endian word from 68000 work RAM with no side effects. */
    public int readWorkRamWord(int addr) {
        return (readWorkRam(addr) << 8) | readWorkRam(addr + 1);
    }
}
