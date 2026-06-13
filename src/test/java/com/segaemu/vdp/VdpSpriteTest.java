package com.segaemu.vdp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for the VDP sprite layer added in Phase 1. */
class VdpSpriteTest {

    private Vdp vdp;
    private static final int SAT = 0xB000;       // sprite attribute table base
    private static final int WHITE = 0x0EEE;     // CRAM colour we render with

    @BeforeEach
    void setUp() {
        vdp = new Vdp();
        // reg1 = $40: display enabled.
        vdp.writeControl(0x8140);
        // reg5: sprite table base = SAT ($B000 >> 9 = $58).
        vdp.writeControl(0x8500 | (SAT >> 9));

        byte[] vram = vdp.vram();
        // Tile 1 pattern: every pixel uses colour index 2 (byte $22).
        for (int i = 0; i < 32; i++) {
            vram[32 + i] = (byte) 0x22;
        }
        // CRAM index 2 = white, via the data port (code 3, address 4).
        vdp.writeControl(0xC004);
        vdp.writeControl(0x0000);
        vdp.writeData(WHITE);
    }

    /** Place a single 1x1-cell sprite and confirm it renders at the right spot. */
    @Test
    void singleSpriteRendersAtPosition() {
        writeSprite(SAT, /*screenY*/ 10, /*screenX*/ 20, /*sizeByte*/ 0,
                /*link*/ 0, /*attr tile=1*/ 0x0001);
        renderFrame();

        int[] fb = vdp.framebuffer();
        int white = toArgb(WHITE);
        // Inside the sprite (top-left pixel at 20,10).
        assertEquals(white, fb[10 * Vdp.SCREEN_W + 20]);
        assertEquals(white, fb[17 * Vdp.SCREEN_W + 27]);
        // Just outside it stays the backdrop.
        assertNotEquals(white, fb[10 * Vdp.SCREEN_W + 19]);
        assertNotEquals(white, fb[18 * Vdp.SCREEN_W + 20]);
    }

    /** A sprite must draw on top of a (low-priority) background plane pixel. */
    @Test
    void spriteDrawsOverPlane() {
        // Fill plane A nametable (base 0) cell (0,0) with tile 1 so the plane is
        // opaque at the top-left, then put a sprite over it with a different
        // colour and confirm the sprite wins.
        byte[] vram = vdp.vram();
        // Plane A entry 0 -> tile 1, palette 1 (so a different CRAM colour).
        vram[0] = 0x20; // palette 1 (bits 13-14) high byte
        vram[1] = 0x01; // tile 1
        // CRAM index (palette1*16 + 2) = 18 -> set to a non-white colour.
        vdp.writeControl(0xC000 | (18 * 2));
        vdp.writeControl(0x0000);
        vdp.writeData(0x000E); // red-ish

        writeSprite(SAT, 0, 0, 0, 0, 0x0001); // sprite at (0,0), palette 0 -> white
        renderFrame();

        assertEquals(toArgb(WHITE), vdp.framebuffer()[0], "sprite should cover the plane");
    }

    // --- helpers -----------------------------------------------------------

    private void writeSprite(int addr, int screenY, int screenX, int sizeByte,
                             int link, int attr) {
        byte[] v = vdp.vram();
        int y = screenY + 128;
        int x = screenX + 128;
        v[addr] = (byte) (y >> 8);
        v[addr + 1] = (byte) y;
        v[addr + 2] = (byte) sizeByte;
        v[addr + 3] = (byte) link;
        v[addr + 4] = (byte) (attr >> 8);
        v[addr + 5] = (byte) attr;
        v[addr + 6] = (byte) (x >> 8);
        v[addr + 7] = (byte) x;
    }

    private void renderFrame() {
        for (int i = 0; i < Vdp.SCREEN_H; i++) {
            vdp.stepScanline();
        }
    }

    /** Mirror of Vdp.toRgb for the 9-bit colour the test writes. */
    private static int toArgb(int c) {
        int[] lv = {0, 36, 73, 109, 146, 182, 219, 255};
        int r = lv[(c >> 1) & 7], g = lv[(c >> 5) & 7], b = lv[(c >> 9) & 7];
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
