package com.segaemu.vdp;

/**
 * A subset of the Mega Drive Video Display Processor (Sega 315-5313).
 *
 * <p><b>What is modelled:</b>
 * <ul>
 *   <li>The 24 internal registers, written through the control port.</li>
 *   <li>VRAM (64 KB), CRAM (64 colour entries) and VSRAM (40 entries).</li>
 *   <li>The control-port command protocol: two 16-bit writes build a 32-bit
 *       address+code word selecting which memory the data port targets, and the
 *       data port auto-increments by register&nbsp;15 after each access.</li>
 *   <li>The status register (VBLANK, sprite overflow/collision) and the HV
 *       counter (V position; H position is not sub-line accurate).</li>
 *   <li>The vertical (level 6) and horizontal (level 4, register-10 counter)
 *       interrupts.</li>
 *   <li>A scanline-based, layered renderer: scroll planes A and B plus the
 *       sprite layer, composited by the Mega Drive per-pixel priority rules into
 *       a 320&times;224 RGB framebuffer.</li>
 *   <li>Sprites: sprite-attribute-table linked-list traversal, 1-4 cell sizes,
 *       H/V flip, palettes, priority, the per-line pixel limit (overflow flag),
 *       sprite collision, and basic X=0 masking.</li>
 *   <li>DMA: 68000&rarr;VRAM/CRAM/VSRAM transfer (driven by the bus), VRAM fill
 *       and VRAM copy.</li>
 * </ul>
 *
 * <p>Per-plane scrolling is honoured: plane A scrolls by VSRAM[0] and plane B by
 * VSRAM[1], and the register-11 horizontal scroll modes (whole screen / per cell
 * / per line) are supported.
 *
 * <p><b>What is not yet modelled</b> (documented stubs for the roadmap): the
 * window plane, per-column vertical scroll, and the shadow/highlight mode.
 */
public final class Vdp {

    public static final int SCREEN_W = 320;
    public static final int SCREEN_H = 224;

    // --- memories ----------------------------------------------------------
    private final byte[] vram = new byte[0x10000];
    private final int[] cram = new int[64];   // 9-bit BGR colour, one per entry
    private final int[] vsram = new int[40];   // vertical scroll, 11 bits each
    private final int[] reg = new int[24];

    // --- control-port command state ---------------------------------------
    private boolean controlPending = false; // first command word latched?
    private int controlFirst = 0;
    private int addressReg = 0;             // current VDP address
    private int codeReg = 0;                // current VDP access code (CD0-CD5)
    private int readBuffer = 0;

    // --- timing / status ---------------------------------------------------
    private int scanline = 0;
    private boolean vblank = false;
    private boolean frameComplete = false;
    private boolean vIntPending = false;
    private boolean hIntPending = false;
    private int hIntCounter = 0; // counts down register 10 across the display

    // --- output ------------------------------------------------------------
    private final int[] framebuffer = new int[SCREEN_W * SCREEN_H];

    // Per-scanline working layers (reused each line). Each holds a CRAM index
    // (palette*16 + colour); a low nibble of 0 means transparent. The parallel
    // priority arrays carry each pixel's priority bit.
    private final int[] layerA = new int[SCREEN_W];
    private final int[] layerB = new int[SCREEN_W];
    private final int[] layerS = new int[SCREEN_W];
    private final boolean[] priA = new boolean[SCREEN_W];
    private final boolean[] priB = new boolean[SCREEN_W];
    private final boolean[] priS = new boolean[SCREEN_W];

    // Sprite status, surfaced in the status register and cleared when read.
    private boolean spriteOverflow;
    private boolean spriteCollision;

    // Access codes (CD5..CD0).
    private static final int CODE_VRAM_READ = 0;
    private static final int CODE_VRAM_WRITE = 1;
    private static final int CODE_CRAM_WRITE = 3;
    private static final int CODE_VSRAM_READ = 4;
    private static final int CODE_VSRAM_WRITE = 5;
    private static final int CODE_CRAM_READ = 8;

    public Vdp() {
        reset();
    }

    public void reset() {
        java.util.Arrays.fill(vram, (byte) 0);
        java.util.Arrays.fill(cram, 0);
        java.util.Arrays.fill(vsram, 0);
        java.util.Arrays.fill(reg, 0);
        controlPending = false;
        addressReg = 0;
        codeReg = 0;
        scanline = 0;
        vblank = false;
        frameComplete = false;
        vIntPending = false;
        hIntPending = false;
        hIntCounter = 0;
    }

