package com.segaemu.vdp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the Phase 8 VDP features: H40/H32 width switching, the window plane,
 * per-column vertical scroll, and shadow/highlight mode.
 */
class VdpAdvancedTest {

    private Vdp vdp;

    @BeforeEach
    void setUp() {
        vdp = new Vdp();
        vdp.writeControl(0x8140); // reg1 = $40: display enabled
    }

    @Test
    void activeWidthFollowsRegister12() {
        assertEquals(256, vdp.activeWidth(), "reset state is H32");
        vdp.writeControl(0x8C81);          // reg12 = $81: H40
        assertEquals(320, vdp.activeWidth());
        vdp.writeControl(0x8C00);          // reg12 = $00: H32
        assertEquals(256, vdp.activeWidth());
    }

    @Test
    void windowPlaneReplacesPlaneAInRegion() {
        // Window covers the top 16 lines (reg18 = 2 -> WVP = 16, DOWN = 0); its
        // nametable lives at $1000 (reg3 = 8 -> ($08 & $3F) << 9 = $1000).
        vdp.writeControl(0x9202);          // reg18 = 2
        vdp.writeControl(0x8308);          // reg3  = 8

        byte[] v = vdp.vram();
        // Window cell (0,0) -> tile 1; plane A stays empty (tile 0, transparent).
        v[0x1000] = 0x00;
        v[0x1001] = 0x01;
        // Tile 1 = colour index 2 everywhere.
        for (int i = 0; i < 32; i++) {
            v[32 + i] = 0x22;
        }
        writeCram(2, 0x0EEE);              // white

        renderFrame();
        int[] fb = vdp.framebuffer();
        assertEquals(toArgb(0x0EEE), fb[0], "window shows inside the top band");
        // Line 20 is below the window band: plane A is transparent -> backdrop.
        assertNotEquals(toArgb(0x0EEE), fb[20 * Vdp.SCREEN_W], "no window below the band");
    }

    @Test
    void perColumnVerticalScrollUsesVsramPerColumn() {
        vdp.writeControl(0x8B04);          // reg11 = $04: per-column vscroll
        vdp.writeControl(0x8208);          // reg2 = 8: plane A nametable at $2000

        byte[] v = vdp.vram();
        int base = 0x2000;                 // keep the nametable clear of tile data
        setEntry(v, base, 0, 0, 0x00, 1);  // (row 0, col 0) -> tile 1
        setEntry(v, base, 1, 2, 0x00, 2);  // (row 1, col 2) -> tile 2
        for (int i = 0; i < 32; i++) v[32 + i] = 0x22; // tile 1 -> colour 2
        for (int i = 0; i < 32; i++) v[64 + i] = 0x33; // tile 2 -> colour 3
        writeCram(2, 0x0EEE);              // white
        writeCram(3, 0x000E);             // red-ish

        // VSRAM: column 0 plane A (index 0) = 0; column 1 plane A (index 2) = 8.
        vdp.writeControl(0x8F04);          // auto-increment 4
        vdp.writeControl(0x4000);          // VSRAM write, address 0
        vdp.writeControl(0x0010);
        vdp.writeData(0);                  // vsram[0]
        vdp.writeData(8);                  // vsram[2]

        renderFrame();
        int[] fb = vdp.framebuffer();
        assertEquals(toArgb(0x0EEE), fb[0], "column 0: no scroll -> row 0 tile");
        assertEquals(toArgb(0x000E), fb[16], "column 1: scrolled down one tile -> row 1 tile");
    }

    @Test
    void shadowHighlightDarkensLowPriorityBackground() {
        vdp.writeControl(0x8C08);          // reg12 = $08: shadow/highlight on
        vdp.writeControl(0x8200);          // plane A base 0

        byte[] v = vdp.vram();
        // Plane A entry 0 -> tile 1, palette 0, low priority.
        v[0] = 0x00;
        v[1] = 0x01;
        for (int i = 0; i < 32; i++) v[32 + i] = 0x22; // tile 1 -> colour 2
        writeCram(2, 0x0EEE);             // white

        renderFrame();
        // Low-priority background is shadowed: white -> half brightness.
        assertEquals(0xFF7F7F7F, vdp.framebuffer()[0], "low-priority pixel is shadowed");

        // Set the priority bit on the tile entry: now it renders at full bright.
        v[0] = (byte) 0x80;
        renderFrame();
        assertEquals(0xFFFFFFFF, vdp.framebuffer()[0], "high-priority pixel is normal");
    }

    // --- helpers -----------------------------------------------------------

    private static void setEntry(byte[] v, int base, int tileRow, int tileCol, int hi, int tile) {
        int addr = base + (tileRow * 32 + tileCol) * 2;
        v[addr] = (byte) (hi | (tile >> 8));
        v[addr + 1] = (byte) tile;
    }

    private void writeCram(int index, int colour) {
        vdp.writeControl(0xC000 | (index * 2));
        vdp.writeControl(0x0000);
        vdp.writeData(colour);
    }

    private void renderFrame() {
        // A full frame resets the scanline counter so repeated calls are stable.
        for (int i = 0; i < 262; i++) {
            vdp.stepScanline();
        }
    }

    private static int toArgb(int c) {
        int[] lv = {0, 36, 73, 109, 146, 182, 219, 255};
        int r = lv[(c >> 1) & 7], g = lv[(c >> 5) & 7], b = lv[(c >> 9) & 7];
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
