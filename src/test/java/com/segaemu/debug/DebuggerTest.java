package com.segaemu.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.segaemu.GenesisSystem;
import com.segaemu.cartridge.Rom;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Tests the headless debug harness against a small synthetic ROM. */
class DebuggerTest {

    /**
     * A minimal valid ROM whose reset PC runs a tiny program that sets a VDP
     * register then loops forever, so the system has something deterministic to
     * execute.
     */
    private static Rom syntheticRom() {
        byte[] d = new byte[0x4000];
        // Reset vectors: SP, then PC -> $200.
        writeLong(d, 0x000, 0x00FF0000);
        writeLong(d, 0x004, 0x00000200);
        // Header signature.
        put(d, 0x100, "SEGA MEGA DRIVE ");
        put(d, 0x150, "HARNESS TEST");
        // Program at $200: MOVE.W #$8144,($C00004).L ; BRA *
        int p = 0x200;
        writeWord(d, p, 0x33FC);       // MOVE.W #imm,(abs).L
        writeWord(d, p + 2, 0x8144);   // imm = reg1 = display + vint
        writeWord(d, p + 4, 0x00C0);   // dest high
        writeWord(d, p + 6, 0x0004);   // dest low ($00C00004)
        writeWord(d, p + 8, 0x60FE);   // BRA * (infinite loop)
        return Rom.fromBytes(d);
    }

    @Test
    void runsFramesAndCountsInstructions() {
        Debugger dbg = new Debugger(new GenesisSystem(syntheticRom()));
        dbg.runFrames(3);
        assertTrue(dbg.instructionCount() > 0, "should have executed instructions");
        // The program reaches the BRA self-loop, so VDP register 1 must be set.
        assertEquals(0x44, dbg.system().vdp().reg(1));
    }

    @Test
    void frameHashIsDeterministic() {
        Debugger a = new Debugger(new GenesisSystem(syntheticRom()));
        Debugger b = new Debugger(new GenesisSystem(syntheticRom()));
        a.runFrames(5);
        b.runFrames(5);
        assertEquals(a.frameHash(), b.frameHash(),
                "identical inputs must produce identical frames");
    }

    @Test
    void breakpointStopsAtPc() {
        Debugger dbg = new Debugger(new GenesisSystem(syntheticRom()));
        boolean hit = dbg.runUntilPc(0x208, 5); // the BRA self-loop address
        assertTrue(hit, "breakpoint at the self-loop should be reached");
        assertEquals(0x208, dbg.system().cpu().getPc());
    }

    @Test
    void screenshotWritesPng() throws Exception {
        Debugger dbg = new Debugger(new GenesisSystem(syntheticRom()));
        dbg.runFrames(2);
        Path out = Files.createTempFile("harness", ".png");
        try {
            dbg.screenshot(out);
            assertTrue(Files.size(out) > 0, "PNG should be non-empty");
        } finally {
            Files.deleteIfExists(out);
        }
    }

    private static void put(byte[] d, int off, String s) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, d, off, b.length);
    }

    private static void writeWord(byte[] d, int off, int v) {
        d[off] = (byte) (v >> 8);
        d[off + 1] = (byte) v;
    }

    private static void writeLong(byte[] d, int off, int v) {
        d[off] = (byte) (v >> 24);
        d[off + 1] = (byte) (v >> 16);
        d[off + 2] = (byte) (v >> 8);
        d[off + 3] = (byte) v;
    }
}