    // ======================================================================
    //  Control port
    // ======================================================================

    public void writeControl(int value) {
        value &= 0xFFFF;
        if (!controlPending) {
            if ((value & 0xC000) == 0x8000) {
                // Register write: 100r rrrr dddd dddd
                int r = (value >> 8) & 0x1F;
                if (r < reg.length) {
                    reg[r] = value & 0xFF;
                }
                return;
            }
            // First half of an address/code command.
            controlFirst = value;
            controlPending = true;
        } else {
            controlPending = false;
            int second = value;
            addressReg = (controlFirst & 0x3FFF) | ((second & 0x3) << 14);
            codeReg = ((controlFirst >> 14) & 0x3) | ((second >> 2) & 0x3C);
            // A command with CD5 set and DMA enabled (reg1 bit 4) starts a DMA.
            if ((codeReg & 0x20) != 0 && (reg[1] & 0x10) != 0) {
                if ((reg[23] & 0x80) == 0) {
                    // 68000 -> VDP: the bus performs the copy (it owns the source).
                    dma68kPending = true;
                } else if ((reg[23] & 0xC0) == 0xC0) {
                    vramCopy(); // VRAM -> VRAM, internal
                } else {
                    fillPending = true; // VRAM fill, armed until the next data write
                }
            }
        }
    }

    /** Reading the control port returns the status register. */
    public int readControl() {
        controlPending = false;
        return readStatus();
    }

    /**
     * Status register bits (the ones games actually poll):
     * <pre>
     *   bit 3  VBLANK active
     *   bit 2  HBLANK active
     *   bit 1  DMA busy (always 0 here)
     *   bit 0  PAL (0 = NTSC)
     * </pre>
     * The upper bits report FIFO empty so busy-wait loops terminate.
     */
    public int readStatus() {
        int s = 0x3400; // FIFO empty + always-set bits seen on hardware
        if (vblank) {
            s |= 0x08;
        }
        if (spriteOverflow) {
            s |= 0x40;
        }
        if (spriteCollision) {
            s |= 0x20;
        }
        // These flags are cleared by reading the status register.
        spriteOverflow = false;
        spriteCollision = false;
        return s;
    }

    // ======================================================================
    //  Data port
    // ======================================================================

    public void writeData(int value) {
        value &= 0xFFFF;
        if (fillPending) {
            vramFill(value);
            fillPending = false;
            return;
        }
        switch (codeReg & 0x0F) {
            case CODE_VRAM_WRITE -> {
                int a = addressReg & 0xFFFF;
                vram[a] = (byte) (value >> 8);
                vram[(a + 1) & 0xFFFF] = (byte) (value & 0xFF);
            }
            case CODE_CRAM_WRITE -> cram[(addressReg >> 1) & 0x3F] = value & 0x0EEE;
            case CODE_VSRAM_WRITE -> {
                int idx = (addressReg >> 1) % vsram.length;
                vsram[idx] = value & 0x7FF;
            }
            default -> { /* writing with a read code is ignored */ }
        }
        addressReg = (addressReg + increment()) & 0xFFFF;
    }

    public int readData() {
        int result;
        switch (codeReg & 0x0F) {
            case CODE_VRAM_READ -> {
                int a = addressReg & 0xFFFF;
                result = ((vram[a] & 0xFF) << 8) | (vram[(a + 1) & 0xFFFF] & 0xFF);
            }
            case CODE_CRAM_READ -> result = cram[(addressReg >> 1) & 0x3F];
            case CODE_VSRAM_READ -> result = vsram[(addressReg >> 1) % vsram.length];
            default -> result = readBuffer;
        }
        readBuffer = result;
        addressReg = (addressReg + increment()) & 0xFFFF;
        return result & 0xFFFF;
    }

    private int increment() {
        return reg[15] & 0xFF;
    }

    // ======================================================================
    //  DMA
    // ======================================================================
    //
    //  Three modes, selected by register 23:
    //    - 68000 -> VDP : copy from 68000 memory into VRAM/CRAM/VSRAM. The VDP
    //      cannot read the 68000 bus itself, so GenesisBus drives this: it polls
    //      isDma68kPending(), reads dmaLengthWords() words from dmaSourceAddress()
    //      and feeds each through writeData() (which honours the destination code
    //      and auto-increment), then calls clearDma68k().
    //    - VRAM fill : the next data-port write supplies the fill byte.
    //    - VRAM copy : an internal VRAM->VRAM byte copy.

    private boolean dma68kPending = false;
    private boolean fillPending = false;

