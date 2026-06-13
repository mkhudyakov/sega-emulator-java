package com.segaemu.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.segaemu.cartridge.Rom;
import com.segaemu.io.Controller;
import com.segaemu.sound.Ym2612;
import com.segaemu.vdp.Vdp;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Regression tests for the bus-level bugs that blocked Sonic's boot. */
class GenesisBusTest {

    private GenesisBus bus;

    @BeforeEach
    void setUp() {
        byte[] img = new byte[0x4000];
        byte[] name = "SEGA MEGA DRIVE ".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(name, 0, img, 0x100, name.length);
        Rom rom = Rom.fromBytes(img);
        bus = new GenesisBus(rom, new Vdp(), new Controller(), new Controller(), new Ym2612());
    }

    /**
     * A word write of $0100 to $A11100 requests the Z80 bus. The odd-byte half
     * ($00 to $A11101) must be ignored — otherwise it clears the request and the
     * 68000's "btst #0,($A11100); bne" wait loop spins forever.
     */
    @Test
    void z80BusRequestSurvivesWordWrite() {
        bus.write16(0xA11100, 0x0100);
        assertEquals(0x00, bus.read8(0xA11100) & 0x01,
                "bit 0 must read 0 (bus granted) after requesting the Z80 bus");
    }

    /**
     * Reading the YM2612 status port ($A04000) must return the busy flag, not
     * Z80 RAM. Our stub is never busy, so bit 7 is clear and busy-wait loops end.
     */
    @Test
    void ym2612StatusPortIsNotZ80Ram() {
        // Poke a byte into Z80 RAM at the wrapped offset to prove it is not read.
        bus.write8(0xA00000, 0xFF);
        assertEquals(0x00, bus.read8(0xA04000) & 0x80,
                "YM2612 BUSY (bit 7) must read 0");
    }
}
