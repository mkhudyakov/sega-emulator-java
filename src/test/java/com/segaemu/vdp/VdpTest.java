package com.segaemu.vdp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VdpTest {

    private Vdp vdp;

    @BeforeEach
    void setUp() {
        vdp = new Vdp();
    }

    @Test
    void registerWriteSetsAutoIncrement() {
        // Register write form: $8Frr — write register 15 (auto-increment) = 2.
        vdp.writeControl(0x8F02);
        assertEquals(2, vdp.reg(15));
    }

    @Test
    void vramWriteCommandAndDataPort() {
        // Build a VRAM-write command targeting address $0000.
        // First word $4000 (CD0=1 -> VRAM write low bits), second word $0000.
        vdp.writeControl(0x4000);
        vdp.writeControl(0x0000);
        assertEquals(1, vdp.code()); // CODE_VRAM_WRITE

        vdp.writeControl(0x8F02); // auto-increment by 2
        vdp.writeControl(0x4000);
        vdp.writeControl(0x0000);

        vdp.writeData(0x1234);
        vdp.writeData(0x5678);
        assertEquals(0x12, vdp.vram()[0] & 0xFF);
        assertEquals(0x34, vdp.vram()[1] & 0xFF);
        assertEquals(0x56, vdp.vram()[2] & 0xFF);
        assertEquals(0x78, vdp.vram()[3] & 0xFF);
    }

    @Test
    void cramWriteStoresColour() {
        // CRAM write command: code 3. First word bits 15-14 = CD1-CD0 = 11 -> $C000,
        // second word CD bits give CD2 etc. Use the documented encoding.
        // CD = 3 -> CD1CD0 = 11 (0xC000), CD5..2 = 0 (second word 0). Address 0.
        vdp.writeControl(0xC000);
        vdp.writeControl(0x0000);
        assertEquals(3, vdp.code());
        vdp.writeData(0x0EEE); // white-ish
        assertEquals(0x0EEE, vdp.cram(0));
    }

    @Test
    void horizontalInterruptFiresAtRegister10Cadence() {
        vdp.writeControl(0x8010); // reg0 = $10: enable H-interrupt
        vdp.writeControl(0x8A03); // reg10 = 3: fire every (3+1) lines

        vdp.stepScanline();       // line 0: counter underflows immediately
        assertTrue(vdp.isHorizontalInterruptPending());
        vdp.clearHorizontalInterrupt();

        for (int i = 0; i < 3; i++) {
            vdp.stepScanline();
            assertFalse(vdp.isHorizontalInterruptPending(), "no H-int mid-cadence");
        }
        vdp.stepScanline();       // 4th line: fires again
        assertTrue(vdp.isHorizontalInterruptPending());
    }

    @Test
    void horizontalInterruptStaysSilentWhenDisabled() {
        vdp.writeControl(0x8A01); // reg10 = 1, but H-int disabled (reg0 bit4 = 0)
        for (int i = 0; i < 10; i++) {
            vdp.stepScanline();
            assertFalse(vdp.isHorizontalInterruptPending());
        }
    }

    @Test
    void scanlineSteppingProducesFrameAndVblank() {
        vdp.writeControl(0x8120); // register 1 = $20 -> enable vertical interrupt
        boolean sawFrame = false;
        for (int i = 0; i < 262; i++) {
            sawFrame |= vdp.stepScanline();
        }
        assertTrue(sawFrame);
        assertTrue(vdp.isVerticalInterruptPending());
        vdp.clearVerticalInterrupt();
        assertFalse(vdp.isVerticalInterruptPending());
    }
}