    public boolean isDma68kPending() {
        return dma68kPending;
    }

    public void clearDma68k() {
        dma68kPending = false;
    }

    /** DMA length in words; a programmed length of 0 means 65536. */
    public int dmaLengthWords() {
        int len = (reg[19] | (reg[20] << 8)) & 0xFFFF;
        return len == 0 ? 0x10000 : len;
    }

    /** 68000 source byte address (registers hold a word address). */
    public int dmaSourceAddress() {
        int wordAddr = (reg[21] & 0xFF) | ((reg[22] & 0xFF) << 8) | ((reg[23] & 0x7F) << 16);
        return (wordAddr << 1) & 0xFFFFFF;
    }

    private void vramFill(int value) {
        int fill = (value >> 8) & 0xFF;
        int len = dmaLengthWords();
        int a = addressReg & 0xFFFF;
        // VRAM fill writes the byte to the addressed cell, then to a run of
        // cells stepped by the auto-increment register.
        vram[a] = (byte) (value & 0xFF);
        for (int i = 0; i < len; i++) {
            vram[a & 0xFFFF] = (byte) fill;
            a += increment();
        }
        addressReg = a & 0xFFFF;
    }

    private void vramCopy() {
        int len = dmaLengthWords();
        int src = (reg[21] & 0xFF) | ((reg[22] & 0xFF) << 8);
        int dst = addressReg & 0xFFFF;
        for (int i = 0; i < len; i++) {
            vram[dst & 0xFFFF] = vram[src & 0xFFFF];
            src = (src + 1) & 0xFFFF;
            dst = (dst + increment()) & 0xFFFF;
        }
        addressReg = dst & 0xFFFF;
    }

    // ======================================================================
    //  HV counter (approximate)
    // ======================================================================

    public int readHvCounter() {
        int v = scanline & 0xFF;
        int h = 0; // horizontal position within the line is not tracked
        return (v << 8) | h;
    }

    // ======================================================================
    //  Timing — driven once per scanline by GenesisSystem
    // ======================================================================

    /** Advance one scanline. Returns true when a full frame has been produced. */
    public boolean stepScanline() {
        frameComplete = false;
        if (scanline < SCREEN_H) {
            renderScanline(scanline);
        }

        // Horizontal-interrupt counter. It runs across the active display and
        // the first vblank line, reloading from register 10 and raising a
        // level-4 interrupt when it underflows; it is held reloaded during the
        // rest of vblank so each frame starts fresh.
        if (scanline <= SCREEN_H) {
            if (hIntCounter == 0) {
                hIntCounter = reg[10] & 0xFF;
                if (horizontalInterruptEnabled()) {
                    hIntPending = true;
                }
            } else {
                hIntCounter--;
            }
        } else {
            hIntCounter = reg[10] & 0xFF;
        }

        scanline++;
        if (scanline == SCREEN_H) {
            vblank = true;
            if (verticalInterruptEnabled()) {
                vIntPending = true;
            }
        }
        if (scanline >= 262) { // NTSC total scanlines
            scanline = 0;
            vblank = false;
            frameComplete = true;
        }
        return frameComplete;
    }

    public boolean isFrameComplete() {
        return frameComplete;
    }

    public boolean isVerticalInterruptPending() {
        return vIntPending;
    }

    public void clearVerticalInterrupt() {
        vIntPending = false;
    }

    /** Level-4 horizontal interrupt enable (register 0 bit 4). */
    public boolean horizontalInterruptEnabled() {
        return (reg[0] & 0x10) != 0;
    }

    public boolean isHorizontalInterruptPending() {
        return hIntPending;
    }

    public void clearHorizontalInterrupt() {
        hIntPending = false;
    }

    public boolean verticalInterruptEnabled() {
        return (reg[1] & 0x20) != 0;
    }

    public boolean displayEnabled() {
        return (reg[1] & 0x40) != 0;
    }

    // ======================================================================
    //  Renderer
    // ======================================================================

    private void renderScanline(int y) {
        int rowBase = y * SCREEN_W;
        if (!displayEnabled()) {
            java.util.Arrays.fill(framebuffer, rowBase, rowBase + SCREEN_W, backdropColor());
            return;
        }
        // Build the three layers for this line, then composite by priority.
        computePlaneRow(y, planeBBase(), 1, layerB, priB);
        computePlaneRow(y, planeABase(), 0, layerA, priA);
        computeSpriteRow(y);

        int backdrop = backdropColor();
        for (int x = 0; x < SCREEN_W; x++) {
            framebuffer[rowBase + x] = resolvePixel(x, backdrop);
        }
    }

    /**
     * Mega Drive per-pixel priority resolution. Among non-transparent layers the
     * winner is, in order: high-priority sprite, high-priority plane A,
     * high-priority plane B, then the low-priority sprite/A/B, then the backdrop.
     */
    private int resolvePixel(int x, int backdrop) {
        int s = layerS[x], a = layerA[x], b = layerB[x];
        boolean so = (s & 0x0F) != 0, ao = (a & 0x0F) != 0, bo = (b & 0x0F) != 0;
        if (so && priS[x]) return toRgb(cram[s]);
        if (ao && priA[x]) return toRgb(cram[a]);
        if (bo && priB[x]) return toRgb(cram[b]);
        if (so) return toRgb(cram[s]);
        if (ao) return toRgb(cram[a]);
        if (bo) return toRgb(cram[b]);
        return backdrop;
    }

    /**
     * Build one scanline of a scroll plane into {@code layer} (CRAM index, low
     * nibble 0 = transparent) and {@code pri} (the per-tile priority bit).
     * Horizontal scroll honours the register-11 mode (full screen / per cell /
     * per line); vertical scroll is whole-screen from VSRAM[planeIndex].
     */
    private void computePlaneRow(int y, int nametableBase, int planeIndex, int[] layer, boolean[] pri) {
        int planeW = planeWidthTiles();
        int planeH = planeHeightTiles();

        int hscroll = planeHScroll(y, planeIndex) & ((planeW * 8) - 1);
        int vscroll = vsram[planeIndex] & ((planeH * 8) - 1);

        int worldY = (y + vscroll) & ((planeH * 8) - 1);
        int tileRow = worldY >> 3;
        int fineY = worldY & 7;

        for (int x = 0; x < SCREEN_W; x++) {
            int worldX = (x - hscroll) & ((planeW * 8) - 1);
            int tileCol = worldX >> 3;
            int fineX = worldX & 7;

            int entryAddr = nametableBase + ((tileRow * planeW + tileCol) * 2);
            int entry = ((vram[entryAddr & 0xFFFF] & 0xFF) << 8)
                    | (vram[(entryAddr + 1) & 0xFFFF] & 0xFF);

            int tileIndex = entry & 0x07FF;
            boolean hflip = (entry & 0x0800) != 0;
            boolean vflip = (entry & 0x1000) != 0;
            int palette = (entry >> 13) & 0x3;
            boolean priority = (entry & 0x8000) != 0;

            int px = hflip ? 7 - fineX : fineX;
            int py = vflip ? 7 - fineY : fineY;

            int patternAddr = tileIndex * 32 + py * 4 + (px >> 1);
            int b = vram[patternAddr & 0xFFFF] & 0xFF;
            int colorIndex = (px & 1) == 0 ? (b >> 4) : (b & 0x0F);

            layer[x] = colorIndex == 0 ? 0 : palette * 16 + colorIndex;
            pri[x] = priority;
        }
    }

    /**
     * Build the sprite layer for one scanline by walking the sprite attribute
     * table as a linked list. Honours size (1-4 cells each axis), H/V flip,
     * priority, palette, the column-major tile layout, the per-line pixel limit
     * (overflow flag), sprite-on-sprite collision, and basic X=0 masking. The
     * first sprite (in link order) to cover a pixel wins.
     */
    private void computeSpriteRow(int line) {
        java.util.Arrays.fill(layerS, 0);
        int satBase = (reg[5] & 0x7F) << 9;
        final int maxSprites = 80;       // H40
        int pixelBudget = SCREEN_W;      // H40 per-line sprite pixel limit (320)
        int sprite = 0;
        boolean drewOnLine = false;

        for (int i = 0; i < maxSprites; i++) {
            int addr = satBase + sprite * 8;
            int yword = readVramWord(addr);
            int sy = (yword & 0x3FF) - 128;
            int sizeByte = vram[(addr + 2) & 0xFFFF] & 0xFF;
            int hcells = ((sizeByte >> 2) & 3) + 1;
            int vcells = (sizeByte & 3) + 1;
            int link = vram[(addr + 3) & 0xFFFF] & 0x7F;
            int height = vcells * 8;

            if (line >= sy && line < sy + height) {
                int xword = readVramWord(addr + 6);
                int rawX = xword & 0x1FF;
                // A sprite with X=0 masks the rest of the line once another
                // sprite has already been placed on it.
                if (rawX == 0 && drewOnLine) {
                    break;
                }
                int attr = readVramWord(addr + 4);
                boolean priority = (attr & 0x8000) != 0;
                int palette = (attr >> 13) & 3;
                boolean vflip = (attr & 0x1000) != 0;
                boolean hflip = (attr & 0x0800) != 0;
                int tile = attr & 0x7FF;
                int width = hcells * 8;
                int sx = rawX - 128;

                int syRel = line - sy;
                int py = vflip ? (height - 1 - syRel) : syRel;
                int cellRow = py >> 3;
                int fineY = py & 7;

                for (int sxRel = 0; sxRel < width; sxRel++) {
                    if (pixelBudget <= 0) {
                        spriteOverflow = true;
                        break;
                    }
                    pixelBudget--;
                    int screenX = sx + sxRel;
                    if (screenX < 0 || screenX >= SCREEN_W) {
                        continue;
                    }
                    int px = hflip ? (width - 1 - sxRel) : sxRel;
                    int cellCol = px >> 3;
                    int fineX = px & 7;
                    int tileNum = tile + cellCol * vcells + cellRow;
                    int patternAddr = (tileNum * 32 + fineY * 4 + (fineX >> 1)) & 0xFFFF;
                    int bb = vram[patternAddr] & 0xFF;
                    int colorIndex = (fineX & 1) == 0 ? (bb >> 4) : (bb & 0x0F);
                    if (colorIndex == 0) {
                        continue;
                    }
                    if ((layerS[screenX] & 0x0F) != 0) {
                        spriteCollision = true; // first sprite already owns this pixel
                        continue;
                    }
                    layerS[screenX] = palette * 16 + colorIndex;
                    priS[screenX] = priority;
                }
                drewOnLine = true;
            }

            sprite = link;
            if (sprite == 0) {
                break; // end of the sprite list
            }
        }
    }

    private int readVramWord(int addr) {
        return ((vram[addr & 0xFFFF] & 0xFF) << 8) | (vram[(addr + 1) & 0xFFFF] & 0xFF);
    }

    private int backdropColor() {
        int idx = reg[7] & 0x3F;
        return toRgb(cram[idx]);
    }

    /** Convert a 9-bit Mega Drive BGR colour (0000 BBB0 GGG0 RRR0) to ARGB. */
    private static int toRgb(int c) {
        int r = (c >> 1) & 0x7;
        int g = (c >> 5) & 0x7;
        int b = (c >> 9) & 0x7;
        // Expand 3 bits to 8 by replicating the level table.
        int[] lv = {0, 36, 73, 109, 146, 182, 219, 255};
        return 0xFF000000 | (lv[r] << 16) | (lv[g] << 8) | lv[b];
    }

    // --- register-derived geometry ----------------------------------------

    private int planeABase() {
        return (reg[2] & 0x38) << 10;
    }

    private int planeBBase() {
        return (reg[4] & 0x07) << 13;
    }

    /**
     * Horizontal scroll for one plane on scanline {@code y}. The H-scroll table
     * (base = register 13 &times; $400) holds a plane-A word then a plane-B word
     * per entry; register 11 bits 0-1 pick how often entries change:
     * 0 = whole screen, 2 = every 8 lines (cell), 3 = every line.
     */
    private int planeHScroll(int y, int planeIndex) {
        int base = (reg[13] & 0x3F) << 10;
        int row = switch (reg[11] & 0x03) {
            case 0 -> 0;            // full screen
            case 2 -> y & ~7;       // per cell (every 8 lines)
            default -> y;           // per line (mode 3, and the rare mode 1)
        };
        int entry = base + row * 4 + planeIndex * 2;
        int hs = ((vram[entry & 0xFFFF] & 0xFF) << 8) | (vram[(entry + 1) & 0xFFFF] & 0xFF);
        return -(hs & 0x3FF);
    }

    private int planeWidthTiles() {
        return switch (reg[16] & 0x03) {
            case 0 -> 32;
            case 1 -> 64;
            default -> 128;
        };
    }

    private int planeHeightTiles() {
        return switch ((reg[16] >> 4) & 0x03) {
            case 0 -> 32;
            case 1 -> 64;
            default -> 128;
        };
    }

    public int[] framebuffer() {
        return framebuffer;
    }

    // Test / debug accessors.
    public int reg(int i) {
        return reg[i];
    }

    public byte[] vram() {
        return vram;
    }

    public int cram(int i) {
        return cram[i];
    }

    public int address() {
        return addressReg;
    }

    public int code() {
        return codeReg;
    }
}
